package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.tool.DataTypes;
import com.ouisani.aios.core.tool.Port;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HTTP 客户端节点 — AIOS 的网络请求设备。
 * <p>
 * Agent 通过 VFS write 发送 HTTP 请求（JSON 格式指定 URL、方法、请求体），
 * 通过 VFS read 读取响应。实现"一切皆文件"的网络 I/O 模型。
 *
 * <h3>写入格式</h3>
 * <pre>
 * {"url": "https://api.example.com", "method": "POST", "body": "{...}"}
 * </pre>
 *
 * <h3>OS 类比</h3>
 * 类比 Linux 的 {@code /dev/tcp} 或 {@code /dev/udp} —
 * 通过文件描述符进行网络 I/O。
 */
public non-sealed class HttpNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(HttpNode.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String path;
    private int ownerUid;
    private int permissions;
    private final HttpClient httpClient;
    private final ConcurrentLinkedQueue<String> responseQueue = new ConcurrentLinkedQueue<>();

    public HttpNode(String path) {
        this(path, 0, 0666);
    }

    public HttpNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.WEBHOOK;
    }

    // ── 强类型 I/O 契约 ──
    @Override
    public List<Port> inputPorts() {
        return List.of(new Port("request", DataTypes.JSON_DATA,
                "HTTP 请求 JSON：{url, method, body}（write 入口）", true));
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(new Port("response", DataTypes.HTTP_RESPONSE,
                "HTTP 响应 JSON：{status, body}（read 出口）", true));
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    @Override
    public String read() {
        String response = responseQueue.poll();
        if (response != null) {
            log.debug("HttpNode.read: path={}, dequeued response ({} chars)", path, response.length());
            return response;
        }
        return "{\"status\":\"empty\",\"message\":\"No HTTP responses in queue\"}";
    }

    @Override
    public boolean write(String payload) {
        try {
            // 解析 JSON: {"url": "...", "body": "...", "method": "POST"}
            var tree = objectMapper.readTree(payload);
            String url = tree.has("url") ? tree.get("url").asText() : null;
            String body = tree.has("body") ? tree.get("body").asText() : "";
            String method = tree.has("method") ? tree.get("method").asText() : "POST";

            if (url == null || url.isBlank()) {
                log.warn("[HttpNode] Missing 'url' in payload");
                System.err.printf("  ❌ [HttpNode] Missing 'url' in write payload: %s%n", path);
                return false;
            }

            System.out.printf("  🌐 [HttpNode] %s %s%n", method, url);
            log.info("[HttpNode] Sending {} request to: {}", method, url);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            if ("GET".equalsIgnoreCase(method)) {
                requestBuilder.GET();
            } else if ("PUT".equalsIgnoreCase(method)) {
                requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body));
            } else {
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
            }

            requestBuilder.header("Content-Type", "application/json");

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String result = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("status", httpResponse.statusCode());
                put("body", httpResponse.body());
            }});

            responseQueue.add(result);
            System.out.printf("  🌐 [HttpNode] 响应: 状态码=%d, 正文 %d 字符%n",
                    httpResponse.statusCode(), httpResponse.body().length());
            log.info("[HttpNode] 响应: 状态码={}, 正文 {} 字符", httpResponse.statusCode(), httpResponse.body().length());
            return true;

        } catch (Exception e) {
            log.error("[HttpNode] Request failed: {}", e.getMessage());
            System.err.printf("  ❌ [HttpNode] HTTP request failed: %s%n", e.getMessage());
            String errorResponse = "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            responseQueue.add(errorResponse);
            return false;
        }
    }
}
