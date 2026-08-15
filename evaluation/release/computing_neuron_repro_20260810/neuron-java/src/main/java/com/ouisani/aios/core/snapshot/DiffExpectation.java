package com.ouisani.aios.core.snapshot;

import java.util.Set;

/**
 * diff 期望约束 — 声明哪些 section 允许变更、哪些禁止变更,供 judge 判定。
 * <p>
 * 借鉴 mobilegym 的 state-diff judging:rollout 后用 diff(before, after, expectation)
 * 判定是否符合预期(如"只允许 NodeOutput 变更,Carryover 禁止遗忘")。
 *
 * @param allowedChangedSections 允许变更的 section 类型集合;空集合表示"不限定"(全部允许)
 * @param forbiddenChangedSections 禁止变更的 section 类型集合(优先级高于 allowed)
 */
public record DiffExpectation(
        Set<String> allowedChangedSections,
        Set<String> forbiddenChangedSections
) {

    /** 宽松期望:允许所有 section 变更(默认)。 */
    public static DiffExpectation permissive() {
        return new DiffExpectation(Set.of(), Set.of());
    }

    /**
     * 判定一份 {@link StateDiff} 是否满足本期望。
     * <p>
     * 规则:对每个有变更的 section ——
     * <ol>
     *   <li>在 forbiddenChangedSections 中 → 不满足</li>
     *   <li>allowedChangedSections 非空且不含该 section → 不满足</li>
     * </ol>
     */
    public boolean validate(StateDiff diff) {
        for (SectionDiff sd : diff.sectionDiffs()) {
            boolean hasChanges = !sd.deltas().isEmpty();
            if (!hasChanges) continue;
            if (forbiddenChangedSections.contains(sd.sectionType())) return false;
            if (!allowedChangedSections.isEmpty()
                    && !allowedChangedSections.contains(sd.sectionType())) {
                return false;
            }
        }
        return true;
    }
}
