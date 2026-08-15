package com.ouisani.aios.core.trace;

import java.util.List;

/**
 * Tracing Exporter — 将完成的 Span 批量导出到外部系统，参考 OpenTelemetry SpanExporter。
 * <p>
 * 与 {@link TracingProcessor} 的区别：
 * <ul>
 *   <li>Processor 关注 Span 生命周期事件（start/end）</li>
 *   <li>Exporter 关注批量导出已完成的 Span（push 模型）</li>
 * </ul>
 * <p>
 * 典型实现：
 * <ul>
 *   <li>Jaeger / Zipkin / Tempo — 通过 OTLP 协议推送</li>
 *   <li>{@link com.ouisani.aios.core.trace.builtin.JsonFileTracingExporter} — 落盘为 JSON 文件</li>
 *   <li>Stdout — 打印到控制台</li>
 * </ul>
 * <p>
 * 实现必须是线程安全的。
 *
 * @see TracingManager
 * @see TracingProcessor
 */
public interface TracingExporter {

    /**
     * 批量导出已完成的 Span。
     *
     * @param spans 已结束的 Span 列表（endTimeNanos 已设置）
     */
    void export(List<TraceSpan> spans);
}
