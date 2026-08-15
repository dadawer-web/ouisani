package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.tool.Port;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 静态图检查器 (Graph Validator) — 类似 Java 编译器的类型检查。
 * <p>
 * 在 LLM 返回 DAG 拓扑图 JSON 后、实际交由 WorkflowEngine 运行前，
 * 遍历每条连线 (Edge)，验证上游节点的 OutputSchema 是否与下游节点的 InputSchema 类型兼容。
 * <p>
 * 如果不兼容，直接抛出 {@link TopologyCompileException}，并生成具体的修正建议发回给 LLM 重新生成。
 * <p>
 * 检查项：
 * <ol>
 *   <li><b>类型兼容性</b>：上游 OutputSchema 的字段类型必须与下游 InputSchema 兼容</li>
 *   <li><b>必需字段检查</b>：下游 InputSchema 的 required 字段必须在上游 OutputSchema 中存在</li>
 *   <li><b>DAG 合法性</b>：检测循环依赖、悬空引用</li>
 *   <li><b>孤立节点</b>：检测无入边且无出边的中间节点（可能是幻觉）</li>
 * </ol>
 */
public class GraphValidator {

    private static final Logger log = LoggerFactory.getLogger(GraphValidator.class);

    private static final class Holder {
        static final GraphValidator INSTANCE = new GraphValidator();
    }

    public static GraphValidator getInstance() {
        return Holder.INSTANCE;
    }

    private GraphValidator() {
    }

    /**
     * 验证整个 DAG 拓扑图。
     *
     * @param nodes 工作流节点列表
     * @throws TopologyCompileException 如果存在类型不兼容或其他拓扑错误
     */
    public void validate(List<WorkflowNode> nodes) throws TopologyCompileException {
        if (nodes == null || nodes.isEmpty()) {
            throw new TopologyCompileException("节点列表为空", List.of("节点列表为空"), List.of("请生成至少一个节点"));
        }

        List<String> errors = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        // 构建节点索引
        Map<String, WorkflowNode> nodeMap = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            nodeMap.put(node.instanceId(), node);
        }

        // ── 检查 1: 悬空引用 — 上游依赖指向不存在的节点 ──
        for (WorkflowNode node : nodes) {
            for (String upstreamId : node.getUpstreamDependencies()) {
                if (!nodeMap.containsKey(upstreamId)) {
                    errors.add("节点 '" + node.instanceId() + "' 的上游依赖 '" + upstreamId + "' 不存在（悬空引用）");
                    suggestions.add("请确保节点 '" + upstreamId + "' 在 nodes 数组中定义，或移除节点 '" + node.instanceId() + "' 对它的依赖");
                }
            }
        }

        // ── 检查 2: 循环依赖检测 (Kahn 全枚举 + DFS 首路径建议) ──
        Set<String> cyclicSet = new HashSet<>(allCycleNodeIds(nodes, nodeMap));
        if (!cyclicSet.isEmpty()) {
            List<String> sortedCycle = new ArrayList<>(cyclicSet);
            Collections.sort(sortedCycle);
            errors.add("检测到循环依赖,环内节点: " + String.join(", ", sortedCycle));
            List<String> cyclePath = detectCycle(nodes, nodeMap);
            if (cyclePath != null) {
                suggestions.add("请打破循环: " + String.join(" → ", cyclePath)
                        + " → " + cyclePath.get(0) + "。DAG 不允许有环。");
            } else {
                suggestions.add("请移除上述环内节点之间的相互依赖。");
            }
        }

        // ── 检查 3: 类型兼容性 — 遍历每条边验证 Schema ──
        int edgesChecked = 0;
        int edgesPassed = 0;
        for (WorkflowNode downstream : nodes) {
            for (String upstreamId : downstream.getUpstreamDependencies()) {
                WorkflowNode upstream = nodeMap.get(upstreamId);
                if (upstream == null) continue; // 已在检查 1 中报告

                edgesChecked++;
                String incompatibility = checkEdgeCompatibility(upstream, downstream);
                if (incompatibility == null) {
                    edgesPassed++;
                } else {
                    errors.add("边 [" + upstream.instanceId() + " → " + downstream.instanceId() + "] 类型不兼容: " + incompatibility);
                    suggestions.add("节点 '" + downstream.instanceId() + "' 的 InputSchema 要求字段 '"
                            + extractFieldName(incompatibility) + "'，但上游节点 '" + upstream.instanceId()
                            + "' 的 OutputSchema 未提供兼容类型。请调整上游节点的输出 Schema 或下游节点的输入 Schema。");
                }
            }
        }

