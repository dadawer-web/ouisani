package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.network.EventBus;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remote Device Mount Node — a VFS device node that represents a remote
 * physical or virtual device connected via WebSocket.
 * <p>
 * This is the core of AIOS's "everything is a file" distributed architecture.
 * A remote device (IoT sensor, VCP Node, mobile phone, another AIOS instance)
 * connects via the {@code /ws/remote/{deviceId}} WebSocket endpoint, and this
 * node is dynamically mounted at {@code /dev/remote/{deviceId}} in the VFS.
 * <p>
 * <h3>Unix Philosophy: Network Transparency</h3>
 * The Agent process does not know about the network. It simply performs
 * {@code sys_write("/dev/remote/device_abcd", command)} to issue a command
 * and {@code sys_read("/dev/remote/device_abcd")} to read the response.
 * The kernel handles all WebSocket framing, reconnection buffering, and
 * offline detection transparently.
 * <p>
 * <h3>Read Path (Remote → Agent)</h3>
 * <pre>
 *   Remote Device → WS frame → onWsMessage() → recvQueue → read()
 *   ↓
 *   Agent calls sys_read("/dev/remote/device_abcd")
 *   ↓
 *   read() blocks on recvQueue.take() → returns JSON/String/Byte data
 * </pre>
 * <p>
 * <h3>Write Path (Agent → Remote)</h3>
 * <pre>
 *   Agent calls sys_write("/dev/remote/device_abcd", command)
 *   ↓
 *   write() → wsContext.send(command) if online, else sendQueue buffer
 *   ↓
 *   WS frame → Remote Device
 * </pre>
 * <p>
 * <h3>Offline Handling</h3>
 * When the remote device disconnects:
 * <ul>
 *   <li>{@link #detachWsContext()} marks the node as offline</li>
 *   <li>Subsequent {@code read()} throws {@link DeviceOfflineException}</li>
 *   <li>Subsequent {@code write()} buffers in sendQueue (for reconnection)</li>
 *   <li>EventBus broadcasts a {@code device_offline} event</li>
 * </ul>
 *
 * @see DeviceOfflineException
 * @see com.ouisani.aios.core.VfsManager
 */
public non-sealed class RemoteDeviceMountNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(RemoteDeviceMountNode.class);

    // ── Device Identity ──

    private final String path;
    private final String deviceId;
    private final String deviceType;
    private volatile long connectedAt;
    private volatile long lastActivityAt;

    // ── WebSocket Transport ──

    private volatile WsContext wsContext;
    private final LinkedBlockingQueue<String> sendQueue;
    private final LinkedBlockingQueue<RemoteFrame> recvQueue;

    // ── Device State ──

    private volatile boolean online = false;
    private volatile boolean permanentlyUnmounted = false;
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalReceived = new AtomicLong(0);

    // ── Permissions ──

    private int ownerUid;
    private int permissions;

    // ── Device Metadata ──

    private final Map<String, Object> deviceMetadata = new LinkedHashMap<>();

    // ════════════════════════════════════════════════════════════════
    //  Construction
    // ════════════════════════════════════════════════════════════════

    public RemoteDeviceMountNode(String path, String deviceId) {
        this(path, deviceId, "generic", 256, 0, 0666);
    }

    public RemoteDeviceMountNode(String path, String deviceId, String deviceType,
                                 int queueCapacity, int ownerUid, int permissions) {
        this.path = path;
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.sendQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.recvQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.ownerUid = ownerUid;
        this.permissions = permissions;

        log.info("[RemoteDevice] Node created: path={}, deviceId={}, type={}", path, deviceId, deviceType);
    }

    // ════════════════════════════════════════════════════════════════
    //  WebSocket Lifecycle
    // ════════════════════════════════════════════════════════════════

    /**
     * Bind a WebSocket context to this device node — called when the
     * remote device connects (or reconnects).
     * <p>
     * Drains any buffered sendQueue messages that accumulated while
     * the device was offline.
     */
    public void attachWsContext(WsContext ctx) {
        this.wsContext = ctx;
        this.online = true;
        this.connectedAt = System.currentTimeMillis();
        this.lastActivityAt = this.connectedAt;

        drainSendQueue();

        log.info("[RemoteDevice] WS attached: deviceId={}, sessionId={}, path={}",
                deviceId, ctx.sessionId(), path);
        System.out.println("  \u001B[32m[RemoteDevice] Device '" + deviceId + "' online at " + path + "\u001B[0m");

        EventBus.instance().broadcast("device_online",
                "{\"deviceId\":\"" + deviceId + "\",\"path\":\"" + path
                        + "\",\"type\":\"" + deviceType + "\"}");
    }

    /**
     * Detach the WebSocket context — called when the remote device
     * disconnects.
     * <p>
     * Marks the node as offline. Subsequent {@code read()} calls will
     * throw {@link DeviceOfflineException} to prevent Agent deadlocks.
     * Write calls are buffered in sendQueue for potential reconnection.
     */
    public void detachWsContext() {
        this.wsContext = null;
        this.online = false;

        log.warn("[RemoteDevice] WS detached: deviceId={}, path={}", deviceId, path);
        System.out.println("  \u001B[31m[RemoteDevice] Device '" + deviceId + "' OFFLINE at " + path + "\u001B[0m");

        EventBus.instance().broadcast("device_offline",
                "{\"deviceId\":\"" + deviceId + "\",\"path\":\"" + path
                        + "\",\"type\":\"" + deviceType + "\"}");

        // Wake up any threads blocked on recvQueue.take() by injecting a poison frame
        try {
            recvQueue.put(RemoteFrame.offlineSignal(deviceId, path));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Mark this node as permanently removed — the device has been
     * unmounted from the VFS and will not reconnect.
     */
    public void markPermanentlyUnmounted() {
        this.permanentlyUnmounted = true;
        this.online = false;
        this.wsContext = null;

        // Wake up any blocked readers with EOF
        try {
            recvQueue.put(RemoteFrame.eofSignal(deviceId, path));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("[RemoteDevice] Permanently unmounted: deviceId={}, path={}", deviceId, path);
    }

    /**
     * Handle an incoming WebSocket message from the remote device.
     * <p>
     * The message is parsed into a {@link RemoteFrame} and placed into
     * the recvQueue, where it will be consumed by the next {@code read()}.
     */
    public void onWsMessage(String message) {
        if (permanentlyUnmounted) {
            log.warn("[RemoteDevice] Discarded message from unmounted device: deviceId={}", deviceId);
            return;
        }

        lastActivityAt = System.currentTimeMillis();

        RemoteFrame frame = RemoteFrame.fromJson(message, deviceId);
        try {
            if (!recvQueue.offer(frame, 5, TimeUnit.SECONDS)) {
                log.warn("[RemoteDevice] recvQueue full, dropping frame: deviceId={}, type={}",
                        deviceId, frame.type);
            }
            totalReceived.incrementAndGet();
            log.debug("[RemoteDevice] WS→Agent: deviceId={}, type={}, dataLen={}",
                    deviceId, frame.type, frame.data.length());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  VfsNode Interface — sys_read / sys_write Transparent Proxy
    // ════════════════════════════════════════════════════════════════

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.DEVICE;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    /**
     * Read data from the remote device — blocks until a frame arrives.
     * <p>
     * This is the {@code sys_read} path. The Agent calls
     * {@code sys_read("/dev/remote/device_abcd")} and the kernel
     * invokes this method, which blocks on the recvQueue until the
     * remote device sends data.
     * <p>
     * <b>Offline behavior:</b> If the device is offline and no buffered
     * data remains, throws {@link DeviceOfflineException} to prevent
     * the Agent from blocking indefinitely.
     * <p>
     * <b>EOF behavior:</b> If the node has been permanently unmounted,
     * returns an empty string (EOF), signaling the Agent that the
     * device is gone.
     *
     * @return the data payload from the remote device
     * @throws DeviceOfflineException if the device is offline with no buffered data
     */
    @Override
    public String read() {
        if (permanentlyUnmounted) {
            return ""; // EOF — device is gone
        }

        try {
            RemoteFrame frame = recvQueue.take();

            // Check for control frames
            if (frame.isOfflineSignal()) {
                if (permanentlyUnmounted) {
                    return ""; // EOF
                }
                throw new DeviceOfflineException(path, deviceId,
                        "Remote device disconnected during blocking read");
            }

            if (frame.isEofSignal()) {
                return ""; // EOF
            }

            lastActivityAt = System.currentTimeMillis();
            log.debug("[RemoteDevice] Agent←read: deviceId={}, type={}, dataLen={}",
                    deviceId, frame.type, frame.data.length());

            return frame.data;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!online) {
                throw new DeviceOfflineException(path, deviceId,
                        "Read interrupted and device is offline");
            }
            return "";
        }
    }

    /**
     * Non-blocking read — returns null if no data is available.
     * <p>
     * Useful for polling patterns where the Agent doesn't want to
     * block on a potentially offline device.
     *
     * @return the data payload, or null if no data is available
     * @throws DeviceOfflineException if the device is offline
     */
    public String pollRead() {
        if (permanentlyUnmounted) {
            return ""; // EOF
        }
        if (!online && recvQueue.isEmpty()) {
            throw new DeviceOfflineException(path, deviceId);
        }

        RemoteFrame frame = recvQueue.poll();
        if (frame == null) return null;

        if (frame.isOfflineSignal()) {
            throw new DeviceOfflineException(path, deviceId);
        }
        if (frame.isEofSignal()) {
            return "";
        }

        lastActivityAt = System.currentTimeMillis();
        return frame.data;
    }

    /**
     * Read with timeout — blocks for up to the specified duration.
     *
     * @param timeoutMs timeout in milliseconds
     * @return the data payload, or null if timeout elapsed
     * @throws DeviceOfflineException if the device is offline with no buffered data
     */
    public String readWithTimeout(long timeoutMs) {
        if (permanentlyUnmounted) {
            return ""; // EOF
        }

        try {
            RemoteFrame frame = recvQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (frame == null) return null; // timeout

            if (frame.isOfflineSignal()) {
                if (permanentlyUnmounted) return "";
                throw new DeviceOfflineException(path, deviceId);
            }
            if (frame.isEofSignal()) return "";

            lastActivityAt = System.currentTimeMillis();
            return frame.data;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!online) {
                throw new DeviceOfflineException(path, deviceId);
            }
            return null;
        }
    }

    /**
     * Write data to the remote device — the {@code sys_write} path.
     * <p>
     * The Agent calls {@code sys_write("/dev/remote/device_abcd", command)}
     * and the kernel invokes this method. If the WebSocket is connected,
     * the data is sent immediately as a frame. If the device is offline,
     * the data is buffered in the sendQueue for delivery upon reconnection.
     * <p>
     * <b>Unlike read(), write() does NOT throw DeviceOfflineException.</b>
     * This mirrors Unix behavior: writing to a disconnected TTY doesn't
     * fail — the data goes to the bit bucket (or in our case, the buffer).
     * The Agent can keep issuing commands; they'll be delivered when the
     * device comes back online.
     *
     * @param data the command or data to send to the remote device
     * @return true if the data was sent or buffered successfully
     */
    @Override
    public boolean write(String data) {
        if (permanentlyUnmounted) {
            log.warn("[RemoteDevice] Write to unmounted device: deviceId={}", deviceId);
            return false;
        }

        lastActivityAt = System.currentTimeMillis();

        // Try direct send if WS is connected
        if (wsContext != null && online) {
            try {
                wsContext.send(data);
                totalSent.incrementAndGet();
                log.debug("[RemoteDevice] Agent→WS direct: deviceId={}, dataLen={}", deviceId, data.length());
                return true;
            } catch (Exception e) {
                log.warn("[RemoteDevice] WS send failed, buffering: deviceId={}, error={}", deviceId, e.getMessage());
                // Fall through to buffer
            }
        }

        // Buffer for reconnection
        try {
            if (!sendQueue.offer(data, 5, TimeUnit.SECONDS)) {
                log.warn("[RemoteDevice] sendQueue full, dropping write: deviceId={}, dataLen={}",
                        deviceId, data.length());
                return false;
            }
            log.debug("[RemoteDevice] Agent→sendQueue: deviceId={}, dataLen={}, queueSize={}",
                    deviceId, data.length(), sendQueue.size());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Internal: Send Queue Drain (Reconnection)
    // ════════════════════════════════════════════════════════════════

    private void drainSendQueue() {
        if (wsContext == null) return;
        String pending;
        int drained = 0;
        while ((pending = sendQueue.poll()) != null) {
            try {
                wsContext.send(pending);
                totalSent.incrementAndGet();
                drained++;
            } catch (Exception e) {
                log.warn("[RemoteDevice] Failed to drain sendQueue: deviceId={}", deviceId);
                // Put it back and stop draining
                try { sendQueue.put(pending); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                break;
            }
        }
        if (drained > 0) {
            log.info("[RemoteDevice] Drained {} pending frames on reconnection: deviceId={}", drained, deviceId);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Device Metadata & Diagnostics
    // ════════════════════════════════════════════════════════════════

    public String deviceId() { return deviceId; }
    public String deviceType() { return deviceType; }
    public boolean isOnline() { return online; }
    public boolean isPermanentlyUnmounted() { return permanentlyUnmounted; }
    public long connectedAt() { return connectedAt; }
    public long lastActivityAt() { return lastActivityAt; }
    public long totalSent() { return totalSent.get(); }
    public long totalReceived() { return totalReceived.get(); }
    public int sendQueueSize() { return sendQueue.size(); }
    public int recvQueueSize() { return recvQueue.size(); }

    public Map<String, Object> deviceMetadata() { return deviceMetadata; }

    public void setDeviceMetadata(String key, Object value) {
        deviceMetadata.put(key, value);
    }

    public Object getDeviceMetadata(String key) {
        return deviceMetadata.get(key);
    }

    /**
     * Convert buffered recv data to an InputStream for binary consumption.
     * <p>
     * Drains all currently buffered frames and concatenates their data
     * into a single stream. Useful for consuming binary payloads (images,
     * sensor data, etc.) from the remote device.
     */
    public InputStream asInputStream() {
        StringBuilder sb = new StringBuilder();
        RemoteFrame frame;
        while ((frame = recvQueue.poll()) != null) {
            if (!frame.isOfflineSignal() && !frame.isEofSignal()) {
                sb.append(frame.data);
            }
        }
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "RemoteDeviceMountNode{path='%s', deviceId='%s', type='%s', online=%s, sent=%d, recv=%d}"
                .formatted(path, deviceId, deviceType, online, totalSent.get(), totalReceived.get());
    }

    // ════════════════════════════════════════════════════════════════
    //  Remote Frame — structured data from remote devices
    // ════════════════════════════════════════════════════════════════

    /**
     * A structured frame received from a remote device.
     * <p>
     * Remote devices may send:
     * <ul>
     *   <li>JSON payloads with a {@code type} field (e.g., "sensor", "response", "error")</li>
     *   <li>Raw string data (treated as type "data")</li>
     *   <li>Control signals (offline, eof) injected by the kernel</li>
     * </ul>
     */
    public static final class RemoteFrame {

        private static final String TYPE_OFFLINE = "__OFFLINE__";
        private static final String TYPE_EOF = "__EOF__";

        final String type;
        final String data;
        final String sourceDeviceId;
        final long timestamp;

        private RemoteFrame(String type, String data, String sourceDeviceId) {
            this.type = type;
            this.data = data;
            this.sourceDeviceId = sourceDeviceId;
            this.timestamp = System.currentTimeMillis();
        }

        /**
         * Parse a raw WebSocket message into a RemoteFrame.
         * <p>
         * If the message is valid JSON with a "type" field, extracts
         * type and data. Otherwise, treats the entire message as raw data.
         */
        static RemoteFrame fromJson(String message, String deviceId) {
            // Simple JSON detection — avoid pulling in Jackson for this
            if (message != null && message.startsWith("{") && message.endsWith("}")) {
                // Minimal JSON extraction: look for "type" and "data" fields
                String type = extractJsonField(message, "type");
                String data = extractJsonField(message, "data");
                if (type == null) type = "json";
                if (data == null) data = message;
                return new RemoteFrame(type, data, deviceId);
            }
            return new RemoteFrame("data", message != null ? message : "", deviceId);
        }

        static RemoteFrame offlineSignal(String deviceId, String path) {
            return new RemoteFrame(TYPE_OFFLINE,
                    "Device offline: " + deviceId + " at " + path, deviceId);
        }

        static RemoteFrame eofSignal(String deviceId, String path) {
            return new RemoteFrame(TYPE_EOF,
                    "Device unmounted: " + deviceId + " at " + path, deviceId);
        }

        boolean isOfflineSignal() { return TYPE_OFFLINE.equals(type); }
        boolean isEofSignal() { return TYPE_EOF.equals(type); }

        /**
         * Minimal JSON field extraction without a full parser.
         * Handles simple cases: "field":"value" and "field":value
         */
        private static String extractJsonField(String json, String fieldName) {
            String needle = "\"" + fieldName + "\"";
            int idx = json.indexOf(needle);
            if (idx < 0) return null;

            // Find the colon after the field name
            int colonIdx = json.indexOf(':', idx + needle.length());
            if (colonIdx < 0) return null;

            // Skip whitespace
            int valStart = colonIdx + 1;
            while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

            if (valStart >= json.length()) return null;

            // String value
            if (json.charAt(valStart) == '"') {
                int valEnd = json.indexOf('"', valStart + 1);
                if (valEnd < 0) return null;
                return json.substring(valStart + 1, valEnd);
            }

            // Non-string value (number, boolean, null)
            int valEnd = valStart;
            while (valEnd < json.length() && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '}') {
                valEnd++;
            }
            return json.substring(valStart, valEnd).trim();
        }
    }
}
