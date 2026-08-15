package com.ouisani.aios.core.provenance;

import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.permission.PermissionBehavior;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionRule;
import com.ouisani.aios.core.recovery.BudgetAwareRecoveryGate;
import com.ouisani.aios.core.recovery.RecoveryPermissionGuard;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 恢复决策点 → provenance 直记集成测试（P2）。
 * <p>
 * 验证新论文两个决策点（{@link RecoveryPermissionGuard} / {@link BudgetAwareRecoveryGate}）在同步上下文
 * 直接调用 {@link RecoveryProvenanceRecorder#onRecoveryDecision}，把拦截/放行决策写进审计链 ——
 * 这是 scenario7（Vector A 越权）/ scenario8（预算耗尽）红队反查"载荷是否触达下游"的依据。
 * <p>
 * 与论文1的边界：本测试不触碰 {@link com.ouisani.aios.core.recovery.RecoveryOrchestrator}；
 * guard/gate 是新论文组件（或已为 sanitizer 改造），直记 provenance 是其自身行为。
 */
class RecoveryDecisionProvenanceTest {

    private static final int QUOTA = 10_000;
    private static final ToolContext GUARD_CTX = new ToolContext("agent_guard_test", null, "/tmp");

    private final RecoveryProvenanceRecorder recorder = RecoveryProvenanceRecorder.instance();
    private final RecoveryPermissionGuard guard = RecoveryPermissionGuard.instance();
    private final BudgetAwareRecoveryGate gate = BudgetAwareRecoveryGate.instance();

    @TempDir
    Path tmpDir;

    @BeforeEach
    void setup() {
        recorder.resetForTesting();
        recorder.setEnabled(true);
        recorder.setFile(tmpDir.resolve("decision_provenance.jsonl"));
    }

    @AfterEach
    void teardown() {
        recorder.resetForTesting();
        PermissionChecker.clearGlobalDenialSink();
    }

    // ── guard 决策直记（Vector A：重试越权）──

    @Test
    void guard_deny_records_interception() {
        // file_write 被 DENY —— 模拟 Vector A 越权原始调用借恢复重试，guard 拦截
        PermissionChecker pc = new PermissionChecker();
        pc.addRule(new PermissionRule(PermissionRule.RuleSource.SESSION,
                PermissionBehavior.DENY, "file_write", null));

        RecoveryPermissionGuard.GuardResult r = guard.recheck(pc, writeTool("file_write"),
                jsonInput("{}"), GUARD_CTX);

        assertFalse(r.allowed(), "越权 file_write 应被 guard 拦截");
        var records = recorder.listByAgent("agent_guard_test");
        assertEquals(1, records.size(), "guard 决策应直记一条 provenance");
        RecoveryProvenanceRecord rec = records.get(0);
        assertEquals("RECOVERY_GUARD", rec.strategyName());
        assertEquals("RECOVERY_GUARD_DENIED", rec.category());
        assertFalse(rec.success());
        assertTrue(rec.reason().contains("DENIED"), "reason 应标明拦截原因");
    }

    @Test
    void guard_allow_records_passthrough() {
        // file_read 只读放行 —— 模拟 Vector B 合法原始调用，guard 放行（载荷 fate 交给 sanitizer）
        PermissionChecker pc = new PermissionChecker(); // 无规则 = 默认放行 read-only fast path

        RecoveryPermissionGuard.GuardResult r = guard.recheck(pc, readOnlyTool("file_read"),
                jsonInput("{}"), GUARD_CTX);

        assertTrue(r.allowed(), "只读 file_read 应被 guard 放行");
        var records = recorder.listByAgent("agent_guard_test");
        assertEquals(1, records.size());
        assertEquals("RECOVERY_GUARD_ALLOWED", records.get(0).category());
        assertTrue(records.get(0).success());
    }

    @Test
    void guard_null_checker_records_legacy_allow() {
        // 无 PermissionChecker 配置 —— legacy 放行，仍记一条 ALLOW（审计可见"未接入权限子系统"）
        RecoveryPermissionGuard.GuardResult r = guard.recheck(null, writeTool("file_write"),
                jsonInput("{}"), GUARD_CTX);
        assertTrue(r.allowed());
        var records = recorder.listByAgent("agent_guard_test");
        assertEquals(1, records.size());
        assertEquals("RECOVERY_GUARD_ALLOWED", records.get(0).category());
    }

    // ── gate 决策直记（scenario8：预算感知熔断）──

    @Test
    void gate_exhausted_budget_records_deny() {
        // 90% 预算见底 —— gate 拒绝重试升级人类，阻止借重试烧光预算
        CgroupNode node = nodeAt(90);

        BudgetAwareRecoveryGate.BudgetDecision d = gate.evaluate(node, 0);

        assertFalse(d.allowRetry(), "90% 见底应拒绝重试");
        var records = recorder.listByAgent(node.name());
        assertEquals(1, records.size(), "gate 决策应直记一条 provenance");
        RecoveryProvenanceRecord rec = records.get(0);
        assertEquals("BUDGET_GATE", rec.strategyName());
        assertEquals("BUDGET_GATE_DENIED", rec.category());
        assertFalse(rec.success());
        assertTrue(rec.reason().contains("usage=90%"), "reason 应携带 usage");
    }

    @Test
    void gate_healthy_budget_records_allow() {
        CgroupNode node = nodeAt(30);

        BudgetAwareRecoveryGate.BudgetDecision d = gate.evaluate(node, 2);

        assertTrue(d.allowRetry(), "30% 健康 + 2 次失败应放行");
        var records = recorder.listByAgent(node.name());
        assertEquals(1, records.size());
        assertEquals("BUDGET_GATE_ALLOWED", records.get(0).category());
        assertTrue(records.get(0).success());
    }

    @Test
    void guard_and_gate_decisions_isolated_by_agent() {
        // guard 记到 agent_guard_test，gate 记到 cgroup name —— 互不污染，按 agent 隔离可回溯
        PermissionChecker pc = new PermissionChecker();
        pc.addRule(new PermissionRule(PermissionRule.RuleSource.SESSION,
                PermissionBehavior.DENY, "file_write", null));
        guard.recheck(pc, writeTool("file_write"), jsonInput("{}"), GUARD_CTX);
        gate.evaluate(nodeAt(90), 0);

        assertEquals(1, recorder.listByAgent("agent_guard_test").size(), "guard 记录隔离");
        assertEquals(1, recorder.listByAgent(nodeAt(90).name()).size(), "gate 记录隔离");
        assertEquals(2, recorder.listAll().size(), "合计两条决策");
    }

    // ── Stub 工具（模式取自 ReflectionInjectionRedTeamTest）──

    private static Tool<ToolInput> readOnlyTool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return true; }
        };
    }

    private static Tool<ToolInput> writeTool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
    }

    private static ToolInput jsonInput(String json) {
        return () -> json;
    }

    /** 构造指定使用率的 cgroup 节点（取自 BudgetAwareRecoveryGateTest.nodeAt 同款）。 */
    private static CgroupNode nodeAt(int usagePercent) {
        CgroupNode node = new CgroupNode("test_" + usagePercent, QUOTA, null, 1.0);
        if (usagePercent > 0) {
            node.consumeTokens(usagePercent * 100L);
        }
        return node;
    }
}
