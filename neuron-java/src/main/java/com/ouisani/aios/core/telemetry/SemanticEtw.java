package com.ouisani.aios.core.telemetry;

import com.ouisani.aios.core.ipc.TraceContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 语义事件追踪 (Semantic ETW) — AIOS 的零开销安全审计内核。
 * <p>
 * 将被 BpfManager 拦截的危险行为，连同 Agent 当时的"思考逻辑上下文"，
 * 写入 SemanticEtw 进行审计追溯。
 *
 * <h3>OS 类比: Windows ETW + Linux Auditd</h3>
 * <ul>
 *   <li>Windows ETW → 零开销环形缓冲区事件写入</li>
 *   <li>Linux auditd → 安全审计日志，记录谁在什么时候做了什么</li>
 *   <li>AIOS SemanticEtw → 两者融合 + 语义上下文（Agent 的思考逻辑）</li>
 * </ul>
 *
 * <h3>语义审计记录 (SemanticAuditRecord)</h3>
 * 与普通 EventRecord 不同，语义审计记录包含：
 * <ul>
 *   <li>{@code agentId} — 哪个 Agent 触发了此事件</li>
 *   <li>{@code securityToken} — Agent 当时的安全令牌（权限等级）</li>
 *   <li>{@code thinkingContext} — Agent 当时的思考逻辑上下文</li>
 *   <li>{@code threatLevel} — 威胁等级</li>
 *   <li>{@code ruleId} — 触发拦截的规则 ID</li>
 * </ul>
 *
 * <h3>Component 约定</h3>
 * <ul>
 *   <li>"LLM" — LLM Provider（延迟、模型选择）</li>
 *   <li>"CGROUP" — Cgroup（Token 消费、OOM 事件）</li>
 *   <li>"SCHEDULER" — 调度器（上下文切换、spawn/cancel）</li>
 *   <li>"VFS" — VFS（读写操作）</li>
 *   <li>"SECURITY" — 安全（BpfManager 拦截、冒充操作、OOM Kill）</li>
 *   <li>"WATCHDOG" — 看门狗（截止时间超限）</li>
 *   <li>"AUDIT" — 语义审计（带思考上下文的安全事件）</li>
 * </ul>
 */
public final class SemanticEtw {

    private static final int BUFFER_SIZE = 16384;
    private static final int INDEX_MASK = BUFFER_SIZE - 1;

    private static final class Holder {
        static final SemanticEtw INSTANCE = new SemanticEtw();
    }

    public static SemanticEtw getInstance() {
        return Holder.INSTANCE;
    }

    // ── 通用事件环形缓冲区 ──

    private final EventRecord[] ringBuffer = new EventRecord[BUFFER_SIZE];
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final AtomicLong totalEvents = new AtomicLong(0);
    private volatile boolean enabled = true;

    // ── 语义审计专用缓冲区 ──

    private static final int AUDIT_BUFFER_SIZE = 4096;
    private final SemanticAuditRecord[] auditBuffer = new SemanticAuditRecord[AUDIT_BUFFER_SIZE];
    private final AtomicInteger auditCursor = new AtomicInteger(0);
    private final AtomicLong totalAuditEvents = new AtomicLong(0);

    // ── 安全事件统计 ──

    private final ConcurrentHashMap<String, AtomicLong> securityStats = new ConcurrentHashMap<>();

    // ── TraceSink 体系 (借鉴 OMA observability) ──

    private volatile TraceSink.TraceCapturePolicy capturePolicy = TraceSink.TraceCapturePolicy.DEFAULT;
    private final List<TraceSink> sinks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final TraceSink.DiagnosticReporter diagnostics = new TraceSink.DiagnosticReporter();
    private volatile boolean sinkShutdown = false;

    private SemanticEtw() {}

    // ════════════════════════════════════════════════════════════════
    //  通用事件写入（零开销）
    // ════════════════════════════════════════════════════════════════

