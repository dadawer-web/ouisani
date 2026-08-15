package com.ouisani.aios.core.remote;

import com.ouisani.aios.core.transport.Transport;
import com.ouisani.aios.core.transport.WebSocketTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 远程会话管理器 — 对标 Claude Code 的 RemoteSessionManager。
 * <p>
 * 管理与 CCR (Cloud Code Runtime) 后端的远程会话：
 * - WebSocket 连接管理
 * - 权限桥接（远程权限请求 → 本地审批）
 * - SDK 消息适配
 * - 心跳保活
 * <p>
 * OS 类比：相当于 SSH 客户端 — 本地终端 ↔ 远程 Shell 桥接。
 */
public class RemoteSessionManager {

    private static final Logger log = LoggerFactory.getLogger(RemoteSessionManager.class);

    /** 远程会话配置 */
    public record RemoteSessionConfig(
            String sessionId,
            String wsUrl,
            String accessToken,
            boolean viewerOnly
    ) {}

    /** 远程会话回调 */
    public interface RemoteSessionCallbacks {
        void onMessage(String message);
        void onPermissionRequest(String requestId, String toolName, String input);
        void onPermissionCancelled(String requestId);
        void onConnected();
        void onDisconnected(String reason);
        void onReconnecting();
        void onError(String error);
    }

    /** 权限响应 */
    public record PermissionResponse(
            String requestId,
            boolean allowed,
            String message,
            String updatedInput
    ) {}

    private Transport transport;
    private RemoteSessionConfig config;
    private RemoteSessionCallbacks callbacks;
    private final Map<String, PermissionResponse> pendingPermissions = new ConcurrentHashMap<>();
    private volatile boolean active = false;

    /**
     * 初始化远程会话。
     */
    public void init(RemoteSessionConfig config, RemoteSessionCallbacks callbacks) {
        this.config = config;
        this.callbacks = callbacks;

        transport = new WebSocketTransport(config.wsUrl());

        transport.onData(data -> {
            try {
                handleIncomingMessage(data);
            } catch (Exception e) {
                log.error("[RemoteSession] Message handling error: {}", e.getMessage());
            }
        });

        transport.onClose(reason -> {
            if (callbacks != null) callbacks.onDisconnected(reason);
        });

        log.info("[RemoteSession] Initialized for session: {}", config.sessionId());
    }

    /**
     * 连接到远程会话。
     */
    public void connect() {
        active = true;
        transport.connect();
        if (callbacks != null) callbacks.onConnected();
        log.info("[RemoteSession] Connected");
    }

    /**
     * 断开远程会话。
     */
    public void disconnect() {
        active = false;
        transport.close();
        log.info("[RemoteSession] Disconnected");
    }

    /**
     * 发送消息到远程。
     */
    public void sendMessage(String message) {
        if (transport != null && transport.isConnected()) {
            transport.send(message);
        }
    }

    /**
     * 响应权限请求。
     */
    public void respondToPermission(PermissionResponse response) {
        pendingPermissions.remove(response.requestId());
        String json = String.format(
                "{\"type\":\"control_response\",\"request_id\":\"%s\",\"allowed\":%b,\"message\":\"%s\"}",
                response.requestId(), response.allowed(), response.message()
        );
        sendMessage(json);
    }

    /**
     * 处理入站消息。
     */
    private void handleIncomingMessage(String data) {
        if (data.contains("\"type\":\"control_request\"")) {
            // 权限请求
            String requestId = extractField(data, "request_id");
            String toolName = extractField(data, "tool_name");
            if (callbacks != null) {
                callbacks.onPermissionRequest(requestId, toolName, data);
            }
        } else {
            // 普通消息
            if (callbacks != null) {
                callbacks.onMessage(data);
            }
        }
    }

    private String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    public boolean isActive() { return active; }
    public boolean isConnected() { return transport != null && transport.isConnected(); }
}
