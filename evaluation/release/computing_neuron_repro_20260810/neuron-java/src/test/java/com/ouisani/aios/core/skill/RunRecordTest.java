package com.ouisani.aios.core.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RunRecord} 单元测试 — R3 运行记录数据模型。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>{@code from(ChainRun, input, ctx, snapshotId, runDir)} 工厂：字段正确映射</li>
 *   <li>{@code toJson()} / {@code fromJson(String)} 往返一致性（含特殊字符转义）</li>
 *   <li>{@code reproducePrompt()} 包含重放所需的全部信息（meta-skill、input、ctx、snapshotId、代码片段）</li>
 *   <li>{@code elapsedMs()} 计算</li>
 *   <li>边界：null/空输入、空 snapshotId</li>
 * </ul>
 */
class RunRecordTest {

    // ── 工具：构造一个 ChainRun ──

    private static SkillChain.ChainRun makeChainRun(
            String runId, long startedAt, long finishedAt,
            SkillChain.ChainStatus status, String outputBasePath,
            List<SkillChain.StepRun> steps) {
        return new SkillChain.ChainRun(
                runId, "ai4s-agent", startedAt, finishedAt,
                steps, status, outputBasePath
        );
    }

    private static SkillChain.StepRun successfulStep(String skill, int idx, long t0, long t1) {
        return new SkillChain.StepRun(
                skill, idx, t0, t1, "args-" + skill,
                "output-" + skill, "/vfs/" + skill + "/output.md",
                SkillChain.StepStatus.SUCCESS, null
        );
    }

