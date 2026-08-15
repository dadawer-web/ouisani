package com.ouisani.aios.core.permission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PermissionDenialLedger} 单元测试 — 验证权限拒绝的持久化、按 agent 查询、
 * bypass_immune 筛选和跨 session 磁盘回读。
 * <p>
 * 对齐项目记忆约束：
 * <ul>
 *   <li>权限拒绝 JSONL 文件必须跨 session 可读</li>
 *   <li>best-effort 错误处理：坏行跳过，不抛异常</li>
 *   <li>同 ProvenanceQuery / ReviewLedger 范式：内存缓冲 + 磁盘合并</li>
 * </ul>
 */
class PermissionDenialLedgerTest {

    private Path tempFile;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("permission_denials_test", ".jsonl");
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

    private static PermissionChecker.DenialRecord denialRecord(
            String agentId, String tool, String input, boolean bypassImmune) {
        PermissionDecision decision = bypassImmune
                ? PermissionDecision.deny("dangerous op", "dont_ask_converted_safety_ask",
                        List.of()).withBypassImmune(true)
                : PermissionDecision.deny("auto-denied", "mode",
                        List.of(new PermissionRule(PermissionRule.RuleSource.SESSION,
                                PermissionBehavior.ALLOW, "Bash", "ls:*")));
        return new PermissionChecker.DenialRecord(
                System.currentTimeMillis(), agentId, tool, input, decision);
    }

    // ════════════════════════════════════════════════════════════════
    //  append + listByAgent（内存缓冲）
    // ════════════════════════════════════════════════════════════════

    @Test
    void append_thenListByAgent_returnsRecord() {
        PermissionChecker.DenialRecord r = denialRecord("agent_1", "bash", "rm -rf /", true);
        PermissionDenialLedger.append(r);

        List<PermissionChecker.DenialRecord> result = PermissionDenialLedger.listByAgent("agent_1");
        assertEquals(1, result.size());
        assertEquals("bash", result.get(0).toolName());
        assertEquals("agent_1", result.get(0).agentId());
    }

    @Test
    void listByAgent_filtersByAgentId() {
        PermissionDenialLedger.append(denialRecord("agent_1", "bash", "rm", true));
        PermissionDenialLedger.append(denialRecord("agent_2", "file_write", "/tmp/a", false));

        assertEquals(1, PermissionDenialLedger.listByAgent("agent_1").size());
        assertEquals(1, PermissionDenialLedger.listByAgent("agent_2").size());
        assertTrue(PermissionDenialLedger.listByAgent("agent_3").isEmpty());
    }

