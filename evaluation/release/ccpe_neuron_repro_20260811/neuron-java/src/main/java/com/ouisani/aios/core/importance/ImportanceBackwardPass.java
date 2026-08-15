package com.ouisani.aios.core.importance;

import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Importance Score 反向传播算法 — 借鉴 DyLAN（arXiv:2310.02170）的 LLMLP.backward。
 * <p>
 * <b>DyLAN 原版</b>：最后活跃层命中共识答案的节点 importance = 1/命中数，更早层 =
 * Σ(edge.weight × 下游.importance)，edge.weight 由下游节点 reply 中自评 1-5 分归一化。
 * <p>
 * <b>neuron-java 适配</b>：
 * <ul>
 *   <li><b>伪标签</b>：用 {@link WorkflowNode.Status#SUCCESS}（客观验证通过）替代 DyLAN 的
 *       "答案命中共识" — 避免"共识即正确"强化系统性错误。</li>
 *   <li><b>等权传播</b>：第一版无边权重（DyLAN 的 1-5 自评需 prompt 工程），下游节点把自己的
 *       importance 均分给所有上游。信号弱于 DyLAN 但零 prompt 改造，follow-up 可补边权重。</li>
 *   <li><b>DAG 数据源</b>：基于 {@link WorkflowNode} 图（role + upstreamDependencies + status），
 *       非 provenance（provenance 是文件版本链，无 role 无调用图）。</li>
 * </ul>
 *
 * <h2>算法</h2>
 * <pre>
 * 1. 构建 downstream 邻接表（从 upstreamDependencies 反向）
 * 2. Kahn 拓扑排序（upstream→downstream 正序）+ 环路检测
 * 3. 逆拓扑序遍历（downstream 先于 upstream）：
 *    - 叶子（无 downstream）：SUCCESS → 1/命中数；否则 0
 *    - 内部：Σ_{d in downstream} ( d.importance / d 的有效上游数 )
 * 4. role 聚合：roleImportance[role] = Σ node.importance
 * </pre>
 *
 * <h2>性质</h2>
 * 守恒：每层 importance 总和 = SUCCESS 叶子总 importance = 1.0（每个下游把全部 importance
 * 分配给上游）。故单节点 importance ∈ [0, 1.0]；role 聚合后可超 1.0（同 role 多节点）。
 *
 * <p>纯函数，无副作用。持久化由 {@link ImportanceStore} 负责。
 */
public final class ImportanceBackwardPass {

    private static final Logger log = LoggerFactory.getLogger(ImportanceBackwardPass.class);

    private ImportanceBackwardPass() {}

    /**
     * 计算一次工作流执行后各 role 的贡献度。
     *
     * @param nodes      工作流节点（status 已由 WorkflowEngine 设置）
     * @param workflowId 工作流标识
     * @param taskType   任务类型（第一版用 workflowId 代理）
     * @return importance 记录（不含持久化）
     */
    public static ImportanceRecord compute(List<WorkflowNode> nodes, String workflowId, String taskType) {
        if (nodes == null || nodes.isEmpty()) {
            return ImportanceRecord.empty(workflowId, taskType);
        }

        // 1. instanceId → node 索引
        Map<String, WorkflowNode> byId = new HashMap<>();
        for (WorkflowNode n : nodes) {
            byId.put(n.instanceId(), n);
        }

        // 2. 构建 downstream 邻接表：downstream[upId] = [依赖 upId 的节点 id 列表]
        Map<String, List<String>> downstream = new HashMap<>();
        for (WorkflowNode n : nodes) {
            for (String upId : n.getUpstreamDependencies()) {
                // 仅记图内边（忽略悬挂引用）
                if (byId.containsKey(upId)) {
                    downstream.computeIfAbsent(upId, k -> new ArrayList<>()).add(n.instanceId());
                }
            }
        }

        // 3. 统计 SUCCESS 叶子数（无 downstream 且 status==SUCCESS）
        long successLeaves = nodes.stream()
                .filter(n -> !hasDownstream(downstream, n.instanceId()))
                .filter(n -> n.getStatus() == WorkflowNode.Status.SUCCESS)
                .count();
        double leafImportance = successLeaves > 0 ? 1.0 / successLeaves : 0.0;

        // 4. Kahn 拓扑排序（正序：upstream → downstream）
        List<String> topoOrder = topoSort(nodes, byId, downstream);

        // 5. 逆拓扑序反向传播（downstream 先于 upstream 计算）
        Map<String, Double> importance = new HashMap<>();
        Collections.reverse(topoOrder);  // 逆序：叶子（无 downstream）最先
        for (String id : topoOrder) {
            WorkflowNode node = byId.get(id);
            List<String> downs = downstream.getOrDefault(id, List.of());
            if (downs.isEmpty()) {
                // 叶子节点
                importance.put(id, node.getStatus() == WorkflowNode.Status.SUCCESS ? leafImportance : 0.0);
            } else {
                // 内部节点：聚合所有下游分摊来的 importance
                double sum = 0.0;
                for (String dId : downs) {
                    double downImp = importance.getOrDefault(dId, 0.0);
                    int downUpstreamCount = countValidUpstreams(byId.get(dId), byId);
                    if (downUpstreamCount > 0) {
                        sum += downImp / downUpstreamCount;
                    }
                }
                importance.put(id, sum);
            }
        }

        // 6. role 聚合
        Map<String, Double> roleImportance = new LinkedHashMap<>();
        for (WorkflowNode n : nodes) {
            double v = importance.getOrDefault(n.instanceId(), 0.0);
            roleImportance.merge(n.role(), v, Double::sum);
        }

        log.debug("[ImportanceBackwardPass] workflowId={}, nodes={}, successLeaves={}, roleImportance={}",
                workflowId, nodes.size(), successLeaves, roleImportance);

        return new ImportanceRecord(workflowId, taskType, System.currentTimeMillis(), roleImportance);
    }

    /**
     * Kahn 拓扑排序 — 正序（upstream → downstream）。
     * 环路检测：若 topoOrder.size() &lt; nodes.size()，存在环，环内节点被跳过（importance 保持 0）。
     */
    private static List<String> topoSort(List<WorkflowNode> nodes,
                                         Map<String, WorkflowNode> byId,
                                         Map<String, List<String>> downstream) {
        // in-degree = 节点的有效上游数（图内）
        Map<String, Integer> inDegree = new HashMap<>();
        for (WorkflowNode n : nodes) {
            inDegree.put(n.instanceId(), countValidUpstreams(n, byId));
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        List<String> order = new ArrayList<>(nodes.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String dId : downstream.getOrDefault(id, List.of())) {
                int newDeg = inDegree.merge(dId, -1, Integer::sum);
                if (newDeg == 0) queue.add(dId);
            }
        }

        if (order.size() < nodes.size()) {
            log.warn("[ImportanceBackwardPass] 检测到环：{} 个节点未参与反向传播（importance=0）",
                    nodes.size() - order.size());
        }
        return order;
    }

    /** 节点的有效上游数（仅计图内存在的上游引用） */
    private static int countValidUpstreams(WorkflowNode node, Map<String, WorkflowNode> byId) {
        int count = 0;
        for (String upId : node.getUpstreamDependencies()) {
            if (byId.containsKey(upId)) count++;
        }
        return count;
    }

    private static boolean hasDownstream(Map<String, List<String>> downstream, String id) {
        List<String> downs = downstream.get(id);
        return downs != null && !downs.isEmpty();
    }
}
