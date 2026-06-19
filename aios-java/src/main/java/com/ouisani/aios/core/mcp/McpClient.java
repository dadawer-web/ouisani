package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP 客户端 — 管理与 MCP 服务器子进程的完整生命周期。
 * <p>
 * OS 类比: 用户态程序通过系统调用与内核通信——客户端负责组装请求、
 * 匹配响应、超时管理，是传输层之上的状态管理层。
 * <p>
 * 连接流程遵循 MCP 规范：
 * 1. 启动传输层（stdio/HTTP）
 * 2. 发送 {@code initialize} 请求，交换 clientInfo/capabilities/protocolVersion
 * 3. 发送 {@code notifications/initialized} 通知，完成握手
 * 4. 进入正常通信阶段
 */
public class McpClient {
    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** MCP 协议版本 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final String serverName;
    private final McpTransport transport;
    // 记录正在等待响应的请求 ID -> Future 的映射
    private final ConcurrentHashMap<String, CompletableFuture<McpResponse>> pendingRequests = new ConcurrentHashMap<>();

    /** 握手后服务端声明的能力 */
    private JsonNode serverCapabilities;
    /** 握手后服务端声明的协议版本 */
    private String negotiatedProtocolVersion;

    /**
     * 创建使用 Stdio 传输的 MCP 客户端。
     */
    public McpClient(String serverName) {
        this.serverName = serverName;
        this.transport = new McpStdioTransport();
    }

