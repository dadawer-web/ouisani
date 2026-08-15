package com.ouisani.aios.core.snapshot;

import java.util.List;

/**
 * 整份快照的差异 — 所有 {@link SectionDiff} 的聚合,是 diff 的最终产物。
 * <p>
 * 借鉴 mobilegym:状态差异可被 judge 用于判定 rollout 是否符合期望。
 *
 * @param sectionDiffs    各 section 差异列表
 * @param totalDeltas     所有非 UNCHANGED delta 总数(0 表示两快照完全相同)
 * @param meetsExpectation 是否满足 {@link DiffExpectation} 约束
 */
public record StateDiff(
        List<SectionDiff> sectionDiffs,
        int totalDeltas,
        boolean meetsExpectation
) {
}
