package com.ouisani.aios.core.security.risk;

/**
 * 风险评分对应的执行动作。
 * <p>
 * 借鉴 ECC 的四级动作设计，从低到高：
 *
 * <ul>
 *   <li>{@link #ALLOW} — 自动放行，仅记录基础日志</li>
 *   <li>{@link #REVIEW} — 放行但记录详细审计，用于事后分析</li>
 *   <li>{@link #REQUIRE_CONFIRMATION} — 触发 Human-in-the-Loop 审批门</li>
 *   <li>{@link #BLOCK} — 直接拦截，抛出 SecurityException</li>
 * </ul>
 *
 * @see ToolRiskScore
 */
public enum RiskAction {

    /** 自动放行 — 总分 &lt; 0.35 */
    ALLOW,

    /** 放行但记录审计 — 0.35 ≤ 总分 &lt; 0.60 */
    REVIEW,

    /** 触发 HITL 审批 — 0.60 ≤ 总分 &lt; 0.85 */
    REQUIRE_CONFIRMATION,

    /** 直接拦截 — 总分 ≥ 0.85 */
    BLOCK;

    /**
     * 判断此动作是否需要阻塞等待人类确认。
     */
    public boolean requiresHumanConfirmation() {
        return this == REQUIRE_CONFIRMATION;
    }

    /**
     * 判断此动作是否应该被直接拦截。
     */
    public boolean shouldBlock() {
        return this == BLOCK;
    }

    /**
     * 判断此动作是否需要记录详细审计日志。
     */
    public boolean shouldAudit() {
        return this != ALLOW;
    }
}
