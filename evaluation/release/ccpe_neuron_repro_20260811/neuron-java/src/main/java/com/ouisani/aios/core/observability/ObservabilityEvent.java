package com.ouisani.aios.core.observability;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 一条可观测事件，参考 LMCache 的 {@code Event}。
 * <p>
 * 不可变 record。{@code timestamp} 由 {@link ObservabilityEventBus#publish} 在入队时打戳；
 * 调用方构造时传 {@code 0} 即可（使用 {@link #create} 工厂）。{@code metadata} 为扁平键值负载，
 * 会在紧凑构造器中做防御性拷贝并封装为不可变视图。{@code sessionId} 用于关联 start/end 事件对。
 *
 * @param eventType 事件类型
 * @param timestamp 入队时间戳（毫秒），由 EventBus.publish() 设置
 * @param metadata  扁平键值负载（不可变视图）
 * @param sessionId 会话 ID，用于关联 start/end 对
 */
public record ObservabilityEvent(
        EventType eventType,
        long timestamp,
        Map<String, Object> metadata,
        String sessionId
) {
    /**
     * 紧凑构造器：对 metadata 做防御性拷贝并封装为不可变视图。
     */
    public ObservabilityEvent {
        Map<String, Object> copy = new HashMap<>();
        if (metadata != null) {
            copy.putAll(metadata);
        }
        metadata = Collections.unmodifiableMap(copy);
    }

    /**
     * 创建一条尚未打戳的事件（timestamp=0），由 EventBus.publish() 负责打戳。
     *
     * @param eventType 事件类型
     * @param metadata  扁平键值负载（允许为 null）
     * @param sessionId 会话 ID
     * @return 未打戳的事件
     */
    public static ObservabilityEvent create(EventType eventType, Map<String, Object> metadata, String sessionId) {
        return new ObservabilityEvent(eventType, 0L, metadata, sessionId);
    }

    /**
     * 创建一条尚未打戳的事件，sessionId 为空串。
     *
     * @param eventType 事件类型
     * @param metadata  扁平键值负载（允许为 null）
     * @return 未打戳的事件
     */
    public static ObservabilityEvent create(EventType eventType, Map<String, Object> metadata) {
        return create(eventType, metadata, "");
    }
}