        // ── 检查 4: 孤立节点检测（无入边无出边且不是起始节点） ──
        Set<String> hasIncoming = new HashSet<>();
        Set<String> hasOutgoing = new HashSet<>();
        for (WorkflowNode node : nodes) {
            for (String upstreamId : node.getUpstreamDependencies()) {
                hasIncoming.add(node.instanceId());
                hasOutgoing.add(upstreamId);
            }
        }
        for (WorkflowNode node : nodes) {
            if (!hasIncoming.contains(node.instanceId()) && !hasOutgoing.contains(node.instanceId())) {
                // 孤立节点 — 可能是 LLM 幻觉
                log.warn("[GraphValidator] 孤立节点: {} (无入边无出边)", node.instanceId());
                errors.add("节点 '" + node.instanceId() + "' 是孤立节点（无入边无出边），可能是幻觉");
                suggestions.add("请将节点 '" + node.instanceId() + "' 连接到 DAG 中，或移除它。");
            }
        }

        // ── 检查 5: 不可达节点检测 (从源点正向 BFS,排除已知环内节点) ──
        List<String> unreachable = findUnreachableNodes(nodes, nodeMap, cyclicSet);
        for (String nodeId : unreachable) {
            log.warn("[GraphValidator] 不可达节点: {} (漂浮/上游悬空/上游在环内)", nodeId);
            errors.add("节点 '" + nodeId + "' 从任何源点都不可达(漂浮子图 / 上游悬空 / 上游在环内)");
            suggestions.add(unreachableSuggestion(nodeId, nodeMap));
        }

        log.info("[GraphValidator] 验证完成: {} 个节点, {} 条边 ({} 通过), {} 个错误",
                nodes.size(), edgesChecked, edgesPassed, errors.size());

