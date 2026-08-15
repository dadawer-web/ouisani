package com.ouisani.aios.core.review;

import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.permission.PermissionDenialLedger;
import com.ouisani.aios.core.permission.PermissionRule;
import com.ouisani.aios.core.permission.PermissionBehavior;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReviewGate#injectPermissionDenials} 集成测试 — 验证 ReviewGate 直接消费
 * {@link PermissionDecision} 的 bypass_immune + suggestedRules 字段。
 * <p>
 * 对齐 AgentScope 2.0 设计要点：
 * <ul>
 *   <li>bypass_immune 拒绝（rm -rf /）→ high 严重级 + BLOCKING outcome，suggestedRules 为空</li>
 *   <li>普通拒绝（未在 allow 白名单）→ medium 严重级，suggestedRules 附"加什么规则能放行"建议</li>
 *   <li>无拒绝记录 → verdict 原样返回（零回归）</li>
 * </ul>
 * 同包测试以访问 package-private {@code injectPermissionDenials} 方法。
 */
class ReviewGateDenialIntegrationTest {

    private Path tempFile;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("permission_denials_review_test", ".jsonl");
        PermissionDenialLedger.setDenialFile(tempFile);
        PermissionDenialLedger.setEnabled(true);
        PermissionDenialLedger.resetForTesting();
    }

    @AfterEach
    void tearDown() throws Exception {
        PermissionDenialLedger.resetForTesting();
        PermissionDenialLedger.setEnabled(true);
        Files.deleteIfExists(tempFile);
    }

    // ── 测试数据工厂 ──

    private static void recordDenial(String agentId, String tool, String input,
                                     boolean bypassImmune, String reason) {
        PermissionDecision decision = bypassImmune
                ? PermissionDecision.deny("dangerous: " + input, reason, List.of())
                        .withBypassImmune(true)
                : PermissionDecision.deny("auto-denied: " + input, reason,
                        List.of(new PermissionRule(PermissionRule.RuleSource.SESSION,
                                PermissionBehavior.ALLOW, "Bash", "ls:*")));
        PermissionDenialLedger.append(new PermissionChecker.DenialRecord(
                System.currentTimeMillis(), agentId, tool, input, decision));
    }

    private static ReviewVerdict cleanVerdict() {
        return new ReviewVerdict(ReviewVerdict.Outcome.CLEAN, List.of(), "no issues found");
    }

    // ════════════════════════════════════════════════════════════════
    //  无拒绝记录 → verdict 原样返回（零回归）
    // ════════════════════════════════════════════════════════════════

    @Test
    void noDenials_verdictUnchanged() {
        ReviewVerdict original = cleanVerdict();
        ReviewVerdict result = ReviewGate.injectPermissionDenials(original, "agent_1");

        assertSame(original, result, "无拒绝记录时应返回原 verdict 对象");
    }

    @Test
    void nullAgentId_verdictUnchanged() {
        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), null);
        assertEquals(ReviewVerdict.Outcome.CLEAN, result.outcome(),
                "null agentId 不查询拒绝记录");
    }

    @Test
    void emptyAgentId_verdictUnchanged() {
        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "");
        assertEquals(ReviewVerdict.Outcome.CLEAN, result.outcome(),
                "空 agentId 不查询拒绝记录");
    }

    // ════════════════════════════════════════════════════════════════
    //  bypass_immune 拒绝 → high 严重级 + BLOCKING
    // ════════════════════════════════════════════════════════════════

    @Test
    void bypassImmuneDenial_upgradesToBlocking() {
        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");

        assertEquals(ReviewVerdict.Outcome.BLOCKING, result.outcome(),
                "bypass_immune 拒绝必须升级为 BLOCKING");
        assertTrue(result.isBlocking());
    }

    @Test
    void bypassImmuneDenial_addsHighSeverityFinding() {
        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");

        assertEquals(1, result.findings().size());
        ReviewFinding f = result.findings().get(0);
        assertEquals("high", f.severity(), "bypass_immune 拒绝应为 high 严重级");
        assertTrue(f.bypassImmune(), "finding 应标记 bypassImmune=true");
        assertTrue(f.suggestedRules().isEmpty(),
                "bypass_immune 拒绝的 suggestedRules 应为空（无法通过规则放行）");
    }

    @Test
    void bypassImmuneDenial_findingMessageContainsToolAndInput() {
        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");
        String message = result.findings().get(0).message();

        assertTrue(message.contains("bash"), "消息应包含工具名");
        assertTrue(message.contains("rm -rf /"), "消息应包含输入摘要");
        assertTrue(message.contains("bypass_immune"), "消息应标注 bypass_immune");
    }

    @Test
    void bypassImmuneDenial_evidenceContainsReason() {
        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");

        assertTrue(result.findings().get(0).evidence().contains("dont_ask_converted_safety_ask"),
                "evidence 应包含拒绝 reason 供追溯");
    }

    // ════════════════════════════════════════════════════════════════
    //  普通拒绝 → medium 严重级 + suggestedRules
    // ════════════════════════════════════════════════════════════════

    @Test
    void regularDenial_addsMediumSeverityFinding() {
        recordDenial("agent_1", "file_write", "/tmp/a", false, "mode");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");

        assertEquals(1, result.findings().size());
        ReviewFinding f = result.findings().get(0);
        assertEquals("medium", f.severity(), "普通拒绝应为 medium 严重级");
        assertFalse(f.bypassImmune(), "普通拒绝不应标记 bypassImmune");
        assertFalse(f.suggestedRules().isEmpty(), "普通拒绝应有 suggestedRules");
    }

    @Test
    void regularDenial_doesNotUpgradeToBlocking() {
        recordDenial("agent_1", "file_write", "/tmp/a", false, "mode");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");

        assertEquals(ReviewVerdict.Outcome.CLEAN, result.outcome(),
                "普通拒绝不应升级 outcome（保持原 CLEAN）");
    }

    @Test
    void regularDenial_suggestedRulesContainsRuleString() {
        recordDenial("agent_1", "bash", "npm run build", false, "mode");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");
        List<String> suggestions = result.findings().get(0).suggestedRules();

        assertFalse(suggestions.isEmpty());
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("Bash")),
                "suggestedRules 应包含可解析的规则字符串");
    }

    // ════════════════════════════════════════════════════════════════
    //  混合拒绝（bypass_immune + 普通）
    // ════════════════════════════════════════════════════════════════

    @Test
    void mixedDenials_bypassImmuneDominates() {
        recordDenial("agent_1", "file_write", "/tmp/a", false, "mode");
        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");
        recordDenial("agent_1", "bash", "sudo ls", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");

        assertEquals(3, result.findings().size(), "应有 3 条 finding");
        assertEquals(ReviewVerdict.Outcome.BLOCKING, result.outcome(),
                "只要有 bypass_immune 拒绝就升级为 BLOCKING");

        long highCount = result.findings().stream()
                .filter(f -> "high".equals(f.severity())).count();
        long mediumCount = result.findings().stream()
                .filter(f -> "medium".equals(f.severity())).count();
        assertEquals(2, highCount, "2 条 bypass_immune → high");
        assertEquals(1, mediumCount, "1 条普通 → medium");
    }

    @Test
    void mixedDenials_appendsToExistingFindings() {
        // 原始 verdict 已有 findings
        ReviewFinding existing = new ReviewFinding("medium", "/path/a",
                "existing issue", "claim1", "evidence1");
        ReviewVerdict original = new ReviewVerdict(
                ReviewVerdict.Outcome.FLAGGED, List.of(existing), "flagged");

        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(original, "agent_1");

        assertEquals(2, result.findings().size(), "应在原有 finding 基础上追加");
        assertEquals("existing issue", result.findings().get(0).message(),
                "原有 finding 应保留");
    }

    // ════════════════════════════════════════════════════════════════
    //  按 agent 隔离
    // ════════════════════════════════════════════════════════════════

    @Test
    void denialsFilteredByAgentId() {
        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");
        recordDenial("agent_2", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result1 = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");
        ReviewVerdict result2 = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_2");

        assertEquals(1, result1.findings().size(), "agent_1 只有 1 条拒绝");
        assertEquals(1, result2.findings().size(), "agent_2 只有 1 条拒绝");
    }

    // ════════════════════════════════════════════════════════════════
    //  formatFindings 显示 bypass_immune/suggestedRules
    // ════════════════════════════════════════════════════════════════

    @Test
    void formatFindings_displaysBypassImmune() {
        recordDenial("agent_1", "bash", "rm -rf /", true, "dont_ask_converted_safety_ask");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");

        // formatFindings 是 private，但通过 formatFooter / formatReminder 间接调用
        // 用 ReviewGateResult 的 footer 来验证
        String footer = formatFooterForTest(result);
        assertTrue(footer.contains("bypass_immune"), "footer 应显示 bypass_immune 标记");
    }

    @Test
    void formatFindings_displaysSuggestedRules() {
        recordDenial("agent_1", "bash", "npm run build", false, "mode");

        ReviewVerdict result = ReviewGate.injectPermissionDenials(cleanVerdict(), "agent_1");
        String footer = formatFooterForTest(result);

        assertTrue(footer.contains("suggested_rules"), "footer 应显示 suggested_rules");
        assertTrue(footer.contains("Bash"), "footer 应包含规则内容");
    }

    /**
     * 通过 ANNOTATE level 裁决获取 footer（含 formatFindings 输出）— 间接测试 formatFindings。
     */
    private static String formatFooterForTest(ReviewVerdict v) {
        ReviewGate.ReviewContext ctx = new ReviewGate.ReviewContext(
                null, "agent_1", "run_1", "/tmp", "test answer", 0, false);
        ReviewGateConfig.clearAllForTesting();
        ReviewGateConfig.setLevelForTesting(ReviewGateLevel.ANNOTATE);
        ReviewGate.ReviewGateResult r = ReviewGate.decideByLevel(ReviewGateLevel.ANNOTATE, ctx, v);
        ReviewGateConfig.clearAllForTesting();
        return r.finalAnswer();
    }
}
