package com.ouisani.aios.core.memory.connector;

import java.util.Map;

/**
 * 创建连接器的上下文 — 封装创建参数，解耦适配器与具体配置。
 * <p>
 * 参考 LMCache 的 {@code ConnectorContext}。适配器通过此 record 获取
 * URL、Agent 标识和额外配置映射，无需感知具体的配置来源。
 *
 * @param url     连接 URL（含 schema，如 {@code redis://localhost:6379}）
 * @param agentId Agent 标识
 * @param config  额外配置映射，可为 {@code null}（规范化为空映射）
 */
public record MemoryConnectorContext(
        String url,
        String agentId,
        Map<String, Object> config
) {

    /**
     * 紧凑构造器 — 将 {@code null} 配置规范化为空映射，避免下游空指针。
     */
    public MemoryConnectorContext {
        if (config == null) {
            config = Map.of();
        }
    }

    /**
     * 返回连接 URL。
     *
     * @return URL 字符串
     */
    @Override
    public String url() {
        return url;
    }
    // agentId() 与 config() 的访问器由 record 自动生成
}
