package com.ouisani.aios.core.observability;

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
import java.util.function.Predicate;

/**
 * Upstream 元数据追溯钩子 — 每次 syscall/工具调用后追加一条调用元数据记录到
 * {@code .aios/upstream_meta.jsonl}。
 * <p>
 * 与 {@code ProvenanceHook} 完全同范式（best-effort + ThreadLocal 上下文 +
 * FileChannel 原子 append + 内存缓冲 FIFO 淘汰 + 测试可重置）。两者互补：
 * <ul>
 *   <li><b>ProvenanceHook</b> — 关心 artifact 版本链（path/version），
 *       hook 在 {@code VfsManager.writeText}</li>
 *   <li><b>UpstreamMetaHook</b> — 关心上游调用元数据（latency/status/cost/bytes），
 *       hook 在 {@code SyscallDispatcher.execute} 的 finally 块</li>
 * </ul>
 * 两者通过 agentId + sessionId + ts 可联合查询（DAG 可追溯硬约束）。
 *
 * <h3>OS 类比</h3>
 * 相当于 Linux 的 {@code /proc/<pid>/io} + {@code strace -T -e trace=all} —
 * 前者聚合进程 IO 字节统计，后者按 syscall 输出 per-call 耗时与状态。
 *
 * <h3>JSONL 格式</h3>
 * <pre>
 * {"upstream_name":"llm.think","upstream_duration_ms":842,"upstream_status_code":200,"upstream_cost_units":null,"upstream_bytes":1536,"error_code":null,"ts":1784592000000,"agentId":"agent_5","sessionId":"sess_abc"}
 * {"upstream_name":"storage.write","upstream_duration_ms":5,"upstream_status_code":500,"upstream_cost_units":null,"upstream_bytes":0,"error_code":"ERR:FAIL","ts":1784592005000,"agentId":"agent_5","sessionId":"sess_abc"}
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // SyscallDispatcher.execute 的 finally 块调用
 * UpstreamMeta meta = new UpstreamMeta("llm.think", 842, 200, null, 1536, null,
 *         System.currentTimeMillis(), "agent_5", "sess_abc");
 * UpstreamMetaHook.onUpstreamCall(meta);
 *
 * // 后续查询历史
 * List&lt;UpstreamMeta&gt; calls = UpstreamMetaHook.listByUpstream("llm.think");
 * </pre>
 *
 * @see UpstreamMeta
 * @see com.ouisani.aios.core.provenance.ProvenanceHook
 */
public final class UpstreamMetaHook {

    private static final Logger log = LoggerFactory.getLogger(UpstreamMetaHook.class);

    /** UpstreamMeta JSONL 文件路径（与 ProvenanceHook 的 .aios/provenance.jsonl 同目录） */
    private static volatile Path upstreamMetaFile = Paths.get(".aios", "upstream_meta.jsonl");

    /** 全局启用开关 — 默认启用 */
    private static volatile boolean enabled = true;

    /** 按 upstream_name 维护计数（用于查询统计，无版本概念） */
    private static final ConcurrentMap<String, AtomicLong> callCounters = new ConcurrentHashMap<>();

    /** 内存缓冲（最近 N 条）— 支持快速 listByXxx 查询，避免每次读文件 */
    private static final List<UpstreamMeta> recentBuffer = new ArrayList<>();
    private static final int BUFFER_CAPACITY = 1024;
    private static final Object bufferLock = new Object();

    private UpstreamMetaHook() {}

