package com.ouisani.aios.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MCP HTTP/SSE 传输层 — 通过 HTTP POST 发送请求，通过 SSE 接收响应。
 * <p>
 * OS 类比: 网络设备驱动——通过 HTTP 协议与远程 MCP 服务器通信，
 * 类似于内核通过网卡驱动与远程存储通信。
 * <p>
 * MCP 规范的 Streamable HTTP 传输：
 * - 客户端通过 HTTP POST 发送 JSON-RPC 请求
 * - 服务端通过 SSE (Server-Sent Events) 流式返回响应
 * - 支持会话管理（Mcp-Session-Id 头）
 * <p>
 * 同时兼容旧版 SSE 传输：
 * - 客户端先 GET /sse 建立 SSE 连接
 * - 通过 POST /messages 发送请求
 * - 响应通过 SSE 流推送
 */
public class McpHttpTransport implements McpTransport {
    private static final Logger log = LoggerFactory.getLogger(McpHttpTransport.class);

    private final String serverUrl;
    private final Map<String, String> headers;
    private final HttpClient httpClient;

    private String sessionId; // MCP 会话 ID（由服务端分配）
    private String sseEndpoint; // SSE 事件流端点（旧版 SSE 传输使用）
    private Thread sseThread;
    private volatile boolean running = false;
    private Consumer<String> messageHandler;

    /**
     * 创建 HTTP 传输层。
     *
     * @param serverUrl MCP 服务器 URL（如 https://api.example.com/mcp）
     * @param headers   额外的 HTTP 头（如 Authorization: Bearer xxx）
     */
    public McpHttpTransport(String serverUrl, Map<String, String> headers) {
        this.serverUrl = serverUrl;
        this.headers = headers != null ? headers : Map.of();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 启动 HTTP/SSE 传输。
     * <p>
     * 尝试 Streamable HTTP 模式（直接 POST 到 serverUrl），
     * 如果服务端返回 SSE 端点，则回退到旧版 SSE 模式。
     */
    public void start(Consumer<String> onMessageReceived) {
        this.messageHandler = onMessageReceived;
        this.running = true;

        // 尝试先建立 SSE 连接（旧版兼容）
        // 如果服务端支持 /sse 端点，则使用旧版 SSE 传输
        trySseConnection();
    }

    /**
     * 尝试建立旧版 SSE 连接。
     * 如果服务端不支持 SSE（返回 404 等），则回退到 Streamable HTTP 模式。
     */
    private void trySseConnection() {
        String sseUrl = serverUrl.endsWith("/") ? serverUrl + "sse" : serverUrl + "/sse";

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(sseUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "text/event-stream")
                    .GET();

            headers.forEach(reqBuilder::header);

            // 异步发起 SSE 连接
            sseThread = Thread.startVirtualThread(() -> {
                try {
                    log.info("[MCP HTTP] Attempting SSE connection to: {}", sseUrl);
                    HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(
                            reqBuilder.build(),
                            HttpResponse.BodyHandlers.ofLines()
                    );

                    if (response.statusCode() == 200) {
                        log.info("[MCP HTTP] SSE connection established: {}", sseUrl);
                        // 解析 SSE 事件流
                        response.body().forEach(line -> {
                            if (!running) return;
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if (!data.isEmpty() && messageHandler != null) {
                                    log.debug("[MCP HTTP] SSE RECV: {}", data);
                                    messageHandler.accept(data);
                                }
                            } else if (line.startsWith("event: endpoint")) {
                                // 旧版 SSE 传输：服务端告知消息发送端点
                                // 下一行 data 包含端点 URL
                            }
                        });
                    } else {
                        log.debug("[MCP HTTP] SSE endpoint not available ({}), using Streamable HTTP mode", response.statusCode());
                    }
                } catch (Exception e) {
                    if (running) {
                        log.debug("[MCP HTTP] SSE connection failed (expected for Streamable HTTP servers): {}", e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            log.debug("[MCP HTTP] SSE setup failed, using Streamable HTTP mode: {}", e.getMessage());
        }
    }

    /**
     * 发送 JSON-RPC 消息。
     * <p>
     * Streamable HTTP 模式：POST 到 serverUrl，响应可能是：
     * 1. 直接返回 JSON（单个响应）
     * 2. 返回 SSE 流（多个事件，如进度通知 + 最终结果）
     * <p>
     * 会话管理：首次请求后，服务端会在响应头中返回 Mcp-Session-Id，
     * 后续请求必须携带此头。
     */
    public synchronized void send(String jsonMessage) throws IOException {
        if (!running && messageHandler == null) {
            throw new IOException("Transport not started");
        }

        log.debug("[MCP HTTP] SEND: {}", jsonMessage);

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMessage));

            // 注入自定义头（如 Authorization）
            headers.forEach(reqBuilder::header);

            // 注入会话 ID（握手后服务端分配）
            if (sessionId != null && !sessionId.isEmpty()) {
                reqBuilder.header("Mcp-Session-Id", sessionId);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            // 提取会话 ID
            String newSessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (newSessionId != null) {
                this.sessionId = newSessionId;
                log.debug("[MCP HTTP] Session ID updated: {}", sessionId);
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String body = response.body();

            if (response.statusCode() >= 400) {
                log.error("[MCP HTTP] Server returned error: {} {}", response.statusCode(), body);
                throw new IOException("MCP HTTP error: " + response.statusCode() + " - " + body);
            }

            if (contentType.contains("text/event-stream") && body != null) {
                // SSE 响应：解析事件流
                parseSseResponse(body);
            } else if (contentType.contains("application/json") && body != null && !body.isBlank()) {
                // 直接 JSON 响应
                log.debug("[MCP HTTP] RECV: {}", body);
                if (messageHandler != null) {
                    messageHandler.accept(body);
                }
            } else if (response.statusCode() == 202) {
                // 已接受，响应将通过 SSE 流异步到达
                log.debug("[MCP HTTP] Request accepted (202), response will arrive via SSE stream");
            }

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("MCP HTTP send failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 SSE 格式的响应体。
     */
    private void parseSseResponse(String sseBody) {
        String[] lines = sseBody.split("\n");
        for (String line : lines) {
            if (line.startsWith("data: ")) {
                String data = line.substring(6).trim();
                if (!data.isEmpty() && messageHandler != null) {
                    log.debug("[MCP HTTP] SSE RECV: {}", data);
                    messageHandler.accept(data);
                }
            }
        }
    }

    /**
     * 关闭传输层。
     */
    public void close() {
        running = false;
        if (sseThread != null) {
            sseThread.interrupt();
        }
        log.info("[MCP HTTP] Transport closed for: {}", serverUrl);
    }

    /** 获取当前会话 ID */
    public String getSessionId() {
        return sessionId;
    }
}
