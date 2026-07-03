package com.ouisani.aios.core.plan;

import java.util.List;

/**
 * 声明式 DAG 节点 — 镜像 jcode {@code jcode-plan/src/lib.rs:8-22} 的 {@code PlanItem}。
 * <p>
 * 与运行时 PCB {@link com.ouisani.aios.core.AgentTask} 互补：
 * <ul>
 *   <li>PlanItem 描述"做什么"（声明式依赖图节点）</li>
 *   <li>AgentTask 描述"怎么跑"（运行时进程控制块）</li>
 *   <li>映射：{@code assignedTo} ↔ {@code AgentTask.pid} 的字符串形式</li>
 * </ul>
 * <p>
 * {@code status} 保留 {@code String} 而非 enum，忠实镜像 jcode 的别名容忍
 * （{@code completed}/{@code done} 等价），避免 enum 别名映射复杂度。
 *
 * @param content    任务内容描述
 * @param status     状态字符串（queued/ready/pending/todo/running/running_stale/completed/done/failed/stopped/crashed）
 * @param priority   优先级字符串（high/urgent/p0/medium/normal/p1/low/p2）
 * @param id         节点唯一标识
 * @param subsystem  AIOS 子系统分片标识（可空，如 "vfs"/"memory"/"llm"）
 * @param fileScope  文件作用域（空列表表示无约束）
 * @param blockedBy  阻塞依赖节点 id 列表（构成 DAG 边）
 * @param assignedTo 已分配的执行者标识（可空，↔ AgentTask.pid 字符串）
 */
public record PlanItem(
        String content,
        String status,
        String priority,
        String id,
        String subsystem,
        List<String> fileScope,
        List<String> blockedBy,
        String assignedTo
) {

    public PlanItem {
        if (fileScope == null) fileScope = List.of();
        else fileScope = List.copyOf(fileScope);
        if (blockedBy == null) blockedBy = List.of();
        else blockedBy = List.copyOf(blockedBy);
    }

    // ════════════════════════════════════════════════════════════════
    //  状态分类 — 忠实镜像 jcode lib.rs:163-180
    // ════════════════════════════════════════════════════════════════

    /** 是否已完成状态（completed/done）。 */
    public static boolean isCompleted(String status) {
        return "completed".equals(status) || "done".equals(status);
    }

    /** 是否终态（completed/done/failed/stopped/crashed）。 */
    public static boolean isTerminal(String status) {
        return isCompleted(status)
                || "failed".equals(status)
                || "stopped".equals(status)
                || "crashed".equals(status);
    }

    /** 是否活跃状态（running/running_stale）— sweepTick 扫描目标。 */
    public static boolean isActive(String status) {
        return "running".equals(status) || "running_stale".equals(status);
    }

    /** 是否可运行状态（queued/ready/pending/todo）— 调度候选。 */
    public static boolean isRunnable(String status) {
        return "queued".equals(status)
                || "ready".equals(status)
                || "pending".equals(status)
                || "todo".equals(status);
    }

    /**
     * 优先级排序值 — 镜像 jcode lib.rs:303 priority_rank。
     * <ul>
     *   <li>high/urgent/p0 → 0（最高）</li>
     *   <li>medium/normal/p1 → 1（默认）</li>
     *   <li>low/p2 → 2</li>
     *   <li>其他/null → 1（默认）</li>
     * </ul>
     */
    public static int priorityRank(String priority) {
        if (priority == null) return 1;
        return switch (priority.toLowerCase()) {
            case "high", "urgent", "p0" -> 0;
            case "low", "p2" -> 2;
            default -> 1; // medium/normal/p1 及未知值
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  不可变更新 + 工厂
    // ════════════════════════════════════════════════════════════════

    /** 返回新状态副本（不可变更新）。 */
    public PlanItem withStatus(String newStatus) {
        return new PlanItem(content, newStatus, priority, id, subsystem, fileScope, blockedBy, assignedTo);
    }

    /** 返回新分配者副本（不可变更新）。 */
    public PlanItem withAssignedTo(String newAssignee) {
        return new PlanItem(content, status, priority, id, subsystem, fileScope, blockedBy, newAssignee);
    }

    /**
     * 创建一个 queued 状态的待调度节点。
     *
     * @param id        节点 id
     * @param content   任务内容
     * @param priority  优先级（null 视为 "medium"）
     * @param subsystem  子系统（可空）
     * @param fileScope 文件作用域（可空）
     * @param blockedBy 阻塞依赖（可空）
     * @return queued 状态的 PlanItem
     */
    public static PlanItem queued(String id, String content, String priority,
                                   String subsystem, List<String> fileScope, List<String> blockedBy) {
        return new PlanItem(
                content,
                "queued",
                priority != null ? priority : "medium",
                id,
                subsystem,
                fileScope,
                blockedBy,
                null
        );
    }
}