        if (!errors.isEmpty()) {
            throw new TopologyCompileException(
                    "DAG 拓扑图验证失败，共 " + errors.size() + " 个错误",
                    errors,
                    suggestions
            );
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  端口级类型校验 (Port-based Type Validation)
    // ════════════════════════════════════════════════════════════════

    /**
     * 验证 WorkflowManifest 的端口级类型兼容性。
     * <p>
     * 遍历 manifest 中的每条 {@link WorkflowEdge}（连线），获取连线的
     * sourceNode 的 outputPorts 以及 targetNode 的 inputPorts，
     * 检查上游节点输出的数据类型（Type）是否与下游节点需要的输入类型兼容。
     * <p>
     * 例如：如果上游输出 'HTML_String'，但下游需要 'JSON_Array'，则校验失败。
     * <p>
     * 同时检查所有必填（required=true）Input 端口是否都有连线连接。
     * 如果发现类型不匹配、或者必填 Input 端口没有被连线，立即抛出
     * {@link TopologyCompileException}，并在异常 message 中用自然语言
     * 清晰说明错误原因，以便后续发回给大模型纠正。
     *
     * @param manifest 工作流清单（包含 nodes 和 edges）
     * @throws TopologyCompileException 如果类型不兼容或必填端口未连接
     */
    public void validate(WorkflowManifest manifest) throws TopologyCompileException {
        List<WorkflowNode> nodes = manifest.nodes();
        List<WorkflowEdge> edges = manifest.edges();

        if (nodes == null || nodes.isEmpty()) {
            throw new TopologyCompileException(
                    "节点列表为空",
                    List.of("节点列表为空"),
                    List.of("请生成至少一个节点")
            );
        }

        List<String> errors = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        // ── 检查 1: 结构性检查（委托给现有 validate(List<WorkflowNode>)） ──
        // 包括：悬空引用、循环依赖、孤立节点、SchemaDefinition 类型兼容性
        try {
            validate(nodes);
        } catch (TopologyCompileException e) {
            // 收集结构性错误，但继续执行端口级检查以收集所有错误
            errors.addAll(e.validationErrors());
            suggestions.addAll(e.fixSuggestions());
        }

        // 如果没有 edges，跳过端口级检查（向后兼容旧拓扑模型，仅依赖 upstreamDependencies）
        if (edges == null || edges.isEmpty()) {
            if (!errors.isEmpty()) {
                throw new TopologyCompileException(
                        "DAG 拓扑图验证失败，共 " + errors.size() + " 个错误",
                        errors,
                        suggestions
                );
            }
            log.info("[GraphValidator] manifest 无 edges，跳过端口级检查 ({} 个节点)", nodes.size());
            return;
        }

        // 构建节点索引
        Map<String, WorkflowNode> nodeMap = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            nodeMap.put(node.instanceId(), node);
        }

        // ── 检查 2: 边类型兼容性 — 遍历每条 WorkflowEdge ──
        Set<String> connectedInputPorts = new HashSet<>(); // 记录已连接的输入端口 "nodeId.portName"
        int edgesChecked = 0;
        int edgesPassed = 0;

        for (WorkflowEdge edge : edges) {
            WorkflowNode sourceNode = nodeMap.get(edge.sourceNodeId());
            WorkflowNode targetNode = nodeMap.get(edge.targetNodeId());

            // 检查源节点和目标节点是否存在
            if (sourceNode == null) {
                errors.add("连线 " + edge + " 的源节点 '" + edge.sourceNodeId() + "' 不存在于 nodes 列表中");
                suggestions.add("请确保节点 '" + edge.sourceNodeId() + "' 在 nodes 数组中定义，或移除这条连线");
                continue;
            }
            if (targetNode == null) {
                errors.add("连线 " + edge + " 的目标节点 '" + edge.targetNodeId() + "' 不存在于 nodes 列表中");
                suggestions.add("请确保节点 '" + edge.targetNodeId() + "' 在 nodes 数组中定义，或移除这条连线");
                continue;
            }

            // 查找源端口和目标端口
            Port sourcePort = findPort(sourceNode.outputPorts(), edge.sourcePortName());
            Port targetPort = findPort(targetNode.inputPorts(), edge.targetPortName());

            if (sourcePort == null) {
                errors.add("节点 '" + sourceNode.instanceId() + "' 没有名为 '" + edge.sourcePortName()
                        + "' 的输出端口。可用输出端口: " + portNames(sourceNode.outputPorts()));
                suggestions.add("请在节点 '" + sourceNode.instanceId() + "' 的 outputPorts 中定义端口 '"
                        + edge.sourcePortName() + "'，或修改连线的 sourcePortName 为已存在的端口");
                continue;
            }

            if (targetPort == null) {
                errors.add("节点 '" + targetNode.instanceId() + "' 没有名为 '" + edge.targetPortName()
                        + "' 的输入端口。可用输入端口: " + portNames(targetNode.inputPorts()));
                suggestions.add("请在节点 '" + targetNode.instanceId() + "' 的 inputPorts 中定义端口 '"
                        + edge.targetPortName() + "'，或修改连线的 targetPortName 为已存在的端口");
                continue;
            }

            // 记录已连接的输入端口（用于后续必填端口检查）
            connectedInputPorts.add(targetNode.instanceId() + "." + edge.targetPortName());

            edgesChecked++;

            // 【核心逻辑】检查上游输出类型是否与下游输入类型兼容
            if (!sourcePort.isCompatibleWith(targetPort)) {
                errors.add("节点 '" + sourceNode.instanceId() + "' 输出的 " + sourcePort.type()
                        + " 无法接入节点 '" + targetNode.instanceId() + "'，因为节点 "
                        + targetNode.instanceId() + " 需要 " + targetPort.type());
                suggestions.add("请将节点 '" + sourceNode.instanceId() + "' 的输出端口 '"
                        + sourcePort.name() + "' 的类型改为 " + targetPort.type()
                        + "，或将节点 '" + targetNode.instanceId() + "' 的输入端口 '"
                        + targetPort.name() + "' 的类型改为 " + sourcePort.type());
            } else {
                edgesPassed++;
            }
        }

        // ── 检查 3: 必填 Input 端口必须被连线连接 ──
        for (WorkflowNode node : nodes) {
            for (Port inputPort : node.inputPorts()) {
                if (inputPort.required()) {
                    String portKey = node.instanceId() + "." + inputPort.name();
                    if (!connectedInputPorts.contains(portKey)) {
                        errors.add("节点 '" + node.instanceId() + "' 的必填输入端口 '"
                                + inputPort.name() + "' (" + inputPort.type() + ") 没有被任何连线连接");
                        suggestions.add("请添加一条连线，将上游节点的输出端口连接到节点 '"
                                + node.instanceId() + "' 的输入端口 '" + inputPort.name()
                                + "' (" + inputPort.type() + ")");
                    }
                }
            }
        }

        log.info("[GraphValidator] 端口级验证完成: {} 个节点, {} 条边 ({} 通过), {} 个错误",
                nodes.size(), edgesChecked, edgesPassed, errors.size());

        if (!errors.isEmpty()) {
            throw new TopologyCompileException(
                    "DAG 拓扑图端口级验证失败，共 " + errors.size() + " 个错误",
                    errors,
                    suggestions
            );
        }
    }

