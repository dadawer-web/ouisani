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
 * <p>
 * <b>记忆元数据一等化</b>：{@link #store(String, MemoryRecord)} 是新的抽象方法，
 * 承载 source / timestamp / confidence / domain / version 一等字段。
 * 旧的 {@link #store(String, String)} 改为 default 方法，包装成
 * {@link MemoryRecord#legacy} 后委托给新方法，默认 {@link MemoryDomain#AGENT} 域。
 *
 * @see com.ouisani.aios.core.memory.MemoryManager
 */
public interface MemoryProvider {

    /**
     * 存储一条带元数据的记忆 — Provider 必须实现此方法。
     * <p>
     * 实现方应读取 {@link MemoryRecord#content()} 作为正文，
     * 其余字段（source / timestamp / confidence / domain / version）
     * 按后端能力持久化或忽略（不支持元数据持久化的后端可仅用 content）。
     *
     * @param agentId Agent 标识
     * @param record  记忆条目（含元数据）
     * @return 存储成功返回 {@code true}
     */
    boolean store(String agentId, MemoryRecord record);

    /**
     * 存储一条记忆（旧式入口，向后兼容）。
     * <p>
     * 默认实现把裸字符串包装成 {@link MemoryRecord#legacy}（domain=AGENT,
     * source={@code "legacy"}, confidence=1.0, version=1）后委托给
     * {@link #store(String, MemoryRecord)}。
     *
     * @param agentId       Agent 标识
     * @param memoryContent 要存储的内容
     * @return 存储成功返回 {@code true}
     */
    default boolean store(String agentId, String memoryContent) {
        return store(agentId, MemoryRecord.legacy(memoryContent));
    }

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
