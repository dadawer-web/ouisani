package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.cgroup.CgroupNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BudgetAwareRecoveryGate} 单元测试 — 验证预算感知重试决策的全分支契约。
 * <p>
 * 核心断言：熔断决策接入 token 预算 —— 预算见底跳过重试升级人类介入，预算健康放宽阈值，
 * 预算承压收紧阈值。本闸门是独立组件，不触碰论文1的 {@link RecoveryOrchestrator} 固定阈值。
 */
class BudgetAwareRecoveryGateTest {

    private static final int QUOTA = 10_000;

    private final BudgetAwareRecoveryGate gate = BudgetAwareRecoveryGate.instance();

    /** 构造一个指定使用率的 cgroup 节点（softLimitRatio=1.0 避免软限日志噪音）。 */
    private static CgroupNode nodeAt(int usagePercent) {
        CgroupNode node = new CgroupNode("test_" + usagePercent, QUOTA, null, 1.0);
        if (usagePercent > 0) {
            // null agentId + ratio 1.0：consumeTokens 干净执行，不触发软/硬 OOM
            node.consumeTokens(usagePercent * 100L);
        }
        return node;
    }

    // ── legacy 分支 ──

    @Test
    void null_cgroup_allows_in_legacy_mode() {
        BudgetAwareRecoveryGate.BudgetDecision d = gate.evaluate((CgroupNode) null, 0);
        assertTrue(d.allowRetry(), "无 cgroup 绑定应放行（legacy）");
        assertEquals(-1, d.usagePercent());
    }

    @Test
    void zero_quota_allows_in_legacy_mode() {
        CgroupNode node = new CgroupNode("zero", 0, null, 1.0);
        BudgetAwareRecoveryGate.BudgetDecision d = gate.evaluate(node, 0);
        assertTrue(d.allowRetry(), "零配额应放行（legacy）");
        assertEquals(-1, d.usagePercent());
    }

    // ── 预算见底分支 ──

    @Test
    void exhausted_budget_denies_retry_immediately() {
        BudgetAwareRecoveryGate.BudgetDecision d = gate.evaluate(nodeAt(90), 0);
        assertFalse(d.allowRetry(), "90% 预算见底应立即拒绝重试");
        assertEquals(90, d.usagePercent());
        assertTrue(d.reason().contains("Budget exhausted"), "原因应标明预算见底");
    }

    @Test
    void exhausted_threshold_boundary_at_85_percent() {
        // 边界：usage >= 85 即见底
        assertFalse(gate.evaluate(nodeAt(85), 0).allowRetry(), "85% 应判定见底拒绝");
        assertTrue(gate.evaluate(nodeAt(84), 0).allowRetry(), "84% 应判定承压放行");
    }

    // ── 预算健康分支（usage < 50，动态阈值 = 5）──

    @Test
    void healthy_budget_uses_relaxed_threshold_5() {
        BudgetAwareRecoveryGate.BudgetDecision d = gate.evaluate(nodeAt(30), 2);
        assertTrue(d.allowRetry(), "30% 健康 + 2 次失败应放行");
        assertEquals(5, d.dynamicThreshold(), "健康阈值应为 5");
    }

    @Test
    void healthy_budget_denies_when_failures_reach_5() {
        assertTrue(gate.evaluate(nodeAt(30), 4).allowRetry(), "30% + 4 次失败应放行（4<5）");
        assertFalse(gate.evaluate(nodeAt(30), 5).allowRetry(), "30% + 5 次失败应拒绝（5>=5）");
    }

    @Test
    void healthy_threshold_boundary_at_49_percent() {
        // 边界：usage < 50 才健康
        assertEquals(5, gate.evaluate(nodeAt(49), 0).dynamicThreshold(), "49% 应健康（阈值5）");
        assertEquals(3, gate.evaluate(nodeAt(50), 0).dynamicThreshold(), "50% 应承压（阈值3）");
    }

    // ── 预算承压分支（50 <= usage < 85，动态阈值 = 3）──

    @Test
    void stressed_budget_uses_tightened_threshold_3() {
        BudgetAwareRecoveryGate.BudgetDecision d = gate.evaluate(nodeAt(60), 2);
        assertTrue(d.allowRetry(), "60% 承压 + 2 次失败应放行（2<3）");
        assertEquals(3, d.dynamicThreshold(), "承压阈值应为 3");
    }

    @Test
    void stressed_budget_denies_when_failures_reach_3() {
        assertTrue(gate.evaluate(nodeAt(60), 2).allowRetry(), "60% + 2 次失败应放行");
        assertFalse(gate.evaluate(nodeAt(60), 3).allowRetry(), "60% + 3 次失败应拒绝（3>=3）");
    }

    // ── 决策语义完整性 ──

    @Test
    void deny_decision_carries_threshold_for_audit() {
        // 即便拒绝，dynamicThreshold 字段也应携带当前等级阈值，供审计/provenance 记录
        BudgetAwareRecoveryGate.BudgetDecision d = gate.evaluate(nodeAt(60), 3);
        assertFalse(d.allowRetry());
        assertEquals(3, d.dynamicThreshold(), "拒绝决策应携带阈值用于审计");
        assertEquals(60, d.usagePercent());
        assertTrue(d.reason().contains("3"), "原因应包含阈值");
    }
}
