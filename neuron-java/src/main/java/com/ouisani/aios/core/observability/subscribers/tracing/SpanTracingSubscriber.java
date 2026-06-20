package com.ouisani.aios.core.observability.subscribers.tracing;

import com.ouisani.aios.core.observability.EventSubscriber;
import com.ouisani.aios.core.observability.EventType;
import com.ouisani.aios.core.observability.ObservabilityEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Span tracing 订阅器，订阅 start/end 事件对并创建 Span。
 * <p>
 * 不直接依赖 {@code TracingManager}（避免循环依赖），而是通过 {@link TracingBridge} 回调接口
 * 委托 Span 的创建/结束。外部代码（如初始化逻辑）可通过 {@link #setTracingBridge(TracingBridge)}
 * 注入桥接实现，将其适配到 {@code com.ouisani.aios.core.trace.TracingManager}。
 * <p>
 * 通过 {@code sessionId} 关联 start/end 对：start 时调用 {@link TracingBridge#startSpan} 获得
 * spanId 并缓存，end 时取出 spanId 调用 {@link TracingBridge#endSpan}。
 */
public class SpanTracingSubscriber extends EventSubscriber {

    /**
     * Span 生命周期回调接口，用于将 Span 创建/结束委托给外部 TracingManager。
     */
    public interface TracingBridge {

        /**
         * 创建并启动一个 Span。
         *
         * @param name   Span 名称
         * @param event  触发该 Span 的可观测事件
         * @return 新 Span 的 ID，若桥接未启用则返回 {@code null}
         */
        String startSpan(String name, ObservabilityEvent event);

        /**
         * 结束一个 Span。
         *
         * @param spanId 要结束的 Span ID
         * @param event  触发结束的可观测事件
         */
        void endSpan(String spanId, ObservabilityEvent event);
    }

    /** 默认 no-op 桥接。 */
    private static final TracingBridge NOOP = new TracingBridge() {
        @Override
        public String startSpan(String name, ObservabilityEvent event) {
            return null;
        }

        @Override
        public void endSpan(String spanId, ObservabilityEvent event) {
            // no-op
        }
    };

    private volatile TracingBridge bridge = NOOP;

    /** sessionId → spanId，用于配对 start/end。 */
    private final ConcurrentHashMap<String, String> sessionToSpan = new ConcurrentHashMap<>();

    /**
     * 注入 TracingBridge 桥接实现。传 {@code null} 时回退为 no-op。
     *
     * @param bridge 桥接实现
     */
    public void setTracingBridge(TracingBridge bridge) {
        this.bridge = bridge != null ? bridge : NOOP;
    }

    @Override
    public Map<EventType, Consumer<ObservabilityEvent>> getSubscriptions() {
        return Map.of(
                EventType.LLM_THINK_START, e -> startSpan(e, "llm.think"),
                EventType.LLM_THINK_END, this::endSpan,
                EventType.TOOL_CALL_START, e -> startSpan(e, "tool.call"),
                EventType.TOOL_CALL_END, this::endSpan,
                EventType.TASK_SPAWN, e -> startSpan(e, "task.spawn"),
                EventType.TASK_COMPLETE, this::endSpan
        );
    }

    private void startSpan(ObservabilityEvent event, String name) {
        String spanId = bridge.startSpan(name, event);
        String sid = event.sessionId();
        if (spanId != null && sid != null && !sid.isEmpty()) {
            sessionToSpan.put(sid, spanId);
        }
    }

    private void endSpan(ObservabilityEvent event) {
        String sid = event.sessionId();
        if (sid == null || sid.isEmpty()) {
            return;
        }
        String spanId = sessionToSpan.remove(sid);
        if (spanId != null) {
            bridge.endSpan(spanId, event);
        }
    }

    @Override
    public void shutdown() {
        sessionToSpan.clear();
    }
}
