package com.ouisani.aios.core.trace.builtin;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.trace.TraceSpan;
import com.ouisani.aios.core.trace.TracingProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EventBus 广播 Processor — 将 Span 事件通过 {@link EventBus} 广播到 {@code sys.trace.span} 频道。
 * <p>
 * 前端可订阅该频道实时展示 Span 树（类似 Jaeger UI 的实时火焰图）。
 * <p>
 * 广播的 JSON 结构：
 * <pre>
 * {
 *   "phase": "start" | "end" | "trace_start" | "trace_end",
 *   "traceId": "...",
 *   "spanId": "...",
 *   "parentSpanId": "...",
 *   "name": "agent.query",
 *   "type": "TASK",
 *   "startTimeUnixNano": "...",
 *   "endTimeUnixNano": "...",
 *   "durationMs": 1234,
 *   "status": "OK",
 *   "attributes": { ... }
 * }
 * </pre>
 */
public class EventBusTracingProcessor implements TracingProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventBusTracingProcessor.class);

    /** Span 事件广播频道。 */
    public static final String CHANNEL = "sys.trace.span";

    @Override
    public void onTraceStart(String traceId) {
        broadcast(buildPhase("trace_start", traceId, null));
    }

    @Override
    public void onTraceEnd(String traceId) {
        broadcast(buildPhase("trace_end", traceId, null));
    }

    @Override
    public void onSpanStart(TraceSpan span) {
        broadcast(buildSpanEvent("start", span));
    }

    @Override
    public void onSpanEnd(TraceSpan span) {
        broadcast(buildSpanEvent("end", span));
    }

    @Override
    public void shutdown() {
        log.info("[EventBusTracingProcessor] 已关闭，停止广播到频道 {}", CHANNEL);
    }

    private static Map<String, Object> buildPhase(String phase, String traceId, TraceSpan span) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase);
        m.put("traceId", traceId);
        if (span != null) {
            m.put("spanId", span.spanId());
            m.put("name", span.name());
            m.put("type", span.spanType().name());
        }
        return m;
    }

    private static Map<String, Object> buildSpanEvent(String phase, TraceSpan span) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase);
        m.put("traceId", span.traceId());
        m.put("spanId", span.spanId());
        m.put("parentSpanId", span.parentSpanId());
        m.put("name", span.name());
        m.put("type", span.spanType().name());
        m.put("startTimeUnixNano", String.valueOf(span.startTimeNanos()));
        if (span.endTimeNanos() > 0) {
            m.put("endTimeUnixNano", String.valueOf(span.endTimeNanos()));
        }
        m.put("durationMs", span.duration());
        m.put("status", span.status().name());
        m.put("attributes", span.attributes());
        return m;
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private static void broadcast(Map<String, Object> payload) {
        try {
            EventBus.instance().broadcast(CHANNEL, GSON.toJson(payload));
        } catch (Exception e) {
            log.debug("[EventBusTracingProcessor] 广播失败: {}", e.getMessage());
        }
    }
}
