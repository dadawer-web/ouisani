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
public class MailMessage implements Comparable<MailMessage> {

    private final String messageId;
    private final String senderId;     // 发件人 (如: "Architect_01")
    private final String receiverId;   // 收件人 (如: "Coder_02")
    private final MessageType type;    // 消息类型
    private final Object payload;      // 消息负载 (JSON/String/AST)
    private final long timestamp;

    /** 端到端 Trace ID — 贯穿整个调用链 */
    private final String traceId;
    private final Priority priority;   // 消息优先级

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

    /**
     * 消息优先级 — 借鉴 Linux 进程的 nice 值。
     * 数字越小优先级越高，系统级消息永远插队执行。
     */
    public enum Priority {
        /** 系统级最高优先级（POISON_PILL、SIGTERM 等） */
        SYSTEM_CRITICAL(0),
        /** 高优先级（紧急任务、中断通知） */
        HIGH(1),
        /** 普通优先级（TASK_ASSIGN、STATUS_UPDATE 等） */
        NORMAL(2),
        /** 低优先级（非紧急通知） */
        LOW(3);

        private final int level;
        Priority(int level) { this.level = level; }
        public int getLevel() { return level; }
    }

    public MailMessage(String senderId, String receiverId, MessageType type, Object payload) {
        this(senderId, receiverId, type, payload,
             type == MessageType.POISON_PILL ? Priority.SYSTEM_CRITICAL : Priority.NORMAL);
    }

    public MailMessage(String senderId, String receiverId, MessageType type, Object payload, Priority priority) {
        this.messageId = UUID.randomUUID().toString();
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.type = type;
        this.payload = payload;
        this.priority = priority;
        this.timestamp = System.currentTimeMillis();
        // 自动从当前线程上下文继承 Trace ID，或从 VariablePool 获取
        String tid = com.ouisani.aios.core.ipc.TraceContext.getCurrentTraceId();
        this.traceId = tid != null ? tid : com.ouisani.aios.core.ipc.TraceContext.generateTraceId();
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
    public Priority getPriority() { return priority; }
    public String getTraceId() { return traceId; }

    /**
     * 获取字符串形式的负载（安全转换）。
     */
    public String getPayloadAsString() {
        return payload != null ? payload.toString() : "";
    }

    @Override
    public int compareTo(MailMessage other) {
        // 优先级高的（level 小的）排前面
        int cmp = Integer.compare(this.priority.getLevel(), other.priority.getLevel());
        if (cmp != 0) return cmp;
        // 同优先级按时间戳排序（FIFO）
        return Long.compare(this.timestamp, other.timestamp);
    }

    @Override
    public String toString() {
        return String.format("[%s] [%s/%s] %s -> %s: %s", traceId, type, priority, senderId, receiverId, payload);
    }
}
