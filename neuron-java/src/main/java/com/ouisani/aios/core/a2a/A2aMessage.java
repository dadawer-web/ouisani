package com.ouisani.aios.core.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * A2A 消息 — 跨节点 Agent 间通信的消息格式。
 * <p>
 * 借鉴 Agent Zero 的 A2A 协议，基于 JSON-RPC 2.0 格式。
 */
public class A2aMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String messageId;
    private final A2aProtocol.MessageType type;
    private final String senderNodeId;
    private final String senderAgentId;
    private final String targetNodeId;
    private final String targetAgentId;
    private final String payload;
    private final long timestamp;
    private final String replyTo; // 回复的消息 ID

    public A2aMessage(A2aProtocol.MessageType type, String senderNodeId, String senderAgentId,
                      String targetNodeId, String targetAgentId, String payload) {
        this(UUID.randomUUID().toString(), type, senderNodeId, senderAgentId,
                targetNodeId, targetAgentId, payload, null, System.currentTimeMillis());
    }

    public A2aMessage(String messageId, A2aProtocol.MessageType type, String senderNodeId,
                      String senderAgentId, String targetNodeId, String targetAgentId,
                      String payload, String replyTo, long timestamp) {
        this.messageId = messageId;
        this.type = type;
        this.senderNodeId = senderNodeId;
        this.senderAgentId = senderAgentId;
        this.targetNodeId = targetNodeId;
        this.targetAgentId = targetAgentId;
        this.payload = payload;
        this.replyTo = replyTo;
        this.timestamp = timestamp;
    }

    // ── 序列化/反序列化 ──

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                    .put("protocol", A2aProtocol.PROTOCOL_VERSION)
                    .put("messageId", messageId)
                    .put("type", type.name())
                    .put("senderNodeId", senderNodeId)
                    .put("senderAgentId", senderAgentId)
                    .put("targetNodeId", targetNodeId)
                    .put("targetAgentId", targetAgentId)
                    .put("payload", payload)
                    .put("replyTo", replyTo)
                    .put("timestamp", timestamp));
        } catch (Exception e) {
            return "{}";
        }
    }

    public static A2aMessage fromJson(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return new A2aMessage(
                    node.path("messageId").asText(),
                    A2aProtocol.MessageType.valueOf(node.path("type").asText()),
                    node.path("senderNodeId").asText(),
                    node.path("senderAgentId").asText(),
                    node.path("targetNodeId").asText(),
                    node.path("targetAgentId").asText(),
                    node.path("payload").asText(),
                    node.path("replyTo").asText(null),
                    node.path("timestamp").asLong()
            );
        } catch (Exception e) {
            return null;
        }
    }

    // ── 创建回复 ──

    public A2aMessage createReply(String replyPayload) {
        return new A2aMessage(
                UUID.randomUUID().toString(),
                A2aProtocol.MessageType.TASK_RESULT,
                this.targetNodeId,
                this.targetAgentId,
                this.senderNodeId,
                this.senderAgentId,
                replyPayload,
                this.messageId,
                System.currentTimeMillis()
        );
    }

    // ── Getters ──
    public String getMessageId() { return messageId; }
    public A2aProtocol.MessageType getType() { return type; }
    public String getSenderNodeId() { return senderNodeId; }
    public String getSenderAgentId() { return senderAgentId; }
    public String getTargetNodeId() { return targetNodeId; }
    public String getTargetAgentId() { return targetAgentId; }
    public String getPayload() { return payload; }
    public String getReplyTo() { return replyTo; }
    public long getTimestamp() { return timestamp; }
}
