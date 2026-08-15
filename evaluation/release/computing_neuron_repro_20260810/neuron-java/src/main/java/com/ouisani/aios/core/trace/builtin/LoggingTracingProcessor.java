package com.ouisani.aios.core.trace.builtin;

import com.ouisani.aios.core.trace.TraceSpan;
import com.ouisani.aios.core.trace.TracingProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志输出 Processor — 将 Span 开始/结束事件输出到 SLF4J Logger。
 * <p>
 * 适用于本地调试和开发环境，提供与 OpenAI Agents Python 的 ConsoleSpanProcessor 类似的体验。
 * <p>
 * 输出示例：
 * <pre>
 * [Tracing] ▶ START span='agent.query' type=TASK spanId=... parent=null
 * [Tracing] ▶ START span='llm.generate' type=GENERATION spanId=... parent=...
 * [Tracing] ■ END   span='llm.generate' type=GENERATION durationMs=1234 status=OK
 * [Tracing] ■ END   span='agent.query' type=TASK durationMs=5678 status=OK
 * </pre>
 */
public class LoggingTracingProcessor implements TracingProcessor {

    private static final Logger log = LoggerFactory.getLogger(LoggingTracingProcessor.class);

    private final boolean debugLevel;

    /** 默认使用 INFO 级别输出。 */
    public LoggingTracingProcessor() {
        this(false);
    }

    /**
     * @param debugLevel true 时使用 DEBUG 级别（生产环境推荐），false 时使用 INFO 级别（开发环境）
     */
    public LoggingTracingProcessor(boolean debugLevel) {
        this.debugLevel = debugLevel;
    }

    @Override
    public void onTraceStart(String traceId) {
        emit("[Tracing] ╔══ TRACE START traceId={}", traceId);
    }

    @Override
    public void onTraceEnd(String traceId) {
        emit("[Tracing] ╚══ TRACE END   traceId={}", traceId);
    }

    @Override
    public void onSpanStart(TraceSpan span) {
        emit("[Tracing] ▶ START span='{}' type={} spanId={} parent={}",
                span.name(), span.spanType(), span.spanId(),
                span.parentSpanId() != null ? span.parentSpanId() : "-");
    }

    @Override
    public void onSpanEnd(TraceSpan span) {
        emit("[Tracing] ■ END   span='{}' type={} durationMs={} status={} attrs={}",
                span.name(), span.spanType(), span.duration(), span.status(),
                span.attributes().isEmpty() ? "{}" : span.attributes());
    }

    @Override
    public void shutdown() {
        emit("[Tracing] LoggingTracingProcessor 已关闭", "");
    }

    private void emit(String format, Object... args) {
        if (debugLevel) {
            if (log.isDebugEnabled()) {
                log.debug(format, args);
            }
        } else {
            log.info(format, args);
        }
    }
}
