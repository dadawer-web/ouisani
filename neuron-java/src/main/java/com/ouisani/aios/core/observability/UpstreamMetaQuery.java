package com.ouisani.aios.core.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * UpstreamMeta 调用链查询服务 — 把 .aios/upstream_meta.jsonl 落盘的记录
 * 从"只写不读"升级为"可跨 session 回读 + 聚合统计"。
 * <p>
 * 仿 {@link com.ouisani.aios.core.provenance.ProvenanceQuery} 范式：
 * 合并内存缓冲（{@link UpstreamMetaHook#listByUpstream} 等）+ 磁盘回读，
 * 按 (upstreamName, ts, agentId, sessionId, latencyMs, bytes, status) 去重，
 * 按 ts 升序返回。提供两种查询形态：
 * <ul>
 *   <li><b>明细列表</b> — {@link #listByUpstream} / {@link #listByAgent} /
 *       {@link #listBySession} / {@link #listByTimeWindow}</li>
 *   <li><b>聚合统计</b> — {@link #statsByUpstream}（avg/p50/p99/errorRate/bytes）</li>
 * </ul>
 *
 * <h3>与 ProvenanceQuery 的互补关系</h3>
 * <ul>
 *   <li><b>ProvenanceQuery</b> — 关心 artifact 版本链（path/version），
 *       按 path 维度回溯"数据从哪儿来"</li>
 *   <li><b>UpstreamMetaQuery</b> — 关心上游调用元数据（latency/status/cost/bytes），
 *       按 upstream_name 维度聚合"调用是否快/是否稳"</li>
 * </ul>
 * 两者通过 {@code agentId + sessionId + ts} 可做 DAG 联合查询
 * （如 "agent_5 的某次 LLM 调用产生了哪些 artifact"）。
 *
 * <h3>Best-effort 原则</h3>
 * <ul>
 *   <li>文件不存在 → 返回空列表 / {@link UpstreamStats#empty}（不抛）</li>
 *   <li>单行解析失败 → 跳过该行，继续处理后续行</li>
 *   <li>IO 异常 → 记录 WARN，返回已读到的部分</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * 相当于 Linux 的 {@code /proc/<pid>/io} + {@code perf stat} 的查询接口 ——
 * 把内核态 syscall trace 聚合成可读的统计视图。
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 跨 session 查询某上游的所有调用
 * List&lt;UpstreamMeta&gt; calls = UpstreamMetaQuery.listByUpstream("llm.think");
 *
 * // 聚合统计：avg/p50/p99 latency + 错误率
 * UpstreamStats stats = UpstreamMetaQuery.statsByUpstream("llm.think");
 * System.out.printf("calls=%d, p99=%dms, errorRate=%.2f%n",
 *         stats.callCount(), stats.p99LatencyMs(), stats.errorRate());
 *
 * // 联合 ProvenanceQuery：trace agent_5 的所有 LLM 调用 + artifact 产出
 * TraceabilityReport artifacts = ProvenanceQuery.traceByAgent("agent_5");
 * List&lt;UpstreamMeta&gt; upstreamCalls = UpstreamMetaQuery.listByAgent("agent_5");
 * </pre>
 *
 * @see UpstreamMeta
 * @see UpstreamMetaHook
 * @see UpstreamStats
 * @see com.ouisani.aios.core.provenance.ProvenanceQuery
 */
public final class UpstreamMetaQuery {

    private static final Logger log = LoggerFactory.getLogger(UpstreamMetaQuery.class);

    private UpstreamMetaQuery() {}

    // ════════════════════════════════════════════════════════════════
    //  公共查询入口 — 明细列表（合并内存缓冲 + 磁盘，去重，按 ts 升序）
    // ════════════════════════════════════════════════════════════════

    /**
     * 按上游标识查询所有调用记录（合并内存缓冲 + 磁盘，去重）。
     *
     * @param upstreamName 上游标识，如 "llm.think" / "tool.web_search"
     * @return 调用记录列表（按 ts 升序，可能为空）
     */
    public static List<UpstreamMeta> listByUpstream(String upstreamName) {
        if (upstreamName == null || upstreamName.isEmpty()) return List.of();
        return merge(
                UpstreamMetaHook.listByUpstream(upstreamName),
                readFromDisk(UpstreamMetaHook.upstreamMetaFile(),
                        m -> upstreamName.equals(m.upstreamName())));
    }

    /**
     * 按 agentId 查询所有上游调用记录（合并内存缓冲 + 磁盘，去重）。
     * <p>
     * 用于联合 {@code ProvenanceQuery.traceByAgent} 做 DAG 联合查询。
     *
     * @param agentId Agent 标识
     * @return 调用记录列表（按 ts 升序，可能为空）
     */
    public static List<UpstreamMeta> listByAgent(String agentId) {
        if (agentId == null || agentId.isEmpty()) return List.of();
        return merge(
                UpstreamMetaHook.listByAgent(agentId),
                readFromDisk(UpstreamMetaHook.upstreamMetaFile(),
                        m -> agentId.equals(m.agentId())));
    }

    /**
     * 按 sessionId 查询所有上游调用记录（合并内存缓冲 + 磁盘，去重）。
     * <p>
     * sessionId 是 DAG 联合查询的关键键 —— 一次推理 session 内所有 syscall
     * 调用串成调用链。
     *
     * @param sessionId 会话标识
     * @return 调用记录列表（按 ts 升序，可能为空）
     */
    public static List<UpstreamMeta> listBySession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return List.of();
        String sid = sessionId;
        return merge(
                UpstreamMetaHook.listBySession(sid),
                readFromDisk(UpstreamMetaHook.upstreamMetaFile(),
                        m -> sid.equals(m.sessionId())));
    }

    /**
     * 按时间窗口查询调用记录（合并内存缓冲 + 磁盘，去重）。
     *
     * @param startMs 起始时间戳（epoch millis，包含）
     * @param endMs   结束时间戳（epoch millis，不包含）
     * @return 时间窗口内的调用记录列表（按 ts 升序，可能为空）
     */
    public static List<UpstreamMeta> listByTimeWindow(long startMs, long endMs) {
        return merge(
                UpstreamMetaHook.listByTimeWindow(startMs, endMs),
                readFromDisk(UpstreamMetaHook.upstreamMetaFile(),
                        m -> m.ts() >= startMs && m.ts() < endMs));
    }

    // ════════════════════════════════════════════════════════════════
    //  公共查询入口 — 聚合统计
    // ════════════════════════════════════════════════════════════════

    /**
     * 聚合统计指定上游的调用指标 — avg/p50/p99 latency + 错误率 + 吞吐。
     * <p>
     * 用户需求"所有 syscall/tool 调用可统一聚合分析"的直接入口。
     *
     * @param upstreamName 上游标识，如 "llm.think"
     * @return 聚合统计；无调用记录时返回 {@link UpstreamStats#empty}
     */
    public static UpstreamStats statsByUpstream(String upstreamName) {
        if (upstreamName == null || upstreamName.isEmpty()) {
            return UpstreamStats.empty("");
        }
        return UpstreamStats.from(listByUpstream(upstreamName), upstreamName);
    }

    // ════════════════════════════════════════════════════════════════
    //  测试入口 — 显式指定 jsonl 文件路径（避免静态全局状态干扰）
    // ════════════════════════════════════════════════════════════════

    /**
     * 按上游标识查询 — 显式指定 jsonl 文件路径（测试用：跨 session 模拟）。
     * <p>
     * 不读内存缓冲，只读磁盘 —— 模拟"新 session 无缓冲"的场景。
     */
    public static List<UpstreamMeta> listByUpstream(String upstreamName, Path file) {
        if (upstreamName == null || upstreamName.isEmpty()) return List.of();
        return readFromDisk(file, m -> upstreamName.equals(m.upstreamName()));
    }

    /**
     * 按 agentId 查询 — 显式指定 jsonl 文件路径（测试用）。
     */
    public static List<UpstreamMeta> listByAgent(String agentId, Path file) {
        if (agentId == null || agentId.isEmpty()) return List.of();
        return readFromDisk(file, m -> agentId.equals(m.agentId()));
    }

    /**
     * 按 sessionId 查询 — 显式指定 jsonl 文件路径（测试用）。
     */
    public static List<UpstreamMeta> listBySession(String sessionId, Path file) {
        if (sessionId == null || sessionId.isEmpty()) return List.of();
        String sid = sessionId;
        return readFromDisk(file, m -> sid.equals(m.sessionId()));
    }

    /**
     * 按时间窗口查询 — 显式指定 jsonl 文件路径（测试用）。
     */
    public static List<UpstreamMeta> listByTimeWindow(long startMs, long endMs, Path file) {
        return readFromDisk(file, m -> m.ts() >= startMs && m.ts() < endMs);
    }

    /**
     * 聚合统计 — 显式指定 jsonl 文件路径（测试用）。
     */
    public static UpstreamStats statsByUpstream(String upstreamName, Path file) {
        if (upstreamName == null || upstreamName.isEmpty()) {
            return UpstreamStats.empty("");
        }
        return UpstreamStats.from(listByUpstream(upstreamName, file), upstreamName);
    }

    // ════════════════════════════════════════════════════════════════
    //  磁盘回读 — UpstreamMeta.fromJsonLine（Gson 树模型），best-effort
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 upstream_meta.jsonl 磁盘文件回读并过滤。Best-effort：跳过不可解析行 + 缺文件返回空。
     */
    static List<UpstreamMeta> readFromDisk(Path file, Predicate<UpstreamMeta> filter) {
        List<UpstreamMeta> result = new ArrayList<>();
        if (file == null) {
            return result;
        }
        for (String line : readLinesBestEffort(file)) {
            if (line.isBlank()) continue;
            // 用 record.fromJsonLine（Gson 树模型）而非 GSON.fromJson(cls) — 后者需反射 record，
            // 而本包未 opens 到 gson，会抛 InaccessibleObjectException（与 ProvenanceQuery 同策略）
            UpstreamMeta m = UpstreamMeta.fromJsonLine(line);
            if (m != null && filter.test(m)) {
                result.add(m);
            }
        }
        return result;
    }

    /**
     * 读文件所有行 — best-effort：文件不存在 / IO 异常返回空列表（不抛）。
     */
    private static List<String> readLinesBestEffort(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[UpstreamMetaQuery] 读文件失败 ({}): {}", file, e.getMessage());
            return List.of();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  合并 + 去重 — 内存缓冲 ∪ 磁盘记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 合并内存缓冲与磁盘记录，按记录全字段签名去重，按 ts 升序排序。
     * <p>
     * 去重 key = (upstreamName, ts, agentId, sessionId, durationMs, bytes, status)。
     * 比 {@code ProvenanceQuery} 的 (path, version, ts) 更严格 —— upstream 调用没有
     * version 概念，全字段签名避免误合并同毫秒内的不同调用。
     */
    private static List<UpstreamMeta> merge(List<UpstreamMeta> buffer, List<UpstreamMeta> disk) {
        Map<String, UpstreamMeta> dedup = new LinkedHashMap<>();
        for (UpstreamMeta m : buffer) {
            dedup.putIfAbsent(key(m), m);
        }
        for (UpstreamMeta m : disk) {
            dedup.putIfAbsent(key(m), m);
        }
        List<UpstreamMeta> merged = new ArrayList<>(dedup.values());
        merged.sort(Comparator.comparingLong(UpstreamMeta::ts));
        return merged;
    }

    private static String key(UpstreamMeta m) {
        return m.upstreamName() + "|" + m.ts() + "|" + m.agentId() + "|" + m.sessionId()
                + "|" + m.upstreamDurationMs() + "|" + m.upstreamBytes() + "|" + m.upstreamStatusCode();
    }
}
