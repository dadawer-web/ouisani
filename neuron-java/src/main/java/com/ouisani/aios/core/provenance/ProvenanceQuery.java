package com.ouisani.aios.core.provenance;

import com.ouisani.aios.core.review.ReviewLedger;
import com.ouisani.aios.core.review.ReviewRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provenance DAG 查询服务 — Phase 6 把"数字是否可追溯"从 LLM 读文件判断变成 DAG 查询。
 * <p>
 * 借鉴 OpenScience {@code provenance.ts} + {@code science/provenance/review.ts}：
 * 从磁盘回读 {@code .aios/provenance.jsonl} + {@code .aios/review.jsonl}（Gson 反序列化 record），
 * 合并内存缓冲（{@link ProvenanceHook#listByPath} / {@link ReviewLedger#listByTargetPath}），
 * 按 (path,version,ts) / (targetPath,runId,ts) 去重，返回 {@link TraceabilityReport}。
 * <p>
 * <b>关键修复（用户指出的真实缺口）</b>：Phase 1 的 jsonl 持久化了但<b>从不回读</b>，
 * 跨 session 查询实际不工作 —— 本类补齐磁盘回读路径。
 *
 * <h3>查询入口</h3>
 * <ul>
 *   <li>{@link #traceByPath(String)} — 按 artifact 路径追溯 provenance 版本链 + 关联 review</li>
 *   <li>{@link #traceByAgent(String)} — 按 agentId 追溯其产出的所有 artifact + 被审记录</li>
 * </ul>
 *
 * <h3>Best-effort 原则</h3>
 * <ul>
 *   <li>文件不存在 → 返回空列表（不抛）</li>
 *   <li>单行解析失败 → 跳过该行，继续处理后续行</li>
 *   <li>IO 异常 → 记录 WARN，返回已读到的部分</li>
 * </ul>
 *
 * <h3>盲性不破</h3>
 * provenance 记录的 content = artifact 内容（reviewer 本就能 file_read 看到），provenance 额外
 * 给的是 agent/tool/version 元数据 —— 不泄漏父 CoT 推理。本服务是只读查询，PLAN 模式可用。
 */
public final class ProvenanceQuery {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceQuery.class);

    private ProvenanceQuery() {}

    // ════════════════════════════════════════════════════════════════
    //  公共查询入口
    // ════════════════════════════════════════════════════════════════

    /**
     * 按 artifact 路径追溯可追溯性 — provenance 版本链 + 关联 review。
     * <p>
     * 合并磁盘（{@code .aios/provenance.jsonl} + {@code .aios/review.jsonl}）与内存缓冲，
     * 去重后按 ts 升序返回。
     *
     * @param path VFS 虚拟路径
     * @return 可追溯性报告（无命中时 provenance/reviews 均空）
     */
    public static TraceabilityReport traceByPath(String path) {
        if (path == null || path.isEmpty()) {
            return TraceabilityReport.empty("");
        }
        List<ProvenanceRecord> prov = mergeProvenance(
                ProvenanceHook.listByPath(path),
                readProvenanceFromDisk(ProvenanceHook.provenanceFile(), r -> path.equals(r.path())));
        List<ReviewRecord> rev = mergeReviews(
                ReviewLedger.listByTargetPath(path),
                readReviewsFromDisk(ReviewLedger.reviewFile(), r -> path.equals(r.targetPath())));
        return new TraceabilityReport(path, prov, rev);
    }

    /**
     * 按 agentId 追溯可追溯性 — 该 agent 产出的所有 artifact + 被审记录。
     *
     * @param agentId Agent 标识
     * @return 可追溯性报告
     */
    public static TraceabilityReport traceByAgent(String agentId) {
        if (agentId == null || agentId.isEmpty()) {
            return TraceabilityReport.empty("");
        }
        List<ProvenanceRecord> prov = mergeProvenance(
                ProvenanceHook.listByAgent(agentId),
                readProvenanceFromDisk(ProvenanceHook.provenanceFile(), r -> agentId.equals(r.agentId())));
        List<ReviewRecord> rev = mergeReviews(
                ReviewLedger.listByAgent(agentId),
                readReviewsFromDisk(ReviewLedger.reviewFile(), r -> agentId.equals(r.agentId())));
        return new TraceabilityReport(agentId, prov, rev);
    }

    // ════════════════════════════════════════════════════════════════
    //  测试入口 — 显式指定 jsonl 文件路径（避免静态全局状态干扰）
    // ════════════════════════════════════════════════════════════════

    /**
     * 按 path 追溯 — 显式指定 jsonl 文件路径（测试用：跨 session 模拟）。
     * <p>
     * 不读内存缓冲，只读磁盘 —— 模拟"新 session 无缓冲"的场景。
     */
    public static TraceabilityReport traceByPath(String path, Path provenanceFile, Path reviewFile) {
        if (path == null || path.isEmpty()) {
            return TraceabilityReport.empty("");
        }
        List<ProvenanceRecord> prov = readProvenanceFromDisk(provenanceFile, r -> path.equals(r.path()));
        List<ReviewRecord> rev = readReviewsFromDisk(reviewFile, r -> path.equals(r.targetPath()));
        return new TraceabilityReport(path, prov, rev);
    }

    /**
     * 按 agentId 追溯 — 显式指定 jsonl 文件路径（测试用）。
     */
    public static TraceabilityReport traceByAgent(String agentId, Path provenanceFile, Path reviewFile) {
        if (agentId == null || agentId.isEmpty()) {
            return TraceabilityReport.empty("");
        }
        List<ProvenanceRecord> prov = readProvenanceFromDisk(provenanceFile, r -> agentId.equals(r.agentId()));
        List<ReviewRecord> rev = readReviewsFromDisk(reviewFile, r -> agentId.equals(r.agentId()));
        return new TraceabilityReport(agentId, prov, rev);
    }

    // ════════════════════════════════════════════════════════════════
    //  磁盘回读 — record.fromJsonLine（Gson 树模型，不反射 record），best-effort
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 provenance.jsonl 磁盘文件回读并过滤。Best-effort：跳过不可解析行 + 缺文件返回空。
     */
    static List<ProvenanceRecord> readProvenanceFromDisk(Path file, java.util.function.Predicate<ProvenanceRecord> filter) {
        List<ProvenanceRecord> result = new ArrayList<>();
        if (file == null) {
            return result;
        }
        for (String line : readLinesBestEffort(file)) {
            if (line.isBlank()) continue;
            // 用 record.fromJsonLine（Gson 树模型）而非 GSON.fromJson(cls) — 后者需反射 record，
            // 而本包未 opens 到 gson，会抛 InaccessibleObjectAccessException
            ProvenanceRecord r = ProvenanceRecord.fromJsonLine(line);
            if (r != null && filter.test(r)) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 从 review.jsonl 磁盘文件回读并过滤。Best-effort：跳过不可解析行 + 缺文件返回空。
     */
    static List<ReviewRecord> readReviewsFromDisk(Path file, java.util.function.Predicate<ReviewRecord> filter) {
        List<ReviewRecord> result = new ArrayList<>();
        if (file == null) {
            return result;
        }
        for (String line : readLinesBestEffort(file)) {
            if (line.isBlank()) continue;
            ReviewRecord r = ReviewRecord.fromJsonLine(line);
            if (r != null && filter.test(r)) {
                result.add(r);
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
            return Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[ProvenanceQuery] 读文件失败 ({}): {}", file, e.getMessage());
            return List.of();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  合并 + 去重 — 内存缓冲 ∪ 磁盘记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 合并 provenance 内存缓冲与磁盘记录，按 (path, version, ts) 去重，按 ts 升序排序。
     */
    private static List<ProvenanceRecord> mergeProvenance(List<ProvenanceRecord> buffer, List<ProvenanceRecord> disk) {
        Map<String, ProvenanceRecord> dedup = new LinkedHashMap<>();
        for (ProvenanceRecord r : buffer) {
            dedup.putIfAbsent(provenanceKey(r), r);
        }
        for (ProvenanceRecord r : disk) {
            dedup.putIfAbsent(provenanceKey(r), r);
        }
        List<ProvenanceRecord> merged = new ArrayList<>(dedup.values());
        merged.sort(Comparator.comparingLong(ProvenanceRecord::ts));
        return merged;
    }

    /**
     * 合并 review 内存缓冲与磁盘记录，按 (targetPath, runId, ts) 去重，按 ts 升序排序。
     */
    private static List<ReviewRecord> mergeReviews(List<ReviewRecord> buffer, List<ReviewRecord> disk) {
        Map<String, ReviewRecord> dedup = new LinkedHashMap<>();
        for (ReviewRecord r : buffer) {
            dedup.putIfAbsent(reviewKey(r), r);
        }
        for (ReviewRecord r : disk) {
            dedup.putIfAbsent(reviewKey(r), r);
        }
        List<ReviewRecord> merged = new ArrayList<>(dedup.values());
        merged.sort(Comparator.comparingLong(ReviewRecord::ts));
        return merged;
    }

    private static String provenanceKey(ProvenanceRecord r) {
        return r.path() + "|" + r.version() + "|" + r.ts();
    }

    private static String reviewKey(ReviewRecord r) {
        return r.targetPath() + "|" + r.runId() + "|" + r.ts();
    }
}
