package com.ouisani.aios.core.security.risk;

/**
 * 工具调用风险评分 — 四维加权评分模型。
 * <p>
 * 借鉴 ECC (Everything Claude Code) 的 ToolCallEvent::compute_risk() 设计，
 * 将每次工具调用的风险评估从黑白名单升级为加权评分模型。
 *
 * <h3>四个评分维度</h3>
 * <ul>
 *   <li>{@link #baseToolRisk} — 基础工具风险 (0.05-0.20)</li>
 *   <li>{@link #fileSensitivity} — 文件敏感性 (0-0.25)</li>
 *   <li>{@link #blastRadius} — 爆炸半径 (0-0.35)</li>
 *   <li>{@link #irreversibility} — 不可逆性 (0-0.45)</li>
 * </ul>
 *
 * <h3>OS 类比: Linux Kernel Risk Assessment</h3>
 * 类似 Linux 内核对 syscall 的风险评估，但更精细：
 * 不再是简单的 allow/deny，而是基于多维度的加权评分，
 * 映射到四级动作 Allow → Review → Confirm → Block。
 *
 * @see RiskAction
 * @see ToolRiskScorer
 */
public record ToolRiskScore(
        double baseToolRisk,
        double fileSensitivity,
        double blastRadius,
        double irreversibility
) {

    /**
     * 计算总风险分 — 四维加权求和。
     * <p>
     * 总分范围 [0.0, 1.25]，但实际应用中会被 clamp 到 [0.0, 1.0]。
     *
     * @return 总风险分
     */
    public double totalScore() {
        return clamp(baseToolRisk + fileSensitivity + blastRadius + irreversibility);
    }

    /**
     * 根据总风险分决定执行动作。
     * <p>
     * 映射规则（借鉴 ECC 的四级动作）：
     * <ul>
     *   <li>{@link RiskAction#ALLOW} — 总分 &lt; 0.35，自动放行</li>
     *   <li>{@link RiskAction#REVIEW} — 0.35 ≤ 总分 &lt; 0.60，记录审计但放行</li>
     *   <li>{@link RiskAction#REQUIRE_CONFIRMATION} — 0.60 ≤ 总分 &lt; 0.85，触发 HITL 审批</li>
     *   <li>{@link RiskAction#BLOCK} — 总分 ≥ 0.85，直接拦截</li>
     * </ul>
     *
     * @return 建议的执行动作
     */
    public RiskAction recommendedAction() {
        double total = totalScore();
        if (total >= 0.85) return RiskAction.BLOCK;
        if (total >= 0.60) return RiskAction.REQUIRE_CONFIRMATION;
        if (total >= 0.35) return RiskAction.REVIEW;
        return RiskAction.ALLOW;
    }

    /**
     * 生成诊断报告字符串。
     */
    public String diagnosticReport() {
        return String.format(
                "RiskScore{base=%.2f, file=%.2f, blast=%.2f, irrev=%.2f, total=%.2f, action=%s}",
                baseToolRisk, fileSensitivity, blastRadius, irreversibility,
                totalScore(), recommendedAction()
        );
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
