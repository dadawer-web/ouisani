package com.ouisani.aios.user.apps.omnifactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双向连接索引 — 借鉴 n8n 的 connectionsBySourceNode + connectionsByDestinationNode 设计。
 * <p>
 * 当前 WorkflowEngine 仅维护 upstreamDependencies（单向 Pull），下游关系需要 O(n) 遍历推导。
 * 此索引在 DAG 初始化时构建反向映射，将下游查找从 O(n) 降为 O(1)。
 * <p>
 * <h3>核心场景</h3>
 * <ul>
 *   <li>RecoveryOrchestrator 挂起崩溃节点的所有下游节点</li>
 *   <li>TopologyMutationStrategy 动态插入节点时更新依赖链</li>
 *   <li>部分执行 (Partial Execution) 时快速定位受影响子图</li>
 *   <li>Sink 节点计算从 O(n) 降为 O(1)</li>
 * </ul>
 * <p>
 * OS 类比：相当于 Linux 内核的父进程→子进程双向链表，
 * 既能从父找子（下游），也能从子找父（上游）。
 *
 * @see WorkflowEngine
 * @see WorkflowNode
 */
public class DownstreamDependencyIndex {

    /** 正向索引: nodeId → 下游节点 ID 列表 (nodeId → 谁依赖我) */
    private final Map<String, List<String>> downstreamMap = new ConcurrentHashMap<>();

    /** 反向索引: nodeId → 上游节点 ID 列表 (nodeId → 我依赖谁) — 缓存 upstreamDependencies */
    private final Map<String, List<String>> upstreamMap = new ConcurrentHashMap<>();

    /** Sink 节点集合 (无下游依赖的节点) — 缓存计算结果 */
    private volatile Set<String> sinkNodeIds = Collections.emptySet();

    /** Source 节点集合 (无上游依赖的节点) — 缓存计算结果 */
    private volatile Set<String> sourceNodeIds = Collections.emptySet();

    /**
     * 从节点列表构建双向索引。
     *
     * @param nodes DAG 中的所有节点
     */
    public void buildFromNodes(List<WorkflowNode> nodes) {
        downstreamMap.clear();
        upstreamMap.clear();

        // 初始化所有节点的空列表
        for (WorkflowNode node : nodes) {
            downstreamMap.putIfAbsent(node.instanceId(), new ArrayList<>());
            upstreamMap.put(node.instanceId(), new ArrayList<>(node.getUpstreamDependencies()));
        }

        // 构建反向索引: 如果 B 依赖 A，则 A 的下游包含 B
        for (WorkflowNode node : nodes) {
            String downstreamId = node.instanceId();
            for (String upstreamId : node.getUpstreamDependencies()) {
                downstreamMap.computeIfAbsent(upstreamId, k -> new ArrayList<>())
                        .add(downstreamId);
            }
        }

        // 计算缓存
        recomputeCachedSets();
    }

    /**
     * 获取指定节点的所有直接下游节点 ID — O(1) 查找。
     *
     * @param nodeId 节点 ID
     * @return 下游节点 ID 列表（不可变），不存在则返回空列表
     */
    public List<String> getDownstreamNodes(String nodeId) {
        return Collections.unmodifiableList(
                downstreamMap.getOrDefault(nodeId, Collections.emptyList())
        );
    }

    /**
     * 获取指定节点的所有直接上游节点 ID — O(1) 查找。
     *
     * @param nodeId 节点 ID
     * @return 上游节点 ID 列表（不可变），不存在则返回空列表
     */
    public List<String> getUpstreamNodes(String nodeId) {
        return Collections.unmodifiableList(
                upstreamMap.getOrDefault(nodeId, Collections.emptyList())
        );
    }

    /**
     * 获取指定节点的所有下游节点（递归遍历整个子树）— BFS 遍历。
     * <p>
     * 用于 RecoveryOrchestrator 挂起崩溃节点的整个下游链路。
     *
     * @param nodeId 起始节点 ID
     * @return 所有递归下游节点 ID 集合（不含起始节点本身）
     */
    public Set<String> getAllDownstreamNodes(String nodeId) {
        Set<String> result = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(nodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<String> directDownstream = downstreamMap.get(current);
            if (directDownstream != null) {
                for (String dsId : directDownstream) {
                    if (result.add(dsId)) { // 仅在未访问过时入队
                        queue.add(dsId);
                    }
                }
            }
        }

        result.remove(nodeId); // 排除起始节点本身
        return result;
    }

    /**
     * 获取指定节点的所有上游节点（递归遍历整个祖先链）— BFS 遍历。
     * <p>
     * 用于部分执行时确定需要重新执行的祖先节点。
     *
     * @param nodeId 起始节点 ID
     * @return 所有递归上游节点 ID 集合（不含起始节点本身）
     */
    public Set<String> getAllUpstreamNodes(String nodeId) {
        Set<String> result = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(nodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<String> directUpstream = upstreamMap.get(current);
            if (directUpstream != null) {
                for (String usId : directUpstream) {
                    if (result.add(usId)) {
                        queue.add(usId);
                    }
                }
            }
        }

        result.remove(nodeId);
        return result;
    }

    /**
     * 获取所有 Sink 节点（无下游依赖的节点）— O(1) 缓存查找。
     */
    public Set<String> getSinkNodeIds() {
        return sinkNodeIds;
    }

