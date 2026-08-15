package com.ouisani.aios.core.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 消息节点树 — 对话消息的树形结构管理器，支持分支和软删除重链接。
 * <p>
 * 借鉴 Apix 的 {@code AgentNodeHelper}（位于 {@code message_node_helper.py}），
 * 适配 Java 数据结构。核心能力：
 * <ul>
 *   <li><b>树构建</b>：从消息列表构建节点树，自动插入虚拟根节点</li>
 *   <li><b>软删除 + 重链接</b>：删除节点时不破坏树结构，而是将子节点重新链接到
 *       最近的可见祖先，自动生成新分支</li>
 *   <li><b>分支构建</b>：从指定节点向上追溯到根，再向下扩展到最新子节点，
 *       形成"当前分支"的完整路径</li>
 *   <li><b>分支扁平化</b>：将分支路径中的节点按 cursor 排序展开为消息列表</li>
 * </ul>
 * <p>
 * <b>工作流程</b>：
 * <ol>
 *   <li>用户编辑/删除某条消息 → 调用 {@link #softDelete}</li>
 *   <li>重建树 → {@link #rebuild} 触发 {@link #relinkDeletedNodes}</li>
 *   <li>被删节点的子节点重新链接到最近的可见祖先</li>
 *   <li>用户从该节点重新生成 → 新消息的 parentId 指向重链接后的父节点</li>
 *   <li>调用 {@link #buildBranch} 获取当前分支路径</li>
 * </ol>
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 VFS dentry 树 + 软链接重定向。
 *
 * @see MessageNode
 */
public class MessageNodeTree {

    private static final Logger log = LoggerFactory.getLogger(MessageNodeTree.class);

    /** 所有节点列表（含根节点） */
    private final List<MessageNode> nodes = new ArrayList<>();

    /** nodeId → 节点 映射（快速查找） */
    private final Map<String, MessageNode> nodeMap = new LinkedHashMap<>();

    /** parentId → 子节点列表 映射（重链接后的最终结构） */
    private final Map<String, List<MessageNode>> childrenMap = new HashMap<>();

    /**
     * 从消息列表构建节点树。
     *
     * @param messages 消息节点列表（不含根节点，根节点会自动插入）
     */
    public MessageNodeTree(List<MessageNode> messages) {
        rebuild(messages);
    }

    /**
     * 重建树 — 重新构建节点映射和父子关系，并执行软删除重链接。
     *
     * @param messages 消息节点列表
     */
    public final void rebuild(List<MessageNode> messages) {
        nodes.clear();
        nodeMap.clear();
        childrenMap.clear();

        // 插入虚拟根节点
        MessageNode root = MessageNode.root(-1);
        nodes.add(root);
        nodeMap.put(root.nodeId(), root);

        // 添加所有消息节点
        for (MessageNode msg : messages) {
            nodes.add(msg);
            // 若 nodeId 已存在，保留 cursor 较大的（最新版本）
            MessageNode existing = nodeMap.get(msg.nodeId());
            if (existing == null || msg.cursor() > existing.cursor()) {
                nodeMap.put(msg.nodeId(), msg);
            }
        }

        // 构建初始 childrenMap
        Map<String, List<MessageNode>> rawChildren = new HashMap<>();
        for (MessageNode node : nodes) {
            if (node.parentId() == null) continue;
            rawChildren.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node);
        }
        // 按 cursor 排序
        for (List<MessageNode> children : rawChildren.values()) {
            children.sort(Comparator.comparingLong(MessageNode::cursor));
        }

        // 执行软删除重链接
        this.childrenMap.putAll(relinkDeletedNodes(rawChildren));

        log.debug("[MessageNodeTree] 树已重建: {} 个节点, {} 个父节点",
                nodes.size(), childrenMap.size());
    }

    /**
     * 软删除重链接 — 被删节点的子节点重新链接到最近的可见祖先。
     * <p>
     * 这是"编辑/删除后自动生成新分支"的关键逻辑：
     * <ol>
     *   <li>遍历所有节点，跳过已删除（不可见）的节点</li>
     *   <li>对可见节点，若其 parent 不可见，则向上查找最近的可见祖先作为新 parent</li>
     *   <li>重建 childrenMap，确保树结构中只包含可见节点</li>
     * </ol>
     *
     * @param rawChildren 原始的父子关系映射
     * @return 重链接后的父子关系映射
     */
    private Map<String, List<MessageNode>> relinkDeletedNodes(
            Map<String, List<MessageNode>> rawChildren) {

        Map<String, List<MessageNode>> newChildren = new HashMap<>();

        for (MessageNode node : nodes) {
            if (MessageNode.ROOT_ID.equals(node.nodeId())) continue;
            if (!node.visible()) continue;

            MessageNode parent = nodeMap.get(node.parentId());
            String newParentId;

            if (parent != null && parent.visible()) {
                // 父节点可见，保持原链接
                newParentId = parent.nodeId();
            } else {
                // 父节点不可见，向上查找最近的可见祖先
                MessageNode ancestor = findNearestVisibleAncestor(node.parentId());
                newParentId = ancestor != null ? ancestor.nodeId() : MessageNode.ROOT_ID;
            }

            // 创建重链接后的节点副本
            MessageNode relinked = node.relink(newParentId);
            newChildren.computeIfAbsent(newParentId, k -> new ArrayList<>()).add(relinked);
            // 更新 nodeMap 中的节点为重链接后的版本
            nodeMap.put(node.nodeId(), relinked);
        }

        // 按 cursor 排序
        for (List<MessageNode> children : newChildren.values()) {
            children.sort(Comparator.comparingLong(MessageNode::cursor));
        }

        return newChildren;
    }

    /**
     * 查找最近的可见祖先 — 从指定节点向上遍历，返回第一个可见的祖先。
     *
     * @param nodeId 起始节点 ID
     * @return 最近的可见祖先，不存在则返回 null
     */
    private MessageNode findNearestVisibleAncestor(String nodeId) {
        MessageNode cur = nodeMap.get(nodeId);
        while (cur != null) {
            if (cur.visible()) return cur;
            cur = nodeMap.get(cur.parentId());
        }
        return null;
    }

    /**
     * 获取节点的所有子节点。
     *
     * @param nodeId 节点 ID
     * @return 子节点列表（可能为空）
     */
    public List<MessageNode> getChildren(String nodeId) {
        return childrenMap.getOrDefault(nodeId, Collections.emptyList());
    }

    /**
     * 获取从根到指定节点的路径。
     * <p>
     * 从目标节点向上追溯到根，返回路径（根在前，目标在后）。
     *
     * @param nodeId 目标节点 ID
     * @return 路径节点列表（根 → ... → 目标），节点不存在返回空列表
     */
    public List<MessageNode> getPath(String nodeId) {
        List<MessageNode> path = new ArrayList<>();
        MessageNode cur = nodeMap.get(nodeId);

        while (cur != null) {
            path.add(cur);
            cur = nodeMap.get(cur.parentId());
        }

        Collections.reverse(path);
        return path;
    }

    /**
     * 扩展路径 — 从路径末端向下扩展，每层选择 cursor 最大的可见子节点。
     * <p>
     * 这模拟"当前分支"的语义：自动延伸到最新的对话消息。
     *
     * @param path 已有路径（根 → ... → 当前末端）
     * @return 扩展后的完整路径
     */
    public List<MessageNode> extendPath(List<MessageNode> path) {
        if (path == null || path.isEmpty()) return path;

        List<MessageNode> result = new ArrayList<>(path);
        MessageNode cur = path.get(path.size() - 1);

        while (true) {
            List<MessageNode> children = getChildren(cur.nodeId());
            if (children.isEmpty()) break;

            // 优先选择可见子节点中 cursor 最大的
            List<MessageNode> visible = children.stream()
                    .filter(MessageNode::visible)
                    .collect(Collectors.toList());

            MessageNode next;
            if (!visible.isEmpty()) {
                next = visible.stream()
                        .max(Comparator.comparingLong(MessageNode::cursor))
                        .orElse(null);
            } else {
                next = children.stream()
                        .max(Comparator.comparingLong(MessageNode::cursor))
                        .orElse(null);
            }

            if (next == null) break;
            result.add(next);
            cur = next;
        }

        return result;
    }

    /**
     * 构建分支 — 从指定节点构建完整分支路径。
     * <p>
     * 先获取从根到该节点的路径，再向下扩展到最新子节点。
     *
     * @param currentNodeId 当前节点 ID
     * @return 完整分支路径（根 → ... → 当前节点 → ... → 最新子节点）
     */
    public List<MessageNode> buildBranch(String currentNodeId) {
        List<MessageNode> path = getPath(currentNodeId);
        return extendPath(path);
    }

    /**
     * 扁平化分支 — 将分支路径中的节点按 cursor 排序展开为消息列表。
     *
     * @param branch 分支路径
     * @return 排序后的消息节点列表
     */
    public List<MessageNode> flattenBranch(List<MessageNode> branch) {
        if (branch == null || branch.isEmpty()) return Collections.emptyList();
        List<MessageNode> result = new ArrayList<>(branch);
        result.removeIf(n -> MessageNode.ROOT_ID.equals(n.nodeId())); // 排除根节点
        result.sort(Comparator.comparingLong(MessageNode::cursor));
        return result;
    }

    /**
     * 软删除节点 — 标记节点为不可见，并重建树触发重链接。
     *
     * @param nodeId 要删除的节点 ID
     */
    public void softDelete(String nodeId) {
        MessageNode node = nodeMap.get(nodeId);
        if (node == null) {
            log.warn("[MessageNodeTree] 软删除失败: 节点不存在 '{}'", nodeId);
            return;
        }

        // 创建软删除副本
        MessageNode deleted = node.softDelete();
        int idx = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).nodeId().equals(nodeId)) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            nodes.set(idx, deleted);
        }

        // 重建树以触发重链接
        List<MessageNode> messages = new ArrayList<>(nodes);
        messages.removeIf(n -> MessageNode.ROOT_ID.equals(n.nodeId()));
        rebuild(messages);

        log.info("[MessageNodeTree] 节点已软删除: {} (子节点已重链接到可见祖先)", nodeId);
    }

    /**
     * 查找分支点 — 返回有多个可见子节点的父节点 ID列表。
     * <p>
     * 前端可据此渲染分支切换 UI。
     *
     * @return 分支点 parentId 集合
     */
    public Set<String> findBranchPoints() {
        return childrenMap.entrySet().stream()
                .filter(e -> e.getValue().stream().filter(MessageNode::visible).count() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * 获取节点。
     *
     * @param nodeId 节点 ID
     * @return 节点，不存在返回 null
     */
    public MessageNode getNode(String nodeId) {
        return nodeMap.get(nodeId);
    }

    /**
     * 获取所有可见节点。
     *
     * @return 可见节点列表（按 cursor 排序）
     */
    public List<MessageNode> getVisibleNodes() {
        return nodes.stream()
                .filter(MessageNode::visible)
                .filter(n -> !MessageNode.ROOT_ID.equals(n.nodeId()))
                .sorted(Comparator.comparingLong(MessageNode::cursor))
                .toList();
    }

    /**
     * 获取当前分支的扁平化消息列表。
     * <p>
     * 便捷方法：从最新可见节点构建分支并扁平化。
     *
     * @return 当前分支的消息列表
     */
    public List<MessageNode> getCurrentBranchMessages() {
        // 找到 cursor 最大的可见节点作为当前节点
        MessageNode latest = nodes.stream()
                .filter(MessageNode::visible)
                .filter(n -> !MessageNode.ROOT_ID.equals(n.nodeId()))
                .max(Comparator.comparingLong(MessageNode::cursor))
                .orElse(null);

        if (latest == null) return Collections.emptyList();

        List<MessageNode> branch = buildBranch(latest.nodeId());
        return flattenBranch(branch);
    }
}
