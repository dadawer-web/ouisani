package com.ouisani.aios.core.plan;

import com.google.gson.Gson;

import java.util.List;

/**
 * 图摘要 — 镜像 jcode {@code jcode-protocol/src/lib.rs:296-359} 的 {@code PlanGraphStatus}。
 * <p>
 * 作为 EventBus {@code "plan_version"} 事件 payload 的一部分，描述当前任务图的全局状态：
 * <ul>
 *   <li>{@code readyIds} — 可调度（runnable 且无未满足依赖且非环）</li>
 *   <li>{@code blockedIds} — 阻塞（依赖未完成）</li>
 *   <li>{@code activeIds} — 活跃（running/running_stale）</li>
 *   <li>{@code completedIds} — 已完成</li>
 *   <li>{@code terminalIds} — 终态（含 failed/stopped/crashed）</li>
 *   <li>{@code unresolvedDependencyIds} — 依赖了未知 id</li>
 *   <li>{@code cycleIds} — 处于依赖环中</li>
 * </ul>
 *
 * @param readyIds                就绪节点 id
 * @param blockedIds              阻塞节点 id
 * @param activeIds               活跃节点 id
 * @param completedIds            已完成节点 id
 * @param terminalIds            终态节点 id
 * @param unresolvedDependencyIds 依赖未知 id 的节点 id
 * @param cycleIds                环中节点 id
 */
public record PlanGraphSummary(
        List<String> readyIds,
        List<String> blockedIds,
        List<String> activeIds,
        List<String> completedIds,
        List<String> terminalIds,
        List<String> unresolvedDependencyIds,
        List<String> cycleIds
) {

    private static final Gson GSON = new Gson();

    /** 序列化为 JSON 字符串，供 EventBus 广播。 */
    public String toJson() {
        return GSON.toJson(this);
    }

    /** 空图摘要工厂。 */
    public static PlanGraphSummary empty() {
        return new PlanGraphSummary(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}
