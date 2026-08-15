package com.ouisani.aios.core.observability.subscribers.metrics;

import com.ouisani.aios.core.observability.EventSubscriber;
import com.ouisani.aios.core.observability.EventType;
import com.ouisani.aios.core.observability.ObservabilityEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * LLM metrics 订阅器，订阅 {@link EventType#LLM_THINK_START}/{@link EventType#LLM_THINK_END} 事件。
 * <p>
 * 通过 {@code sessionId} 关联 start/end 对，累计 LLM 调用次数与总耗时（毫秒）。
 */
public class LLMMetricsSubscriber extends EventSubscriber {

    private final AtomicLong llmCallCount = new AtomicLong();
    private final AtomicLong totalLlmDurationMs = new AtomicLong();

    /** sessionId → start 纳秒时间戳，用于配对计算耗时。 */
    private final ConcurrentHashMap<String, Long> startNanos = new ConcurrentHashMap<>();

    @Override
    public Map<EventType, Consumer<ObservabilityEvent>> getSubscriptions() {
        return Map.of(
                EventType.LLM_THINK_START, this::onThinkStart,
                EventType.LLM_THINK_END, this::onThinkEnd
        );
    }

    private void onThinkStart(ObservabilityEvent event) {
        String sid = event.sessionId();
        if (sid != null && !sid.isEmpty()) {
            startNanos.put(sid, System.nanoTime());
        }
    }

    private void onThinkEnd(ObservabilityEvent event) {
        String sid = event.sessionId();
        if (sid == null || sid.isEmpty()) {
            return;
        }
        Long start = startNanos.remove(sid);
        if (start != null) {
            long durationMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
            totalLlmDurationMs.addAndGet(durationMs);
            llmCallCount.incrementAndGet();
        }
    }

    /**
     * 返回已完成的 LLM 调用累计次数（仅成功配对 start/end 的调用）。
     *
     * @return 调用次数
     */
    public long getLlmCallCount() {
        return llmCallCount.get();
    }

    /**
     * 返回 LLM 调用累计耗时（毫秒）。
     *
     * @return 总耗时（毫秒）
     */
    public long getTotalLlmDurationMs() {
        return totalLlmDurationMs.get();
    }
}