    /**
     * 创建使用指定传输层的 MCP 客户端。
     *
     * @param serverName 服务器名称
     * @param transport  传输层实例（Stdio / HTTP）
     */
    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
    }

    /**
     * 连接到 Stdio 类型的 MCP 服务器。
     */
    public void connect(List<String> command) throws IOException {
        log.info("[MCP Client] 正在通过 Stdio 连接服务器 '{}'", serverName);
        if (transport instanceof McpStdioTransport stdio) {
            stdio.start(command, this::handleIncomingMessage);
        } else {
            throw new IOException("Cannot use stdio connect with non-stdio transport");
        }

        // ── MCP 规范握手流程 ──
        try {
            performHandshake();
            log.info("[MCP Client] 服务器 '{}' 已连接并初始化，Protocol={}", serverName, negotiatedProtocolVersion);
        } catch (Exception e) {
            log.error("[MCP Client] 服务器 '{}' 握手失败: {}", serverName, e.getMessage());
            transport.close();
            throw new IOException("MCP 握手失败: " + e.getMessage(), e);
        }
    }

    /**
     * 连接到 HTTP/SSE 类型的 MCP 服务器。
     */
    public void connectHttp() throws IOException {
        log.info("[MCP Client] 正在通过 HTTP/SSE 连接服务器 '{}'", serverName);
        if (transport instanceof McpHttpTransport http) {
            http.start(this::handleIncomingMessage);
        } else {
            throw new IOException("Cannot use HTTP connect with non-HTTP transport");
        }

        // ── MCP 规范握手流程 ──
        try {
            performHandshake();
            log.info("[MCP Client] 服务器 '{}' 已通过 HTTP 连接并初始化，Protocol={}", serverName, negotiatedProtocolVersion);
        } catch (Exception e) {
            log.error("[MCP Client] 服务器 '{}' HTTP 握手失败: {}", serverName, e.getMessage());
            transport.close();
            throw new IOException("MCP HTTP 握手失败: " + e.getMessage(), e);
        }
    }

    /**
     * MCP 握手协议：发送 initialize 请求 + notifications/initialized 通知。
     * <p>
     * 遵循 MCP 规范 2024-11-05：
     * 1. 客户端发送 initialize 请求，包含 clientInfo、capabilities、protocolVersion
     * 2. 服务端响应其 capabilities 和 protocolVersion
     * 3. 客户端发送 notifications/initialized 通知，握手完成
     */
    private void performHandshake() {
        // 构建 initialize 请求参数
        ObjectNode initParams = mapper.createObjectNode();
        initParams.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "aios-mcp-client");
        clientInfo.put("version", "1.0.0");
        initParams.set("clientInfo", clientInfo);

        ObjectNode capabilities = mapper.createObjectNode();
        // 声明客户端支持的能力
        capabilities.putObject("roots"); // 支持 roots/list
        capabilities.putObject("sampling"); // 支持 sampling/createMessage
        initParams.set("capabilities", capabilities);

        // 发送 initialize 请求（超时 10 秒）
        JsonNode initResult = request("initialize", initParams, 10_000);

        // 解析服务端能力
        if (initResult != null) {
            this.serverCapabilities = initResult.path("capabilities");
            this.negotiatedProtocolVersion = initResult.path("protocolVersion").asText(PROTOCOL_VERSION);
            JsonNode serverInfo = initResult.path("serverInfo");
            String serverName = serverInfo.path("name").asText("unknown");
            String serverVersion = serverInfo.path("version").asText("unknown");
            log.info("[MCP Client] 服务器信息: name={}, version={}, protocol={}, capabilities={}",
                    serverName, serverVersion, negotiatedProtocolVersion,
                    serverCapabilities != null && !serverCapabilities.isMissingNode() ? serverCapabilities.fieldNames().hasNext() : "none");
        }

        // 发送 initialized 通知（无 id，不需要响应）
        try {
            McpNotification notification = new McpNotification("notifications/initialized", null);
            transport.send(mapper.writeValueAsString(notification));
            log.debug("[MCP Client] 已发送 notifications/initialized 到服务器 '{}'", serverName);
        } catch (IOException e) {
            log.warn("[MCP Client] 发送 initialized 通知失败: {}", e.getMessage());
        }
    }

    private void handleIncomingMessage(String rawJson) {
        try {
            JsonNode root = mapper.readTree(rawJson);
            // 区分 Response 和 Notification
            if (root.has("id") && (root.has("result") || root.has("error"))) {
                String id = root.get("id").asText();
                CompletableFuture<McpResponse> future = pendingRequests.remove(id);
                if (future != null) {
                    McpResponse response = mapper.treeToValue(root, McpResponse.class);
                    future.complete(response);
                } else {
                    log.warn("[MCP Client] 收到未知/过期 ID 的响应: {}", id);
                }
            } else if (root.has("method")) {
                // 处理服务端发来的通知 (如 log/message, notifications/resources/updated)
                String method = root.get("method").asText();
                log.info("[MCP Client] 收到服务器 '{}' 的通知: {}", serverName, method);
            }
        } catch (Exception e) {
            log.error("[MCP Client] 解析传入消息失败: {}", rawJson, e);
        }
    }

    /**
     * 发送请求并阻塞等待结果 (支持超时)
     */
    public JsonNode request(String method, JsonNode params, long timeoutMs) {
        String id = UUID.randomUUID().toString();
        McpRequest req = new McpRequest(id, method, params);

        CompletableFuture<McpResponse> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            transport.send(mapper.writeValueAsString(req));

            // 使用 Virtual Threads 阻塞等待，不消耗物理线程
            McpResponse response = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            if (response.isError()) {
                throw new RuntimeException(String.format("MCP Error [%d]: %s",
                        response.getError().getCode(), response.getError().getMessage()));
            }
            return response.getResult();

        } catch (java.util.concurrent.TimeoutException e) {
            pendingRequests.remove(id);
            throw new RuntimeException("MCP 请求超时 (" + timeoutMs + "ms)");
        } catch (Exception e) {
            pendingRequests.remove(id);
            throw new RuntimeException("MCP 请求失败: " + e.getMessage(), e);
        }
    }

    public void disconnect() {
        transport.close();
        pendingRequests.values().forEach(f -> f.completeExceptionally(new RuntimeException("客户端已断开连接")));
        pendingRequests.clear();
    }

    /** 获取服务端能力（握手后可用） */
    public JsonNode getServerCapabilities() {
        return serverCapabilities;
    }

    /** 获取协商的协议版本 */
    public String getNegotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
    }
}
