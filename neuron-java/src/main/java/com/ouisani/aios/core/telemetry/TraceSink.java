package com.ouisani.aios.core.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TraceSink 可观测性体系 — 借鉴 OMA (open-multi-agent) 的 observability/sink.ts 设计。
 * <p>
 * 给 SemanticEtw 增加:
 * <ul>
 *   <li>{@link TraceCapturePolicy} — 捕获策略 (none/redacted),控制 prompt/completion/toolIO 是否记录</li>
 *   <li>{@link TraceSink} — Sink 接口 + forceFlush/shutdown 生命周期</li>
 *   <li>{@link DiagnosticReporter} — 限频诊断 (60s 内同 code 只报一次,防止日志风暴)</li>
 *   <li>{@link CompositeTraceSink} — 多 sink 组合</li>
 * </ul>
 * <p>
 * 隔夜 Runner 早上汇报时 forceFlush() 确保所有 trace 落盘。
 */
public interface TraceSink {

    /**
     * 同步非阻塞地接受一条记录。
     */
    void emit(TraceRecord record);

    /**
     * 强制刷新所有缓冲记录到下游。隔夜 Runner 早上汇报时调用。
     */
    FlushResult forceFlush();

    /**
     * 幂等关闭。关闭后 emit 被拒绝并诊断。
     */
    FlushResult shutdown();

    /**
     * 获取统计快照。
     */
    default TraceSinkStats getStats() {
        return TraceSinkStats.EMPTY;
    }

