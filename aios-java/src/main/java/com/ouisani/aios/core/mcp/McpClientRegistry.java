package com.ouisani.aios.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端注册中心 — 对标 Claude Code 的 MCP client 管理。
 * <p>
 * 管理所有 MCP 服务器连接的生命周期：
 * - 连接/断开
 * - 工具发现
 * - 认证状态
 * <p>
 * OS 类比：相当于 Linux 的设备驱动注册中心 — 每个驱动（MCP服务器）
 * 注册自己的能力（工具），内核通过统一接口调用。
 */
public class McpClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistry.class);
    private static final McpClientRegistry INSTANCE = new McpClientRegistry();

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    /** MCP 连接状态 */
    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, AUTH_REQUIRED, FAILED
    }

    /** MCP 连接 */
    public static class McpConnection {
        private final String serverName;
        private final McpConfigManager.McpServerConfig config;
        private volatile ConnectionState state = ConnectionState.DISCONNECTED;
        private final List<McpToolDef> tools = new ArrayList<>();
        private String authToken;

        public McpConnection(String serverName, McpConfigManager.McpServerConfig config) {
            this.serverName = serverName;
            this.config = config;
        }

        public String serverName() { return serverName; }
        public McpConfigManager.McpServerConfig config() { return config; }
        public ConnectionState state() { return state; }
        public void setState(ConnectionState s) { this.state = s; }
        public List<McpToolDef> tools() { return Collections.unmodifiableList(tools); }
        public void addTool(McpToolDef tool) { tools.add(tool); }
        public String authToken() { return authToken; }
        public void setAuthToken(String token) { this.authToken = token; }
    }

    /** MCP 工具定义 */
    public record McpToolDef(
            String name,
            String description,
            String inputSchema,
            String serverName
    ) {}

    private McpClientRegistry() {}

    public static McpClientRegistry instance() { return INSTANCE; }

    /**
     * 注册 MCP 服务器连接。
     */
    public McpConnection register(String serverName, McpConfigManager.McpServerConfig config) {
        McpConnection conn = new McpConnection(serverName, config);
        connections.put(serverName, conn);
        log.info("[McpClientRegistry] Registered: {} (type: {})", serverName, config.type());
        return conn;
    }

    /**
     * 获取连接。
     */
    public Optional<McpConnection> getConnection(String serverName) {
        return Optional.ofNullable(connections.get(serverName));
    }

    /**
     * 获取所有连接。
     */
    public Collection<McpConnection> allConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    /**
     * 获取所有已连接服务器的工具列表。
     */
    public List<McpToolDef> allTools() {
        List<McpToolDef> all = new ArrayList<>();
        for (McpConnection conn : connections.values()) {
            if (conn.state() == ConnectionState.CONNECTED) {
                all.addAll(conn.tools());
            }
        }
        return all;
    }

    /**
     * 断开所有连接。
     */
    public void disconnectAll() {
        connections.values().forEach(c -> c.setState(ConnectionState.DISCONNECTED));
        connections.clear();
        log.info("[McpClientRegistry] All connections closed");
    }

    // ── 兼容旧 API ──

    /** 兼容旧 McpClientRegistry.getInstance() */
    public static McpClientRegistry getInstance() { return INSTANCE; }

    /** 兼容旧 hasServer() */
    public boolean hasServer(String serverName) {
        return connections.containsKey(serverName);
    }

    /** 兼容旧 serverNames() */
    public Set<String> serverNames() {
        return Collections.unmodifiableSet(connections.keySet());
    }

    /** 兼容旧 callTool() — 简化实现 */
    public Object callTool(String serverName, String toolName, Map<String, Object> args) {
        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            return Map.of("error", "MCP server '" + serverName + "' not connected");
        }
        // 简化实现：返回工具调用请求
        return Map.of("server", serverName, "tool", toolName, "args", args, "status", "dispatched");
    }

    /** 兼容旧 listTools() */
    public Object listTools(String serverName) {
        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            return List.of();
        }
        return conn.tools();
    }
}
