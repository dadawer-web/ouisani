package com.ouisani.aios.core.provenance;

import com.ouisani.aios.core.network.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RecoveryProvenanceSubscriber} 测试 — 验证论文1 orchestrator 的恢复事件被旁路转写进审计链。
 * <p>
 * 核心断言：订阅者监听 {@code sys.telemetry.events} + {@code sys.kernel.panic}，把论文1
 * broadcastRecoveryEvent / triggerCircuitBreaker 的广播转写成 {@link RecoveryProvenanceRecord}，
 * <b>不修改 orchestrator</b>。含 EventBus 端到端集成。
 */
class RecoveryProvenanceSubscriberTest {

    private final RecoveryProvenanceRecorder recorder = RecoveryProvenanceRecorder.instance();
    private final RecoveryProvenanceSubscriber subscriber = RecoveryProvenanceSubscriber.instance();

    @TempDir
    Path tmpDir;

    @BeforeEach
    void setup() {
        subscriber.stop(); // 确保干净起点
        recorder.resetForTesting();
        recorder.setEnabled(true);
        recorder.setFile(tmpDir.resolve("recovery_provenance.jsonl"));
    }

    @AfterEach
    void teardown() {
        subscriber.stop();
        recorder.resetForTesting();
    }

    // 论文1 orchestrator 的实际 payload 格式（broadcastRecoveryEvent / triggerCircuitBreaker 手写 JSON）
    private static final String TELEMETRY_SUCCESS =
            "{\"eventType\":\"RECOVERY_SUCCESS\", \"agentId\":\"agent_42\", \"strategy\":\"ReflectionInjection\", \"message\":\"retry ok\", \"timestamp\":1700000000000}";
    private static final String TELEMETRY_FAILED =
            "{\"eventType\":\"RECOVERY_FAILED\", \"agentId\":\"agent_42\", \"strategy\":\"ToolError\", \"message\":\"tool threw NPE\", \"timestamp\":1700000000001}";
    private static final String KERNEL_PANIC =
            "{\"eventType\":\"CIRCUIT_BREAKER_TRIGGERED\", \"agentId\":\"agent_42\", \"consecutiveFailures\":5, \"timestamp\":1700000000002}";
    private static final String NON_RECOVERY_TELEMETRY =
            "{\"eventType\":\"METRIC_SAMPLE\", \"agentId\":\"agent_42\", \"strategy\":\"\", \"message\":\"cpu=0.3\", \"timestamp\":1700000000003}";

    // P2 新增通道 payload（论文1 WorkflowEngine.broadcast sys.semantic.crash + orchestrator.triggerHumanIntervention 手写 JSON）
    // 注意：crash/intervention payload 用 nodeId，而 orchestrator 用 nodeId 作 agentId（performCrashRecovery
    // new RecoveryContext(nodeId,...)），故测试里 nodeId=agent_42 使整链同 key 可拼接。
    private static final String SEMANTIC_CRASH =
            "{\"eventType\":\"SEMANTIC_CRASH\", \"nodeId\":\"agent_42\", \"workflowId\":\"wf_1\", "
                    + "\"dumpPath\":\"/tmp/dump.json\", \"error\":\"NPE in node\", \"durationMs\":42, "
                    + "\"role\":\"worker\", \"blueprintId\":\"bp_1\", \"timestamp\":1700000000004}";
    private static final String HUMAN_INTERVENTION =
            "{\"eventType\":\"HUMAN_INTERVENTION_REQUIRED\", \"nodeId\":\"agent_42\", \"workflowId\":\"wf_1\", "
                    + "\"dumpPath\":\"/tmp/dump.json\", \"diagnosis\":\"all strategies exhausted\", \"timestamp\":1700000000005}";
    private static final String NON_CRASH_EVENT =
            "{\"eventType\":\"OTHER_CRASH\", \"nodeId\":\"agent_42\", \"timestamp\":1700000000006}";

    // ── 直接喂 payload 的单元测试（绕开 EventBus，确定性）──

    @Test
    void handle_telemetry_success_records_decision() {
        subscriber.handleTelemetryEvent(TELEMETRY_SUCCESS);
        var records = recorder.listByAgent("agent_42");
        assertEquals(1, records.size());
        assertEquals("ReflectionInjection", records.get(0).strategyName());
        assertEquals("RECOVERY_SUCCESS", records.get(0).category());
        assertTrue(records.get(0).success());
        assertEquals("retry ok", records.get(0).reason());
    }

    @Test
    void handle_telemetry_failed_records_decision() {
        subscriber.handleTelemetryEvent(TELEMETRY_FAILED);
        var records = recorder.listByAgent("agent_42");
        assertEquals(1, records.size());
        assertEquals("RECOVERY_FAILED", records.get(0).category());
        assertFalse(records.get(0).success());
        assertEquals("ToolError", records.get(0).strategyName());
    }

