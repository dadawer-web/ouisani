package com.ouisani.aios.user.apps.omnifactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 部分执行计划器 — 借鉴 n8n 的 DirectedGraph → findSubgraph → findStartNodes 七步算法。
 * <p>
 * 根据 ExecutionMode 和目标节点，计算需要执行的节点子集，
 * 以及需要 Mock 数据短路的节点列表。
 * <p>
 * <h3>工作流程</h3>
 * <pre>
 *   FULL_RUN:
 *     → 返回全部节点，无 Mock
 *
 *   EXECUTE_TO_NODE(target):
 *     → 计算目标节点的所有上游依赖链（递归）
 *     → 返回 [上游链 + 目标节点]
 *     → 已有 Boulder/Frozen 缓存的节点可短路（Mock）
 *
 *   EXECUTE_SINGLE_NODE(target):
 *     → 返回 [仅目标节点]
 *     → 所有上游依赖用 Mock 数据填充
 * </pre>
 * <p>
 * OS 类比：相当于 Linux 的 cgroup freezer — 选择性冻结/解冻进程子树。
 *
 * @see ExecutionMode
 * @see MockDataProvider
 * @see DownstreamDependencyIndex
 */
public class PartialExecutionPlanner {

    private final ExecutionMode mode;
    private final String targetNodeId;
    private final DownstreamDependencyIndex index;

    /**
     * 创建部分执行计划。
     *
     * @param mode         执行模式
     * @param targetNodeId 目标节点 ID（EXECUTE_TO_NODE 和 EXECUTE_SINGLE_NODE 模式下必需）
     * @param index        双向连接索引（用于递归查找上游依赖）
     */
    public PartialExecutionPlanner(ExecutionMode mode, String targetNodeId, DownstreamDependencyIndex index) {
        this.mode = mode != null ? mode : ExecutionMode.FULL_RUN;
        this.targetNodeId = targetNodeId;
        this.index = index;
    }

    /**
     * 计算执行计划。
     *
     * @param allNodes 完整 DAG 节点列表
     * @return 执行计划
     */
    public ExecutionPlan plan(List<WorkflowNode> allNodes) {
        if (mode == ExecutionMode.FULL_RUN || targetNodeId == null) {
            return new ExecutionPlan(allNodes, Collections.emptySet(), Collections.emptySet());
        }

        // 构建索引（如果未提供）
        DownstreamDependencyIndex idx = index;
        if (idx == null) {
            idx = new DownstreamDependencyIndex();
            idx.buildFromNodes(allNodes);
        }

        // 计算需要执行的节点集合
        Set<String> executeNodeIds = new LinkedHashSet<>();

        if (mode == ExecutionMode.EXECUTE_SINGLE_NODE) {
            // 仅执行目标节点
            executeNodeIds.add(targetNodeId);
        } else if (mode == ExecutionMode.EXECUTE_TO_NODE) {
            // 执行目标节点 + 所有上游依赖链
            executeNodeIds.add(targetNodeId);
            executeNodeIds.addAll(idx.getAllUpstreamNodes(targetNodeId));
        }

        // 过滤出需要执行的节点
        List<WorkflowNode> nodesToExecute = allNodes.stream()
                .filter(n -> executeNodeIds.contains(n.instanceId()))
                .collect(Collectors.toList());

        // 计算需要 Mock 的节点（不在执行列表中的上游依赖）
        Set<String> mockNodeIds = new HashSet<>();
        if (mode == ExecutionMode.EXECUTE_SINGLE_NODE) {
            // 所有上游依赖都需要 Mock
            mockNodeIds.addAll(idx.getAllUpstreamNodes(targetNodeId));
        } else if (mode == ExecutionMode.EXECUTE_TO_NODE) {
            // 没有需要 Mock 的节点（上游链都会真实执行）
            // 除非上游节点有 Frozen/Boulder 缓存，那由 WorkflowEngine 内部处理
        }

        // 计算被跳过的节点（不在执行列表中的节点）
        Set<String> skippedNodeIds = allNodes.stream()
                .map(WorkflowNode::instanceId)
                .filter(id -> !executeNodeIds.contains(id))
                .collect(Collectors.toSet());

        return new ExecutionPlan(nodesToExecute, mockNodeIds, skippedNodeIds);
    }

    /**
     * 执行计划 — 包含需要执行的节点列表和需要 Mock 的节点 ID 集合。
     */
    public record ExecutionPlan(
            List<WorkflowNode> nodesToExecute,
            Set<String> mockNodeIds,
            Set<String> skippedNodeIds
    ) {
        /**
         * 获取执行计划摘要。
         */
        public String summary() {
            return String.format(
                    "ExecutionPlan{execute=%d nodes, mock=%d nodes, skip=%d nodes}",
                    nodesToExecute.size(), mockNodeIds.size(), skippedNodeIds.size()
            );
        }
    }
}
