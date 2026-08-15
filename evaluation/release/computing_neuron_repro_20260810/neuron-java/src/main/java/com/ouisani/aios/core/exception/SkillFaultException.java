package com.ouisani.aios.core.exception;

import com.ouisani.aios.core.task.AiosTask;
import com.ouisani.aios.core.tool.ToolRegistry;

/**
 * 缺能中断异常 — 当 MoEGatingRouter 的认知置信度低于阈值时抛出。
 * <p>
 * 类比 CPU 的缺页中断 (Page Fault)：当系统发现当前没有足够的"技能"
 * 来处理用户请求时，不是勉强执行（产生幻觉），而是立即中断，
 * 将执行现场（AiosTask + ToolRegistry）打包挂起，等待技能补全后恢复。
 * <p>
 * <b>设计哲学</b>：宁可拒绝，不可幻觉。
 * 如果大模型对某个领域的匹配度低于 {@code cognitiveThreshold}，
 * 说明系统尚未装载该领域的 SOP 驱动，此时强行路由只会产生垃圾输出。
 * <p>
 * 异常携带的现场信息：
 * <ul>
 *   <li>{@link #task} — 触发中断的原始任务（含用户需求、任务 ID、状态）</li>
 *   <li>{@link #toolRegistry} — 当前系统可用的工具注册表（供中断处理器检查能力缺口）</li>
 *   <li>{@link #bestScore} — 最高匹配得分（低于阈值才抛出）</li>
 *   <li>{@link #threshold} — 认知置信度阈值</li>
 * </ul>
 *
 * @see com.ouisani.aios.user.cli.MoEGatingRouter
 */
public class SkillFaultException extends RuntimeException {

    /** 触发中断的原始任务 */
    private final AiosTask task;

    /** 当前系统可用的工具注册表 */
    private final ToolRegistry toolRegistry;

    /** 最高匹配得分（低于 threshold 才抛出） */
    private final double bestScore;

    /** 认知置信度阈值 */
    private final double threshold;

    /** 匹配度最高的领域名（可能为 null 表示无任何匹配） */
    private final String bestMatchDomain;

    /**
     * 构造缺能中断异常。
     *
     * @param task            触发中断的原始任务
     * @param toolRegistry    当前可用的工具注册表
     * @param bestScore       最高匹配得分
     * @param threshold       认知置信度阈值
     * @param bestMatchDomain 匹配度最高的领域名（可能为 null）
     */
    public SkillFaultException(AiosTask task, ToolRegistry toolRegistry,
                               double bestScore, double threshold, String bestMatchDomain) {
        super(String.format(
                "缺能中断：最高匹配得分 %.4f 低于认知阈值 %.4f。最佳匹配领域: %s。"
                + "系统拒绝路由 — 请补全 SOP 驱动或降低阈值后重试。",
                bestScore, threshold,
                bestMatchDomain != null ? bestMatchDomain : "(无匹配)"));
        this.task = task;
        this.toolRegistry = toolRegistry;
        this.bestScore = bestScore;
        this.threshold = threshold;
        this.bestMatchDomain = bestMatchDomain;
    }

    /** 获取触发中断的原始任务 */
    public AiosTask task() {
        return task;
    }

    /** 获取当前可用的工具注册表 */
    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    /** 获取最高匹配得分 */
    public double bestScore() {
        return bestScore;
    }

    /** 获取认知置信度阈值 */
    public double threshold() {
        return threshold;
    }

    /** 获取匹配度最高的领域名 */
    public String bestMatchDomain() {
        return bestMatchDomain;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ SkillFaultException ═══\n");
        sb.append("  消息: ").append(getMessage()).append("\n");
        sb.append("  最高得分: ").append(String.format("%.4f", bestScore)).append("\n");
        sb.append("  认知阈值: ").append(String.format("%.4f", threshold)).append("\n");
        sb.append("  最佳匹配: ").append(bestMatchDomain != null ? bestMatchDomain : "(无)").append("\n");
        if (task != null) {
            sb.append("  任务ID: ").append(task.taskId()).append("\n");
            sb.append("  任务名: ").append(task.name()).append("\n");
            sb.append("  任务描述: ").append(truncate(task.description(), 80)).append("\n");
        }
        if (toolRegistry != null) {
            sb.append("  可用工具数: ").append(toolRegistry.all().size()).append("\n");
        }
        sb.append("═══════════════════════════");
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