    @Test
    void handle_non_recovery_event_is_ignored() {
        // telemetry 通道还有别的事件 —— 非恢复事件不应污染恢复审计链
        subscriber.handleTelemetryEvent(NON_RECOVERY_TELEMETRY);
        assertTrue(recorder.listAll().isEmpty(), "非恢复事件应被忽略");
    }

    @Test
    void handle_kernel_panic_records_circuit_breaker() {
        subscriber.handleKernelPanicEvent(KERNEL_PANIC);
        var records = recorder.listByAgent("agent_42");
        assertEquals(1, records.size());
        assertEquals("CIRCUIT_BREAKER", records.get(0).strategyName());
        assertEquals("CIRCUIT_BREAKER_TRIGGERED", records.get(0).category());
        assertFalse(records.get(0).success());
        assertTrue(records.get(0).reason().contains("consecutiveFailures=5"), "原因应含失败次数");
    }

    @Test
    void handle_kernel_panic_non_circuit_event_ignored() {
        subscriber.handleKernelPanicEvent("{\"eventType\":\"OTHER_PANIC\", \"agentId\":\"a\", \"timestamp\":1}");
        assertTrue(recorder.listAll().isEmpty(), "非熔断 panic 事件应被忽略");
    }

    @Test
    void malformed_payload_does_not_throw() {
        // best-effort：坏 payload 不应抛异常、不记录
        assertDoesNotThrow(() -> subscriber.handleTelemetryEvent("not json"));
        assertDoesNotThrow(() -> subscriber.handleKernelPanicEvent("{broken"));
        assertDoesNotThrow(() -> subscriber.handleSemanticCrashEvent("not json"));
        assertDoesNotThrow(() -> subscriber.handleHumanInterventionEvent("{broken"));
        assertTrue(recorder.listAll().isEmpty());
    }

    // ── P2 新增：crash 起点 + 人类介入终态 ──

    @Test
    void handle_semantic_crash_records_chain_start() {
        subscriber.handleSemanticCrashEvent(SEMANTIC_CRASH);
        var records = recorder.listByAgent("agent_42");
        assertEquals(1, records.size());
        RecoveryProvenanceRecord r = records.get(0);
        assertEquals("SEMANTIC_CRASH", r.strategyName());
        assertEquals("CRASH_ARRIVED", r.category());
        assertFalse(r.success(), "crash 到达本身非成功放行");
        assertTrue(r.reason().contains("workflowId=wf_1"), "reason 应含 workflowId");
        assertTrue(r.reason().contains("error=NPE in node"), "reason 应含 error");
    }

    @Test
    void handle_non_crash_event_ignored() {
        subscriber.handleSemanticCrashEvent(NON_CRASH_EVENT);
        assertTrue(recorder.listAll().isEmpty(), "非 SEMANTIC_CRASH 事件应被忽略");
    }

    @Test
    void handle_human_intervention_records_chain_terminal() {
        subscriber.handleHumanInterventionEvent(HUMAN_INTERVENTION);
        var records = recorder.listByAgent("agent_42");
        assertEquals(1, records.size());
        RecoveryProvenanceRecord r = records.get(0);
        assertEquals("HUMAN_INTERVENTION", r.strategyName());
        assertEquals("HUMAN_INTERVENTION_REQUIRED", r.category());
        assertFalse(r.success(), "升级人类=恢复放弃，非成功");
        assertTrue(r.reason().contains("diagnosis=all strategies exhausted"));
    }

    // ── EventBus 端到端集成（论文1 orchestrator 的真实广播路径）──
    //
    // EventBus 的订阅者 handler 在独立虚拟线程异步执行（见 EventBus.broadcast 的
    // Thread.startVirtualThread），广播返回时记录尚未落盘 —— 断言前必须轮询等待。
    // 这里用 bounded polling 而非给 production 代码注入 CountDownLatch，避免污染
    // RecoveryProvenanceSubscriber 的设计（审计是 best-effort，不应向调用方暴露同步原语）。

