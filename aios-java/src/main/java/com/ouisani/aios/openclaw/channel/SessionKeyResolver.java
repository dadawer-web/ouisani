package com.ouisani.aios.openclaw.channel;

/**
 * 会话键解析器 — 对标 OpenClaw 的 resolveSessionKey。
 * <p>
 * 根据消息来源和会话范围，解析出唯一的会话键。
 * 会话键决定了消息路由到哪个 Agent 会话。
 * <p>
 * 路由规则：
 * <ul>
 *   <li>GLOBAL 范围：所有消息路由到同一个会话</li>
 *   <li>PER_SENDER 范围：每个发送者路由到独立会话</li>
 *   <li>群组消息：按群组 ID 路由，前缀 agent:{agentId}: 做隔离</li>
 *   <li>私聊消息：按发送者 ID 路由</li>
 * </ul>
 */
public class SessionKeyResolver {

    /** 会话范围 */
    public enum SessionScope {
        /** 每个发送者独立会话 */
        PER_SENDER,
        /** 全局共享会话 */
        GLOBAL
    }

    /**
     * 解析会话键。
     *
     * @param scope   会话范围
     * @param channel 消息来源渠道
     * @param senderId 发送者 ID
     * @param groupId 群组 ID（群聊时非空）
     * @param agentId Agent ID（用于多 Agent 隔离）
     * @return 规范化的会话键
     */
    public static String resolve(SessionScope scope, String channel,
                                  String senderId, String groupId, String agentId) {
        // GLOBAL 范围直接返回固定键
        if (scope == SessionScope.GLOBAL) {
            return agentId != null ? "agent:" + agentId + ":global" : "global";
        }

        // 群组消息
        if (groupId != null && !groupId.isBlank()) {
            String raw = "group:" + channel + ":" + groupId;
            return agentId != null ? "agent:" + agentId + ":" + raw : raw;
        }

        // 私聊消息
        if (senderId != null && !senderId.isBlank()) {
            String raw = "dm:" + channel + ":" + senderId;
            return agentId != null ? "agent:" + agentId + ":" + raw : raw;
        }

        // 兜底
        return agentId != null ? "agent:" + agentId + ":default" : "default";
    }
}
