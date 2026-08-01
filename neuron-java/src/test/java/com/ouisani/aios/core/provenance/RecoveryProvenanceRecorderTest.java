package com.ouisani.aios.core.provenance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RecoveryProvenanceRecorder} 单元测试 — 验证恢复决策审计链的记录与查询契约。
 * <p>
 * 核心断言：恢复决策被结构化记录、可按 agent 回溯、best-effort 不抛、JSONL 持久化。
 * 本记录器是独立组件，不触碰论文1的 {@link ProvenanceHook}。
 */
class RecoveryProvenanceRecorderTest {

    private final RecoveryProvenanceRecorder recorder = RecoveryProvenanceRecorder.instance();

    @TempDir
    Path tmpDir;

    @BeforeEach
    void setup() {
        recorder.resetForTesting();
        recorder.setEnabled(true);
        recorder.setFile(tmpDir.resolve("recovery_provenance.jsonl"));
    }

    @AfterEach
    void teardown() {
        recorder.resetForTesting();
    }

    @Test
    void recorded_decision_queryable_by_agent() {
        recorder.onRecoveryDecision("agent_7", "ReflectionInjection", "RECOVERY_FAILED",
                false, "Permission guard denied retry", "trace_abc");

        var records = recorder.listByAgent("agent_7");
        assertEquals(1, records.size());
        RecoveryProvenanceRecord r = records.get(0);
        assertEquals("agent_7", r.agentId());
        assertEquals("ReflectionInjection", r.strategyName());
        assertEquals("RECOVERY_FAILED", r.category());
        assertFalse(r.success());
        assertEquals("Permission guard denied retry", r.reason());
        assertEquals("trace_abc", r.traceId());
        assertTrue(r.ts() > 0);
    }

    @Test
    void list_by_agent_isolates_agents() {
        recorder.onRecoveryDecision("agent_A", "Fallback", "RECOVERY_SUCCESS", true, "ok", null);
        recorder.onRecoveryDecision("agent_B", "CIRCUIT_BREAKER", "CIRCUIT_BREAKER_TRIGGERED", false, "threshold", null);
        recorder.onRecoveryDecision("agent_A", "ToolError", "RECOVERY_FAILED", false, "err", null);

        assertEquals(2, recorder.listByAgent("agent_A").size());
        assertEquals(1, recorder.listByAgent("agent_B").size());
        assertTrue(recorder.listByAgent("agent_C").isEmpty());
    }

    @Test
    void multiple_decisions_form_audit_chain_in_order() {
        // 模拟一个 agent 经历的完整恢复决策链：失败 → 反思 → 再失败 → 熔断
        recorder.onRecoveryDecision("agent_X", "ToolError", "RECOVERY_FAILED", false, "tool threw", null);
        recorder.onRecoveryDecision("agent_X", "ReflectionInjection", "RECOVERY_SUCCESS", true, "retry ok", null);
        recorder.onRecoveryDecision("agent_X", "CIRCUIT_BREAKER", "CIRCUIT_BREAKER_TRIGGERED", false, "threshold", null);

        var chain = recorder.listByAgent("agent_X");
        assertEquals(3, chain.size());
        // 按写入顺序（时间正序）形成可追溯链条
        assertEquals("ToolError", chain.get(0).strategyName());
        assertEquals("ReflectionInjection", chain.get(1).strategyName());
        assertEquals("CIRCUIT_BREAKER", chain.get(2).strategyName());
    }

    @Test
    void disabled_skips_recording() {
        recorder.setEnabled(false);
        recorder.onRecoveryDecision("agent_D", "Fallback", "RECOVERY_SUCCESS", true, "ok", null);
        assertTrue(recorder.listAll().isEmpty(), "禁用时应不记录");
    }

    @Test
    void null_agent_id_does_not_throw() {
        // best-effort：null agent 不应抛异常
        assertDoesNotThrow(() -> recorder.onRecoveryDecision(null, "Fallback", "RECOVERY_FAILED", false, "x", null));
        assertEquals(1, recorder.listByAgent("").size(), "null agent 应归一化为空串并记录");
    }

    @Test
    void reset_clears_buffer() {
        recorder.onRecoveryDecision("agent_R", "Fallback", "RECOVERY_SUCCESS", true, "ok", null);
        assertEquals(1, recorder.listAll().size());
        recorder.resetForTesting();
        assertTrue(recorder.listAll().isEmpty());
    }

    @Test
    void json_round_trip_preserves_fields() {
        RecoveryProvenanceRecord original = new RecoveryProvenanceRecord(
                "agent_42", "ReflectionInjection", "RECOVERY_FAILED", false,
                "denied: budget exhausted", "trace_xyz", 1700000000000L);
        String json = original.toJsonLine();
        RecoveryProvenanceRecord parsed = RecoveryProvenanceRecord.fromJsonLine(json);
        assertNotNull(parsed);
        assertEquals(original.agentId(), parsed.agentId());
        assertEquals(original.strategyName(), parsed.strategyName());
        assertEquals(original.category(), parsed.category());
        assertEquals(original.success(), parsed.success());
        assertEquals(original.reason(), parsed.reason());
        assertEquals(original.traceId(), parsed.traceId());
        assertEquals(original.ts(), parsed.ts());
    }

    @Test
    void file_persisted_as_jsonl() throws Exception {
        recorder.onRecoveryDecision("agent_F", "Fallback", "RECOVERY_SUCCESS", true, "all good", "t1");
        recorder.onRecoveryDecision("agent_F", "ToolError", "RECOVERY_FAILED", false, "boom", "t2");

        Path file = recorder.file();
        assertTrue(Files.exists(file), "JSONL 文件应已创建");
        String content = Files.readString(file);
        long lines = content.lines().count();
        assertEquals(2, lines, "应有 2 行 JSONL（每决策一行）");
        // 每行含 seq 字段（per-agent 决策序号）
        assertTrue(content.contains("\"seq\":1"));
        assertTrue(content.contains("\"seq\":2"));
        // 可被 fromJsonLine 反序列化回结构化记录
        String firstLine = content.lines().findFirst().orElse("");
        // 去掉注入的 seq 字段后应能解析（seq 是额外字段，fromJsonLine 忽略未知字段）
        RecoveryProvenanceRecord parsed = RecoveryProvenanceRecord.fromJsonLine(firstLine);
        assertNotNull(parsed, "磁盘行应可反序列化");
        assertEquals("agent_F", parsed.agentId());
    }

    @Test
    void list_all_returns_defensive_copy() {
        recorder.onRecoveryDecision("agent_L", "Fallback", "RECOVERY_SUCCESS", true, "ok", null);
        var snapshot = recorder.listAll();
        snapshot.clear(); // 修改返回的快照
        // 原缓冲不受影响
        assertEquals(1, recorder.listAll().size(), "listAll 应返回防御性拷贝");
    }
}
