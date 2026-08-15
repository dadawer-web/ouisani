package com.ouisani.aios.core.audit;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一审计账本 — 把 cgroup / sandbox / permission 三层治理决策按 traceId 串成一条
 * 可审计的追溯链条。
 * <p>
 * 这是"联合治理 vs 各自为战"的代码级证据落点：三层的拦截决策（OOM Kill / Sandbox Fault /
 * Permission Deny）原本散落在 {@link com.ouisani.aios.core.telemetry.SemanticEtw}（环形缓冲）、
 * {@link com.ouisani.aios.core.permission.PermissionDenialLedger}（JSONL）、
 * {@link com.ouisani.aios.core.provenance.ProvenanceHook}（VFS 写入）三个互不联动的 sink 里。
 * 本类作为<b>第四个、跨层聚合</b>的 sink，在三个 enforcement 决策点各调用一次
 * {@link #append}，统一附带 {@link TraceContext#getCurrentTraceId()} 注入的 traceId，
 * 之后可通过 {@link #listByTraceId} 把同一次攻击的三层响应按时间序重放出来。
 * <p>
 * <b>论文差异化意义</b>：主流多租户 Agent 内核（Codex 插件、MCP）的权限模型是声明式 + 各管一段，
 * 拦截事件分别落在各自的日志里、没有跨机制 correlation id。本类把三层决策统一到一个 schema +
 * 一个 traceId 下，使"资源压力削弱防护响应"这类跨维度攻击链可被端到端审计回溯——这是别的内存
 * 治理论文没有的统一审计证据。
 * <p>
 * <b>设计原则</b>（镜像 ProvenanceHook / PermissionDenialLedger / ReviewLedger 范式）：
 * <ul>
 *   <li>静态 + best-effort + {@link FileChannel} 追加 + 内存缓冲（最近 1024 条）</li>
 *   <li>所有异常 catch，<b>永不中断 agent 主流程</b>（recording must never break the chat flow）</li>
 *   <li>不经 SyscallDispatcher：直接 {@link FileChannel} 写，避免递归触发权限检查</li>
 *   <li>traceId 自动从 {@link TraceContext} 的 InheritableThreadLocal 取，调用方无需传递</li>
 * </ul>
 *
 * @see com.ouisani.aios.core.cgroup.CgroupManager#oomKill(String)
 * @see com.ouisani.aios.core.sandbox.GraalWasmSandbox
 * @see com.ouisani.aios.core.permission.PermissionChecker
 */
public final class UnifiedAuditLog {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAuditLog.class);

    /** 三层治理决策的层标识（写入 AuditEntry.layer 字段）。 */
    public static final String LAYER_CGROUP = "CGROUP";
    public static final String LAYER_SANDBOX = "SANDBOX";
    public static final String LAYER_PERMISSION = "PERMISSION";
    /** 资源层限流决策（EventBus/VFS 令牌桶）— 第四层，补齐跨维度挤兑盲点。 */
    public static final String LAYER_RATELIMIT = "RATELIMIT";

    /** 统一审计 JSONL 文件路径（与 provenance.jsonl / permission_denials.jsonl / review.jsonl 并列） */
    private static volatile Path auditFile = Paths.get(".aios", "unified_audit.jsonl");

    /** 全局启用开关 — 默认启用 */
    private static volatile boolean enabled = true;

    /** 内存缓冲（最近 N 条）— 支持快速 listByTraceId 查询，避免每次读文件 */
    private static final List<AuditEntry> recentBuffer = new ArrayList<>();
    private static final int BUFFER_CAPACITY = 1024;
    private static final Object bufferLock = new Object();

    private UnifiedAuditLog() {}

    // ════════════════════════════════════════════════════════════════
    //  主入口 — 三个 enforcement 决策点调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 追加一条跨层治理决策记录。Best-effort：永不抛出。
     * <p>
     * traceId 自动从 {@link TraceContext#getCurrentTraceId()} 取（由 {@link
     * com.ouisani.aios.core.tool.QueryEngine} 在 turn 入口注入 InheritableThreadLocal，
     * 同线程 + 虚拟线程继承的子线程均可读到）。traceId 为 null 时仍记录（层内事件，
     * 不属于任何已知 turn），便于事后补全。
     *
     * @param layer    决策所在层（{@link #LAYER_CGROUP} / {@link #LAYER_SANDBOX} / {@link #LAYER_PERMISSION}）
     * @param decision 决策类型（如 OOM_KILL / SOFT_OOM / FAULT / DENY / ASK）
     * @param agentId  被治理的 Agent 标识（可能为 null）
     * @param target   决策目标（如被 kill 的 cgroup 名 / 被拦的 tool+target / sandbox instanceId）
     * @param reason   决策原因摘要（会被截断到 512 字符防日志爆炸）
     */
    public static void append(String layer, String decision, String agentId,
                              String target, String reason) {
        if (!enabled) return;
        try {
            String traceId = TraceContext.getCurrentTraceId();
            AuditEntry entry = new AuditEntry(
                    System.currentTimeMillis(),
                    traceId,
                    layer,
                    decision,
                    agentId,
                    target,
                    truncate(reason, 512)
            );
            appendRecord(entry);
        } catch (Throwable t) {
            log.debug("[UnifiedAuditLog] 记录失败 (layer={}, decision={}): {}",
                    layer, decision, t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 — 按 traceId 重建跨层决策链
    // ════════════════════════════════════════════════════════════════

    /**
     * 按 traceId 查询所有跨层治理决策（内存缓冲 + 磁盘合并，按时间升序）。
     * <p>
     * 论文评测用：给定一次攻击的 traceId，重放 cgroup→permission→sandbox 三层的响应时序，
     * 用于计算"跨层联动延迟""审计完整性"等指标。
     *
     * @param traceId 端到端追踪标识（null 返回空列表）
     * @return 该 traceId 下的所有审计条目，按 ts 升序
     */
    public static List<AuditEntry> listByTraceId(String traceId) {
        if (traceId == null) return List.of();
        // 去重合并：内存缓冲与磁盘文件可能包含同一条记录（append 同时写两处）。
        // AuditEntry 是 record，具备结构化 equals/hashCode，LinkedHashSet 去重并保留插入序。
        LinkedHashSet<AuditEntry> merged = new LinkedHashSet<>();

        synchronized (bufferLock) {
            for (AuditEntry e : recentBuffer) {
                if (traceId.equals(e.traceId())) {
                    merged.add(e);
                }
            }
        }
        for (AuditEntry e : listByTraceIdFromDisk(traceId)) {
            merged.add(e);
        }
        return new ArrayList<>(merged);
    }

    /**
     * 列出所有已记录的 traceId（去重）— 供评测脚本枚举攻击样本。
     */
    public static Set<String> listTraceIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        synchronized (bufferLock) {
            for (AuditEntry e : recentBuffer) {
                if (e.traceId() != null) ids.add(e.traceId());
            }
        }
        for (AuditEntry e : listAllFromDisk()) {
            if (e.traceId() != null) ids.add(e.traceId());
        }
        return ids;
    }

    /** 按层统计条目数（内存缓冲）— 供快速 sanity check。 */
    public static java.util.Map<String, Long> countByLayer() {
        java.util.Map<String, Long> stats = new java.util.LinkedHashMap<>();
        synchronized (bufferLock) {
            for (AuditEntry e : recentBuffer) {
                stats.merge(e.layer(), 1L, Long::sum);
            }
        }
        return stats;
    }

    // ════════════════════════════════════════════════════════════════
    //  配置 — 启用/禁用 + 文件路径（测试用）
    // ════════════════════════════════════════════════════════════════

    public static void setEnabled(boolean enabled) {
        UnifiedAuditLog.enabled = enabled;
    }

    public static void setAuditFile(Path file) {
        UnifiedAuditLog.auditFile = file;
    }

    public static Path auditFile() {
        return auditFile;
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
    //  内部 — JSONL 追加与回读（同 ProvenanceHook / PermissionDenialLedger 范式）
    // ════════════════════════════════════════════════════════════════

    private static void appendRecord(AuditEntry entry) throws IOException {
        // 1. 追加到内存缓冲
        synchronized (bufferLock) {
            if (recentBuffer.size() >= BUFFER_CAPACITY) {
                recentBuffer.subList(0, BUFFER_CAPACITY / 4).clear();
            }
            recentBuffer.add(entry);
        }

        // 2. 追加到 JSONL 文件（持久化）
        Path file = auditFile;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String line = entry.toJsonLine() + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
        }
    }

    private static List<AuditEntry> listByTraceIdFromDisk(String traceId) {
        List<AuditEntry> result = new ArrayList<>();
        Path file = auditFile;
        if (!Files.exists(file)) return result;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                AuditEntry e = AuditEntry.fromJsonLine(line);
                if (e != null && traceId.equals(e.traceId())) {
                    result.add(e);
                }
            }
        } catch (IOException e) {
            log.debug("[UnifiedAuditLog] 磁盘回读失败: {}", e.getMessage());
        }
        return result;
    }

    private static List<AuditEntry> listAllFromDisk() {
        List<AuditEntry> result = new ArrayList<>();
        Path file = auditFile;
        if (!Files.exists(file)) return result;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                AuditEntry e = AuditEntry.fromJsonLine(line);
                if (e != null) result.add(e);
            }
        } catch (IOException e) {
            log.debug("[UnifiedAuditLog] 磁盘回读失败: {}", e.getMessage());
        }
        return result;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    // ════════════════════════════════════════════════════════════════
    //  审计条目 — 统一 schema（三层共用）
    // ════════════════════════════════════════════════════════════════

    /**
     * 跨层治理审计条目 — cgroup / sandbox / permission 三层共用的统一记录 schema。
     * <p>
     * 与各自为战的散落日志（SemanticEtw 环形缓冲 / PermissionDenialLedger JSONL /
     * ProvenanceHook VFS 写入）不同，本 schema 强制把<b>层标识 + 决策类型 + traceId</b>
     * 放到同一结构里，使跨层关联可机器查询。
     *
     * @param ts       时间戳（epoch millis）
     * @param traceId  端到端追踪标识（可能为 null，表示无 turn 上下文）
     * @param layer    决策层（CGROUP / SANDBOX / PERMISSION）
     * @param decision 决策类型（OOM_KILL / SOFT_OOM / FAULT / DENY / ASK 等）
     * @param agentId  被治理 Agent 标识（可能为 null）
     * @param target   决策目标（cgroup 名 / tool+target / sandbox instanceId）
     * @param reason   决策原因摘要
     */
    public record AuditEntry(
            long ts,
            String traceId,
            String layer,
            String decision,
            String agentId,
            String target,
            String reason
    ) {
        public AuditEntry {
            if (layer == null) layer = "";
            if (decision == null) decision = "";
            if (reason == null) reason = "";
        }

        public String toJsonLine() {
            StringBuilder sb = new StringBuilder(160);
            sb.append('{');
            sb.append("\"ts\":").append(ts).append(',');
            sb.append("\"traceId\":").append(traceId == null ? "null" : escape(traceId)).append(',');
            sb.append("\"layer\":").append(escape(layer)).append(',');
            sb.append("\"decision\":").append(escape(decision)).append(',');
            sb.append("\"agentId\":").append(agentId == null ? "null" : escape(agentId)).append(',');
            sb.append("\"target\":").append(target == null ? "null" : escape(target)).append(',');
            sb.append("\"reason\":").append(escape(reason));
            sb.append('}');
            return sb.toString();
        }

        public static AuditEntry fromJsonLine(String line) {
            if (line == null || line.isBlank()) return null;
            try {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                return new AuditEntry(
                        o.has("ts") && o.get("ts").isJsonPrimitive() ? o.get("ts").getAsLong() : 0L,
                        optStr(o, "traceId"),
                        optStr(o, "layer"),
                        optStr(o, "decision"),
                        optStr(o, "agentId"),
                        optStr(o, "target"),
                        optStr(o, "reason")
                );
            } catch (Exception e) {
                return null;
            }
        }

        private static String optStr(com.google.gson.JsonObject o, String key) {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
        }

        private static String escape(String s) {
            if (s == null) return "null";
            StringBuilder out = new StringBuilder(s.length() + 8);
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
            return out.toString();
        }
    }
}
