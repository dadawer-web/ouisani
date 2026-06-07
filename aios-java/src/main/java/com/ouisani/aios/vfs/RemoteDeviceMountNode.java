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
 * 远程设备挂载节点 — 通过 WebSocket 连接的远程物理/虚拟设备的 VFS 设备节点。
 * <p>
 * 这是 AIOS "一切皆文件"分布式架构的核心。远程设备（IoT 传感器、VCP 节点、
 * 手机、另一个 AIOS 实例）通过 {@code /ws/remote/{deviceId}} WebSocket 端点
 * 连入，此节点被动态挂载到 VFS 的 {@code /dev/remote/{deviceId}} 路径。
 *
 * <h3>Unix 哲学：网络透明性</h3>
 * Agent 进程无需感知网络。它只需执行
 * {@code sys_write("/dev/remote/device_abcd", command)} 发送命令，
 * {@code sys_read("/dev/remote/device_abcd")} 读取响应。
 * 内核透明地处理所有 WebSocket 帧、重连缓冲和离线检测。
 *
 * <h3>读取路径（远程 → Agent）</h3>
 * <pre>
 *   远程设备 → WS 帧 → onWsMessage() → recvQueue → read()
 *   ↓
 *   Agent 调用 sys_read("/dev/remote/device_abcd")
 *   ↓
 *   read() 阻塞在 recvQueue.take() → 返回 JSON/String/Byte 数据
 * </pre>
 *
 * <h3>写入路径（Agent → 远程）</h3>
 * <pre>
 *   Agent 调用 sys_write("/dev/remote/device_abcd", command)
 *   ↓
 *   write() → 在线时 wsContext.send(command)，离线时缓冲到 sendQueue
 *   ↓
 *   WS 帧 → 远程设备
 * </pre>
 *
 * <h3>离线处理</h3>
 * 当远程设备断开连接时：
 * <ul>
 *   <li>{@link #detachWsContext()} 将节点标记为离线</li>
 *   <li>后续 {@code read()} 抛出 {@link DeviceOfflineException}</li>
 *   <li>后续 {@code write()} 缓冲到 sendQueue（等待重连）</li>
 *   <li>EventBus 广播 {@code device_offline} 事件</li>
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
     * 绑定 WebSocket 上下文到设备节点 — 远程设备连接（或重连）时调用。
     * <p>
     * 会排空设备离线期间在 sendQueue 中积累的缓冲消息。
     *
     * @param ctx WebSocket 上下文
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
     * 解绑 WebSocket 上下文 — 远程设备断开连接时调用。
     * <p>
     * 将节点标记为离线。后续 {@code read()} 调用将抛出
     * {@link DeviceOfflineException} 以防止 Agent 死锁。
     * 写入调用会缓冲到 sendQueue，等待可能的重新连接。
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
     * 标记节点为永久移除 — 设备已从 VFS 卸载且不会重连。
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
     * 处理远程设备发来的 WebSocket 消息。
     * <p>
     * 消息被解析为 {@link RemoteFrame} 并放入 recvQueue，
     * 等待下一次 {@code read()} 消费。
     *
     * @param message 原始 WebSocket 消息
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
     * 从远程设备读取数据 — 阻塞直到有帧到达。
     * <p>
     * 这是 {@code sys_read} 路径。Agent 调用
     * {@code sys_read("/dev/remote/device_abcd")}，内核调用此方法，
     * 阻塞在 recvQueue 上直到远程设备发送数据。
     * <p>
     * <b>离线行为：</b>如果设备离线且无缓冲数据，抛出
     * {@link DeviceOfflineException} 以防止 Agent 无限阻塞。
     * <p>
     * <b>EOF 行为：</b>如果节点已被永久卸载，返回空字符串（EOF），
     * 通知 Agent 设备已消失。
     *
     * @return 远程设备的数据载荷
     * @throws DeviceOfflineException 如果设备离线且无缓冲数据
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
     * 非阻塞读取 — 无数据时返回 null。
     * <p>
     * 适用于轮询模式，Agent 不希望在可能离线的设备上阻塞。
     *
     * @return 数据载荷，无数据时返回 null
     * @throws DeviceOfflineException 如果设备离线
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
     * 带超时的读取 — 阻塞最多指定时长。
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return 数据载荷，超时返回 null
     * @throws DeviceOfflineException 如果设备离线且无缓冲数据
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
     * 向远程设备写入数据 — {@code sys_write} 路径。
     * <p>
     * Agent 调用 {@code sys_write("/dev/remote/device_abcd", command)}，
     * 内核调用此方法。如果 WebSocket 已连接，数据立即作为帧发送；
     * 如果设备离线，数据缓冲到 sendQueue，等待重连后投递。
     * <p>
     * <b>与 read() 不同，write() 不抛出 DeviceOfflineException。</b>
     * 这模拟了 Unix 行为：向断开的 TTY 写入不会失败 — 数据进入
     * 位桶（或在本实现中，缓冲区）。Agent 可以持续发出命令，
     * 设备重新上线后会被投递。
     *
     * @param data 要发送到远程设备的命令或数据
     * @return 数据是否成功发送或缓冲
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
     * 将缓冲的接收数据转换为 InputStream，供二进制消费使用。
     * <p>
     * 排空当前所有缓冲帧并将数据拼接为单一流。
     * 适用于消费来自远程设备的二进制载荷（图片、传感器数据等）。
     *
     * @return 包含所有缓冲数据的输入流
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
     * 远程帧 — 来自远程设备的结构化数据单元。
     * <p>
     * 远程设备可能发送：
     * <ul>
     *   <li>带有 {@code type} 字段的 JSON 载荷（如 "sensor"、"response"、"error"）</li>
     *   <li>原始字符串数据（视为 type "data"）</li>
     *   <li>内核注入的控制信号（offline、eof）</li>
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
         * 将原始 WebSocket 消息解析为 RemoteFrame。
         * <p>
         * 如果消息是包含 "type" 字段的有效 JSON，提取 type 和 data；
         * 否则将整个消息视为原始数据。
         *
         * @param message  原始 WebSocket 消息
         * @param deviceId 设备 ID
         * @return 解析后的 RemoteFrame
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

        /** 最小化 JSON 字段提取（无需完整解析器）。处理 "field":"value" 和 "field":value 两种情况 */
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