    /**
     * 在端口列表中按名称查找端口。
     *
     * @param ports 端口列表
     * @param name  端口名
     * @return 匹配的端口，未找到返回 null
     */
    private Port findPort(List<Port> ports, String name) {
        if (ports == null || name == null) return null;
        for (Port port : ports) {
            if (port.name().equals(name)) return port;
        }
        return null;
    }

    /**
     * 获取端口名称和类型列表（用于错误提示）。
     */
    private String portNames(List<Port> ports) {
        if (ports == null || ports.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ports.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ports.get(i).name()).append(":").append(ports.get(i).type());
        }
        return sb.toString();
    }

    /**
     * 检查单条边的类型兼容性。
     *
     * @return null 表示兼容，否则返回不兼容原因
     */
    private String checkEdgeCompatibility(WorkflowNode upstream, WorkflowNode downstream) {
        SchemaDefinition outputSchema = upstream.outputSchema();
        SchemaDefinition inputSchema = downstream.inputSchema();

        // 如果任一节点没有定义 Schema，跳过检查（向后兼容）
        if (outputSchema == null || inputSchema == null) {
            return null;
        }

        return outputSchema.checkCompatibility(inputSchema);
    }

    /**
     * 从不兼容原因中提取字段名。
     */
    private String extractFieldName(String incompatibility) {
        // 格式: "字段 'xxx' 类型不兼容: ..." 或 "上游输出缺少下游必需的字段: 'xxx'"
        int start = incompatibility.indexOf("'");
        int end = incompatibility.indexOf("'", start + 1);
        if (start >= 0 && end > start) {
            return incompatibility.substring(start + 1, end);
        }
        return "未知字段";
    }

    /**
     * Kahn 拓扑排序枚举所有环内节点 id — 镜像 PlanGraphQuery.cycleItemIds,
     * 返回无法拓扑排序到的节点 id 列表(即所有环内节点),已排序。
     * 与 detectCycle(DFS 首路径)互补:本方法用于错误枚举,前者用于修正建议。
     */
    private List<String> allCycleNodeIds(List<WorkflowNode> nodes, Map<String, WorkflowNode> nodeMap) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (WorkflowNode n : nodes) {
            indegree.putIfAbsent(n.instanceId(), 0);
            dependents.computeIfAbsent(n.instanceId(), k -> new ArrayList<>());
        }
        for (WorkflowNode n : nodes) {
            for (String upstreamId : n.getUpstreamDependencies()) {
                if (nodeMap.containsKey(upstreamId)) {  // 跳过悬空引用(已在检查 1 报告)
                    indegree.merge(n.instanceId(), 1, Integer::sum);
                    dependents.computeIfAbsent(upstreamId, k -> new ArrayList<>()).add(n.instanceId());
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
        for (WorkflowNode n : nodes) {
            if (!visited.contains(n.instanceId())) cycleIds.add(n.instanceId());
        }
        Collections.sort(cycleIds);
        return cycleIds;
    }

    /**
     * 从源点(upstreamDependencies 为空)正向 BFS,返回任何源点都到不了的节点。
     * 减去 cyclicSet 以免与检查 2 重复报环内节点。
     * 注:源点定义用"字段空"而非 Kahn 入度 0,以暴露悬空引用下游受害者。
     */
    private List<String> findUnreachableNodes(List<WorkflowNode> nodes,
                                              Map<String, WorkflowNode> nodeMap,
                                              Set<String> cyclicSet) {
        // 反向邻接:upstreamId -> [downstreamIds]
        Map<String, List<String>> dependents = new HashMap<>();
        Set<String> sources = new LinkedHashSet<>();
        for (WorkflowNode n : nodes) {
            List<String> ups = n.getUpstreamDependencies();
            if (ups == null || ups.isEmpty()) {
                sources.add(n.instanceId());
            }
            dependents.computeIfAbsent(n.instanceId(), k -> new ArrayList<>());
        }
        for (WorkflowNode n : nodes) {
            for (String upstreamId : n.getUpstreamDependencies()) {
                if (nodeMap.containsKey(upstreamId)) {  // 跳过悬空引用
                    dependents.computeIfAbsent(upstreamId, k -> new ArrayList<>()).add(n.instanceId());
                }
            }
        }
        // BFS
        Set<String> visited = new HashSet<>(sources);
        Queue<String> queue = new ArrayDeque<>(sources);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            List<String> downs = dependents.get(id);
            if (downs == null) continue;
            for (String child : downs) {
                if (visited.add(child)) queue.add(child);
            }
        }
        // 不可达 = 全部节点 - visited - cyclicSet
        List<String> unreachable = new ArrayList<>();
        for (WorkflowNode n : nodes) {
            String id = n.instanceId();
            if (!visited.contains(id) && !cyclicSet.contains(id)) {
                unreachable.add(id);
            }
        }
        return unreachable;
    }

    private String unreachableSuggestion(String nodeId, Map<String, WorkflowNode> nodeMap) {
        WorkflowNode n = nodeMap.get(nodeId);
        if (n == null) return "请移除节点 '" + nodeId + "'。";
        List<String> ups = n.getUpstreamDependencies();
        if (ups == null || ups.isEmpty()) {
            return "节点 '" + nodeId + "' 无上游却不可达(可能自环或被排除)。请检查它是否应作为源点。";
        }
        // 判断上游是否都在环内或都悬空
        boolean allDanglingOrCyclic = true;
        for (String up : ups) {
            if (nodeMap.containsKey(up)) { allDanglingOrCyclic = false; break; }
        }
        if (allDanglingOrCyclic) {
            return "节点 '" + nodeId + "' 的所有上游均为悬空引用。请补充缺失的上游节点定义,或移除该节点。";
        }
        return "节点 '" + nodeId + "' 的上游链路无法从源点到达。请将其直接上游之一改为源点可达节点,或新增源点连接到该链路。";
    }

    /**
     * 循环依赖检测 — DFS 染色法。
     *
     * @return 循环路径，null 表示无环
     */
    private List<String> detectCycle(List<WorkflowNode> nodes, Map<String, WorkflowNode> nodeMap) {
        Set<String> white = new HashSet<>(nodeMap.keySet()); // 未访问
        Set<String> gray = new HashSet<>();  // 正在访问
        Set<String> black = new HashSet<>(); // 已完成

        Map<String, String> parent = new HashMap<>();

        for (String nodeId : nodeMap.keySet()) {
            if (white.contains(nodeId)) {
                List<String> cycle = dfsDetectCycle(nodeId, nodeMap, white, gray, black, parent);
                if (cycle != null) return cycle;
            }
        }
        return null;
    }

    private List<String> dfsDetectCycle(String nodeId, Map<String, WorkflowNode> nodeMap,
                                        Set<String> white, Set<String> gray, Set<String> black,
                                        Map<String, String> parent) {
        white.remove(nodeId);
        gray.add(nodeId);

        WorkflowNode node = nodeMap.get(nodeId);
        if (node == null) return null;

        for (String upstreamId : node.getUpstreamDependencies()) {
            if (!nodeMap.containsKey(upstreamId)) continue;

            if (gray.contains(upstreamId)) {
                // 发现环！回溯路径
                List<String> cycle = new ArrayList<>();
                String current = nodeId;
                while (current != null && !current.equals(upstreamId)) {
                    cycle.add(0, current);
                    current = parent.get(current);
                }
                cycle.add(0, upstreamId);
                return cycle;
            }

            if (white.contains(upstreamId)) {
                parent.put(upstreamId, nodeId);
                List<String> cycle = dfsDetectCycle(upstreamId, nodeMap, white, gray, black, parent);
                if (cycle != null) return cycle;
            }
        }

        gray.remove(nodeId);
        black.add(nodeId);
        return null;
    }
}