    /**
     * 轮询 recorder 直到某 agent 的决策记录达到期望计数，或超时。
     * 超时后让随后的 assertEquals 打印实际值，便于诊断。
     */
    private void awaitAgentRecords(String agentId, int expectedCount) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (recorder.listByAgent(agentId).size() >= expectedCount) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 轮询确认某 agent 的记录数在窗口内保持 0（用于 stop 后验证不再有写入）。
     */
    private void assertNoRecordsFor(String agentId, long windowMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs);
        while (System.nanoTime() < deadline) {
            assertTrue(recorder.listByAgent(agentId).isEmpty(),
                    "stop 后不应再记录该 agent 的恢复决策");
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void eventbus_broadcast_reaches_subscriber() {
        subscriber.start();
        assertTrue(subscriber.isStarted());

        // 模拟论文1 orchestrator 的广播（broadcastRecoveryEvent + triggerCircuitBreaker）
        EventBus.instance().broadcast("sys.telemetry.events", TELEMETRY_FAILED);
        EventBus.instance().broadcast("sys.telemetry.events", TELEMETRY_SUCCESS);
        EventBus.instance().broadcast("sys.kernel.panic", KERNEL_PANIC);

        awaitAgentRecords("agent_42", 3);
        var records = recorder.listByAgent("agent_42");
        assertEquals(3, records.size(), "三次广播应产生三条审计记录");
        // 验证三类决策都被捕获
        assertTrue(records.stream().anyMatch(r -> "RECOVERY_FAILED".equals(r.category())));
        assertTrue(records.stream().anyMatch(r -> "RECOVERY_SUCCESS".equals(r.category())));
        assertTrue(records.stream().anyMatch(r -> "CIRCUIT_BREAKER_TRIGGERED".equals(r.category())));
    }

    @Test
    void start_is_idempotent_no_duplicate_records() {
        subscriber.start();
        subscriber.start(); // 重复 start 应幂等，不重复订阅
        EventBus.instance().broadcast("sys.telemetry.events", TELEMETRY_FAILED);
        awaitAgentRecords("agent_42", 1);
        assertEquals(1, recorder.listByAgent("agent_42").size(),
                "幂等 start 不应导致重复记录");
    }

    @Test
    void stop_unsubscribes_no_more_records() {
        subscriber.start();
        EventBus.instance().broadcast("sys.telemetry.events", TELEMETRY_FAILED);
        // 必须先等首条广播的虚拟线程落盘 —— 否则 stop()+reset 后它仍可能写入，
        // 造成"stop 后仍记录"的假阳性。
        awaitAgentRecords("agent_42", 1);
        assertEquals(1, recorder.listByAgent("agent_42").size());

        subscriber.stop();
        assertFalse(subscriber.isStarted());
        recorder.resetForTesting();

        EventBus.instance().broadcast("sys.telemetry.events", TELEMETRY_FAILED);
        assertNoRecordsFor("agent_42", 300);
    }

    @Test
    void full_audit_chain_for_one_agent() {
        // 端到端：一个 agent 经历 失败→成功→熔断 的完整决策链，全部进审计账本
        subscriber.start();
        EventBus.instance().broadcast("sys.telemetry.events", TELEMETRY_FAILED);
        EventBus.instance().broadcast("sys.telemetry.events", TELEMETRY_SUCCESS);
        EventBus.instance().broadcast("sys.kernel.panic", KERNEL_PANIC);

        awaitAgentRecords("agent_42", 3);
        var chain = recorder.listByAgent("agent_42");
        assertEquals(3, chain.size());
        // 形成可追溯链条：失败 → 成功 → 熔断（按广播顺序，时间正序）
        assertTrue(chain.stream().anyMatch(r -> "RECOVERY_FAILED".equals(r.category())));
        assertTrue(chain.stream().anyMatch(r -> "RECOVERY_SUCCESS".equals(r.category())));
        assertTrue(chain.stream().anyMatch(r -> "CIRCUIT_BREAKER_TRIGGERED".equals(r.category())));
    }

    @Test
    void full_recovery_chain_crash_to_resolution() {
        // P2 端到端：一个节点经历 crash 到达 → 策略失败 → 策略成功(resumeNode) → 熔断/升级
        // 全部经 EventBus 旁路落进审计链，同 key(nodeId=agent_42) 可拼接成完整链。
        subscriber.start();
        EventBus.instance().broadcast("sys.semantic.crash", SEMANTIC_CRASH);
        EventBus.instance().broadcast("sys.telemetry.events", TELEMETRY_FAILED);
        EventBus.instance().broadcast("sys.telemetry.events", TELEMETRY_SUCCESS);
        EventBus.instance().broadcast("sys.kernel.panic", KERNEL_PANIC);
        EventBus.instance().broadcast("sys.human_intervention_required", HUMAN_INTERVENTION);

        awaitAgentRecords("agent_42", 5);
        var chain = recorder.listByAgent("agent_42");
        assertEquals(5, chain.size(), "五类事件应产生五条审计记录，串成完整链");
        // 链节点齐全：crash 起点 → 失败 → 成功 → 熔断 → 升级人类
        assertTrue(chain.stream().anyMatch(r -> "CRASH_ARRIVED".equals(r.category())), "缺 crash 起点");
        assertTrue(chain.stream().anyMatch(r -> "RECOVERY_FAILED".equals(r.category())), "缺策略失败");
        assertTrue(chain.stream().anyMatch(r -> "RECOVERY_SUCCESS".equals(r.category())), "缺策略成功");
        assertTrue(chain.stream().anyMatch(r -> "CIRCUIT_BREAKER_TRIGGERED".equals(r.category())), "缺熔断");
        assertTrue(chain.stream().anyMatch(r -> "HUMAN_INTERVENTION_REQUIRED".equals(r.category())), "缺升级人类");
    }
}
