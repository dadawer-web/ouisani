package com.ouisani.aios.core.plan;

import com.ouisani.aios.core.ranking.ActivityResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 纯函数图查询层 — 镜像 jcode {@code jcode-plan/src/lib.rs:312-651} 的纯函数集。
 * <p>
 * 所有方法无副作用，接收 {@code List<PlanItem>} / 原始数据入参，输出摘要/拓扑/亲和性。
 * {@link VersionedPlan} 将查询委托给本类，避免在可变状态类中混入算法逻辑。
 * <p>
 * <b>设计原则</b>：本类不依赖 {@link VersionedPlan}（避免循环依赖），
 * 仅依赖 {@link PlanItem} / {@link SwarmTaskProgress} / {@link PlanGraphSummary}。
 */
final class PlanGraphQuery {

    private PlanGraphQuery() {}

    // ════════════════════════════════════════════════════════════════
    //  ActivityResolver 注入 — 镜像 CompactCutoffGuard 的 NOOP+volatile+setter 三件套
    // ════════════════════════════════════════════════════════════════

    /** NOOP 活跃度解析器：默认零回归，所有 activityOf 返回 0 */
    static final ActivityResolver NOOP_ACTIVITY_RESOLVER = id -> 0.0;

    /** 当前活跃度解析器（package-private，供 VersionedPlan 同包访问） */
    static volatile ActivityResolver ACTIVITY_RESOLVER = NOOP_ACTIVITY_RESOLVER;

    /** 注入活跃度解析器；传 null 重置为 NOOP（零回归） */
    static void setActivityResolver(ActivityResolver resolver) {
        ACTIVITY_RESOLVER = resolver == null ? NOOP_ACTIVITY_RESOLVER : resolver;
    }

    // ════════════════════════════════════════════════════════════════
    //  Kahn 拓扑排序环检测 — 镜像 jcode lib.rs:349-400 cycle_item_ids
    // ════════════════════════════════════════════════════════════════

    /**
     * Kahn 拓扑排序检环 — 仅 {@code blockedBy} 中引用了 knownIds 的边计入。
     * <p>
     * 算法：
     * <ol>
     *   <li>knownIds = 所有 item.id</li>
     *   <li>对每个 item.blockedBy，仅当 dep ∈ knownIds 才 indegree[item.id]++ 且 dependents[dep].add(item.id)</li>
     *   <li>队列 = indegree==0 的 id；BFS 弹出 → visited → 对 dependents 减 indegree，为 0 入队</li>
     *   <li>cycleIds = 未 visited 的 id（indegree 仍 > 0，处于环中），排序返回</li>
     * </ol>
     *
     * @param items 任务图节点列表
     * @return 处于依赖环中的节点 id 列表（已排序），无环返回空列表
     */
    static List<String> cycleItemIds(List<PlanItem> items) {
        if (items == null || items.isEmpty()) return List.of();

        Set<String> knownIds = new HashSet<>();
        for (PlanItem item : items) knownIds.add(item.id());

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (PlanItem item : items) {
            indegree.putIfAbsent(item.id(), 0);
            dependents.computeIfAbsent(item.id(), k -> new ArrayList<>());
        }

        for (PlanItem item : items) {
            for (String dep : item.blockedBy()) {
                if (knownIds.contains(dep)) {
                    indegree.merge(item.id(), 1, Integer::sum);
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(item.id());
                }
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) continue;
            List<String> deps = dependents.get(id);
            if (deps != null) {
                for (String child : deps) {
                    int newDeg = indegree.merge(child, -1, Integer::sum);
                    if (newDeg == 0) queue.add(child);
                }
            }
        }

        List<String> cycleIds = new ArrayList<>();
        for (PlanItem item : items) {
            if (!visited.contains(item.id())) cycleIds.add(item.id());
        }
        Collections.sort(cycleIds);
        return cycleIds;
    }

    // ════════════════════════════════════════════════════════════════
    //  依赖解析辅助
    // ════════════════════════════════════════════════════════════════

    /** 返回 item.blockedBy 中不在 knownIds 中的依赖（缺失依赖）。 */
    static List<String> missingDependencies(PlanItem item, Set<String> knownIds) {
        List<String> missing = new ArrayList<>();
        for (String dep : item.blockedBy()) {
            if (!knownIds.contains(dep)) missing.add(dep);
        }
        return missing;
    }

    /** 返回 item.blockedBy 中在 knownIds 但未完成的依赖（未满足依赖）。 */
    static List<String> unresolvedDependencies(PlanItem item, Set<String> knownIds, Set<String> completedIds) {
        List<String> unresolved = new ArrayList<>();
        for (String dep : item.blockedBy()) {
            if (knownIds.contains(dep) && !completedIds.contains(dep)) {
                unresolved.add(dep);
            }
        }
        return unresolved;
    }

    /** 已完成节点 id 集合。 */
    static Set<String> completedItemIds(List<PlanItem> items) {
        Set<String> ids = new LinkedHashSet<>();
        for (PlanItem item : items) {
            if (PlanItem.isCompleted(item.status())) ids.add(item.id());
        }
        return ids;
    }