    // ════════════════════════════════════════════════════════════════
    //  主入口 — SyscallDispatcher.execute 的 finally 块调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 记录一次上游调用 — 由 SyscallDispatcher.execute 的 finally 块调用。
     * <p>
     * <b>Best-effort</b>：所有异常 catch，永不抛出 — syscall 主流程优先于审计记录。
     * <p>
     * 与 ProvenanceHook 不同，<b>无论 success 与否都记录</b>（failures 也包含有价值的
     * latency/status/error_code 信息，用于错误率统计与性能分析）。
     *
     * @param meta 上游调用元数据（不允许为 null）
     */
    public static void onUpstreamCall(UpstreamMeta meta) {
        if (!enabled || meta == null) {
            return;
        }
        try {
            callCounters
                    .computeIfAbsent(meta.upstreamName(), k -> new AtomicLong())
                    .incrementAndGet();
            appendRecord(meta);
        } catch (Throwable t) {
            // best-effort: 永不中断主流程
            log.warn("[UpstreamMeta] 记录失败 (upstream_name={}): {}",
                    meta.upstreamName(), t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 — 支持后续"调用仪表盘"
    // ════════════════════════════════════════════════════════════════

    /**
     * 按 upstream_name 查询所有调用记录（从内存缓冲）。
     * <p>
     * 返回顺序：最近 N 条（BUFFER_CAPACITY=1024），按时间正序。
     * 若需完整历史，直接读 {@code .aios/upstream_meta.jsonl} 文件。
     *
     * @param upstreamName 上游标识，如 "llm.think"
     * @return 调用记录列表（可能为空）
     */
    public static List<UpstreamMeta> listByUpstream(String upstreamName) {
        if (upstreamName == null) return List.of();
        synchronized (bufferLock) {
            List<UpstreamMeta> result = new ArrayList<>();
            for (UpstreamMeta m : recentBuffer) {
                if (upstreamName.equals(m.upstreamName())) {
                    result.add(m);
                }
            }
            return result;
        }
    }

    /**
     * 按 agentId 查询所有调用记录（从内存缓冲）。
     */
    public static List<UpstreamMeta> listByAgent(String agentId) {
        if (agentId == null) return List.of();
        synchronized (bufferLock) {
            List<UpstreamMeta> result = new ArrayList<>();
            for (UpstreamMeta m : recentBuffer) {
                if (agentId.equals(m.agentId())) {
                    result.add(m);
                }
            }
            return result;
        }
    }

    /**
     * 按时间窗口查询调用记录（从内存缓冲）。
     *
     * @param startMs 起始时间戳（epoch millis，包含）
     * @param endMs   结束时间戳（epoch millis，不包含）
     * @return 时间窗口内的调用记录列表
     */
    public static List<UpstreamMeta> listByTimeWindow(long startMs, long endMs) {
        synchronized (bufferLock) {
            List<UpstreamMeta> result = new ArrayList<>();
            for (UpstreamMeta m : recentBuffer) {
                if (m.ts() >= startMs && m.ts() < endMs) {
                    result.add(m);
                }
            }
            return result;
        }
    }

    /**
     * 获取当前 upstream_name 的累计调用次数。
     */
    public static long callCount(String upstreamName) {
        AtomicLong counter = callCounters.get(upstreamName);
        return counter == null ? 0 : counter.get();
    }

    // ════════════════════════════════════════════════════════════════
    //  配置 — 启用/禁用 + 文件路径
    // ════════════════════════════════════════════════════════════════

    /**
     * 全局启用/禁用 UpstreamMeta 记录。
     * <p>
     * 测试场景可禁用以避免污染 .aios/upstream_meta.jsonl。
     *
     * @param enabled true 启用（默认），false 禁用
     */
    public static void setEnabled(boolean enabled) {
        UpstreamMetaHook.enabled = enabled;
        log.info("[UpstreamMeta] enabled={}", enabled);
    }

    /**
     * 设置 UpstreamMeta JSONL 文件路径。
     * <p>
     * 测试场景可指向临时文件。
     *
     * @param file JSONL 文件路径
     */
    public static void setUpstreamMetaFile(Path file) {
        UpstreamMetaHook.upstreamMetaFile = file;
        log.info("[UpstreamMeta] file={}", file);
    }

    /**
     * 获取 UpstreamMeta JSONL 文件路径（供 {@link UpstreamMetaQuery} 跨 session 回读）。
     */
    public static Path upstreamMetaFile() {
        return upstreamMetaFile;
    }

    /**
     * 重置所有状态 — 仅测试使用。
     * <p>
     * 清空计数器 + 内存缓冲。不影响已写入的 JSONL 文件。
     */
    public static void resetForTesting() {
        callCounters.clear();
        synchronized (bufferLock) {
            recentBuffer.clear();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部 — JSONL 追加
    // ════════════════════════════════════════════════════════════════

    private static void appendRecord(UpstreamMeta record) throws IOException {
        // 1. 追加到内存缓冲（快速查询）
        synchronized (bufferLock) {
            if (recentBuffer.size() >= BUFFER_CAPACITY) {
                // 简单的 FIFO 淘汰 — 移除最旧的 1/4
                recentBuffer.subList(0, BUFFER_CAPACITY / 4).clear();
            }
            recentBuffer.add(record);
        }

        // 2. 追加到 JSONL 文件（持久化）
        Path file = upstreamMetaFile;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String line = record.toJsonLine() + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        // 使用 CREATE + APPEND，线程安全由 OS 保证（单次 write 原子）
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
        }
    }
}
