package com.ouisani.aios.core.context;

/**
 * 消息节点 — 对话消息树的节点，支持分支管理。
 * <p>
 * 借鉴 Apix 的 {@code AgentNodeHelper} 消息树模型，每条消息携带
 * {@code nodeId} 和 {@code parentId} 形成树形结构，支持：
 * <ul>
 *   <li><b>软删除</b>：标记 {@code visible=false} 而非物理删除，保留历史可恢复性</li>
 *   <li><b>重链接</b>：被删节点的子节点自动重新链接到最近的可见祖先</li>
 *   <li><b>分支管理</b>：同一 parent 可有多个可见子节点，形成分支</li>
 * </ul>
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 inode 硬链接 — 删除一个链接不删除数据，
 * 数据在所有链接断开后才真正回收。
 *
 * @param nodeId    节点唯一 ID
 * @param parentId  父节点 ID（根节点为 null 或 "-"）
 * @param role      消息角色（user/assistant/tool/system）
 * @param content   消息内容
 * @param cursor    消息序号（用于排序，时间戳或递增计数）
 * @param visible    是否可见（false 表示已软删除）
 * @see MessageNodeTree
 */
public record MessageNode(
        String nodeId,
        String parentId,
        String role,
        String content,
        long cursor,
        boolean visible
) {
    /** 根节点 ID 常量 */
    public static final String ROOT_ID = "-";

    /**
     * 创建可见的消息节点。
     *
     * @param nodeId   节点 ID
     * @param parentId 父节点 ID
     * @param role     角色
     * @param content  内容
     * @param cursor   序号
     */
    public MessageNode(String nodeId, String parentId, String role, String content, long cursor) {
        this(nodeId, parentId, role, content, cursor, true);
    }

    /**
     * 创建根节点。
     *
     * @param cursor 根节点的序号（通常为 -1）
     */
    public static MessageNode root(long cursor) {
        return new MessageNode(ROOT_ID, null, "system", "", cursor, true);
    }

    /**
     * 创建软删除副本 — 保持其他字段不变，仅标记 visible=false。
     *
     * @return 软删除后的节点副本
     */
    public MessageNode softDelete() {
        return new MessageNode(nodeId, parentId, role, content, cursor, false);
    }

    /**
     * 创建重链接副本 — 更新 parentId，保持其他字段不变。
     *
     * @param newParentId 新的父节点 ID
     * @return 重链接后的节点副本
     */
    public MessageNode relink(String newParentId) {
        return new MessageNode(nodeId, newParentId, role, content, cursor, visible);
    }
}
