package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public non-sealed class WebSocketNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(WebSocketNode.class);

    private final String path;
    private final LinkedBlockingQueue<String> sendQueue;
    private final LinkedBlockingQueue<String> recvQueue;
    private volatile WsContext wsContext;
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalReceived = new AtomicLong(0);
    private int ownerUid;
    private int permissions;

    public WebSocketNode(String path) {
        this(path, 256, 0, 0644);
    }

    public WebSocketNode(String path, int capacity, int ownerUid, int permissions) {
        this.path = path;
        this.sendQueue = new LinkedBlockingQueue<>(capacity);
        this.recvQueue = new LinkedBlockingQueue<>(capacity);
        this.ownerUid = ownerUid;
        this.permissions = permissions;
        log.info("[WebSocketNode] Created: path={}, capacity={}", path, capacity);
    }

    public void attachWsContext(WsContext ctx) {
        this.wsContext = ctx;
        log.info("[WebSocketNode] WS context attached: path={}, sessionId={}", path, ctx.sessionId());
        drainSendQueue();
    }

    public void detachWsContext() {
        this.wsContext = null;
        log.info("[WebSocketNode] WS context detached: path={}", path);
    }

    public void onWsMessage(String message) {
        try {
            recvQueue.put(message);
            totalReceived.incrementAndGet();
            log.debug("[WebSocketNode] WS→Agent: path={}, dataLen={}", path, message.length());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[WebSocketNode] recvQueue put interrupted: path={}", path);
        }
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.WEBHOOK;
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

    @Override
    public String read() {
        try {
            String data = recvQueue.take();
            log.debug("[WebSocketNode] Agent←recv: path={}, dataLen={}", path, data.length());
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[WebSocketNode] read interrupted: path={}", path);
            return "";
        }
    }

    @Override
    public boolean write(String data) {
        if (wsContext != null) {
            try {
                wsContext.send(data);
                totalSent.incrementAndGet();
                log.debug("[WebSocketNode] Agent→WS direct: path={}, dataLen={}", path, data.length());
                return true;
            } catch (Exception e) {
                log.warn("[WebSocketNode] WS send failed, queuing: path={}, error={}", path, e.getMessage());
            }
        }
        try {
            sendQueue.put(data);
            log.debug("[WebSocketNode] Agent→sendQueue: path={}, dataLen={}", path, data.length());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[WebSocketNode] sendQueue put interrupted: path={}", path);
            return false;
        }
    }

    private void drainSendQueue() {
        if (wsContext == null) return;
        String pending;
        while ((pending = sendQueue.poll()) != null) {
            try {
                wsContext.send(pending);
                totalSent.incrementAndGet();
            } catch (Exception e) {
                log.warn("[WebSocketNode] Failed to drain sendQueue: path={}", path);
                break;
            }
        }
    }

    public String pollRecv() {
        String data = recvQueue.poll();
        if (data != null) totalReceived.incrementAndGet();
        return data;
    }

    public int recvQueueSize() {
        return recvQueue.size();
    }

    public int sendQueueSize() {
        return sendQueue.size();
    }

    public boolean isWsConnected() {
        return wsContext != null;
    }

    public long totalSent() {
        return totalSent.get();
    }

    public long totalReceived() {
        return totalReceived.get();
    }

    @Override
    public String toString() {
        return "WebSocketNode{path='%s', ws=%s, recvQ=%d, sendQ=%d, sent=%d, recv=%d}"
                .formatted(path, isWsConnected(), recvQueue.size(), sendQueue.size(),
                        totalSent.get(), totalReceived.get());
    }
}
