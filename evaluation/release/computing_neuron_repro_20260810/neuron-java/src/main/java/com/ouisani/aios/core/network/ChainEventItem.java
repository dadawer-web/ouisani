package com.ouisani.aios.core.network;

/**
 * 责任链事件项 — 携带可变 {@code accepted} 标志的事件，支持链中断。
 * <p>
 * 借鉴 Apix 的 {@code ApixEventItem}（可变 dataclass），适配 Java。
 * <p>
 * <b>核心机制</b>：handler 处理事件后可调用 {@link #accept()} 标记事件为已接受，
 * 后续低优先级的 handler 将不再被调用。{@code accepted} 是可变字段，
 * handler 在虚拟线程中修改后，派发循环能立即读到新值。
 * <p>
 * <b>典型场景</b>：
 * <ul>
 *   <li>安全审查：高优先级的安全 handler 拦截危险操作后 accept，低优先级的日志 handler 跳过</li>
 *   <li>权限校验：高优先级的权限 handler 验证通过后 accept，低优先级的回退 handler 跳过</li>
 * </ul>
 *
 * @see ChainEventDispatcher
 */
public class ChainEventItem {

    private final String eventType;
    private final String content;
    private final long timestamp;
    private final String generationId;
    /** 可变标志 — handler 调用 {@link #accept()} 后设为 true，使用 volatile 保证跨线程可见性 */
    private volatile boolean accepted;

    /**
     * 创建未接受的事件项。
     *
     * @param eventType    事件类型（频道名）
     * @param content      事件内容（payload）
     * @param generationId 关联的 generation ID（可为 null）
     */
    public ChainEventItem(String eventType, String content, String generationId) {
        this.eventType = eventType;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
        this.generationId = generationId;
        this.accepted = false;
    }

    /** 事件类型（频道名） */
    public String eventType() {
        return eventType;
    }

    /** 事件内容 */
    public String content() {
        return content;
    }

    /** 事件时间戳 */
    public long timestamp() {
        return timestamp;
    }

    /** 关联的 generation ID */
    public String generationId() {
        return generationId;
    }

    /** 是否已被接受（链中断标志） */
    public boolean accepted() {
        return accepted;
    }

    /**
     * 标记事件为已接受 — 后续低优先级 handler 不应再处理此事件。
     * <p>
     * 使用 volatile 写保证对派发循环的可见性。
     */
    public void accept() {
        this.accepted = true;
    }
}
