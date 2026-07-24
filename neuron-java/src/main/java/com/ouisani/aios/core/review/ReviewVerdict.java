package com.ouisani.aios.core.review;

import java.util.List;

/**
 * Reviewer 裁决 — LLM verdict 经 {@link ReviewGate} 确定性兜底后的最终判定。
 *
 * @param outcome  CLEAN / FLAGGED / BLOCKING / INCONCLUSIVE
 * @param findings 发现列表（可为空）
 * @param summary  摘要
 */
public record ReviewVerdict(Outcome outcome, List<ReviewFinding> findings, String summary) {

    public enum Outcome { CLEAN, FLAGGED, BLOCKING, INCONCLUSIVE }

    public ReviewVerdict {
        if (findings == null) findings = List.of();
        if (summary == null) summary = "";
    }

    /** outcome 为 BLOCKING，或任一 finding 为 high 严重级 → 视为阻断性。 */
    public boolean isBlocking() {
        return outcome == Outcome.BLOCKING
                || findings.stream().anyMatch(ReviewFinding::isBlocking);
    }

    /** reviewer 不可用 / 超时 / 解析失败时的降级裁决。 */
    public static ReviewVerdict inconclusive() {
        return new ReviewVerdict(Outcome.INCONCLUSIVE, List.of(), "reviewer unavailable");
    }
}
