package com.ouisani.aios.core.memory.providers;

/**
 * 记忆后端统一接口 — AIOS 内核所有记忆 Provider 的顶层抽象。
 * <p>
 * 类比 Linux 的 {@code address_space_operations}：不同的文件系统
 * （ext4, xfs, tmpfs）通过统一的 {@code address_space_operations}
 * 向 VFS 提供页面操作接口，VFS 层无需关心底层实现。
 * <p>
 * 每个记忆后端（TokenZRAM、Mem0、Zep 等）必须实现此接口，
 * 使 {@link com.ouisani.aios.core.memory.MemoryManager} 能够
 * 在运行时根据 VFS 注册表配置热切换后端。
 *
 * @see com.ouisani.aios.core.memory.MemoryManager
 */
public interface MemoryProvider {

    /**
     * 存储一条记忆。
     *
     * @param agentId       Agent 标识
     * @param memoryContent 要存储的内容
     * @return 存储成功返回 {@code true}
     */
    boolean store(String agentId, String memoryContent);

    /**
     * 检索与查询相关的记忆。
     *
     * @param agentId Agent 标识
     * @param query   语义查询
     * @return 检索到的记忆内容（可能为空）
     */
    String retrieve(String agentId, String query);

    /**
     * 清除指定 Agent 的所有记忆。
     *
     * @param agentId Agent 标识
     */
    void clear(String agentId);

    /**
     * 返回后端名称，用于日志和注册表标识。
     */
    String providerName();
}