    // ════════════════════════════════════════════════════════════════
    //  图摘要 — 镜像 jcode lib.rs:402-457 summarize_plan_graph
    // ════════════════════════════════════════════════════════════════

    /**
     * 生成图摘要 — 分类所有节点到 ready/blocked/active/completed/terminal/unresolved/cycle。
     * <p>
     * 分类优先级（互斥）：
     * <ol>
     *   <li>终态 → terminalIds（completed 也加入 completedIds）</li>
     *   <li>活跃 → activeIds</li>
     *   <li>有缺失依赖 → unresolvedDependencyIds</li>
     *   <li>runnable 且无未满足依赖且非环 → readyIds</li>
     *   <li>其余 → blockedIds</li>
     * </ol>
     */
    static PlanGraphSummary summarize(List<PlanItem> items) {
        if (items == null || items.isEmpty()) return PlanGraphSummary.empty();

        Set<String> knownIds = new HashSet<>();
        for (PlanItem item : items) knownIds.add(item.id());

        Set<String> completedIds = completedItemIds(items);
        Set<String> cycleSet = new HashSet<>(cycleItemIds(items));

        List<String> readyIds = new ArrayList<>();
        List<String> blockedIds = new ArrayList<>();
        List<String> activeIds = new ArrayList<>();
        List<String> completedOut = new ArrayList<>();
        List<String> terminalIds = new ArrayList<>();
        List<String> unresolvedIds = new ArrayList<>();
        List<String> cycleOut = new ArrayList<>(cycleSet);
        Collections.sort(cycleOut);

        for (PlanItem item : items) {
            String id = item.id();
            String status = item.status();

            if (PlanItem.isTerminal(status)) {
                terminalIds.add(id);
                if (PlanItem.isCompleted(status)) completedOut.add(id);
            } else if (PlanItem.isActive(status)) {
                activeIds.add(id);
            } else {
                List<String> missing = missingDependencies(item, knownIds);
                if (!missing.isEmpty()) {
                    unresolvedIds.add(id);
                } else {
                    List<String> unresolved = unresolvedDependencies(item, knownIds, completedIds);
                    if (PlanItem.isRunnable(status) && unresolved.isEmpty() && !cycleSet.contains(id)) {
                        readyIds.add(id);
                    } else {
                        blockedIds.add(id);
                    }
                }
            }
        }

        Collections.sort(readyIds);
        Collections.sort(blockedIds);
        Collections.sort(activeIds);
        Collections.sort(completedOut);
        Collections.sort(terminalIds);
        Collections.sort(unresolvedIds);

        return new PlanGraphSummary(
                List.copyOf(readyIds),
                List.copyOf(blockedIds),
                List.copyOf(activeIds),
                List.copyOf(completedOut),
                List.copyOf(terminalIds),
                List.copyOf(unresolvedIds),
                List.copyOf(cycleOut)
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  新就绪差分 — 镜像 jcode lib.rs:645-651 newly_ready_item_ids
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算新就绪节点 — before 与 after 的 readyIds 差集。
     * <p>
     * 镜像 jcode：对比 previous_items 与 current_items 的 summarize().readyIds，
     * 返回 after 中新出现的 ready id。
     *
     * @param before 变更前快照
     * @param after  变更后快照
     * @return 新就绪 id 列表（已排序）
     */
    static List<String> newlyReadyItemIds(List<PlanItem> before, List<PlanItem> after) {
        Set<String> beforeReady = new HashSet<>(summarize(before != null ? before : List.of()).readyIds());
        List<String> afterReady = summarize(after != null ? after : List.of()).readyIds();

        List<String> newlyReady = new ArrayList<>();
        for (String id : afterReady) {
            if (!beforeReady.contains(id)) newlyReady.add(id);
        }
        Collections.sort(newlyReady);
        return newlyReady;
    }

    // ════════════════════════════════════════════════════════════════
    //  下一个可运行节点 — 镜像 jcode lib.rs:459-477 next_runnable_item_ids
    // ════════════════════════════════════════════════════════════════

    /**
     * 返回按 priority_rank + id 排序的可运行节点，limit 控制数量。
     * <p>
     * 零回归重载：委托 {@link #nextRunnableItemIds(List, int, ActivityResolver)} 传当前
     * {@link #ACTIVITY_RESOLVER}（默认 NOOP，注入后生效）。默认时排序键与改前一致：
     * (priorityRank asc, id asc)。
     */
    static List<String> nextRunnableItemIds(List<PlanItem> items, int limit) {
        return nextRunnableItemIds(items, limit, ACTIVITY_RESOLVER);
    }

    /**
     * 返回按 priority_rank + activityScore + id 排序的可运行节点。
     * <p>
     * 排序键（镜像 jcode next_runnable_item_ids 的复合排序）：
     * <ol>
     *   <li>priorityRank 升序（高优先级在前）</li>
     *   <li>activityScore 降序（近期活跃优先，注入 resolver 提供）</li>
     *   <li>id 升序（确定性 tie-break）</li>
     * </ol>
     * 当 resolver 为 NOOP 时 activityScore 恒为 0，退化为原 (priorityRank, id) 排序。
     *
     * @param items   任务图节点列表
     * @param limit   返回数量上限（<=0 表示不限制）
     * @param resolver 活跃度解析器；null 退化为 NOOP
     */
    static List<String> nextRunnableItemIds(List<PlanItem> items, int limit, ActivityResolver resolver) {
        PlanGraphSummary summary = summarize(items);
        List<String> readyIds = new ArrayList<>(summary.readyIds());
        ActivityResolver r = resolver == null ? NOOP_ACTIVITY_RESOLVER : resolver;

        readyIds.sort(Comparator
                .comparingInt((String id) -> priorityRankForId(items, id))   // priorityRank 升序
                .thenComparingDouble((String id) -> -r.activityOf(id))        // activityScore 降序（近期活跃优先）
                .thenComparing(Comparator.naturalOrder()));                     // id 升序（确定性 tie-break）

        if (limit > 0 && readyIds.size() > limit) {
            readyIds = readyIds.subList(0, limit);
        }
        return List.copyOf(readyIds);
    }

    private static int priorityRankForId(List<PlanItem> items, String id) {
        for (PlanItem item : items) {
            if (item.id().equals(id)) return PlanItem.priorityRank(item.priority());
        }
        return 1;
    }

    // ════════════════════════════════════════════════════════════════
    //  调度亲和性 — 镜像 jcode lib.rs:586-643 assignment_affinities_for_task
    // ════════════════════════════════════════════════════════════════

    /**
     * 调度亲和性结果。
     * <ul>
     *   <li>{@code loads} — 每个参与者的活跃任务数</li>
     *   <li>{@code dependencyCarryover} — 依赖 owner 奖励（每完成一个依赖 +1）</li>
     *   <li>{@code metadataCarryover} — subsystem 匹配 (+2) + fileScope 重叠 (+overlap)</li>
     * </ul>
     */
    record AssignmentAffinities(
            Map<String, Integer> loads,
            Map<String, Integer> dependencyCarryover,
            Map<String, Integer> metadataCarryover
    ) {}

    /**
     * 计算任务对各参与者的亲和性权重 — 镜像 jcode lib.rs:586-643。
     * <p>
     * 权重规则：
     * <ul>
     *   <li>依赖 owner +1：target 的每个 blockedBy 依赖，其 assignedTo 获得 +1</li>
     *   <li>subsystem 匹配 +2：参与者已被分配的任务中，subsystem 与 target 相同的，+2</li>
     *   <li>fileScope 重叠 +overlap：参与者已被分配的任务中，与 target 的 fileScope 重叠数累加</li>
     * </ul>
     *
     * @param items        任务图节点
     * @param participants 参与者集合
     * @param taskId       目标任务 id
     * @return 亲和性权重（三张 map）
     */
    static AssignmentAffinities affinitiesForTask(List<PlanItem> items,
                                                   Set<String> participants,
                                                   String taskId) {
        Map<String, Integer> loads = new HashMap<>();
        Map<String, Integer> dependencyCarryover = new HashMap<>();
        Map<String, Integer> metadataCarryover = new HashMap<>();
        for (String p : participants) {
            loads.put(p, 0);
            dependencyCarryover.put(p, 0);
            metadataCarryover.put(p, 0);
        }

        PlanItem target = null;
        for (PlanItem item : items) {
            if (item.id().equals(taskId)) {
                target = item;
                break;
            }
        }
        if (target == null) {
            return new AssignmentAffinities(loads, dependencyCarryover, metadataCarryover);
        }

        // 1. 依赖 owner +1
        for (String depId : target.blockedBy()) {
            for (PlanItem item : items) {
                if (item.id().equals(depId) && item.assignedTo() != null) {
                    dependencyCarryover.merge(item.assignedTo(), 1, Integer::sum);
                    break;
                }
            }
        }

        // 2. subsystem 匹配 +2，fileScope 重叠 +overlap
        String targetSubsystem = target.subsystem();
        Set<String> targetFiles = new HashSet<>(target.fileScope());

        for (PlanItem item : items) {
            String assignee = item.assignedTo();
            if (assignee == null || item.id().equals(taskId)) continue;

            loads.merge(assignee, 0, Integer::sum); // 确保存在

            // subsystem 匹配
            if (targetSubsystem != null && targetSubsystem.equals(item.subsystem())) {
                metadataCarryover.merge(assignee, 2, Integer::sum);
            }

            // fileScope 重叠
            if (!targetFiles.isEmpty()) {
                int overlap = 0;
                for (String f : item.fileScope()) {
                    if (targetFiles.contains(f)) overlap++;
                }
                if (overlap > 0) {
                    metadataCarryover.merge(assignee, overlap, Integer::sum);
                }
            }
        }

        // 3. loads = 活跃任务数
        for (PlanItem item : items) {
            String assignee = item.assignedTo();
            if (assignee == null) continue;
            if (PlanItem.isActive(item.status())) {
                loads.merge(assignee, 1, Integer::sum);
            }
        }

        return new AssignmentAffinities(loads, dependencyCarryover, metadataCarryover);
    }
}