    /**
     * 获取所有 Source 节点（无上游依赖的节点）— O(1) 缓存查找。
     */
    public Set<String> getSourceNodeIds() {
        return sourceNodeIds;
    }

    /**
     * 动态添加边 — 用于 TopologyMutationStrategy 插入新节点。
     *
     * @param upstreamId   上游节点 ID
     * @param downstreamId 下游节点 ID
     */
    public void addEdge(String upstreamId, String downstreamId) {
        downstreamMap.computeIfAbsent(upstreamId, k -> new ArrayList<>())
                .add(downstreamId);
        upstreamMap.computeIfAbsent(downstreamId, k -> new ArrayList<>())
                .add(upstreamId);
        recomputeCachedSets();
    }

    /**
     * 动态移除边 — 用于拓扑突变时断开旧依赖。
     *
     * @param upstreamId   上游节点 ID
     * @param downstreamId 下游节点 ID
     */
    public void removeEdge(String upstreamId, String downstreamId) {
        List<String> downstream = downstreamMap.get(upstreamId);
        if (downstream != null) {
            downstream.remove(downstreamId);
        }
        List<String> upstream = upstreamMap.get(downstreamId);
        if (upstream != null) {
            upstream.remove(upstreamId);
        }
        recomputeCachedSets();
    }

    /**
     * 添加新节点到索引 — 用于动态插入节点。
     *
     * @param nodeId       新节点 ID
     * @param upstreamDeps 上游依赖列表
     */
    public void addNode(String nodeId, List<String> upstreamDeps) {
        downstreamMap.putIfAbsent(nodeId, new ArrayList<>());
        upstreamMap.put(nodeId, new ArrayList<>(upstreamDeps != null ? upstreamDeps : Collections.emptyList()));

        if (upstreamDeps != null) {
            for (String upstreamId : upstreamDeps) {
                downstreamMap.computeIfAbsent(upstreamId, k -> new ArrayList<>())
                        .add(nodeId);
            }
        }

        recomputeCachedSets();
    }

    /**
     * 移除节点及其所有边 — 用于节点删除。
     *
     * @param nodeId 要移除的节点 ID
     */
    public void removeNode(String nodeId) {
        // 从所有上游节点的下游列表中移除
        List<String> upstreams = upstreamMap.remove(nodeId);
        if (upstreams != null) {
            for (String usId : upstreams) {
                List<String> ds = downstreamMap.get(usId);
                if (ds != null) ds.remove(nodeId);
            }
        }

        // 从所有下游节点的上游列表中移除
        List<String> downstreams = downstreamMap.remove(nodeId);
        if (downstreams != null) {
            for (String dsId : downstreams) {
                List<String> us = upstreamMap.get(dsId);
                if (us != null) us.remove(nodeId);
            }
        }

        recomputeCachedSets();
    }

    /**
     * 查找从 startNode 到 endNode 的路径 — BFS 最短路径。
     * <p>
     * 用于判断两个节点是否存在依赖关系。
     *
     * @param startNode 起始节点
     * @param endNode   目标节点
     * @return 路径列表（含起止），不存在则返回空列表
     */
    public List<String> findPath(String startNode, String endNode) {
        Map<String, String> parent = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(startNode);
        parent.put(startNode, null);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(endNode)) {
                // 回溯路径
                List<String> path = new LinkedList<>();
                String node = endNode;
                while (node != null) {
                    path.add(0, node);
                    node = parent.get(node);
                }
                return path;
            }

            List<String> downstreams = downstreamMap.get(current);
            if (downstreams != null) {
                for (String dsId : downstreams) {
                    if (!parent.containsKey(dsId)) {
                        parent.put(dsId, current);
                        queue.add(dsId);
                    }
                }
            }
        }

        return Collections.emptyList();
    }

    /**
     * 获取子图 — 从指定节点开始的所有下游节点构成的子图。
     * <p>
     * 用于部分执行 (Partial Execution) 时确定需要执行的节点集合。
     *
     * @param startNode 起始节点
     * @return 子图中的所有节点 ID（含起始节点）
     */
    public Set<String> getSubgraph(String startNode) {
        Set<String> result = new LinkedHashSet<>();
        result.add(startNode);
        result.addAll(getAllDownstreamNodes(startNode));
        return result;
    }

    /**
     * 获取索引统计信息。
     */
    public String getStats() {
        return String.format(
                "DownstreamDependencyIndex{nodes=%d, edges=%d, sinks=%d, sources=%d}",
                downstreamMap.size(),
                downstreamMap.values().stream().mapToInt(List::size).sum(),
                sinkNodeIds.size(),
                sourceNodeIds.size()
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  内部方法
    // ════════════════════════════════════════════════════════════════

    private void recomputeCachedSets() {
        // Sink 节点 = 没有下游的节点
        Set<String> sinks = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : downstreamMap.entrySet()) {
            if (entry.getValue().isEmpty()) {
                sinks.add(entry.getKey());
            }
        }
        this.sinkNodeIds = Collections.unmodifiableSet(sinks);

        // Source 节点 = 没有上游的节点
        Set<String> sources = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : upstreamMap.entrySet()) {
            if (entry.getValue().isEmpty()) {
                sources.add(entry.getKey());
            }
        }
        this.sourceNodeIds = Collections.unmodifiableSet(sources);
    }
}