    private static SkillChain.StepRun failedStep(String skill, int idx, long t0, long t1, String err) {
        return new SkillChain.StepRun(
                skill, idx, t0, t1, "args-" + skill,
                "", "/vfs/" + skill + "/output.md",
                SkillChain.StepStatus.FAILED, err
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  from() 工厂
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("from() 正确映射 ChainRun + ctx 字段")
    void from_mapsAllFieldsCorrectly() {
        long t0 = 1_700_000_000_000L;
        long t1 = t0 + 5_000;
        List<SkillChain.StepRun> steps = List.of(
                successfulStep("s1", 0, t0, t0 + 1_000),
                successfulStep("s2", 1, t0 + 1_000, t1)
        );
        SkillChain.ChainRun run = makeChainRun(
                "run-abc12345", t0, t1, SkillChain.ChainStatus.COMPLETED,
                "/output/ai4s-agent/my-slug", steps);

        SkillChainContext ctx = new SkillChainContext(
                "agent_42", "sess_x", "/work", "my-slug", "env-1234-abcd");

        RunRecord rec = RunRecord.from(run, "Transformer forecasting", ctx, "env-1234-abcd", "/var/run/run-abc12345");

        assertEquals("run-abc12345", rec.runId());
        assertEquals("ai4s-agent", rec.metaSkillName());
        assertEquals(t0, rec.startedAt());
        assertEquals(t1, rec.finishedAt());
        assertEquals("COMPLETED", rec.status());
        assertEquals("agent_42", rec.agentId());
        assertEquals("my-slug", rec.slug());
        assertEquals("/work", rec.workingDir());
        assertEquals("Transformer forecasting", rec.input());
        assertEquals("env-1234-abcd", rec.snapshotId());
        assertEquals("/output/ai4s-agent/my-slug", rec.outputBasePath());
        assertEquals(2, rec.stepCount());
        assertEquals(2, rec.successCount());
        assertEquals(0, rec.failureCount());
        assertEquals("/var/run/run-abc12345", rec.runDir());
    }

    @Test
    @DisplayName("from() — null input/snapshotId/runDir 规范化为空字符串")
    void from_nullsNormalizedToEmpty() {
        SkillChain.ChainRun run = makeChainRun(
                "run-x", 1000L, 2000L, SkillChain.ChainStatus.FAILED,
                "/out", List.of(failedStep("s1", 0, 1000L, 2000L, "boom")));

        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");

        RunRecord rec = RunRecord.from(run, null, ctx, null, null);

        assertEquals("", rec.input());
        assertEquals("", rec.snapshotId());
        assertEquals("", rec.runDir());
    }

    @Test
    @DisplayName("from() — PARTIAL 状态正确统计 successCount/failureCount")
    void from_partialStatusCounts() {
        long t0 = 1_000L;
        List<SkillChain.StepRun> steps = List.of(
                successfulStep("ok1", 0, t0, t0 + 100),
                failedStep("boom", 1, t0 + 100, t0 + 200, "executor returned empty output")
        );
        SkillChain.ChainRun run = makeChainRun(
                "run-partial", t0, t0 + 200, SkillChain.ChainStatus.PARTIAL,
                "/out", steps);

        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");
        RunRecord rec = RunRecord.from(run, "in", ctx, "", "");

        assertEquals("PARTIAL", rec.status());
        assertEquals(2, rec.stepCount());
        assertEquals(1, rec.successCount());
        assertEquals(1, rec.failureCount());
    }

    // ════════════════════════════════════════════════════════════════
    //  elapsedMs
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("elapsedMs() = finishedAt - startedAt")
    void elapsedMs_simpleDifference() {
        SkillChain.ChainRun run = makeChainRun(
                "r", 1_000L, 3_500L, SkillChain.ChainStatus.COMPLETED,
                "/o", List.of());
        SkillChainContext ctx = new SkillChainContext("a", "/w", "s");

        RunRecord rec = RunRecord.from(run, "in", ctx, "", "");

        assertEquals(2_500L, rec.elapsedMs());
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON 往返
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("toJson() → fromJson() 往返一致 — 字符串字段完整保留")
    void json_roundTrip_preservesAllFields() {
        long t0 = 1_700_000_000_000L;
        long t1 = t0 + 5_000;
        List<SkillChain.StepRun> steps = List.of(successfulStep("s1", 0, t0, t1));
        SkillChain.ChainRun run = makeChainRun(
                "run-abcdef12", t0, t1, SkillChain.ChainStatus.COMPLETED,
                "/output/ai4s-agent/slug-x", steps);

        SkillChainContext ctx = new SkillChainContext(
                "agent_5", "sess_abc", "/work/path", "slug-x", "env-99-zeta");
        RunRecord original = RunRecord.from(run, "Transformer forecasting", ctx,
                "env-99-zeta", "/var/run/run-abcdef12");

        String json = original.toJson();
        RunRecord restored = RunRecord.fromJson(json);

        assertNotNull(restored);
        assertEquals(original.runId(), restored.runId());
        assertEquals(original.metaSkillName(), restored.metaSkillName());
        assertEquals(original.startedAt(), restored.startedAt());
        assertEquals(original.finishedAt(), restored.finishedAt());
        assertEquals(original.status(), restored.status());
        assertEquals(original.agentId(), restored.agentId());
        assertEquals(original.slug(), restored.slug());
        assertEquals(original.workingDir(), restored.workingDir());
        assertEquals(original.input(), restored.input());
        assertEquals(original.snapshotId(), restored.snapshotId());
        assertEquals(original.outputBasePath(), restored.outputBasePath());
        assertEquals(original.stepCount(), restored.stepCount());
        assertEquals(original.successCount(), restored.successCount());
        assertEquals(original.failureCount(), restored.failureCount());
        assertEquals(original.runDir(), restored.runDir());
    }

    @Test
    @DisplayName("toJson() — JSON 字符串包含所有字段名")
    void json_containsAllFieldNames() {
        SkillChain.ChainRun run = makeChainRun(
                "run-x", 1L, 2L, SkillChain.ChainStatus.COMPLETED,
                "/o", List.of());
        SkillChainContext ctx = new SkillChainContext("a", "/w", "s");
        RunRecord rec = RunRecord.from(run, "in", ctx, "snap", "/d");

        String json = rec.toJson();

        assertTrue(json.contains("\"runId\":"));
        assertTrue(json.contains("\"metaSkillName\":"));
        assertTrue(json.contains("\"startedAt\":"));
        assertTrue(json.contains("\"finishedAt\":"));
        assertTrue(json.contains("\"elapsedMs\":"));
        assertTrue(json.contains("\"status\":"));
        assertTrue(json.contains("\"agentId\":"));
        assertTrue(json.contains("\"slug\":"));
        assertTrue(json.contains("\"workingDir\":"));
        assertTrue(json.contains("\"input\":"));
        assertTrue(json.contains("\"snapshotId\":"));
        assertTrue(json.contains("\"outputBasePath\":"));
        assertTrue(json.contains("\"runDir\":"));
        assertTrue(json.contains("\"stepCount\":"));
        assertTrue(json.contains("\"successCount\":"));
        assertTrue(json.contains("\"failureCount\":"));
    }

    @Test
    @DisplayName("JSON 转义：input 含引号、反斜杠、换行 — 往返无损")
    void json_roundTrip_specialCharactersEscaped() {
        String trickyInput = "Hello \"world\"\nback\\slash\ttab and 中文 unicode";
        SkillChain.ChainRun run = makeChainRun(
                "run-uni", 1L, 2L, SkillChain.ChainStatus.COMPLETED,
                "/o", List.of());
        SkillChainContext ctx = new SkillChainContext("a", "/w", "s");
        RunRecord original = RunRecord.from(run, trickyInput, ctx, "", "");

        String json = original.toJson();
        // JSON 中不能含裸换行（必须转义为 \n）
        assertFalse(json.contains("\n"), "JSON 行内不应有裸换行 — " + json);

        RunRecord restored = RunRecord.fromJson(json);
        assertEquals(trickyInput, restored.input(),
                "特殊字符往返应无损 — got: " + restored.input());
    }

    @Test
    @DisplayName("fromJson(null) / fromJson(\"\") 返回 null")
    void fromJson_nullOrBlankReturnsNull() {
        assertNull(RunRecord.fromJson(null));
        assertNull(RunRecord.fromJson(""));
        assertNull(RunRecord.fromJson("   "));
    }

    @Test
    @DisplayName("fromJson 容错：缺失字段使用默认值（0 / 空）")
    void fromJson_missingFieldsDefaultsToEmpty() {
        // 故意构造一个缺字段的 JSON — 不会抛异常
        String partial = "{\"runId\":\"run-1\",\"metaSkillName\":\"test\"}";
        RunRecord rec = RunRecord.fromJson(partial);

        assertNotNull(rec);
        assertEquals("run-1", rec.runId());
        assertEquals("test", rec.metaSkillName());
        assertEquals(0L, rec.startedAt());
        assertEquals(0L, rec.finishedAt());
        assertEquals("", rec.status());
        assertEquals("", rec.input());
        assertEquals(0, rec.stepCount());
    }

    // ════════════════════════════════════════════════════════════════
    //  reproducePrompt
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reproducePrompt() 包含 meta-skill 名 + 输入 + ctx + 代码片段")
    void reproducePrompt_containsReplayEssentials() {
        long t0 = 1_000L;
        SkillChain.ChainRun run = makeChainRun(
                "run-rp", t0, t0 + 500, SkillChain.ChainStatus.COMPLETED,
                "/output/ai4s-agent/slug-rp",
                List.of(successfulStep("s1", 0, t0, t0 + 500)));
        SkillChainContext ctx = new SkillChainContext(
                "agent_rp", "sess_rp", "/work", "slug-rp", "env-rp-001");

        RunRecord rec = RunRecord.from(run, "Forecast transformer load", ctx,
                "env-rp-001", "/var/run/run-rp");

        String prompt = rec.reproducePrompt();

        assertTrue(prompt.contains("# Reproduce Run: run-rp"));
        assertTrue(prompt.contains("ai4s-agent"));
        assertTrue(prompt.contains("Forecast transformer load"));
        assertTrue(prompt.contains("agentId: agent_rp"));
        assertTrue(prompt.contains("slug: slug-rp"));
        assertTrue(prompt.contains("workingDir: /work"));
        assertTrue(prompt.contains("snapshotId: env-rp-001"));
        assertTrue(prompt.contains("COMPLETED"));
        assertTrue(prompt.contains("```java"));
        assertTrue(prompt.contains("MetaSkillRegistry.instance().get(\"ai4s-agent\")"));
        assertTrue(prompt.contains("SkillChain.run(meta,"));
    }

    @Test
    @DisplayName("reproducePrompt() — 无快照时显示 <none>，且不含 load 代码")
    void reproducePrompt_noSnapshot_showsNoneAndOmitsLoadCode() {
        SkillChain.ChainRun run = makeChainRun(
                "r2", 1L, 2L, SkillChain.ChainStatus.COMPLETED,
                "/o", List.of());
        SkillChainContext ctx = new SkillChainContext("a", "/w", "s");

        RunRecord rec = RunRecord.from(run, "in", ctx, "", "");

        String prompt = rec.reproducePrompt();
        assertTrue(prompt.contains("snapshotId: <none>"));
        assertFalse(prompt.contains("EnvironmentSnapshotManager.instance().load"),
                "无快照时不应含 load 调用 — " + prompt);
    }

    @Test
    @DisplayName("reproducePrompt() — 有快照时包含 restore 代码片段")
    void reproducePrompt_withSnapshot_includesRestoreCode() {
        SkillChain.ChainRun run = makeChainRun(
                "r3", 1L, 2L, SkillChain.ChainStatus.COMPLETED,
                "/o", List.of());
        SkillChainContext ctx = new SkillChainContext("a", "/w", "s", "sl", "env-snap-xyz");

        RunRecord rec = RunRecord.from(run, "in", ctx, "env-snap-xyz", "");

        String prompt = rec.reproducePrompt();
        assertTrue(prompt.contains("snapshotId: env-snap-xyz"));
        assertTrue(prompt.contains("EnvironmentSnapshotManager.instance().load(\"env-snap-xyz\")"));
        assertTrue(prompt.contains("ifPresent(EnvironmentSnapshotManager.instance()::restore)"));
    }

    @Test
    @DisplayName("reproducePrompt() — input 含双引号时被转义，不破坏代码片段")
    void reproducePrompt_inputWithQuotes_escapedInCodeBlock() {
        SkillChain.ChainRun run = makeChainRun(
                "r4", 1L, 2L, SkillChain.ChainStatus.COMPLETED,
                "/o", List.of());
        SkillChainContext ctx = new SkillChainContext("a", "/w", "s");
        RunRecord rec = RunRecord.from(run, "He said \"hi\" loudly", ctx, "", "");

        String prompt = rec.reproducePrompt();
        // 在 ## Input 段中保留原文
        assertTrue(prompt.contains("He said \"hi\" loudly"));
        // 在代码片段中应该被转义
        assertTrue(prompt.contains("SkillChain.run(meta, \"He said \\\"hi\\\" loudly\", ctx, executor);"),
                "代码片段中应转义双引号 — " + prompt);
    }
}
