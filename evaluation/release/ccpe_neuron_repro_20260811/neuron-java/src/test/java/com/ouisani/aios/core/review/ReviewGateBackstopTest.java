package com.ouisani.aios.core.review;

import com.ouisani.aios.core.VfsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReviewGate} 的确定性兜底与三级裁决测试 — 直测 package-private 方法。
 * <p>
 * 同包测试以访问：
 * <ul>
 *   <li>{@link ReviewGate#applyDeterministicBackstop(ReviewVerdict, List)} → {@link ReviewGate.BackstopResult}</li>
 *   <li>{@link ReviewGate#decideByLevel(ReviewGateLevel, ReviewGate.ReviewContext, ReviewVerdict)} → {@link ReviewGate.ReviewGateResult}</li>
 * </ul>
 * 这是压制 LLM-as-judge 10.2% 误判的代码级闸门核心。
 */
class ReviewGateBackstopTest {

    private VfsManager vfs;
    private static final String EXISTING = "/bp/exists.md";
    private static final String MISSING = "/bp/missing.md";

    @BeforeEach
    void setUp() {
        vfs = VfsManager.instance();
        vfs.init();
        vfs.writeText(EXISTING, "real artifact content");
        ReviewGateConfig.clearAllForTesting();
    }

    @AfterEach
    void tearDown() {
        ReviewGateConfig.clearAllForTesting();
    }

    // ════════════════════════════════════════════════════════════
    //  applyDeterministicBackstop — 确定性兜底
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("LLM CLEAN + artifact 缺失 → 强制 BLOCKING（deterministicForced=true）")
    void llmClean_artifactMissing_forcesBlocking() {
        ReviewVerdict llm = new ReviewVerdict(ReviewVerdict.Outcome.CLEAN, List.of(), "llm says clean");
        ReviewGate.BackstopResult r = ReviewGate.applyDeterministicBackstop(llm, List.of(MISSING));

        assertEquals(ReviewVerdict.Outcome.BLOCKING, r.verdict().outcome());
        assertTrue(r.deterministicForced(), "确定性 FAIL 必须标记 deterministicForced");
        assertTrue(r.verdict().isBlocking());
        // 应追加一条 deterministic 失败 finding
        assertEquals(1, r.verdict().findings().size());
        assertTrue(r.verdict().findings().get(0).message().contains("Deterministic check FAILED"));
        assertEquals("high", r.verdict().findings().get(0).severity());
    }

    @Test
    @DisplayName("LLM INCONCLUSIVE + artifact 存在 → 提升为 CLEAN（确定性 PASS 清除不确定）")
    void llmInconclusive_artifactExists_promotesClean() {
        ReviewVerdict llm = ReviewVerdict.inconclusive();
        ReviewGate.BackstopResult r = ReviewGate.applyDeterministicBackstop(llm, List.of(EXISTING));

        assertEquals(ReviewVerdict.Outcome.CLEAN, r.verdict().outcome());
        assertFalse(r.deterministicForced());
    }

    @Test
    @DisplayName("LLM CLEAN + artifact 存在 → 采信 LLM（CLEAN，deterministicForced=false）")
    void llmClean_artifactExists_unchanged() {
        ReviewVerdict llm = new ReviewVerdict(ReviewVerdict.Outcome.CLEAN, List.of(), "clean");
        ReviewGate.BackstopResult r = ReviewGate.applyDeterministicBackstop(llm, List.of(EXISTING));

        assertEquals(ReviewVerdict.Outcome.CLEAN, r.verdict().outcome());
        assertFalse(r.deterministicForced());
        assertTrue(r.verdict().findings().isEmpty());
    }

    @Test
    @DisplayName("LLM FLAGGED + artifact 存在 → 保持 FLAGGED（不升级也不降级）")
    void llmFlagged_artifactExists_keepsFlagged() {
        ReviewVerdict llm = new ReviewVerdict(ReviewVerdict.Outcome.FLAGGED,
                List.of(new ReviewFinding("medium", EXISTING, "minor")), "flagged");
        ReviewGate.BackstopResult r = ReviewGate.applyDeterministicBackstop(llm, List.of(EXISTING));

        assertEquals(ReviewVerdict.Outcome.FLAGGED, r.verdict().outcome());
        assertFalse(r.deterministicForced());
        assertEquals(1, r.verdict().findings().size());
    }

    @Test
    @DisplayName("LLM BLOCKING + artifact 缺失 → 仍 BLOCKING（确定性 FAIL 追加 finding，deterministicForced=true）")
    void llmBlocking_artifactMissing_staysBlockingWithForcedFlag() {
        ReviewVerdict llm = new ReviewVerdict(ReviewVerdict.Outcome.BLOCKING,
                List.of(new ReviewFinding("high", MISSING, "llm blocking")), "blocking");
        ReviewGate.BackstopResult r = ReviewGate.applyDeterministicBackstop(llm, List.of(MISSING));

        assertEquals(ReviewVerdict.Outcome.BLOCKING, r.verdict().outcome());
        assertTrue(r.deterministicForced());
        // 原 1 条 + 追加 1 条 deterministic
        assertEquals(2, r.verdict().findings().size());
    }

    @Test
    @DisplayName("空 artifactPaths → 返回 LLM 原样（deterministicForced=false）")
    void emptyArtifactPaths_returnsLlmUnchanged() {
        ReviewVerdict llm = new ReviewVerdict(ReviewVerdict.Outcome.CLEAN, List.of(), "clean");
        ReviewGate.BackstopResult r = ReviewGate.applyDeterministicBackstop(llm, List.of());

        assertEquals(ReviewVerdict.Outcome.CLEAN, r.verdict().outcome());
        assertFalse(r.deterministicForced());
        assertSame(llm, r.verdict(), "空 paths 应直接返回同一 LLM verdict");
    }

    @Test
    @DisplayName("多 artifact 部分缺失 → FAIL（任一缺失即 BLOCKING）")
    void multipleArtifacts_partialMissing_fails() {
        ReviewVerdict llm = new ReviewVerdict(ReviewVerdict.Outcome.CLEAN, List.of(), "clean");
        ReviewGate.BackstopResult r = ReviewGate.applyDeterministicBackstop(
                llm, List.of(EXISTING, MISSING));

        assertEquals(ReviewVerdict.Outcome.BLOCKING, r.verdict().outcome());
        assertTrue(r.deterministicForced());
    }

    // ════════════════════════════════════════════════════════════
    //  decideByLevel — 三级裁决
    // ════════════════════════════════════════════════════════════

    private static ReviewGate.ReviewContext ctx(String answer, int fixCycleCount, boolean canReenter) {
        return new ReviewGate.ReviewContext(
                null, "agent_decide", "run_decide", "/wd", answer, fixCycleCount, canReenter);
    }

    private static ReviewVerdict clean() {
        return new ReviewVerdict(ReviewVerdict.Outcome.CLEAN, List.of(), "clean");
    }

    private static ReviewVerdict flagged() {
        return new ReviewVerdict(ReviewVerdict.Outcome.FLAGGED,
                List.of(new ReviewFinding("medium", "/x", "minor")), "flagged");
    }

    private static ReviewVerdict blocking() {
        return new ReviewVerdict(ReviewVerdict.Outcome.BLOCKING,
                List.of(new ReviewFinding("high", "/x", "must fix")), "blocking");
    }

    @Test
    @DisplayName("ANNOTATE + CLEAN → RETURN + footer")
    void annotate_returnsWithFooter() {
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.ANNOTATE, ctx("my answer", 0, true), clean());
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, r.action());
        assertTrue(r.finalAnswer().contains("my answer"));
        assertTrue(r.finalAnswer().contains("[Review:"), "应追加 footer");
        assertNull(r.fixReminder());
    }

    @Test
    @DisplayName("ANNOTATE + BLOCKING → 仍 RETURN（非阻塞，仅 footer）")
    void annotate_blocking_returnsWithFooter() {
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.ANNOTATE, ctx("ans", 0, true), blocking());
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, r.action());
        assertTrue(r.finalAnswer().contains("[Review:"));
    }

    @Test
    @DisplayName("SOFT + BLOCKING + canFix → REENTER + reminder")
    void soft_blocking_canFix_reenters() {
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.SOFT, ctx("ans", 0, true), blocking());
        assertEquals(ReviewGate.ReviewGateResult.Action.REENTER, r.action());
        assertNotNull(r.fixReminder());
        assertTrue(r.fixReminder().contains("Reviewer findings"));
    }

    @Test
    @DisplayName("SOFT + BLOCKING + cap 达到 → RETURN + 未解决 note")
    void soft_blocking_capReached_returnsWithNote() {
        // 默认 cap=2，fixCycleCount=2 → canFix=false
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.SOFT, ctx("ans", 2, true), blocking());
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, r.action());
        assertTrue(r.finalAnswer().contains("未解决"), "cap 饱和应含未解决 note");
    }

    @Test
    @DisplayName("SOFT + 非阻断 → RETURN + footer（不重入）")
    void soft_nonBlocking_returnsWithFooter() {
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.SOFT, ctx("ans", 0, true), flagged());
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, r.action());
        assertTrue(r.finalAnswer().contains("[Review:"));
        assertFalse(r.finalAnswer().contains("未解决"), "非阻断不应有未解决 note");
    }

    @Test
    @DisplayName("HARD + BLOCKING + cap 达到 → RETURN + 拒绝输出")
    void hard_blocking_capReached_refuses() {
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.HARD, ctx("ans", 2, true), blocking());
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, r.action());
        assertTrue(r.finalAnswer().contains("拒绝输出"), "HARD cap 饱和应拒绝 finalize");
        assertTrue(r.verdict().isBlocking());
    }

    @Test
    @DisplayName("HARD + BLOCKING + canFix → REENTER")
    void hard_blocking_canFix_reenters() {
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.HARD, ctx("ans", 0, true), blocking());
        assertEquals(ReviewGate.ReviewGateResult.Action.REENTER, r.action());
        assertNotNull(r.fixReminder());
    }

    @Test
    @DisplayName("HARD + 非阻断 → RETURN + footer")
    void hard_nonBlocking_returnsWithFooter() {
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.HARD, ctx("ans", 0, true), clean());
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, r.action());
        assertTrue(r.finalAnswer().contains("[Review:"));
    }

    @Test
    @DisplayName("canReenter=false + BLOCKING → RETURN（不重入，即使未达 cap）")
    void cannotReenter_blocking_returnsInsteadOfReenter() {
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.SOFT, ctx("ans", 0, false), blocking());
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, r.action());
        // canFix=false（因 canReenter=false）→ SOFT 走 cap 饱和分支 → 含未解决 note
        assertTrue(r.finalAnswer().contains("未解决"));
    }

    @Test
    @DisplayName("HARD + canReenter=false + BLOCKING → 拒绝输出")
    void hard_cannotReenter_blocking_refuses() {
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.HARD, ctx("ans", 0, false), blocking());
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, r.action());
        assertTrue(r.finalAnswer().contains("拒绝输出"));
    }

    @Test
    @DisplayName("decideByLevel 尊重自定义 maxFixCycles")
    void decideByLevel_respectsCustomCap() {
        ReviewGateConfig.setMaxFixCyclesForTesting(5);
        // fixCycleCount=2 < cap=5 → 仍可重入
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(
                ReviewGateLevel.HARD, ctx("ans", 2, true), blocking());
        assertEquals(ReviewGate.ReviewGateResult.Action.REENTER, r.action());
    }
}
