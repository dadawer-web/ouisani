package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.provenance.RecoveryProvenanceRecorder;

/**
 * 预算感知恢复重试闸门 — 新论文（恢复通道攻击面）的独立贡献组件。
 * <p>
 * <b>与论文1的边界（关键）</b>：本组件是独立新增类，<b>不修改</b>
 * {@link RecoveryOrchestrator} 的固定阈值熔断（{@code CIRCUIT_BREAKER_THRESHOLD=5}、
 * 5 分钟冷却）—— 那是论文1已描述的实现逻辑，保持字节级稳定。本闸门供新论文的恢复路径
 * opt-in 调用，把"熔断决策接入资源治理"作为与论文1正交的新机制。论文1的
 * {@code orchestrate()} 不调用本类；新论文的实验路径才调用。
 * <p>
 * <b>动机</b>：论文1的固定阈值熔断不感知 token 预算。一个预算见底（85%+）的 agent 仍会被
 * 重试到固定阈值次，每次重试都烧 token，把剩余预算浪费在注定失败的尝试上，甚至推到 OOM
 * —— 这正是一条可被利用的恢复通道攻击面：攻击者诱导反复失败触发重试，借重试烧光受害者的
 * token 预算（一种经恢复通道的资源耗尽，区别于 Direction B 的正面配额攻击）。
 * <p>
 * <b>策略</b>：
 * <ul>
 *   <li>usage &gt;= {@value #BUDGET_EXHAUSTED_THRESHOLD}% —— 预算见底，跳过重试直接升级人类介入
 *       （重试也要花 token，预算见底时重试注定得不偿失）</li>
 *   <li>usage &lt; {@value #BUDGET_HEALTHY_THRESHOLD}% —— 预算健康，动态放宽阈值到
 *       {@value #DYNAMIC_THRESHOLD_HEALTHY}（多给几次机会）</li>
 *   <li>{@value #BUDGET_HEALTHY_THRESHOLD}% &le; usage &lt; {@value #BUDGET_EXHAUSTED_THRESHOLD}%
 *       —— 预算承压，动态收紧阈值到 {@value #DYNAMIC_THRESHOLD_STRESSED}（少给几次机会）</li>
 * </ul>
 * <p>
 * <b>OS 类比</b>：Linux cgroup 的 memory.pressure 感知 OOM 决策 —— 内核根据 cgroup 的
 * memory.pressure 等级决定是否回收/杀死进程，而非固定次数后一刀切。
 *
 * @see RecoveryOrchestrator（论文1的固定阈值熔断，本类不改它）
 * @see CgroupManager
 */
public final class BudgetAwareRecoveryGate {

    /** 预算见底阈值（%）—— 达到此值跳过重试，直接升级人类介入。 */
    public static final int BUDGET_EXHAUSTED_THRESHOLD = 85;
    /** 预算健康阈值（%）—— 低于此值放宽动态阈值，多给重试机会。 */
    public static final int BUDGET_HEALTHY_THRESHOLD = 50;
    /** 预算健康时的动态熔断阈值（多给机会）。 */
    public static final int DYNAMIC_THRESHOLD_HEALTHY = 5;
    /** 预算承压时的动态熔断阈值（收紧机会）。 */
    public static final int DYNAMIC_THRESHOLD_STRESSED = 3;

    /**
     * 预算感知重试决策。
     *
     * @param allowRetry        是否允许继续重试；false 表示应跳过重试并升级人类介入
     * @param usagePercent      当前 token 预算使用率（0-100）；-1 表示无 cgroup/零配额（legacy）
     * @param dynamicThreshold  当前预算等级对应的动态熔断阈值
     * @param reason            决策原因（用于审计/provenance/日志）
     */
    public record BudgetDecision(
            boolean allowRetry,
            int usagePercent,
            int dynamicThreshold,
            String reason
    ) {
        public static BudgetDecision allow(int usage, int threshold, String reason) {
            return new BudgetDecision(true, usage, threshold, reason);
        }

        public static BudgetDecision deny(int usage, int threshold, String reason) {
            return new BudgetDecision(false, usage, threshold, reason);
        }
    }

    private static final BudgetAwareRecoveryGate INSTANCE = new BudgetAwareRecoveryGate();

    public static BudgetAwareRecoveryGate instance() {
        return INSTANCE;
    }

    private BudgetAwareRecoveryGate() {
    }

