package com.ouisani.aios.core.trace;

/**
 * Tracing Processor — Span 生命周期监听器，参考 OpenAI Agents Python 的 TracingProcessor。
 * <p>
 * Processor 在 Span/Trace 的关键节点被回调，用于：
 * <ul>
 *   <li>收集 Span 到内存缓冲区（BatchProcessor）</li>
 *   <li>实时输出日志（{@link com.ouisani.aios.core.trace.builtin.LoggingTracingProcessor}）</li>
 *   <li>广播到 EventBus（{@link com.ouisani.aios.core.trace.builtin.EventBusTracingProcessor}）</li>
 *   <li>导出到外部系统（通过 {@link TracingExporter}）</li>
 * </ul>
 * <p>
 * 实现必须是线程安全的 — TracingManager 可能从多个虚拟线程同时触发回调。
 *
 * @see TracingManager
 * @see TracingExporter
 */
public interface TracingProcessor {

    /**
     * 当一个新的 Trace 开始时调用（首个 Span 创建前）。
     *
     * @param traceId Trace ID
     */
    void onTraceStart(String traceId);

    /**
     * 当一个 Trace 结束时调用（最后一个 Span 结束后）。
     *
     * @param traceId Trace ID
     */
    void onTraceEnd(String traceId);

    /**
     * 当一个 Span 开始时调用（startSpan 之后立即触发）。
     *
     * @param span 刚刚开始的 Span
     */
    void onSpanStart(TraceSpan span);

    /**
     * 当一个 Span 结束时调用（endSpan 之后立即触发）。
     *
     * @param span 刚刚结束的 Span（endTimeNanos 已设置）
     */
    void onSpanEnd(TraceSpan span);

    /**
     * 关闭 Processor，释放资源。TracingManager.shutdown() 时调用。
     */
    void shutdown();
}