    // ════════════════════════════════════════════════════════════════
    //  TraceRecord — 传递给 sink 的记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 追踪记录 — 传递给 TraceSink 的标准化记录。
     *
     * @param timestamp  时间戳 (纳秒)
     * @param component  事件来源 (LLM/CGROUP/SCHEDULER/VFS/SECURITY/AUDIT)
     * @param type       事件类型 (CALL/CONSUME/BPF_INTERCEPT/...)
     * @param payload    事件描述 (已按 CapturePolicy 脱敏)
     * @param agentId    Agent 标识 (可为 null)
     * @param severity   严重级别 (info/warning/error)
     */
    record TraceRecord(
            long timestamp,
            String component,
            String type,
            String payload,
            String agentId,
            Severity severity
    ) {
        public enum Severity { INFO, WARNING, ERROR }

        public static TraceRecord of(String component, String type, String payload) {
            return new TraceRecord(System.nanoTime(), component, type, payload, null, Severity.INFO);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  FlushResult — flush/shutdown 的返回值
    // ════════════════════════════════════════════════════════════════

    /**
     * 刷新结果。
     *
     * @param status   ok / partial / timeout / error
     * @param accepted 已接受的记录数
     * @param exported 已导出的记录数
     * @param dropped  丢弃的记录数
     * @param failed   失败的记录数
     */
    record FlushResult(Status status, int accepted, int exported, int dropped, int failed) {
        public enum Status { OK, PARTIAL, TIMEOUT, ERROR }

        public static FlushResult ok(int exported) {
            return new FlushResult(Status.OK, exported, exported, 0, 0);
        }

        public static FlushResult empty() {
            return new FlushResult(Status.OK, 0, 0, 0, 0);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  TraceSinkStats — 统计快照
    // ════════════════════════════════════════════════════════════════

    record TraceSinkStats(
            long accepted,
            long exported,
            long retried,
            long failed,
            long dropped,
            long queuedRecords,
            String lastError
    ) {
        static final TraceSinkStats EMPTY = new TraceSinkStats(0, 0, 0, 0, 0, 0, null);
    }

    // ════════════════════════════════════════════════════════════════
    //  TraceCapturePolicy — 捕获策略
    // ════════════════════════════════════════════════════════════════

    /**
     * 捕获策略 — 控制哪些字段被记录以及如何脱敏。
     * <p>
     * 借鉴 OMA 的 TraceCapturePolicy:
     * <ul>
     *   <li>NONE — 不记录此字段,payload 显示 "[omitted]"</li>
     *   <li>REDACTED — 记录但截断到 maxContentChars,payload 显示截断内容</li>
     * </ul>
     *
     * @param prompt          prompt 字段捕获策略
     * @param completion      completion 字段捕获策略
     * @param toolInput       工具输入捕获策略
     * @param toolOutput      工具输出捕获策略
     * @param maxContentChars 最大内容字符数 (截断用)
     */
    record TraceCapturePolicy(
            FieldCapture prompt,
            FieldCapture completion,
            FieldCapture toolInput,
            FieldCapture toolOutput,
            int maxContentChars
    ) {
        public enum FieldCapture { NONE, REDACTED }

        /** 默认策略 — 所有字段 REDACTED,截断 4096 字符 */
        public static final TraceCapturePolicy DEFAULT = new TraceCapturePolicy(
                FieldCapture.REDACTED, FieldCapture.REDACTED,
                FieldCapture.REDACTED, FieldCapture.REDACTED, 4096);

        /** 全量策略 — 所有字段 REDACTED,不截断 */
        public static final TraceCapturePolicy FULL = new TraceCapturePolicy(
                FieldCapture.REDACTED, FieldCapture.REDACTED,
                FieldCapture.REDACTED, FieldCapture.REDACTED, Integer.MAX_VALUE);

        /** 关闭策略 — 所有字段 NONE */
        public static final TraceCapturePolicy NONE = new TraceCapturePolicy(
                FieldCapture.NONE, FieldCapture.NONE,
                FieldCapture.NONE, FieldCapture.NONE, 0);

        /**
         * 应用捕获策略到内容。
         *
         * @param field   字段捕获级别
         * @param content 原始内容
         * @return 脱敏后的内容;NONE 返回 "[omitted]",REDACTED 返回截断内容
         */
        public String apply(FieldCapture field, String content) {
            if (content == null) return null;
            if (field == FieldCapture.NONE) return "[omitted]";
            if (content.length() <= maxContentChars) return content;
            return content.substring(0, maxContentChars) + "... [truncated]";
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  DiagnosticReporter — 限频诊断
    // ════════════════════════════════════════════════════════════════

    /**
     * 限频诊断报告器 — 借鉴 OMA 的 DiagnosticReporter。
     * <p>
     * 60 秒内同一个 code 只报告一次,防止日志风暴。
     * 诊断处理器本身不做 trace (防止递归)。
     */
    final class DiagnosticReporter {

        private static final Logger log = LoggerFactory.getLogger(DiagnosticReporter.class);
        private static final long DEFAULT_INTERVAL_MS = 60_000;

        private final long intervalMs;
        private final Map<DiagnosticCode, Long> counts = new ConcurrentHashMap<>();
        private final Map<DiagnosticCode, Long> lastEmitted = new ConcurrentHashMap<>();

        public DiagnosticReporter() {
            this(DEFAULT_INTERVAL_MS);
        }

        public DiagnosticReporter(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        /**
         * 报告一个诊断。如果同 code 在 intervalMs 内已报告过,则静默跳过。
         */
        public void report(DiagnosticCode code, String message) {
            long count = counts.merge(code, 1L, Long::sum);
            long now = System.currentTimeMillis();
            Long last = lastEmitted.get(code);
            if (last != null && (now - last) < intervalMs) return;
            lastEmitted.put(code, now);

            log.warn("[TraceSink diagnostic] {} (count={}): {}", code, count, message);
        }

        /** 获取指定 code 的累计计数 */
        public long count(DiagnosticCode code) {
            return counts.getOrDefault(code, 0L);
        }

        /** 重置所有计数 */
        public void reset() {
            counts.clear();
            lastEmitted.clear();
        }
    }

    /** 诊断码 — 借鉴 OMA 的 TelemetryDiagnosticCode */
    enum DiagnosticCode {
        SINK_EMIT_FAILED,
        QUEUE_FULL,
        EXPORT_FAILED,
        FLUSH_TIMEOUT,
        SHUTDOWN_FAILED,
        EMIT_AFTER_SHUTDOWN
    }

    // ════════════════════════════════════════════════════════════════
    //  CompositeTraceSink — 多 sink 组合
    // ════════════════════════════════════════════════════════════════

    /**
     * 组合 Sink — 将记录广播到多个下游 sink。
     * <p>
     * 单个 sink 失败不影响其他 sink,失败被 DiagnosticReporter 捕获。
     */
    final class CompositeTraceSink implements TraceSink {

        private final List<TraceSink> sinks;
        private final DiagnosticReporter diagnostics;
        private volatile boolean shutdown = false;

        public CompositeTraceSink(TraceSink... sinks) {
            this.sinks = List.of(sinks);
            this.diagnostics = new DiagnosticReporter();
        }

        @Override
        public void emit(TraceRecord record) {
            if (shutdown) {
                diagnostics.report(DiagnosticCode.EMIT_AFTER_SHUTDOWN,
                        "emit called after shutdown");
                return;
            }
            for (TraceSink sink : sinks) {
                try {
                    sink.emit(record);
                } catch (Exception e) {
                    diagnostics.report(DiagnosticCode.SINK_EMIT_FAILED,
                            "sink emit failed: " + e.getMessage());
                }
            }
        }

        @Override
        public FlushResult forceFlush() {
            int totalExported = 0;
            int totalFailed = 0;
            boolean anyError = false;

            for (TraceSink sink : sinks) {
                try {
                    FlushResult r = sink.forceFlush();
                    totalExported += r.exported();
                    totalFailed += r.failed();
                    if (r.status() == FlushResult.Status.ERROR) anyError = true;
                } catch (Exception e) {
                    diagnostics.report(DiagnosticCode.FLUSH_TIMEOUT,
                            "sink forceFlush failed: " + e.getMessage());
                    anyError = true;
                }
            }
            return new FlushResult(
                    anyError ? FlushResult.Status.ERROR : FlushResult.Status.OK,
                    totalExported, totalExported, 0, totalFailed);
        }

        @Override
        public FlushResult shutdown() {
            shutdown = true;
            int totalExported = 0;
            int totalFailed = 0;

            for (TraceSink sink : sinks) {
                try {
                    FlushResult r = sink.shutdown();
                    totalExported += r.exported();
                    totalFailed += r.failed();
                } catch (Exception e) {
                    diagnostics.report(DiagnosticCode.SHUTDOWN_FAILED,
                            "sink shutdown failed: " + e.getMessage());
                }
            }
            return new FlushResult(FlushResult.Status.OK,
                    totalExported, totalExported, 0, totalFailed);
        }

        public int sinkCount() {
            return sinks.size();
        }

        public DiagnosticReporter diagnostics() {
            return diagnostics;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  InMemoryTraceSink — 内存 sink (测试和默认用)
    // ════════════════════════════════════════════════════════════════

    /**
     * 内存 Sink — 将记录缓存在内存中,用于测试和默认场景。
     */
    final class InMemoryTraceSink implements TraceSink {

        private final CopyOnWriteArrayList<TraceRecord> records = new CopyOnWriteArrayList<>();
        private final AtomicLong accepted = new AtomicLong(0);
        private final AtomicLong exported = new AtomicLong(0);
        private volatile boolean shutdown = false;

        @Override
        public void emit(TraceRecord record) {
            if (shutdown) return;
            records.add(record);
            accepted.incrementAndGet();
        }

        @Override
        public FlushResult forceFlush() {
            int count = records.size();
            exported.addAndGet(count);
            return FlushResult.ok(count);
        }

        @Override
        public FlushResult shutdown() {
            shutdown = true;
            return forceFlush();
        }

        @Override
        public TraceSinkStats getStats() {
            return new TraceSinkStats(
                    accepted.get(), exported.get(), 0, 0, 0,
                    records.size(), null);
        }

        public List<TraceRecord> records() {
            return List.copyOf(records);
        }

        public int size() {
            return records.size();
        }

        public void clear() {
            records.clear();
            accepted.set(0);
            exported.set(0);
        }
    }
}
