package com.ouisani.aios.core.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * REPL Bridge — 对标 Claude Code 的 replBridge.ts。
 * <p>
 * 提供 IDE/远程控制到 AIOS Agent 的桥接通道：
 * - WebSocket 消息路由
 * - 控制请求/响应
 * - 消息去重
 * <p>
 * OS 类比：相当于 Linux 的 ptmx/pts — 伪终端主从设备桥接。
 */
public class ReplBridge {

    private static final Logger log = LoggerFactory.getLogger(ReplBridge.class);

    private SessionManager.BridgeSession session;
    private Consumer<String> messageHandler;
    private final String environmentId;
    private final int maxInboundUuids = 2000;
    private final Set<String> seenInboundUuids = Collections.newSetFromMap(new java.util.LinkedHashMap<>(maxInboundUuids, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > maxInboundUuids;
        }
    });

    public ReplBridge(String environmentId) {
        this.environmentId = environmentId;
    }

    /**
     * 初始化桥接 — 创建会话并连接。
     */
    public void init() {
        this.session = SessionManager.instance().createSession(environmentId);
        this.session.setState(SessionManager.BridgeState.CONNECTED);
        log.info("[ReplBridge] Initialized: session={}", session.sessionId());
    }

    /**
     * 发送消息到 Agent。
     */
    public void sendMessage(String message) {
        if (session == null) {
            log.warn("[ReplBridge] 无活跃会话");
            return;
        }
        session.appendMessage(message);
        if (messageHandler != null) {
            messageHandler.accept(message);
        }
    }

    /**
     * 接收来自 Agent 的消息。
     */
    public void onMessage(Consumer<String> handler) {
        this.messageHandler = handler;
    }

    /**
     * 去重检查 — 防止重复处理入站消息。
     */
    public boolean isDuplicate(String uuid) {
        return !seenInboundUuids.add(uuid);
    }

    /**
     * 发送控制请求（如权限确认）。
     */
    public void sendControlRequest(String requestId, String toolName, String input) {
        String json = String.format(
                "{\"type\":\"control_request\",\"request_id\":\"%s\",\"request\":{\"subtype\":\"can_use_tool\",\"tool_name\":\"%s\",\"input\":%s}}",
                requestId, toolName, input
        );
        sendMessage(json);
    }

    /**
     * 拆卸桥接。
     */
    public void teardown() {
        if (session != null) {
            SessionManager.instance().archiveSession(session.sessionId());
            session = null;
        }
        messageHandler = null;
        seenInboundUuids.clear();
        log.info("[ReplBridge] Teardown complete");
    }

    public SessionManager.BridgeSession session() { return session; }
    public boolean isConnected() { return session != null && session.state() == SessionManager.BridgeState.CONNECTED; }
}
