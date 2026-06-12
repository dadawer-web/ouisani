package com.ouisani.aios.openclaw.channel;

/**
 * 消息接收上下文 — 对标 OpenClaw 的 MessageReceiveContext。
 * <p>
 * 管理入站消息的确认（ack/nack）状态机，
 * 确保消息不会丢失或重复处理。
 * <p>
 * 状态转换：PENDING → ACKED | NACKED（不可逆）
 */
public class MessageReceiveContext {

    /** 确认策略 */
    public enum AckPolicy {
        /** 接收记录后确认 */
        AFTER_RECEIVE_RECORD,
        /** Agent 调度后确认 */
        AFTER_AGENT_DISPATCH,
        /** 持久发送后确认 */
        AFTER_DURABLE_SEND,
        /** 手动确认 */
        MANUAL
    }

    /** 消息确认状态 */
    public enum AckState {
        PENDING, ACKED, NACKED
    }

    private final String messageId;
    private final String channelId;
    private final String senderId;
    private final String text;
    private final AckPolicy ackPolicy;
    private volatile AckState ackState = AckState.PENDING;

    public MessageReceiveContext(String messageId, String channelId,
                                  String senderId, String text, AckPolicy ackPolicy) {
        this.messageId = messageId;
        this.channelId = channelId;
        this.senderId = senderId;
        this.text = text;
        this.ackPolicy = ackPolicy;
    }

    /** 确认消息 — 状态从 PENDING 转为 ACKED */
    public void ack() {
        if (ackState == AckState.PENDING) {
            ackState = AckState.ACKED;
        }
    }

    /** 拒绝消息 — 状态从 PENDING 转为 NACKED */
    public void nack() {
        if (ackState == AckState.PENDING) {
            ackState = AckState.NACKED;
        }
    }

    public String messageId() { return messageId; }
    public String channelId() { return channelId; }
    public String senderId() { return senderId; }
    public String text() { return text; }
    public AckPolicy ackPolicy() { return ackPolicy; }
    public AckState ackState() { return ackState; }
    public boolean isPending() { return ackState == AckState.PENDING; }
    public boolean isAcked() { return ackState == AckState.ACKED; }
    public boolean isNacked() { return ackState == AckState.NACKED; }
}
