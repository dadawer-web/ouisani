package com.ouisani.aios.core.provenance;

import com.ouisani.aios.core.ipc.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 恢复决策 provenance 记录器 — 新论文（恢复通道攻击面）的独立审计链组件。
 * <p>
 * <b>与论文1的边界（关键）</b>：本类是独立新增组件，<b>不修改</b> {@link ProvenanceHook}、
 * {@link ProvenanceRecord} 或 {@link com.ouisani.aios.core.recovery.RecoveryOrchestrator}。
 * 论文1的文件写入 provenance（{@code .aios/provenance.jsonl}）和 orchestrator 的
 * {@code broadcastRecoveryEvent}/{@code triggerCircuitBreaker} 保持字节级稳定。本记录器有独立的
 * 存储文件 {@code .aios/recovery_provenance.jsonl}，由 {@link RecoveryProvenanceSubscriber} 经
 * EventBus 订阅获得事件后写入 —— 无需在 orchestrator 里插桩。
 * <p>
 * <b>动机</b>：论文1的恢复事件只发到 {@code sys.telemetry.events}（可丢的遥测）和
 * {@code TelemetryService}（日志），没有进入审计账本。新论文要求"恢复决策全部接入 provenance
 * 审计链"——哪层策略触发的、为什么触发、结果是什么，形成"这个 agent 最终为什么成功/失败"的
 * 完整可追溯链条。这对攻击面分析至关重要：经恢复通道的越权/资源耗尽攻击必须能在审计链中回溯。
 * <p>
 * <b>设计</b>（与 {@link ProvenanceHook} 同构，便于读者对照）：
 * <ul>
 *   <li><b>Best-effort</b>：所有异常 catch，永不抛出 —— 审计记录不中断恢复主流程</li>
 *   <li><b>内存缓冲</b>：最近 N 条供 {@link #listByAgent} 快速查询</li>
 *   <li><b>JSONL 持久化</b>：每条记录追加到 {@code .aios/recovery_provenance.jsonl}</li>
 *   <li><b>递增序号</b>：per-agent 维护决策序号（非文件版本，而是决策流水号）</li>
 *   <li><b>可禁用</b>：{@link #setEnabled(false)}（测试/性能场景）</li>
 * </ul>
 * <p>
 * <b>线程模型</b>：{@link RecoveryProvenanceSubscriber} 的 handler 在 EventBus 的独立虚拟线程
 * 异步执行（见 {@code EventBus.broadcast} 的 {@code Thread.startVirtualThread}），<b>不继承</b>
 * 广播方的 {@link TraceContext} ThreadLocal —— 故经 EventBus 转写的记录 traceId=null（best-effort
 * 审计的已知限制）。若需 traceId 富化的恢复决策记录，应由新论文的决策点（guard/gate）在同步
 * 上下文里直接调用 {@link #onRecoveryDecision} 并显式传 traceId，而非依赖 EventBus 旁路转写。
 *
 * @see RecoveryProvenanceRecord
 * @see RecoveryProvenanceSubscriber
 * @see ProvenanceHook（论文1的文件写入 provenance，本类不改它）
 */
public final class RecoveryProvenanceRecorder {

    private static final Logger log = LoggerFactory.getLogger(RecoveryProvenanceRecorder.class);

    private static final RecoveryProvenanceRecorder INSTANCE = new RecoveryProvenanceRecorder();

    /** 恢复决策 provenance JSONL 文件（与论文1的 provenance.jsonl 分离，互不干扰）。 */
    private static volatile Path recoveryProvenanceFile = Paths.get(".aios", "recovery_provenance.jsonl");

    private static volatile boolean enabled = true;

    /** per-agent 决策流水号（agentId → next seq）—— 与 ProvenanceHook 的 versionCounters 同构。 */
    private static final ConcurrentMap<String, AtomicLong> decisionSeq = new ConcurrentHashMap<>();

    /** 内存缓冲（最近 N 条）—— 供 listByAgent 快速查询。 */
    private static final List<RecoveryProvenanceRecord> recentBuffer = new ArrayList<>();
    private static final int BUFFER_CAPACITY = 1024;
    private static final Object bufferLock = new Object();

    private RecoveryProvenanceRecorder() {
    }

    public static RecoveryProvenanceRecorder instance() {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════
    //  主入口 — 恢复决策记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 记录一次恢复决策 —— 与 {@link ProvenanceHook#onWrite} 平级的主入口。
     * <p>
     * <b>Best-effort</b>：所有异常 catch，永不抛出。traceId 若调用方未传，从
     * {@link TraceContext#getCurrentTraceId()} 兜底 —— 注意此兜底仅在<b>同步调用链</b>里有效
     *（如 guard/gate 在 orchestrator 同线程直接调用本方法）；经 {@link RecoveryProvenanceSubscriber}
     * 的 EventBus 旁路转写时，handler 运行在虚拟线程上拿不到广播方 ThreadLocal，traceId 将为 null。
     *
     * @param agentId      触发恢复的 agent
     * @param strategyName 策略名（ReflectionInjection / CIRCUIT_BREAKER / RECOVERY_GUARD / ...）
     * @param category     决策类别（RECOVERY_SUCCESS / RECOVERY_FAILED / CIRCUIT_BREAKER_TRIGGERED / ...）
     * @param success      是否成功放行
     * @param reason       决策原因
     * @param traceId      追踪标识；null 时从 TraceContext 兜底
     */
    public void onRecoveryDecision(String agentId, String strategyName, String category,
                                   boolean success, String reason, String traceId) {
        if (!enabled) return;
        try {
            String tid = traceId != null ? traceId : TraceContext.getCurrentTraceId();
            long seq = decisionSeq
                    .computeIfAbsent(agentId == null ? "" : agentId, k -> new AtomicLong())
                    .incrementAndGet();
            RecoveryProvenanceRecord record = new RecoveryProvenanceRecord(
                    agentId,
                    strategyName,
                    category,
                    success,
                    reason,
                    tid,
                    System.currentTimeMillis()
            );
            appendRecord(record, seq);
        } catch (Throwable t) {
            // best-effort: 永不中断主流程
            log.warn("[RecoveryProvenance] 记录失败 (agent={}, strategy={}): {}",
                    agentId, strategyName, t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 — 支持攻击面审计回溯
    // ════════════════════════════════════════════════════════════════

    /**
     * 按 agentId 查询所有恢复决策记录（从内存缓冲）。
     * <p>
     * 用于回溯"这个 agent 经历了哪些恢复决策、最终为什么成功/失败"。
     *
     * @param agentId agent 标识
     * @return 决策记录列表（按时间正序，可能为空）
     */
    public List<RecoveryProvenanceRecord> listByAgent(String agentId) {
        if (agentId == null) return List.of();
        synchronized (bufferLock) {
            List<RecoveryProvenanceRecord> result = new ArrayList<>();
            for (RecoveryProvenanceRecord r : recentBuffer) {
                if (agentId.equals(r.agentId())) {
                    result.add(r);
                }
            }
            return result;
        }
    }

    /** 返回内存缓冲中的全部恢复决策记录（测试/审计用）。 */
    public List<RecoveryProvenanceRecord> listAll() {
        synchronized (bufferLock) {
            return new ArrayList<>(recentBuffer);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  配置 — 启用/禁用 + 文件路径
    // ════════════════════════════════════════════════════════════════

    public void setEnabled(boolean enabled) {
        RecoveryProvenanceRecorder.enabled = enabled;
        log.info("[RecoveryProvenance] enabled={}", enabled);
    }

    public void setFile(Path file) {
        RecoveryProvenanceRecorder.recoveryProvenanceFile = file;
        log.info("[RecoveryProvenance] file={}", file);
    }

    public Path file() {
        return recoveryProvenanceFile;
    }

    /**
     * 重置所有内存状态 — 仅测试使用。清空流水号 + 缓冲，不影响已写入的 JSONL 文件。
     */
    public void resetForTesting() {
        decisionSeq.clear();
        synchronized (bufferLock) {
            recentBuffer.clear();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部 — JSONL 追加（与 ProvenanceHook.appendRecord 同构）
    // ════════════════════════════════════════════════════════════════

    private void appendRecord(RecoveryProvenanceRecord record, long seq) throws IOException {
        // 1. 内存缓冲（快速查询）
        synchronized (bufferLock) {
            if (recentBuffer.size() >= BUFFER_CAPACITY) {
                recentBuffer.subList(0, BUFFER_CAPACITY / 4).clear();
            }
            recentBuffer.add(record);
        }

        // 2. JSONL 持久化（含 seq 字段，便于跨 session 磁盘回读对齐）
        Path file = recoveryProvenanceFile;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String line = withSeq(record.toJsonLine(), seq) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
        }
    }

    /** 在 JSON 行里注入 per-agent 决策序号字段（插在 agentId 后，便于磁盘回读排序）。 */
    private static String withSeq(String jsonLine, long seq) {
        int idx = jsonLine.indexOf("\"agentId\":");
        if (idx < 0) return jsonLine;
        int commaPos = jsonLine.indexOf(',', idx);
        if (commaPos < 0) return jsonLine;
        // 注意：substring(0, commaPos+1) 含逗号，substring(commaPos+1) 从逗号后开始（无逗号），
        // 故插入 "seq":N 后必须补一个逗号，否则与下一个字段粘连成畸形 JSON。
        return jsonLine.substring(0, commaPos + 1) + "\"seq\":" + seq + "," + jsonLine.substring(commaPos + 1);
    }
}
