package com.ouisani.aios.core.memory.providers;

/**
 * 记忆条目一等数据模型 — 把记忆从"裸字符串"升级为带元数据的结构化记录。
 * <p>
 * 借鉴 Step AOS 的"记忆元数据一等化"理念：source / timestamp / confidence /
 * domain / version 这五个字段不再是各 Provider 自己维护的旁路信息，
 * 而是由 {@link MemoryProvider} 接口直接承袭的"一等字段"。
 * <p>
 * <b>字段语义</b>：
 * <ul>
 *   <li>{@code key}：逻辑主键，用于同 key 冲突时的版本递增与 history 保留。
 *       可为 {@code null}（向后兼容旧 {@code store(String)} 调用方，此时由
 *       Provider 自行分配或退化为追加写入）。</li>
 *   <li>{@code content}：记忆正文，与旧 {@code store(String)} 的入参等价。</li>
 *   <li>{@code source}：来源标识，如 {@code "user-input"} / {@code "agent-inference"}
 *       / {@code "legacy"}。供"记忆查看器"按来源过滤。</li>
 *   <li>{@code timestamp}：写入时间戳（毫秒，{@link System#currentTimeMillis()}）。</li>
 *   <li>{@code confidence}：置信度 0.0-1.0。USER 域通常 1.0，AGENT 域由推理质量决定。</li>
 *   <li>{@code domain}：{@link MemoryDomain#USER} 或 {@link MemoryDomain#AGENT}。</li>
 *   <li>{@code version}：单调递增版本号，从 1 开始。同 key 新写入时由
 *       {@code VersionedMemoryStore} 自动 +1，旧版本保留为 history。</li>
 * </ul>
 * <p>
 * <b>不可变性</b>：record 自身不可变，版本递增通过 {@link #withVersion} 产生新实例。
 *
 * @param key        逻辑主键（可 {@code null}）
 * @param content    记忆正文
 * @param source     来源标识
 * @param timestamp  写入时间戳（毫秒）
 * @param confidence 置信度 [0.0, 1.0]
 * @param domain     记忆域
 * @param version    版本号（从 1 开始）
 */
public record MemoryRecord(
        String key,
        String content,
        String source,
        long timestamp,
        double confidence,
        MemoryDomain domain,
        long version) {

    /**
     * 创建一条 {@link MemoryDomain#AGENT} 域的旧式记忆 — 供 {@code store(String)} 默认实现使用。
     * <p>
     * 不带 key（{@code null}）、source={@code "legacy"}、confidence=1.0、version=1。
     *
     * @param content 记忆正文
     * @return 默认元数据的 AGENT 域记忆记录
     */
    public static MemoryRecord legacy(String content) {
        return new MemoryRecord(
                null,
                content,
                "legacy",
                System.currentTimeMillis(),
                1.0,
                MemoryDomain.AGENT,
                1L);
    }

    /**
     * 版本递增 wither — 产生一个仅 version 字段变更的新实例，其余字段原样保留。
     * <p>
     * 由 {@code VersionedMemoryStore} 在同 key 冲突时调用。
     *
     * @param newVersion 新版本号
     * @return 版本号更新后的新记录
     */
    public MemoryRecord withVersion(long newVersion) {
        return new MemoryRecord(
                key, content, source, timestamp, confidence, domain, newVersion);
    }

    /**
     * 时间戳更新 wither — 用于版本递增时刷新写入时间。
     *
     * @param newTimestamp 新时间戳（毫秒）
     * @return 时间戳更新后的新记录
     */
    public MemoryRecord withTimestamp(long newTimestamp) {
        return new MemoryRecord(
                key, content, source, newTimestamp, confidence, domain, version);
    }
}