    /**
     * 零开销事件写入。无锁、无 I/O、无控制台输出。
     * 使用位运算 AND 实现快速取模（BUFFER_SIZE 为 2 的幂）。
     * <p>
     * traceId 自动从 {@link TraceContext#getCurrentTraceId()} 取（由 QueryEngine 在 turn
     * 入口注入 InheritableThreadLocal），使本事件可被 {@link com.ouisani.aios.core.audit.UnifiedAuditLog}
     * 按 traceId 与 permission/sandbox 层决策关联。无 turn 上下文时 traceId=null，仍记录。
     *
     * @param component 事件来源（如 "LLM", "CGROUP", "SECURITY"）
     * @param type      事件类型（如 "CALL", "CONSUME", "BPF_INTERCEPT"）
     * @param payload   事件描述
     */
    public void logEvent(String component, String type, String payload) {
        if (!enabled) return;
        String traceId = TraceContext.getCurrentTraceId();
        int idx = cursor.getAndIncrement() & INDEX_MASK;
        ringBuffer[idx] = new EventRecord(System.nanoTime(), component, type, payload, traceId);
        totalEvents.incrementAndGet();

        // 安全事件统计
        if ("SECURITY".equals(component)) {
            securityStats.computeIfAbsent(type, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  语义审计写入 — 带思考上下文的安全事件
    // ════════════════════════════════════════════════════════════════

    /**
     * 写入一条语义审计记录。
     * <p>
     * 与 {@link #logEvent} 不同，语义审计记录包含 Agent 的完整安全上下文：
     * <ul>
     *   <li>谁触发了此事件（agentId + securityToken）</li>
     *   <li>Agent 当时的思考逻辑（thinkingContext）</li>
     *   <li>威胁等级和触发规则（threatLevel + ruleId）</li>
     *   <li>被拦截的操作详情（action + reason）</li>
     * </ul>
     *
     * @param agentId         触发事件的 Agent 标识
     * @param securityToken   Agent 当时的安全令牌描述
     * @param thinkingContext Agent 当时的思考逻辑上下文
     * @param threatLevel     威胁等级
     * @param ruleId          触发拦截的规则 ID
     * @param action          被拦截的操作
     * @param reason          拦截原因
     */
    public void logAuditEvent(String agentId, String securityToken, String thinkingContext,
                              String threatLevel, String ruleId, String action, String reason) {
        if (!enabled) return;

        // traceId 自动注入（与 logEvent 一致），使 permission 拒绝事件可被 UnifiedAuditLog 关联
        String traceId = TraceContext.getCurrentTraceId();

        // 同时写入通用环形缓冲区（traceId 经 EventRecord 字段透传，无需进 payload）
        String payload = String.format(
                "traceId=%s agent=%s token=%s threat=%s rule=%s action=%s reason=%s thinking=%s",
                traceId != null ? traceId : "null",
                agentId, securityToken, threatLevel, ruleId, action, reason,
                thinkingContext != null ? truncate(thinkingContext, 200) : "null");
        // 直接走 5 参构造，避免 logEvent 再次查 TraceContext（已取过）
        int ringIdx = cursor.getAndIncrement() & INDEX_MASK;
        ringBuffer[ringIdx] = new EventRecord(System.nanoTime(), "AUDIT", "SECURITY_AUDIT", payload, traceId);
        totalEvents.incrementAndGet();

        // 写入语义审计专用缓冲区
        int auditIdx = auditCursor.getAndIncrement() & (AUDIT_BUFFER_SIZE - 1);
        auditBuffer[auditIdx] = new SemanticAuditRecord(
                System.nanoTime(), agentId, securityToken, thinkingContext,
                threatLevel, ruleId, action, reason);
        totalAuditEvents.incrementAndGet();
    }

    // ════════════════════════════════════════════════════════════════
    //  事件消费
    // ════════════════════════════════════════════════════════════════

    /**
     * 将所有缓冲事件刷新到消费者（如 WebSocket 处理器）。
     * 按插入顺序返回（最旧的在前）。
     */
    public List<EventRecord> flushToConsumer() {
        long total = totalEvents.get();
        int count = (int) Math.min(total, BUFFER_SIZE);

        List<EventRecord> result = new ArrayList<>(count);

        if (total <= BUFFER_SIZE) {
            int end = cursor.get() & INDEX_MASK;
            for (int i = 0; i < end; i++) {
                EventRecord r = ringBuffer[i];
                if (r != null) result.add(r);
            }
        } else {
            int start = cursor.get() & INDEX_MASK;
            for (int i = 0; i < BUFFER_SIZE; i++) {
                int idx = (start + i) & INDEX_MASK;
                EventRecord r = ringBuffer[idx];
                if (r != null) result.add(r);
            }
        }

        return result;
    }

    /**
     * 获取最近的 N 条通用事件。
     */
    public List<EventRecord> fetchRecent(int count) {
        long total = totalEvents.get();
        int available = (int) Math.min(total, BUFFER_SIZE);
        int fetchCount = Math.min(count, available);

        List<EventRecord> result = new ArrayList<>(fetchCount);

        int currentCursor = cursor.get();
        for (int i = fetchCount - 1; i >= 0; i--) {
            int idx = (currentCursor - 1 - i) & INDEX_MASK;
            EventRecord r = ringBuffer[idx];
            if (r != null) result.add(r);
        }

        return result;
    }

    /**
     * 获取最近的 N 条语义审计记录。
     * <p>
     * 语义审计记录包含完整的 Agent 思考上下文，
     * 用于安全回溯分析："为什么 Agent 在那个时刻做了那个决定？"
     */
    public List<SemanticAuditRecord> fetchRecentAudit(int count) {
        long total = totalAuditEvents.get();
        int available = (int) Math.min(total, AUDIT_BUFFER_SIZE);
        int fetchCount = Math.min(count, available);

        List<SemanticAuditRecord> result = new ArrayList<>(fetchCount);

        int currentCursor = auditCursor.get();
        for (int i = fetchCount - 1; i >= 0; i--) {
            int idx = (currentCursor - 1 - i) & (AUDIT_BUFFER_SIZE - 1);
            SemanticAuditRecord r = auditBuffer[idx];
            if (r != null) result.add(r);
        }

        return result;
    }

    /**
     * 获取安全事件统计摘要。
     * <p>
     * 返回每种安全事件类型的计数，如：
     * <pre>
     * BPF_INTERCEPT: 42
     * IMPERSONATE: 15
     * OOM_KILL: 3
     * JS_PROBE_BLOCK: 7
     * </pre>
     */
    public Map<String, Long> getSecurityStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        securityStats.forEach((k, v) -> stats.put(k, v.get()));
        return stats;
    }

    /**
     * 获取安全事件统计的格式化字符串。
     */
    public String getSecurityStatsReport() {
        Map<String, Long> stats = getSecurityStats();
        if (stats.isEmpty()) return "No security events recorded.";

        StringBuilder sb = new StringBuilder();
        sb.append("┌─ Security Event Statistics ──────────────────────\n");
        stats.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append(String.format("│ %-25s %d%n", e.getKey() + ":", e.getValue())));
        sb.append("└─────────────────────────────────────────────────");
        return sb.toString();
    }

    /**
     * 清除所有缓冲区。
     */
    public void clear() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            ringBuffer[i] = null;
        }
        for (int i = 0; i < AUDIT_BUFFER_SIZE; i++) {
            auditBuffer[i] = null;
        }
        cursor.set(0);
        totalEvents.set(0);
        auditCursor.set(0);
        totalAuditEvents.set(0);
        securityStats.clear();
    }

    // ════════════════════════════════════════════════════════════════
    //  TraceSink 体系 (借鉴 OMA observability)
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取当前捕获策略。
     */
    public TraceSink.TraceCapturePolicy getCapturePolicy() {
        return capturePolicy;
    }

    /**
     * 设置捕获策略 — 控制 prompt/completion/toolIO 是否记录及如何脱敏。
     * <p>
     * 隔夜 Runner 可设置为 NONE 减少开销,或 FULL 做完整记录。
     */
    public void setCapturePolicy(TraceSink.TraceCapturePolicy policy) {
        this.capturePolicy = policy;
    }

    /**
     * 注册一个 TraceSink — 所有事件会广播到已注册的 sink。
     * <p>
     * 注册不影响零开销写入路径;sink 的 emit 在 {@link #forceFlushSinks()} 时批量进行。
     */
    public void registerSink(TraceSink sink) {
        if (sink != null && !sinkShutdown) {
            sinks.add(sink);
        }
    }

    /**
     * 强制刷新所有已注册 sink — 隔夜 Runner 早上汇报时调用。
     * <p>
     * 将环形缓冲区的记录批量 emit 到每个 sink,然后调用 sink.forceFlush()。
     * 确保 trace 落盘,不丢数据。
     */
    public TraceSink.FlushResult forceFlushSinks() {
        if (sinks.isEmpty()) return TraceSink.FlushResult.empty();

        // 从环形缓冲区取出所有记录
        List<EventRecord> records = flushToConsumer();
        int totalExported = 0;
        int totalFailed = 0;

        for (TraceSink sink : sinks) {
            try {
                for (EventRecord r : records) {
                    sink.emit(new TraceSink.TraceRecord(
                            r.timestamp(), r.component(), r.eventType(), r.payload(),
                            null, TraceSink.TraceRecord.Severity.INFO));
                }
                TraceSink.FlushResult result = sink.forceFlush();
                totalExported += result.exported();
                totalFailed += result.failed();
            } catch (Exception e) {
                diagnostics.report(TraceSink.DiagnosticCode.SINK_EMIT_FAILED,
                        "forceFlush sink failed: " + e.getMessage());
                totalFailed += records.size();
            }
        }

        return new TraceSink.FlushResult(
                totalFailed > 0 ? TraceSink.FlushResult.Status.PARTIAL : TraceSink.FlushResult.Status.OK,
                records.size(), totalExported, 0, totalFailed);
    }

    /**
     * 关闭所有 sink — 幂等。关闭后 emit 被拒绝并诊断。
     * <p>
     * 先 forceFlush 确保数据落盘,再逐个 shutdown。
     */
    public TraceSink.FlushResult shutdownSinks() {
        if (sinkShutdown) return TraceSink.FlushResult.empty();
        sinkShutdown = true;

        // 先 flush 确保数据落盘
        TraceSink.FlushResult flushResult = forceFlushSinks();

        int totalExported = flushResult.exported();
        int totalFailed = flushResult.failed();

        for (TraceSink sink : sinks) {
            try {
                TraceSink.FlushResult r = sink.shutdown();
                totalExported += r.exported();
                totalFailed += r.failed();
            } catch (Exception e) {
                diagnostics.report(TraceSink.DiagnosticCode.SHUTDOWN_FAILED,
                        "sink shutdown failed: " + e.getMessage());
            }
        }

        return new TraceSink.FlushResult(
                totalFailed > 0 ? TraceSink.FlushResult.Status.PARTIAL : TraceSink.FlushResult.Status.OK,
                totalExported, totalExported, 0, totalFailed);
    }

    /**
     * 获取诊断报告器。
     */
    public TraceSink.DiagnosticReporter diagnostics() {
        return diagnostics;
    }

    /**
     * 获取已注册的 sink 数量。
     */
    public int sinkCount() {
        return sinks.size();
    }

    /**
     * 重置 sink 状态 — 清除所有 sink 和 shutdown 标志。
     * 用于测试隔离和隔夜 Runner 的 run 间重置。
     */
    public void resetSinks() {
        sinks.clear();
        sinkShutdown = false;
        diagnostics.reset();
    }

    public long totalEvents() {
        return totalEvents.get();
    }

    public long totalAuditEvents() {
        return totalAuditEvents.get();
    }

    public int bufferSize() {
        return BUFFER_SIZE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    // ════════════════════════════════════════════════════════════════
    //  语义审计记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 语义审计记录 — 包含 Agent 思考上下文的安全事件。
     * <p>
     * 与 {@link EventRecord} 不同，此记录专门用于安全审计，
     * 记录了 Agent 被拦截时的完整上下文，支持事后回溯分析：
     * "为什么 Agent 在那个时刻试图执行那个危险操作？"
     *
     * @param timestamp       事件时间戳（纳秒）
     * @param agentId         触发事件的 Agent 标识
     * @param securityToken   Agent 当时的安全令牌描述
     * @param thinkingContext Agent 当时的思考逻辑上下文
     * @param threatLevel     威胁等级
     * @param ruleId          触发拦截的规则 ID
     * @param action          被拦截的操作
     * @param reason          拦截原因
     */
    public record SemanticAuditRecord(
            long timestamp,
            String agentId,
            String securityToken,
            String thinkingContext,
            String threatLevel,
            String ruleId,
            String action,
            String reason
    ) {
        @Override
        public String toString() {
            return "[AUDIT] [%s] %d | agent=%s token=%s threat=%s rule=%s action=%s reason=%s".formatted(
                    threatLevel, timestamp, agentId, securityToken, threatLevel, ruleId, action, reason);
        }
    }
}
