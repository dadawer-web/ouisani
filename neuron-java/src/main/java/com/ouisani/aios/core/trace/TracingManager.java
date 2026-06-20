package com.ouisani.aios.core.trace;

import com.ouisani.aios.core.ipc.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Tracing Manager — Span 树的中央管理器，参考 OpenAI Agents Python 的 Tracing。
 * <p>
 * 职责：
 * <ul>
 *   <li>维护当前线程的活跃 Span 栈（{@link ThreadLocal}{@code <Deque<String>>}，虚拟线程安全）</li>
 *   <li>管理 {@link TracingProcessor} 列表和 {@link TracingExporter} 列表</li>
 *   <li>提供 startSpan / endSpan / currentSpan / withSpan 等便捷 API</li>
 *   <li>traceId 从 {@link TraceContext} 继承，未设置时自动生成</li>
 *   <li>禁用时所有操作都是 no-op</li>
 * </ul>
 * <p>
 * 与现有 {@link TraceManager}（strace/ftrace 磁带录制）正交：
 * TraceManager 关注 LLM 调用的录制/回放，TracingManager 关注结构化 Span 树的可观测性。
 * <p>
 * 单例模式 — 通过 {@link #instance()} 获取。
 *
 * @see TraceSpan
 * @see TracingProcessor
 * @see TracingExporter
 */
public final class TracingManager {

    private static final Logger log = LoggerFactory.getLogger(TracingManager.class);

    private static final class Holder {
        static final TracingManager INSTANCE = new TracingManager();
    }

    public static TracingManager instance() {
        return Holder.INSTANCE;
    }

    /** 当前线程的活跃 Span ID 栈 — 虚拟线程安全。 */
    private final ThreadLocal<Deque<String>> spanStack = ThreadLocal.withInitial(LinkedBlockingDeque::new);

    /** spanId → TraceSpan 的全局索引（用于跨线程 endSpan 查找）。 */
    private final Map<String, TraceSpan> activeSpans = new ConcurrentHashMap<>();

    /** 最近完成的 Span 缓冲区 — 供 API 查询。容量上限避免内存膨胀。 */
    private final Deque<TraceSpan> recentSpans = new LinkedBlockingDeque<>(1000);

    /** 已注册的 Processor 列表。 */
    private final List<TracingProcessor> processors = new CopyOnWriteArrayList<>();

    /** 已注册的 Exporter 列表。 */
    private final List<TracingExporter> exporters = new CopyOnWriteArrayList<>();

    /** 是否禁用 Tracing。禁用时所有操作都是 no-op。 */
    private volatile boolean tracingDisabled = false;

    /** 当前活跃的 Trace ID 集合 — 用于 onTraceStart/onTraceEnd 触发。 */
    private final Map<String, Integer> activeTraceCount = new ConcurrentHashMap<>();

    private TracingManager() {
    }

    // ── 配置 ────────────────────────────────────────────────────────

    /** 添加 Processor。 */
    public void addProcessor(TracingProcessor processor) {
        Objects.requireNonNull(processor, "processor");
        processors.add(processor);
        log.info("[TracingManager] Processor 已注册: {} (total={})",
                processor.getClass().getSimpleName(), processors.size());
    }

    /** 添加 Exporter。 */
    public void addExporter(TracingExporter exporter) {
        Objects.requireNonNull(exporter, "exporter");
        exporters.add(exporter);
        log.info("[TracingManager] Exporter 已注册: {} (total={})",
                exporter.getClass().getSimpleName(), exporters.size());
    }

    /** 启用/禁用 Tracing。 */
    public void setTracingDisabled(boolean disabled) {
        this.tracingDisabled = disabled;
        log.info("[TracingManager] Tracing 已{}", disabled ? "禁用" : "启用");
    }

    /** Tracing 是否被禁用。 */
    public boolean isTracingDisabled() {
        return tracingDisabled;
    }

    /** Processor 数量。 */
    public int processorCount() { return processors.size(); }

    /** Exporter 数量。 */
    public int exporterCount() { return exporters.size(); }

    /** 最近完成的 Span 数量（缓冲区当前大小）。 */
    public int recentSpanCount() { return recentSpans.size(); }

    /** 获取最近完成的 Span 列表（只读视图，按时间倒序）。 */
    public List<TraceSpan> getRecentSpans() {
        List<TraceSpan> snapshot = new ArrayList<>(recentSpans);
        Collections.reverse(snapshot);
        return Collections.unmodifiableList(snapshot);
    }

    // ── Span 生命周期 ───────────────────────────────────────────────

    /**
     * 创建并启动一个新 Span。
     * <p>
     * parentSpanId 从当前线程的 Span 栈顶获取；traceId 从 {@link TraceContext} 继承。
     * 新 Span 的 spanId 被推入栈顶，成为后续 startSpan 的 parent。
     *
     * @param name     Span 名称（如 "agent.query", "llm.generate"）
     * @param spanType Span 类型
     * @return 新创建的 Span（已启动，未结束）
     */
    public TraceSpan startSpan(String name, TraceSpan.SpanType spanType) {
        if (tracingDisabled) {
            return null;
        }

        Deque<String> stack = spanStack.get();
        String parentSpanId = stack.peekFirst();
        String traceId = TraceContext.getCurrentTraceId();
        if (traceId == null) {
            traceId = TraceContext.generateTraceId();
            TraceContext.setCurrentTraceId(traceId);
        }

        // 首个 Span — 触发 onTraceStart
        boolean traceStarted = stack.isEmpty();
        if (traceStarted) {
            activeTraceCount.merge(traceId, 1, Integer::sum);
            for (TracingProcessor p : processors) {
                try { p.onTraceStart(traceId); }
                catch (Exception e) { logProcessorError(p, "onTraceStart", e); }
            }
        }

        TraceSpan span = TraceSpan.builder(name, spanType)
                .traceId(traceId)
                .parentSpanId(parentSpanId)
                .build();

        activeSpans.put(span.spanId(), span);
        stack.push(span.spanId());

        for (TracingProcessor p : processors) {
            try { p.onSpanStart(span); }
            catch (Exception e) { logProcessorError(p, "onSpanStart", e); }
        }

        log.debug("[TracingManager] startSpan: name='{}', type={}, spanId={}, parent={}",
                name, spanType, span.spanId(), parentSpanId);
        return span;
    }

    /**
     * 结束一个 Span。
     * <p>
     * 设置 endTimeNanos，从当前线程栈弹出（若栈顶匹配），通知所有 Processor 的 onSpanEnd，
     * 累积到 recentSpans 缓冲区。若该 Span 是栈底（最后一个），触发 onTraceEnd。
     *
     * @param spanId 要结束的 Span ID
     */
    public void endSpan(String spanId) {
        if (tracingDisabled || spanId == null) {
            return;
        }

        TraceSpan span = activeSpans.remove(spanId);
        if (span == null) {
            log.warn("[TracingManager] endSpan 未找到 spanId={}", spanId);
            return;
        }

        span.end();

        // 从栈顶弹出（仅当栈顶匹配时；不匹配说明跨线程结束，仅清理 activeSpans）
        Deque<String> stack = spanStack.get();
        if (spanId.equals(stack.peekFirst())) {
            stack.pop();
            if (stack.isEmpty()) {
                spanStack.remove();
            }
        }

        // 缓冲到 recentSpans（满时丢弃最旧）
        if (!recentSpans.offerFirst(span)) {
            recentSpans.removeLast();
            recentSpans.offerFirst(span);
        }

        for (TracingProcessor p : processors) {
            try { p.onSpanEnd(span); }
            catch (Exception e) { logProcessorError(p, "onSpanEnd", e); }
        }

        // 导出器异步导出
        if (!exporters.isEmpty()) {
            List<TraceSpan> batch = List.of(span);
            for (TracingExporter ex : exporters) {
                try { ex.export(batch); }
                catch (Exception e) {
                    log.warn("[TracingManager] Exporter '{}' 导出失败: {}",
                            ex.getClass().getSimpleName(), e.getMessage());
                }
            }
        }

        // 栈空 — 触发 onTraceEnd
        if (stack.isEmpty()) {
            String traceId = span.traceId();
            Integer remaining = activeTraceCount.get(traceId);
            if (remaining != null) {
                int newVal = remaining - 1;
                if (newVal <= 0) {
                    activeTraceCount.remove(traceId);
                    for (TracingProcessor p : processors) {
                        try { p.onTraceEnd(traceId); }
                        catch (Exception e) { logProcessorError(p, "onTraceEnd", e); }
                    }
                } else {
                    activeTraceCount.put(traceId, newVal);
                }
            }
        }

        log.debug("[TracingManager] endSpan: name='{}', spanId={}, durationMs={}",
                span.name(), span.spanId(), span.duration());
    }

    /**
     * 结束指定 Span 并设置状态。
     */
    public void endSpan(String spanId, TraceSpan.Status status) {
        if (tracingDisabled || spanId == null) {
            return;
        }
        TraceSpan span = activeSpans.get(spanId);
        if (span != null && status != null) {
            span.setStatus(status);
        }
        endSpan(spanId);
    }

    /**
     * 获取当前线程栈顶的 Span（最近 startSpan 且未 endSpan 的 Span）。
     *
     * @return 当前 Span，无活跃 Span 或禁用时返回 null
     */
    public TraceSpan currentSpan() {
        if (tracingDisabled) {
            return null;
        }
        String spanId = spanStack.get().peekFirst();
        return spanId != null ? activeSpans.get(spanId) : null;
    }

    /**
     * 便捷方法：在 Runnable 执行期间自动 start/end Span。
     * <p>
     * 使用 try-finally 语义，确保 Span 总是被关闭（即使 Runnable 抛异常）。
     * 异常时 Span 状态被设置为 ERROR。
     *
     * @param name     Span 名称
     * @param spanType Span 类型
     * @param runnable 要执行的操作
     */
    public void withSpan(String name, TraceSpan.SpanType spanType, Runnable runnable) {
        if (tracingDisabled) {
            runnable.run();
            return;
        }
        TraceSpan span = startSpan(name, spanType);
        try {
            runnable.run();
            if (span != null) {
                span.setStatus(TraceSpan.Status.OK);
            }
        } catch (RuntimeException e) {
            if (span != null) {
                span.setAttribute("error", e.getMessage());
                span.setStatus(TraceSpan.Status.ERROR);
            }
            throw e;
        } finally {
            if (span != null) {
                endSpan(span.spanId());
            }
        }
    }

    /**
     * 为当前 Span 设置属性（便捷方法）。无活跃 Span 时为 no-op。
     */
    public void setAttribute(String key, Object value) {
        if (tracingDisabled) return;
        TraceSpan current = currentSpan();
        if (current != null) {
            current.setAttribute(key, value);
        }
    }

    /**
     * 为当前 Span 添加事件（便捷方法）。无活跃 Span 时为 no-op。
     */
    public void addEvent(String name, Map<String, Object> attributes) {
        if (tracingDisabled) return;
        TraceSpan current = currentSpan();
        if (current != null) {
            current.addEvent(name, attributes);
        }
    }

    /**
     * 强制刷新缓冲区 — 将所有已结束但尚未导出的 Span 推送给 Exporter。
     * <p>
     * 当前实现中 endSpan 已即时导出，此方法主要用于：
     * <ul>
     *   <li>触发 Exporter 内部的批量 flush（如文件落盘、HTTP 推送）</li>
     *   <li>API 端点显式调用，确保数据可见</li>
     * </ul>
     */
    public void flush() {
        if (tracingDisabled) return;
        List<TraceSpan> snapshot = getRecentSpans();
        for (TracingExporter ex : exporters) {
            try { ex.export(snapshot); }
            catch (Exception e) {
                log.warn("[TracingManager] flush 时 Exporter '{}' 失败: {}",
                        ex.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.debug("[TracingManager] flush 完成，已导出 {} 个 Span 给 {} 个 Exporter",
                snapshot.size(), exporters.size());
    }

    /**
     * 关闭 TracingManager — 通知所有 Processor shutdown。
     * 通常在 JVM 关闭钩子中调用。
     */
    public void shutdown() {
        log.info("[TracingManager] 正在关闭，processorCount={}, exporterCount={}",
                processors.size(), exporters.size());
        for (TracingProcessor p : processors) {
            try { p.shutdown(); }
            catch (Exception e) { logProcessorError(p, "shutdown", e); }
        }
    }

    private static void logProcessorError(TracingProcessor p, String method, Exception e) {
        log.warn("[TracingManager] Processor '{}' {} 回调异常: {}",
                p.getClass().getSimpleName(), method, e.getMessage());
    }
}
