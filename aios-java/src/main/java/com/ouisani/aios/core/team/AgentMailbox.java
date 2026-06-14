package com.ouisani.aios.core.team;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Agent 的私人信箱 (Actor Inbox)。
 * <p>
 * 基于 BlockingQueue 实现，结合 Java 21 Virtual Threads 的高效挂起等待：
 * 当 Agent 调用 readNext() 阻塞时，虚拟线程会自动让出载体线程，
 * 不消耗 OS 线程资源，实现百万级 Agent 并发。
 * <p>
 * 对标 oh-my-openagent 的 Team Mailbox 机制：
 * <pre>
 *   ┌─────────────┐    deliver()    ┌──────────────┐    readNext()   ┌─────────────┐
 *   │ Agent A     │ ──────────────→ │ Agent B      │ ←────────────── │ Agent B     │
 *   │ (Architect) │                 │ Mailbox      │                 │ (Coder)     │
 *   └─────────────┘                 └──────────────┘                 └─────────────┘
 * </pre>
 *
 * @see MailMessage
 */
public class AgentMailbox {

    private static final Logger log = LoggerFactory.getLogger(AgentMailbox.class);

    /** 信箱主人的 Agent ID */
    private final String ownerId;

    /** 阻塞队列 — 无界，支持任意数量的积压消息 */
    private final BlockingQueue<MailMessage> inbox = new LinkedBlockingQueue<>();

    /** 信箱容量监控 — 记录历史峰值，用于容量规划 */
    private int peakSize = 0;

    public AgentMailbox(String ownerId) {
        this.ownerId = ownerId;
    }

    /**
     * 投递邮件 — 其他 Agent 往此信箱发送消息。
     * 非阻塞，立即返回。
     */
    public void deliver(MailMessage message) {
        inbox.offer(message);
        int currentSize = inbox.size();
        if (currentSize > peakSize) {
            peakSize = currentSize;
        }
        log.debug("[Mailbox] {} received mail from {} (Type: {}, Queue: {})",
                ownerId, message.getSenderId(), message.getType(), currentSize);

        // 【广播邮件飞梭动效事件 — 供前端大屏渲染 Actor 间通讯动画】
        try {
            String payload = String.format(
                "{\"eventType\":\"MAIL_DELIVERED\", \"sender\":\"%s\", \"receiver\":\"%s\", \"mailType\":\"%s\", \"timestamp\":%d}",
                message.getSenderId(), ownerId, message.getType().name(), System.currentTimeMillis()
            );
            com.ouisani.aios.core.network.EventBus.instance().broadcast("sys.telemetry.events", payload);
        } catch (Exception ignore) {}
    }

    /**
     * 阻塞读取信箱 — 结合 Java 21 虚拟线程，阻塞不消耗 OS 线程。
     * <p>
     * 适用于 Agent 的主循环：while(alive) { msg = mailbox.readNext(30, SECONDS); ... }
     *
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return 收到的消息，超时返回 null
     */
    public MailMessage readNext(long timeout, TimeUnit unit) throws InterruptedException {
        return inbox.poll(timeout, unit);
    }

    /**
     * 无阻塞读取 — 扫一眼信箱有没有新信，没有立即返回 null。
     */
    public MailMessage checkNext() {
        return inbox.poll();
    }

    /**
     * 批量读取 — 一次性取出所有积压消息（非阻塞）。
     * 适用于 Agent 被唤醒后批量处理积压邮件。
     */
    public List<MailMessage> drainAll() {
        List<MailMessage> messages = new ArrayList<>();
        inbox.drainTo(messages);
        return messages;
    }

    /**
     * 按类型筛选读取 — 只取出特定类型的消息，其余放回队列。
     */
    public MailMessage readByType(MailMessage.MessageType type, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        List<MailMessage> skipped = new ArrayList<>();

        try {
            while (System.currentTimeMillis() < deadline) {
                MailMessage msg = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (msg == null) continue;

                if (msg.getType() == type) {
                    return msg;
                }
                skipped.add(msg);
            }
            return null;
        } finally {
            // 放回非目标类型的消息
            for (MailMessage msg : skipped) {
                inbox.offer(msg);
            }
        }
    }

    /** 当前信箱积压数量 */
    public int size() {
        return inbox.size();
    }

    /** 信箱是否为空 */
    public boolean isEmpty() {
        return inbox.isEmpty();
    }

    /** 历史峰值积压量 */
    public int getPeakSize() {
        return peakSize;
    }

    /** 信箱主人 ID */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * 清空信箱 — Agent 销毁时调用。
     */
    public void clear() {
        int cleared = inbox.size();
        inbox.clear();
        if (cleared > 0) {
            log.debug("[Mailbox] {} cleared {} undelivered messages", ownerId, cleared);
        }
    }
}
