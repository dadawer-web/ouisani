package com.ouisani.aios.core.team;

import java.util.UUID;

/**
 * 团队协作消息协议 (The Actor Message)。
 * <p>
 * 对标 oh-my-openagent 的 Team Mailbox 机制：Agent 之间通过异步消息协作，
 * 每条消息携带发件人、收件人、类型和负载，实现松耦合的数字实体通信。
 * <p>
 * 消息类型：
 * <pre>
 *   TASK_ASSIGN   — 委派任务（上级向下级派活）
 *   STATUS_UPDATE — 汇报进度（下级向上级 toast）
 *   QUESTION      — 反向提问（遇到 blocker 求助）
 *   REPLY         — 答复（对 QUESTION/TASK_ASSIGN 的回应）
 *   POISON_PILL   — 死亡药丸（通知 Agent 下班销毁）
 * </pre>
 *
 * @see AgentMailbox
 */
public class MailMessage {

    private final String messageId;
    private final String senderId;     // 发件人 (如: "Architect_01")
    private final String receiverId;   // 收件人 (如: "Coder_02")
    private final MessageType type;    // 消息类型
    private final Object payload;      // 消息负载 (JSON/String/AST)
    private final long timestamp;

    /**
     * 消息类型枚举 — 对标 oh-my-openagent 的协作协议。
     */
    public enum MessageType {
        /** 委派任务 — 上级向下级派活 */
        TASK_ASSIGN,
        /** 汇报进度 — 下级向上级 toast (如 oh-my-openagent 的 toast) */
        STATUS_UPDATE,
        /** 反向提问 — 遇到 blocker 求助 */
        QUESTION,
        /** 答复 — 对 QUESTION/TASK_ASSIGN 的回应 */
        REPLY,
        /** 死亡药丸 — 通知 Agent 下班销毁 */
        POISON_PILL
    }

    public MailMessage(String senderId, String receiverId, MessageType type, Object payload) {
        this.messageId = UUID.randomUUID().toString();
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.type = type;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
    }

    // ════════════════════════════════════════════════════════════════
    //  Getters
    // ════════════════════════════════════════════════════════════════

    public String getMessageId() { return messageId; }
    public String getSenderId() { return senderId; }
    public String getReceiverId() { return receiverId; }
    public MessageType getType() { return type; }
    public Object getPayload() { return payload; }
    public long getTimestamp() { return timestamp; }

    /**
     * 获取字符串形式的负载（安全转换）。
     */
    public String getPayloadAsString() {
        return payload != null ? payload.toString() : "";
    }

    @Override
    public String toString() {
        return String.format("[%s] %s -> %s: %s", type, senderId, receiverId, payload);
    }
}
