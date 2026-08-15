package com.ouisani.aios.core.review;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import com.ouisani.aios.core.tool.QueryEngine;
import com.ouisani.aios.core.tool.ToolSdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReviewGate} 端到端编排 + QueryEngine 接线集成测试。
 * <p>
 * 同包测试以访问 {@link ReviewGate.ReviewContext} / {@link ReviewGate.ReviewGateResult}。
 * 覆盖：review() 完整编排（reviewer→parser→backstop→ledger→decide）、无 artifact 跳过、
 * reviewer 超时降级、QueryEngine.query() 真接线（ANNOTATE footer 追加）。
 */
class ReviewGateIntegrationTest {

    private static final String CLEAN_REVIEW_BLOCK =
            "```review\n{\"verdict\":\"CLEAN\",\"summary\":\"artifacts verified\",\"findings\":[]}\n```";
    private static final String AGENT = "agent_it";
    private static final String ARTIFACT = "/it/report.md";

    @TempDir
    Path tempDir;

    private StubSdk stubSdk;

    @BeforeEach
    void setUp() {
        stubSdk = new StubSdk();
        ReviewGateConfig.setLevelForTesting(ReviewGateLevel.ANNOTATE);
        ReviewLedger.setReviewFile(tempDir.resolve("review.jsonl"));
        ReviewLedger.setEnabled(true);
        ReviewLedger.resetForTesting();
        ProvenanceHook.setProvenanceFile(tempDir.resolve("provenance.jsonl"));
        ProvenanceHook.setEnabled(true);
        ProvenanceHook.resetForTesting();
        VfsManager.instance().init();
        VfsManager.instance().writeText(ARTIFACT, "the report content");
        // 默认 reviewer override 返回 CLEAN
        ReviewerRunner.setOverrideForTesting(
                (sdk, parent, wd, prompt, timeout) -> CLEAN_REVIEW_BLOCK);
    }

    @AfterEach
    void tearDown() {
        ReviewerRunner.clearOverrideForTesting();
        ReviewGateConfig.clearAllForTesting();
        ReviewLedger.resetForTesting();
        ReviewLedger.setEnabled(true);
        ProvenanceHook.CURRENT_AGENT_ID.remove();
        ProvenanceHook.resetForTesting();
        ProvenanceHook.setEnabled(true);
    }

    /** 预填 provenance：以 AGENT 身份写入 ARTIFACT 记录。 */
    private void seedProvenance() {
        ProvenanceHook.CURRENT_AGENT_ID.set(AGENT);
        ProvenanceHook.onWrite(ARTIFACT, "the report content", true);
    }

    @Test
    @DisplayName("review() 端到端 — CLEAN verdict 写 ledger + 追加 footer")
    void review_endToEnd_cleanVerdict_writesLedgerAndFooter() {
        seedProvenance();
        ReviewGate.ReviewContext ctx = new ReviewGate.ReviewContext(
                stubSdk, AGENT, "run1", tempDir.toString(), "final answer", 0, true);

        ReviewGate.ReviewGateResult result = ReviewGate.review(ctx);

        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, result.action());
        assertTrue(result.finalAnswer().contains("final answer"), "应保留原答案");
        assertTrue(result.finalAnswer().contains("[Review:"), "应追加 review footer");
        assertEquals(ReviewVerdict.Outcome.CLEAN, result.verdict().outcome());