    @Test
    void listByAgent_nullReturnsEmpty() {
        assertTrue(PermissionDenialLedger.listByAgent(null).isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  listBypassImmuneByAgent — 筛选危险操作
    // ════════════════════════════════════════════════════════════════

    @Test
    void listBypassImmuneByAgent_filtersOnlyBypassImmune() {
        PermissionDenialLedger.append(denialRecord("agent_1", "bash", "rm -rf /", true));
        PermissionDenialLedger.append(denialRecord("agent_1", "file_write", "/tmp/a", false));
        PermissionDenialLedger.append(denialRecord("agent_1", "bash", "sudo ls", true));

        List<PermissionChecker.DenialRecord> result =
                PermissionDenialLedger.listBypassImmuneByAgent("agent_1");
        assertEquals(2, result.size(), "应只返回 bypass_immune=true 的记录");
        assertTrue(result.stream().allMatch(r -> r.decision().bypassImmune()));
    }

    @Test
    void listBypassImmuneByAgent_noBypassImmune_returnsEmpty() {
        PermissionDenialLedger.append(denialRecord("agent_1", "file_write", "/tmp/a", false));

        assertTrue(PermissionDenialLedger.listBypassImmuneByAgent("agent_1").isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  跨 session 磁盘回读
    // ════════════════════════════════════════════════════════════════

    @Test
    void crossSession_diskReadAfterMemoryReset() {
        PermissionDenialLedger.append(denialRecord("agent_1", "bash", "rm -rf /", true));

        // 模拟新 session：清空内存缓冲，但磁盘文件保留
        PermissionDenialLedger.resetForTesting();

        List<PermissionChecker.DenialRecord> result = PermissionDenialLedger.listByAgent("agent_1");
        assertEquals(1, result.size(), "内存清空后应从磁盘回读");
        assertEquals("bash", result.get(0).toolName());
        assertTrue(result.get(0).decision().bypassImmune());
    }

    @Test
    void crossSession_diskReadMergesWithMemory() {
        // 磁盘上已有 1 条（来自上一 session）
        PermissionDenialLedger.append(denialRecord("agent_1", "bash", "rm", true));
        PermissionDenialLedger.resetForTesting();

        // 本 session 新增 1 条
        PermissionDenialLedger.append(denialRecord("agent_1", "file_write", "/tmp/a", false));

        List<PermissionChecker.DenialRecord> result = PermissionDenialLedger.listByAgent("agent_1");
        assertEquals(2, result.size(), "应合并磁盘 + 内存记录");
    }

    @Test
    void crossSession_diskReadSkipsBadLines() throws Exception {
        // 写入一条好记录 + 一条坏行
        PermissionDenialLedger.append(denialRecord("agent_1", "bash", "rm", true));
        Files.writeString(tempFile, "THIS IS NOT JSON\n",
                java.nio.file.StandardOpenOption.APPEND);

        PermissionDenialLedger.resetForTesting();

        List<PermissionChecker.DenialRecord> result = PermissionDenialLedger.listByAgent("agent_1");
        assertEquals(1, result.size(), "坏行应被跳过，好记录保留");
    }

    @Test
    void crossSession_missingFileReturnsEmpty() {
        PermissionDenialLedger.setDenialFile(Path.of("/nonexistent", "denials.jsonl"));
        PermissionDenialLedger.resetForTesting();

        assertTrue(PermissionDenialLedger.listByAgent("agent_1").isEmpty(),
                "文件不存在时应返回空列表，不抛异常");
    }

    // ════════════════════════════════════════════════════════════════
    //  JSONL 序列化往返
    // ════════════════════════════════════════════════════════════════

    @Test
    void jsonSerialization_roundTripPreservesAllFields() {
        PermissionDecision original = PermissionDecision.deny(
                "Auto-denied (dontAsk mode)", "dont_ask_converted_safety_ask",
                List.of(new PermissionRule(PermissionRule.RuleSource.SESSION,
                        PermissionBehavior.ALLOW, "Bash", "ls:*")))
                .withBypassImmune(true);
        PermissionChecker.DenialRecord record = new PermissionChecker.DenialRecord(
                1234567890L, "agent_1", "bash", "rm -rf /", original);

        String json = record.toJsonLine();
        PermissionChecker.DenialRecord parsed = PermissionChecker.DenialRecord.fromJsonLine(json);

        assertNotNull(parsed);
        assertEquals(1234567890L, parsed.timestamp());
        assertEquals("agent_1", parsed.agentId());
        assertEquals("bash", parsed.toolName());
        assertEquals("rm -rf /", parsed.inputDigest());
        assertEquals(PermissionBehavior.DENY, parsed.decision().behavior());
        assertEquals("dont_ask_converted_safety_ask", parsed.decision().reason());
        assertTrue(parsed.decision().bypassImmune());
        assertEquals(1, parsed.decision().suggestedRules().size());
        assertEquals("Bash(ls:*)", parsed.decision().suggestedRules().get(0).toRuleString());
    }

    @Test
    void jsonSerialization_preservesSuggestedRules() {
        PermissionDecision decision = PermissionDecision.deny("denied", "mode",
                List.of(
                        new PermissionRule(PermissionRule.RuleSource.SESSION,
                                PermissionBehavior.ALLOW, "Bash", "npm run:*"),
                        new PermissionRule(PermissionRule.RuleSource.SESSION,
                                PermissionBehavior.ALLOW, "file_edit", "src/**")));
        PermissionChecker.DenialRecord record = new PermissionChecker.DenialRecord(
                1L, "agent_1", "bash", "npm run build", decision);

        PermissionChecker.DenialRecord parsed =
                PermissionChecker.DenialRecord.fromJsonLine(record.toJsonLine());

        assertEquals(2, parsed.decision().suggestedRules().size());
        assertEquals("Bash(npm run:*)", parsed.decision().suggestedRules().get(0).toRuleString());
        assertEquals("file_edit(src/**)", parsed.decision().suggestedRules().get(1).toRuleString());
    }

    @Test
    void jsonSerialization_fromJsonLineNullReturnsNull() {
        assertNull(PermissionChecker.DenialRecord.fromJsonLine(null));
        assertNull(PermissionChecker.DenialRecord.fromJsonLine(""));
        assertNull(PermissionChecker.DenialRecord.fromJsonLine("  "));
        assertNull(PermissionChecker.DenialRecord.fromJsonLine("NOT JSON"));
    }

    // ════════════════════════════════════════════════════════════════
    //  向后兼容
    // ════════════════════════════════════════════════════════════════

    @Test
    void denialRecord_backwardCompatConstructor_noAgentId() {
        // 旧 4 参数构造器（无 agentId）→ agentId=""
        PermissionChecker.DenialRecord r = new PermissionChecker.DenialRecord(
                1L, "bash", "rm", PermissionDecision.deny("test", "rule"));
        assertEquals("", r.agentId(), "旧构造器 agentId 应为空串");
    }

    @Test
    void append_nullRecordIsNoop() {
        PermissionDenialLedger.append(null);
        assertTrue(PermissionDenialLedger.listByAgent("agent_1").isEmpty());
    }

    @Test
    void append_disabledIsNoop() {
        PermissionDenialLedger.setEnabled(false);
        PermissionDenialLedger.append(denialRecord("agent_1", "bash", "rm", true));
        PermissionDenialLedger.setEnabled(true);

        assertTrue(PermissionDenialLedger.listByAgent("agent_1").isEmpty(),
                "disabled 时不应记录");
    }
}
