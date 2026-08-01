package com.ouisani.aios.core.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EscalationPolicy} 单元测试 — 深度感知升级策略（Gap C-c）。
 * <p>
 * 纯单元测试，不触 ThreadLocal/DelegationGuard，直接调用 3 参 {@link EscalationPolicy#evaluate(int, String, int)}
 * （env 不可 per-test 设置，故用显式 maxDepth 重载）。
 * <p>
 * <b>验证矩阵</b>：
 * <ul>
 *   <li>depth &ge; maxDepth + 破坏性工具 → {@link EscalationPolicy.Verdict#DENY_DEPTH}</li>
 *   <li>depth &lt; maxDepth + 破坏性工具 → {@link EscalationPolicy.Verdict#ASK_WITH_CONTEXT}</li>
 *   <li>任意 depth + 只读/非破坏性工具 → {@link EscalationPolicy.Verdict#ASK_WITH_CONTEXT}</li>
 *   <li>spawn 类工具（agent/handoff）→ 交 DelegationGuard，本策略不拦</li>
 * </ul>
 */
class EscalationPolicyTest {

    private static final int MAX = 2;

    @Test
    void destructiveAtThreshold_denied() {
        assertEquals(EscalationPolicy.Verdict.DENY_DEPTH,
                EscalationPolicy.evaluate(2, "bash", MAX),
                "depth=2 (>=max=2) + bash → DENY_DEPTH");
    }

    @Test
    void destructiveDeepDenied() {
        assertEquals(EscalationPolicy.Verdict.DENY_DEPTH,
                EscalationPolicy.evaluate(3, "bash", MAX),
                "depth=3 + bash → DENY_DEPTH");
        assertEquals(EscalationPolicy.Verdict.DENY_DEPTH,
                EscalationPolicy.evaluate(5, "shell", MAX),
                "depth=5 + shell → DENY_DEPTH");
        assertEquals(EscalationPolicy.Verdict.DENY_DEPTH,
                EscalationPolicy.evaluate(2, "security_scan", MAX),
                "depth=2 + security_scan → DENY_DEPTH");
    }

    @Test
    void destructiveShallow_askWithContext() {
        assertEquals(EscalationPolicy.Verdict.ASK_WITH_CONTEXT,
                EscalationPolicy.evaluate(1, "bash", MAX),
                "depth=1 < max=2 + bash → ASK_WITH_CONTEXT（顶层附近仍可询问人类）");
        assertEquals(EscalationPolicy.Verdict.ASK_WITH_CONTEXT,
                EscalationPolicy.evaluate(0, "bash", MAX),
                "depth=0（顶层 agent）+ bash → ASK_WITH_CONTEXT（零回归：顶层不受限）");
    }

    @Test
    void readOnlyNeverDepthDenied() {
        assertEquals(EscalationPolicy.Verdict.ASK_WITH_CONTEXT,
                EscalationPolicy.evaluate(3, "file_read", MAX),
                "只读工具即使深层也只 ASK，不深度拒");
        assertEquals(EscalationPolicy.Verdict.ASK_WITH_CONTEXT,
                EscalationPolicy.evaluate(9, "grep", MAX),
                "只读工具深层不拒");
    }

    @Test
    void spawnToolsNotGated_here() {
        // agent/handoff 已由 DelegationGuard.maxDepth 硬封顶，本策略不重复拦截
        assertEquals(EscalationPolicy.Verdict.ASK_WITH_CONTEXT,
                EscalationPolicy.evaluate(2, "agent", MAX),
                "spawn 工具交 DelegationGuard，本策略不拦");
        assertEquals(EscalationPolicy.Verdict.ASK_WITH_CONTEXT,
                EscalationPolicy.evaluate(2, "handoff", MAX),
                "handoff 交 DelegationGuard，本策略不拦");
    }

    @Test
    void caseInsensitive_nullSafe() {
        assertEquals(EscalationPolicy.Verdict.DENY_DEPTH,
                EscalationPolicy.evaluate(2, "BASH", MAX),
                "工具名大小写不敏感");
        assertEquals(EscalationPolicy.Verdict.ASK_WITH_CONTEXT,
                EscalationPolicy.evaluate(2, null, MAX),
                "null 工具名 → 非破坏性 → ASK");
        assertEquals(EscalationPolicy.Verdict.ASK_WITH_CONTEXT,
                EscalationPolicy.evaluate(2, "", MAX),
                "空工具名 → 非破坏性 → ASK");
    }

    @Test
    void thresholdZero_deniesEverythingDestructive() {
        // maxDepth=0：任何 depth>=0 的破坏性工具都拒（极端严格配置）
        assertEquals(EscalationPolicy.Verdict.DENY_DEPTH,
                EscalationPolicy.evaluate(0, "bash", 0),
                "maxDepth=0 时顶层 bash 也拒");
    }

    @Test
    void envDrivenOverload_delegatesToCached() {
        // env 驱动重载委托 3 参；cachedMaxDepth 默认 2（除非 env 覆盖）
        int cached = EscalationPolicy.maxEscalationDepth();
        assertEquals(cached >= 1, true, "cachedMaxDepth 应为正数（默认 2 或 env 覆盖）");
        // 与显式 maxDepth=cached 的 3 参结果一致
        assertEquals(EscalationPolicy.evaluate(cached, "bash", cached),
                EscalationPolicy.evaluate(cached, "bash"),
                "env 驱动重载应与显式 maxDepth=cached 的 3 参结果一致");
    }
}