        List<ReviewRecord> recs = ReviewLedger.listByAgent(AGENT);
        assertEquals(1, recs.size());
        assertEquals("CLEAN", recs.get(0).outcome());
        assertFalse(recs.get(0).deterministicForced(), "artifact 存在 → 非 forced");
        assertEquals("annotate", recs.get(0).level());
    }

    @Test
    @DisplayName("无 artifact → SKIP gate（零回归）")
    void review_noArtifacts_skipsGate() {
        // 不预填 provenance → listByAgent 返回空
        ReviewGate.ReviewContext ctx = new ReviewGate.ReviewContext(
                stubSdk, AGENT, "run2", tempDir.toString(), "trivial answer", 0, true);

        ReviewGate.ReviewGateResult result = ReviewGate.review(ctx);

        assertEquals(ReviewGate.ReviewGateResult.Action.SKIP, result.action());
        assertEquals("trivial answer", result.finalAnswer());
        assertTrue(ReviewLedger.listByAgent(AGENT).isEmpty(), "SKIP 不应写 ledger");
    }

    @Test
    @DisplayName("reviewer 返回 null（超时）→ 降级 INCONCLUSIVE，backstop 提升为 CLEAN")
    void review_reviewerOverrideNull_fallsBackInconclusive() {
        seedProvenance();
        ReviewerRunner.setOverrideForTesting((sdk, parent, wd, prompt, timeout) -> null);

        ReviewGate.ReviewContext ctx = new ReviewGate.ReviewContext(
                stubSdk, AGENT, "run3", tempDir.toString(), "answer", 0, true);
        ReviewGate.ReviewGateResult result = ReviewGate.review(ctx);

        // reviewer null → parser inconclusive → backstop: det PASS + LLM INCONCLUSIVE → CLEAN
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, result.action());
        assertEquals(ReviewVerdict.Outcome.CLEAN, result.verdict().outcome());
        List<ReviewRecord> recs = ReviewLedger.listByAgent(AGENT);
        assertEquals(1, recs.size());
        assertEquals("CLEAN", recs.get(0).outcome());
        assertFalse(recs.get(0).deterministicForced());
    }

    @Test
    @DisplayName("reviewer 返回 BLOCKING + artifact 缺失 → backstop 强制 BLOCKING（deterministicForced）")
    void review_blockingWithMissingArtifact_forcedByBackstop() {
        // artifact 不存在（VFS 未写该路径）
        String missingArtifact = "/it/nonexistent.md";
        ProvenanceHook.CURRENT_AGENT_ID.set(AGENT);
        ProvenanceHook.onWrite(missingArtifact, "x", true);
        // reviewer 说 CLEAN（误判），但确定性检查应推翻
        ReviewerRunner.setOverrideForTesting((sdk, parent, wd, prompt, timeout) ->
                "```review\n{\"verdict\":\"CLEAN\",\"summary\":\"looks fine\",\"findings\":[]}\n```");

        ReviewGate.ReviewContext ctx = new ReviewGate.ReviewContext(
                stubSdk, AGENT, "run4", tempDir.toString(), "answer", 0, true);
        ReviewGate.ReviewGateResult result = ReviewGate.review(ctx);

        // ANNOTATE 仍 RETURN，但 verdict 被 backstop 强制为 BLOCKING
        assertEquals(ReviewGate.ReviewGateResult.Action.RETURN, result.action());
        assertEquals(ReviewVerdict.Outcome.BLOCKING, result.verdict().outcome());
        List<ReviewRecord> recs = ReviewLedger.listByAgent(AGENT);
        assertEquals(1, recs.size());
        assertTrue(recs.get(0).deterministicForced(), "确定性 FAIL 应标记 forced");
    }

    @Test
    @DisplayName("QueryEngine.query() ANNOTATE — 答案追加 footer + review.jsonl 落盘")
    void queryEngine_annotate_appendsFooterToAnswer() {
        // 预填 provenance（QueryEngine 不调工具，需手动 seed）
        seedProvenance();
        QueryEngine engine = new QueryEngine(stubSdk, AGENT, tempDir.toString());

        String answer = engine.query("create report");

        assertNotNull(answer);
        assertTrue(answer.contains("[Review:"), "QueryEngine 出口应经 gate 追加 footer");
        List<ReviewRecord> recs = ReviewLedger.listByAgent(AGENT);
        assertEquals(1, recs.size(), "应落盘 1 条 review 记录");
        assertEquals("CLEAN", recs.get(0).outcome());
    }

    // ── Stub ToolSdk：think 返回纯文本（无工具调用）→ 命中 gate ──

    private static class StubSdk implements ToolSdk {
        @Override
        public String think(String agentId, String prompt) {
            return "I created the report.";
        }

        @Override
        public String thinkStream(String agentId, String prompt, Consumer<String> onDelta) {
            return "I created the report.";
        }

        @Override
        public void writeFile(String agentId, String path, String data) {
            // no-op
        }
    }
}
