package com.ouisani.aios.core.mcp;

/**
 * MCP 会话生命周期策略 — 控制 MCP 客户端连接的保活行为。
 * <p>
 * 借鉴 Apix 的 {@code mcp_tool.py} 中的 {@code lifecycle} 配置，
 * 不同 MCP server 可按特性差异化配置保活策略，平衡性能与资源。
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code keepalive} 配置 —
 * 短连接（{@code ALWAYS_CLOSE}）类比 HTTP/1.0，长连接（{@code KEEP_ALIVE}）类比 HTTP/1.1。
 *
 * @see McpSessionRegistry
 * @see McpClient
 */
public enum SessionLifecycle {
    /**
     * 长连接保活 — 跨多次 Agent 调用复用连接。
     * <p>
     * 适用于：启动慢但调用频繁的 MCP server（如需要加载大型模型的 server）。
     * 连接建立后常驻，直到显式 {@code disconnect} 或系统关闭。
     * <p>
     * 对应 Apix 的 {@code keep_alive}。
     */
    KEEP_ALIVE,

    /**
     * 单次循环保活 — 在单次 Agent 循环内复用，循环结束后关闭。
     * <p>
     * 适用于：中等调用频率的 MCP server，在单次任务内多次调用但任务间不需要保持连接。
     * <p>
     * 对应 Apix 的 {@code agent_loop}。
     */
    AGENT_LOOP,

    /**
     * 每次调用后关闭 — 用完即关，不保活。
     * <p>
     * 适用于：启动快但占用资源多的 MCP server，或低频调用的 server。
     * 每次调用都重新建立连接，确保资源及时释放。
     * <p>
     * 对应 Apix 的 {@code always_close}。
     */
    ALWAYS_CLOSE
}
