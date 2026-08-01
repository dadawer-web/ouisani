package com.ouisani.aios.core.permission;

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

/**
 * 权限拒绝持久化账本 — 镜像 {@link com.ouisani.aios.core.review.ReviewLedger} / ProvenanceHook 的设计：
 * 静态 + best-effort + {@link FileChannel} 追加 + 内存缓冲（最近 1024 条）。
 * <p>
 * 每次 {@link PermissionChecker} 产生 DENY 决策时追加一条 {@link PermissionChecker.DenialRecord}
 * 到 {@code .aios/permission_denials.jsonl}。ReviewGate 在审查时调
 * {@link #listByAgent} 查询被审 agent 的权限拒绝历史，把 bypass_immune 拒绝
 * （如 {@code rm -rf /}）作为高严重级 finding 呈现给用户。
 * <p>
 * <b>Best-effort</b>：所有异常 catch，<b>永不中断 agent 主流程</b>
 * （同 ProvenanceHook / ReviewLedger 原则：recording must never break the chat flow）。
 * <p>
 * <b>不经 SyscallDispatcher</b>：直接 {@link FileChannel} 写，避免递归触发权限检查。
 *
 * @see PermissionChecker.DenialRecord
 * @see com.ouisani.aios.core.review.ReviewGate
 */
public final class PermissionDenialLedger {

    private static final Logger log = LoggerFactory.getLogger(PermissionDenialLedger.class);

    /** Permission denials JSONL 文件路径（与 .aios/provenance.jsonl / review.jsonl 并列） */
    private static volatile Path denialFile = Paths.get(".aios", "permission_denials.jsonl");

    /** 全局启用开关 — 默认启用 */
    private static volatile boolean enabled = true;

    /** 内存缓冲（最近 N 条）— 支持快速 listByAgent 查询 */
    private static final List<PermissionChecker.DenialRecord> recentBuffer = new ArrayList<>();
    private static final int BUFFER_CAPACITY = 1024;
    private static final Object bufferLock = new Object();

    private PermissionDenialLedger() {}

    // ════════════════════════════════════════════════════════════════
    //  主入口 — PermissionChecker.recordDenial 调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 追加一条权限拒绝记录。Best-effort：永不抛出。
     * <p>
     * 若调用方未带 traceId（如直接构造的测试 record），从 {@link TraceContext} 补全——
     * 保证审计链里每条 denial 都有 traceId 锚点，可被 {@link com.ouisani.aios.core.audit.UnifiedAuditLog}
     * 按 traceId 与 cgroup/sandbox 决策关联（P0 联合治理）。
     */
    public static void append(PermissionChecker.DenialRecord record) {
        if (!enabled || record == null) {
            return;
        }
        try {
            if (record.traceId() == null) {
                String tid = TraceContext.getCurrentTraceId();
                if (tid != null) {
                    record = record.withTraceId(tid);
                }
            }
            appendRecord(record);
        } catch (Throwable t) {
            log.debug("[PermissionDenialLedger] 记录失败 (agent={}): {}",
                    record.agentId(), t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 — 供 ReviewGate 消费
    // ════════════════════════════════════════════════════════════════

    /**
     * 按 agentId 查询所有权限拒绝记录（从内存缓冲 + 磁盘合并）。
     * <p>
     * ReviewGate 在审查 agent 时调用此方法，把被拒操作（特别是 bypass_immune 的危险操作）
     * 作为 ReviewFinding 呈现给用户。
     *
     * @param agentId 被审 agent 标识
     * @return 拒绝记录列表（按时间升序）；agentId 为 null 返回空列表
     */
    public static List<PermissionChecker.DenialRecord> listByAgent(String agentId) {
        if (agentId == null) return List.of();
        // 去重合并：内存缓冲与磁盘文件可能包含同一条记录（append 同时写两处）。
        // DenialRecord 是 record，具备结构化 equals/hashCode，LinkedHashSet 据此去重并保留插入序
        // （内存优先，磁盘补充跨 session 的旧记录）。同 ProvenanceQuery / UpstreamMetaQuery 范式。
        java.util.LinkedHashSet<PermissionChecker.DenialRecord> merged = new java.util.LinkedHashSet<>();

        // 1. 内存缓冲
        synchronized (bufferLock) {
            for (PermissionChecker.DenialRecord r : recentBuffer) {
                if (agentId.equals(r.agentId())) {
                    merged.add(r);
                }
            }
        }

        // 2. 磁盘回读（跨 session）— best-effort，重复记录由 LinkedHashSet 自动去重
        for (PermissionChecker.DenialRecord r : listByAgentFromDisk(agentId)) {
            merged.add(r);
        }

        return new ArrayList<>(merged);
    }

    /**
     * 按 agentId 查询 bypass_immune 拒绝记录 — 仅供 ReviewGate 筛选危险操作。
     *
     * @param agentId 被审 agent 标识
     * @return bypass_immune=true 的拒绝记录列表
     */
    public static List<PermissionChecker.DenialRecord> listBypassImmuneByAgent(String agentId) {
        List<PermissionChecker.DenialRecord> all = listByAgent(agentId);
        List<PermissionChecker.DenialRecord> result = new ArrayList<>();
        for (PermissionChecker.DenialRecord r : all) {
            if (r.decision().bypassImmune()) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 从磁盘文件按 agentId 查询（跨 session 回读）— best-effort。
     * <p>
     * 跳过坏行（同 ProvenanceQuery 范式），重复记录由调用方去重。
     *
     * @param agentId 被审 agent 标识
     * @return 磁盘上的拒绝记录列表
     */
    private static List<PermissionChecker.DenialRecord> listByAgentFromDisk(String agentId) {
        List<PermissionChecker.DenialRecord> result = new ArrayList<>();
        Path file = denialFile;
        if (!Files.exists(file)) return result;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                PermissionChecker.DenialRecord r = PermissionChecker.DenialRecord.fromJsonLine(line);
                if (r != null && agentId.equals(r.agentId())) {
                    result.add(r);
                }
            }
        } catch (IOException e) {
            log.debug("[PermissionDenialLedger] 磁盘回读失败: {}", e.getMessage());
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  配置 — 启用/禁用 + 文件路径（测试用）
    // ════════════════════════════════════════════════════════════════

    public static void setEnabled(boolean enabled) {
        PermissionDenialLedger.enabled = enabled;
    }

    public static void setDenialFile(Path file) {
        PermissionDenialLedger.denialFile = file;
    }

    /** 获取 JSONL 文件路径 — 供测试验证跨 session 回读。 */
    public static Path denialFile() {
        return denialFile;
    }

    /**
     * 重置所有内存状态 — 仅测试使用。不影响已写入的 JSONL 文件。
     */
    public static void resetForTesting() {
        synchronized (bufferLock) {
            recentBuffer.clear();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部 — JSONL 追加（同 ReviewLedger.appendRecord 模式）
    // ════════════════════════════════════════════════════════════════

    private static void appendRecord(PermissionChecker.DenialRecord record) throws IOException {
        // 1. 追加到内存缓冲
        synchronized (bufferLock) {
            if (recentBuffer.size() >= BUFFER_CAPACITY) {
                // FIFO 淘汰最旧的 1/4
                recentBuffer.subList(0, BUFFER_CAPACITY / 4).clear();
            }
            recentBuffer.add(record);
        }

        // 2. 追加到 JSONL 文件（持久化）
        Path file = denialFile;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String line = record.toJsonLine() + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
        }
    }
}
