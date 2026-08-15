package com.ouisani.aios.core.provenance;

import com.ouisani.aios.core.review.ReviewRecord;

import java.util.List;

/**
 * 可追溯性报告 — 把"某条 claim 是否可追溯"从 LLM 读文件判断变成 DAG 查询的结果视图。
 * <p>
 * Phase 6 借鉴 OpenScience {@code provenance.ts} + {@code science/provenance/review.ts}：
 * reviewer 调 {@code provenance_query} 工具后得到本报告，里面同时包含：
 * <ul>
 *   <li>{@link ProvenanceRecord} 链 — artifact 被谁、何时、用什么工具写入的版本历史（DAG 节点）</li>
 *   <li>{@link ReviewRecord} 链 — 历史 review 对该 artifact 的裁决（按 {@code targetPath} 反查）</li>
 * </ul>
 * 两条链通过 {@code path == targetPath} join，让 reviewer 不再需要 file_read 猜测来源，
 * 而是直接读取结构化元数据判断"数字是否可追溯"。
 *
 * @param key         查询键（path 或 agentId，取决于查询入口）
 * @param provenance  provenance 版本记录（按 ts 升序）
 * @param reviews     review 裁决记录（按 ts 升序）
 */
public record TraceabilityReport(
        String key,
        List<ProvenanceRecord> provenance,
        List<ReviewRecord> reviews
) {

    public TraceabilityReport {
        if (key == null) key = "";
        if (provenance == null) provenance = List.of();
        if (reviews == null) reviews = List.of();
    }

    /** 空报告 — 查询键无任何 provenance / review 命中时返回。 */
    public static TraceabilityReport empty(String key) {
        return new TraceabilityReport(key, List.of(), List.of());
    }

    /** 是否完全没有命中（provenance 与 reviews 均空）。 */
    public boolean isEmpty() {
        return provenance.isEmpty() && reviews.isEmpty();
    }
}