    /**
     * 评估是否允许恢复重试 —— 基于 agent 的 token cgroup 预算。
     *
     * @param agentCgroup        agent 的 cgroup 节点；null 表示无 cgroup 绑定（legacy 放行）
     * @param consecutiveFailures 当前连续失败次数
     * @return 预算感知重试决策
     */
    public BudgetDecision evaluate(CgroupNode agentCgroup, int consecutiveFailures) {
        BudgetDecision decision = decide(agentCgroup, consecutiveFailures);
        recordDecision(agentCgroup, decision);
        return decision;
    }

    /**
     * 预算感知决策的纯逻辑 — {@link #evaluate(CgroupNode, int)} 的内核，不含 provenance 记录。
     * 抽出以便 evaluate 在决策后插入审计埋点。{@link #evaluate(int, int)} 经本方法委托自动获得记录。
     */
    private BudgetDecision decide(CgroupNode agentCgroup, int consecutiveFailures) {
        if (agentCgroup == null) {
            return BudgetDecision.allow(-1, DYNAMIC_THRESHOLD_HEALTHY,
                    "No cgroup bound — legacy mode, allow retry");
        }
        long quota = agentCgroup.tokenQuota();
        if (quota <= 0) {
            return BudgetDecision.allow(-1, DYNAMIC_THRESHOLD_HEALTHY,
                    "Zero quota — legacy mode, allow retry");
        }
        int usage = (int) ((agentCgroup.tokenConsumed() * 100) / quota);

        // 1. 预算见底 —— 跳过重试，升级人类介入
        if (usage >= BUDGET_EXHAUSTED_THRESHOLD) {
            return BudgetDecision.deny(usage, 0,
                    "Budget exhausted (" + usage + "%) — skip retry, escalate to human intervention "
                            + "(retry would burn remaining budget on futile attempts)");
        }
        // 2. 预算健康/承压 —— 动态阈值
        int dynamicThreshold = usage < BUDGET_HEALTHY_THRESHOLD
                ? DYNAMIC_THRESHOLD_HEALTHY : DYNAMIC_THRESHOLD_STRESSED;
        if (consecutiveFailures >= dynamicThreshold) {
            return BudgetDecision.deny(usage, dynamicThreshold,
                    "Dynamic circuit threshold (" + dynamicThreshold + ") reached at " + usage
                            + "% usage — escalate to human intervention");
        }
        return BudgetDecision.allow(usage, dynamicThreshold,
                "Budget " + (usage < BUDGET_HEALTHY_THRESHOLD ? "healthy" : "stressed")
                        + " (" + usage + "%) — allow retry " + (consecutiveFailures + 1) + "/" + dynamicThreshold);
    }

    /**
     * 把预算闸门决策旁路进恢复 provenance 审计链（best-effort，永不抛）。
     * <p>
     * 这是经恢复通道的资源耗尽攻击（scenario8）的决策点：BUDGET_GATE_DENIED = 预算见底/动态阈值
     * 触发，跳过重试升级人类（阻止攻击者借重试烧光预算）；BUDGET_GATE_ALLOWED = 放行重试。
     * <p>
     * agentId 取自 {@link CgroupNode#name()}（CgroupManager.getOrCreateAgentCgroup 把 agentId
     * 印入 cgroup name）；无 cgroup 绑定时为空串。
     */
    private static void recordDecision(CgroupNode agentCgroup, BudgetDecision decision) {
        try {
            String agentId = agentCgroup != null ? agentCgroup.name() : "";
            String category = decision.allowRetry() ? "BUDGET_GATE_ALLOWED" : "BUDGET_GATE_DENIED";
            RecoveryProvenanceRecorder.instance().onRecoveryDecision(
                    agentId, "BUDGET_GATE", category, decision.allowRetry(),
                    decision.reason() + " (usage=" + decision.usagePercent() + "%)", null);
        } catch (Throwable t) {
            // best-effort: 审计埋点绝不中断闸门主流程
        }
    }

    /**
     * 便捷重载：用 int agentId 从 {@link CgroupManager} 解析 cgroup 节点后评估。
     * <p>
     * 生产路径下 agent 的 cgroup 由 {@link CgroupManager#getOrCreateAgentCgroup(int)} 自动创建
     * （默认配额 50000 token）。无消费历史的新 agent usage=0 → 视为健康放行。
     *
     * @param agentId            agent 的 PID
     * @param consecutiveFailures 当前连续失败次数
     * @return 预算感知重试决策
     */
    public BudgetDecision evaluate(int agentId, int consecutiveFailures) {
        return evaluate(CgroupManager.instance().getOrCreateAgentCgroup(agentId), consecutiveFailures);
    }
}
