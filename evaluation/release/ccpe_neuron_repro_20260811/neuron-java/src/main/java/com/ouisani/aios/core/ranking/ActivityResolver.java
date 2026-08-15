package com.ouisani.aios.core.ranking;

/**
 * 任务活跃度解析器 — 依赖反转接口，注入到 {@code PlanGraphQuery}
 * 让 nextRunnableItemIds 在同优先级任务间按近期活跃度排序。
 * <p>
 * 活跃度源可基于 {@code SwarmTaskProgress.lastActivityTimestamp()}（3 级 fallback
 * heartbeat→started→assigned）做半衰期衰减计算。
 * <p>
 * 镜像 {@code CompactCutoffGuard.SemanticBoundaryDetector} 的 NOOP 默认 + setter 注入模式。
 */
@FunctionalInterface
public interface ActivityResolver {
    /** 返回 plan item id 的近期活跃度分数（>=0，越高越活跃）；未知返回 0 */
    double activityOf(String itemId);
}
