package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端全局注册表 (The Device Manager)
 * <p>
 * OS 类比：相当于 Linux 的设备驱动注册中心 — 每个驱动（MCP 服务器）
 * 注册自己的能力（工具），内核通过统一接口调用。
 * <p>
 * 新架构：底层使用 {@link McpClient} 管理真实的 Stdio 连接，
 * 上层保留兼容 API 供 McpTool / SyscallDispatcher / PluginManager 调用。
 */
public class McpClientRegistry {
    private static final Logger log = LoggerFactory.getLogger(McpClientRegistry.class);
    private static final McpClientRegistry INSTANCE = new McpClientRegistry();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 新架构：真实的 MCP 客户端连接 */
    private final ConcurrentHashMap<String, McpClient> clients = new ConcurrentHashMap<>();

    /** 旧架构兼容：连接元数据 */
    private final ConcurrentHashMap<String, McpConnection> connections = new ConcurrentHashMap<>();

    /** MCP 连接状态 */
    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, AUTH_REQUIRED, FAILED
    }

    /** MCP 连接（兼容旧 API） */
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

    private McpClientRegistry() {
        autoDiscoverAndMount();
    }

    public static McpClientRegistry getInstance() { return INSTANCE; }
    public static McpClientRegistry instance() { return INSTANCE; }

    // ── 自动发现与引导挂载 ──

    /**
     * 自动发现并挂载 MCP 服务器（引导加载）。
     * <p>
     * 检查顺序：
     * <ol>
     *   <li>环境变量 {@code MCP_SERVERS}（JSON 内容字符串）</li>
     *   <li>{@code ~/.aios/mcp-servers.json} 配置文件</li>
     * </ol>
     * 配置格式：
     * <pre>
     * {
     *   "servers": [
     *     {"name": "weather", "transport": "stdio", "command": ["node", "server.js"], "args": ["--port", "8080"]},
     *     {"name": "exa", "transport": "http", "url": "https://api.exa.com/mcp", "headers": {"Authorization": "Bearer xxx"}}
     *   ]
     * }
     * </pre>
     */
    public void autoDiscoverAndMount() {
        // 1. 环境变量 MCP_SERVERS（JSON 内容）
        String envConfig = System.getenv("MCP_SERVERS");
        if (envConfig != null && !envConfig.isBlank()) {
            log.info("[MCP Registry] 从环境变量 MCP_SERVERS 加载配置");
            parseAndMount(envConfig);
            return;
        }

        // 2. ~/.aios/mcp-servers.json 配置文件
        String home = System.getProperty("user.home");
        java.nio.file.Path configPath = java.nio.file.Paths.get(home, ".aios", "mcp-servers.json");
        if (java.nio.file.Files.exists(configPath)) {
            try {
                String content = java.nio.file.Files.readString(configPath);
                log.info("[MCP Registry] 从配置文件加载: {}", configPath);
                parseAndMount(content);
            } catch (Exception e) {
                log.warn("[MCP Registry] 读取配置文件失败 {}: {}", configPath, e.getMessage());
            }
        }
    }

    /**
     * 解析 MCP 配置 JSON 并挂载每个服务器。
     */
    private void parseAndMount(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode servers = root.path("servers");
            if (!servers.isArray()) {
                log.warn("[MCP Registry] 配置中缺少 'servers' 数组，跳过自动挂载");
                return;
            }
            for (JsonNode server : servers) {
                String name = server.path("name").asText("");
                String transport = server.path("transport").asText("stdio");
                if (name.isBlank()) {
                    log.warn("[MCP Registry] 跳过未命名的服务器配置: {}", server);
                    continue;
                }
                try {
                    if ("stdio".equals(transport)) {
                        List<String> command = new ArrayList<>();
                        JsonNode cmdNode = server.path("command");
                        if (cmdNode.isArray()) {
                            cmdNode.forEach(n -> command.add(n.asText()));
                        }
                        JsonNode argsNode = server.path("args");
                        if (argsNode.isArray()) {
                            argsNode.forEach(n -> command.add(n.asText()));
                        }
                        if (command.isEmpty()) {
                            log.warn("[MCP Registry] stdio 服务器 '{}' 缺少 command，跳过", name);
                            continue;
                        }
                        mountServer(name, command);
                    } else if ("http".equals(transport) || "sse".equals(transport)) {
                        String url = server.path("url").asText("");
                        if (url.isBlank()) {
                            log.warn("[MCP Registry] http 服务器 '{}' 缺少 url，跳过", name);
                            continue;
                        }
                        Map<String, String> headers = new HashMap<>();
                        JsonNode headersNode = server.path("headers");
                        if (headersNode.isObject()) {
                            headersNode.fields().forEachRemaining(
                                    e -> headers.put(e.getKey(), e.getValue().asText()));
                        }
                        mountHttpServer(name, url, headers);
                    } else {
                        log.warn("[MCP Registry] 不支持的传输类型 '{}'，跳过服务器 '{}'", transport, name);
                    }
                } catch (Exception e) {
                    log.warn("[MCP Registry] 挂载服务器 '{}' 失败: {}", name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[MCP Registry] 解析 MCP 配置失败: {}", e.getMessage());
        }
    }

    // ── 新架构：真实 MCP 客户端管理 ──

    /**
     * 注册并连接一个新的 MCP 服务器 (相当于挂载一个新硬件)
     * 默认使用 Stdio 传输。
     */
    public synchronized void mountServer(String serverName, List<String> commandArgs) {
        if (clients.containsKey(serverName)) {
            log.warn("[MCP Registry] 服务器 '{}' 已挂载", serverName);
            return;
        }

        try {
            McpClient client = new McpClient(serverName);
            client.connect(commandArgs);
            clients.put(serverName, client);

            // 同步更新旧架构的连接元数据
            McpConnection conn = new McpConnection(serverName, new McpConfigManager.McpServerConfig(
                    serverName, "stdio", null, commandArgs, Map.of(), Map.of(),
                    McpConfigManager.ConfigScope.PROJECT
            ));
            conn.setState(ConnectionState.CONNECTED);
            connections.put(serverName, conn);

            log.info("[MCP Registry] 服务器 '{}' 挂载成功", serverName);
        } catch (Exception e) {
            log.error("[MCP Registry] 服务器 '{}' 挂载失败: {}", serverName, e.getMessage());

            // 记录失败状态
            McpConnection conn = new McpConnection(serverName, new McpConfigManager.McpServerConfig(
                    serverName, "stdio", null, commandArgs, Map.of(), Map.of(),
                    McpConfigManager.ConfigScope.PROJECT
            ));
            conn.setState(ConnectionState.FAILED);
            connections.put(serverName, conn);
        }
    }

    /**
     * 注册并连接一个远程 MCP 服务器 (HTTP/SSE 传输)。
     * <p>
     * 适用于远程 MCP 服务器（如 Exa、Context7 等），
     * 通过 HTTP POST 发送请求，通过 SSE 接收响应。
     *
     * @param serverName 服务器名称
     * @param serverUrl  服务器 URL（如 https://api.example.com/mcp）
     * @param headers    额外的 HTTP 头（如 Authorization: Bearer xxx）
     */
    public synchronized void mountHttpServer(String serverName, String serverUrl, Map<String, String> headers) {
        if (clients.containsKey(serverName)) {
            log.warn("[MCP Registry] 服务器 '{}' 已挂载", serverName);
            return;
        }

        try {
            McpHttpTransport httpTransport = new McpHttpTransport(serverUrl, headers);
            McpClient client = new McpClient(serverName, httpTransport);
            client.connectHttp();
            clients.put(serverName, client);

            // 同步更新旧架构的连接元数据
            McpConnection conn = new McpConnection(serverName, new McpConfigManager.McpServerConfig(
                    serverName, "http", serverUrl, null, headers, Map.of(),
                    McpConfigManager.ConfigScope.PROJECT
            ));
            conn.setState(ConnectionState.CONNECTED);
            connections.put(serverName, conn);

            log.info("[MCP Registry] HTTP 服务器 '{}' 挂载成功", serverName);
        } catch (Exception e) {
            log.error("[MCP Registry] HTTP 服务器 '{}' 挂载失败: {}", serverName, e.getMessage());

            McpConnection conn = new McpConnection(serverName, new McpConfigManager.McpServerConfig(
                    serverName, "http", serverUrl, null, headers, Map.of(),
                    McpConfigManager.ConfigScope.PROJECT
            ));
            conn.setState(ConnectionState.FAILED);
            connections.put(serverName, conn);
        }
    }

    /**
     * 卸载指定 MCP 服务器。
     */
    public synchronized void unmountServer(String serverName) {
        McpClient client = clients.remove(serverName);
        if (client != null) {
            client.disconnect();
        }
        connections.remove(serverName);
        log.info("[MCP Registry] 服务器 '{}' 已卸载", serverName);
    }

    /**
     * 获取真实 MCP 客户端。
     */
    public McpClient getClient(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null) {
            throw new RuntimeException("MCP 服务器 '" + serverName + "' 未挂载或离线");
        }
        return client;
    }

    /**
     * 卸载所有 MCP 服务器。
     */
    public void unmountAll() {
        clients.values().forEach(McpClient::disconnect);
        clients.clear();
        connections.values().forEach(c -> c.setState(ConnectionState.DISCONNECTED));
        connections.clear();
        log.info("[MCP Registry] 所有服务器已卸载");
    }

    // ── 旧架构兼容 API ──

    /**
     * 注册 MCP 服务器连接（旧 API，兼容 PluginManager 等调用方）。
     * <p>
     * 根据配置中的 type 字段自动选择传输层：
     * - stdio → McpStdioTransport
     * - http/sse → McpHttpTransport
     */
    public McpConnection register(String serverName, McpConfigManager.McpServerConfig config) {
        McpConnection conn = new McpConnection(serverName, config);
        connections.put(serverName, conn);

        if (clients.containsKey(serverName)) {
            log.info("[McpClientRegistry] 服务器 '{}' 已有活跃客户端，跳过", serverName);
            return conn;
        }

        String type = config.type();
        try {
            if ("stdio".equals(type) && config.command() != null) {
                McpClient client = new McpClient(serverName);
                client.connect(config.command());
                clients.put(serverName, client);
                conn.setState(ConnectionState.CONNECTED);
            } else if ("http".equals(type) || "sse".equals(type)) {
                String url = config.url();
                if (url == null || url.isEmpty()) {
                    throw new RuntimeException("HTTP/SSE 服务器需要有效的 URL");
                }
                // 旧版 sse 类型映射到 http
                Map<String, String> headers = config.headers() != null ? config.headers() : Map.of();
                McpHttpTransport httpTransport = new McpHttpTransport(url, headers);
                McpClient client = new McpClient(serverName, httpTransport);
                client.connectHttp();
                clients.put(serverName, client);
                conn.setState(ConnectionState.CONNECTED);
            } else {
                log.warn("[McpClientRegistry] 不支持的传输类型 '{}'，服务器 '{}'", type, serverName);
                conn.setState(ConnectionState.FAILED);
            }
        } catch (Exception e) {
            log.error("[MCP Registry] 服务器 '{}' 自动挂载失败: {}", serverName, e.getMessage());
            conn.setState(ConnectionState.FAILED);
        }

        log.info("[McpClientRegistry] 已注册: {} (类型: {})", serverName, type);
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
        clients.values().forEach(McpClient::disconnect);
        clients.clear();
        connections.values().forEach(c -> c.setState(ConnectionState.DISCONNECTED));
        connections.clear();
        log.info("[McpClientRegistry] 所有连接已关闭");
    }

    /** 兼容旧 hasServer() */
    public boolean hasServer(String serverName) {
        return connections.containsKey(serverName);
    }

    /** 兼容旧 serverNames() */
    public Set<String> serverNames() {
        return Collections.unmodifiableSet(connections.keySet());
    }

    /**
     * 兼容旧 callTool() — 通过真实 McpClient 执行 MCP tools/call。
     */
    public Object callTool(String serverName, String toolName, Map<String, Object> args) {
        // 优先使用新架构的真实客户端
        McpClient client = clients.get(serverName);
        if (client != null) {
            try {
                ObjectNode params = MAPPER.createObjectNode();
                params.put("name", toolName);
                params.set("arguments", MAPPER.valueToTree(args));

                JsonNode result = client.request("tools/call", params, 30_000);

                // 同步更新旧架构的工具列表（懒发现）
                syncToolsFromClient(serverName, client);

                return MAPPER.treeToValue(result, Map.class);
            } catch (Exception e) {
                return Map.of("error", "MCP 调用失败: " + e.getMessage());
            }
        }

        // 回退到旧架构的 stub
        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            return Map.of("error", "MCP 服务器 '" + serverName + "' 未连接");
        }
        return Map.of("server", serverName, "tool", toolName, "args", args, "status", "dispatched");
    }

    /**
     * 兼容旧 listTools() — 通过真实 McpClient 执行 MCP tools/list。
     */
    public Object listTools(String serverName) {
        McpClient client = clients.get(serverName);
        if (client != null) {
            try {
                JsonNode result = client.request("tools/list", null, 10_000);
                syncToolsFromClient(serverName, client);
                return MAPPER.treeToValue(result, Map.class);
            } catch (Exception e) {
                return List.of();
            }
        }

        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            return List.of();
        }
        return conn.tools();
    }

    /**
     * 从 MCP 客户端同步工具列表到旧架构的 McpConnection。
     */
    private void syncToolsFromClient(String serverName, McpClient client) {
        McpConnection conn = connections.get(serverName);
        if (conn == null) return;

        try {
            JsonNode result = client.request("tools/list", null, 10_000);
            if (result != null && result.has("tools")) {
                for (JsonNode toolNode : result.get("tools")) {
                    String name = toolNode.path("name").asText();
                    String desc = toolNode.path("description").asText("");
                    String schema = toolNode.path("inputSchema").toString();

                    // 避免重复添加
                    boolean exists = conn.tools().stream()
                            .anyMatch(t -> t.name().equals(name));
                    if (!exists) {
                        conn.addTool(new McpToolDef(name, desc, schema, serverName));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[MCP Registry] 服务器 '{}' 工具同步跳过: {}", serverName, e.getMessage());
        }
    }
}
