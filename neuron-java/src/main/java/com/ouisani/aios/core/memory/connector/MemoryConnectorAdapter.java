package com.ouisani.aios.core.memory.connector;

/**
 * 记忆连接器适配器 — 将异构存储协议（URL schema）统一为 {@link MemoryConnector}。
 * <p>
 * 参考 LMCache 的 {@code ConnectorAdapter}。
 * <p>
 * 子类只需实现 {@link #createConnector(MemoryConnectorContext)} 即可被
 * {@link MemoryConnectorManager} 自动发现并按 URL schema 路由。
 * <p>
 * 典型子类示例：
 * <ul>
 *   <li>{@code RedisConnectorAdapter}（schema = {@code "redis://"})</li>
 *   <li>{@code Mem0ConnectorAdapter}（schema = {@code "mem0://"})</li>
 *   <li>{@code FsConnectorAdapter}（schema = {@code "fs://"})</li>
 * </ul>
 */
public abstract class MemoryConnectorAdapter {

    /** 此适配器匹配的 URL schema。 */
    private final String schema;

    /**
     * 构造适配器。
     *
     * @param schema 此适配器匹配的 URL schema（如 {@code "redis://"}）
     */
    protected MemoryConnectorAdapter(String schema) {
        this.schema = schema;
    }

    /**
     * 返回此适配器匹配的 URL schema。
     *
     * @return schema 字符串
     */
    public String schema() {
        return schema;
    }

    /**
     * 判断此适配器能否解析给定 URL。
     * <p>
     * 当 schema 非空且 URL 以 schema 开头时返回 {@code true}。
     *
     * @param url 待解析的 URL
     * @return 能解析返回 {@code true}，否则 {@code false}
     */
    public boolean canParse(String url) {
        return !schema.isEmpty() && url.startsWith(schema);
    }

    /**
     * 根据上下文创建连接器。
     *
     * @param context 创建上下文（含 URL、Agent 标识、配置）
     * @return 已初始化的 {@link MemoryConnector}
     */
    public abstract MemoryConnector createConnector(MemoryConnectorContext context);
}
