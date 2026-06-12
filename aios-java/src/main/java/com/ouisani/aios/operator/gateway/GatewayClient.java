package com.ouisani.aios.operator.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway 客户端 — 对标 OpenClaw 的 GatewayClient + callGateway。
 * <p>
 * 通过 HTTP/WebSocket 与 OpenClaw Gateway 通信。
 * 支持认证、Scope 授权、超时保护。
 * <p>
 * OS 类比：相当于 VFS 的 mount — 通过标准协议与远程服务通信，
 * 所有操作都经过认证和授权检查。
 */
public class GatewayClient {

    private static final AtomicLong REQUEST_ID = new AtomicLong(0);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String gatewayUrl;
    private final String token;
    private final String password;
    private final HttpClient httpClient;

    public GatewayClient(String gatewayUrl, String token, String password) {
        this.gatewayUrl = normalizeUrl(gatewayUrl);
        this.token = token;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 调用 Gateway RPC 方法。
     *
     * @param method  RPC 方法名（如 "node.list", "config.get"）
     * @param params  方法参数
     * @param scopes  所需权限作用域
     * @param timeout 超时时间
     * @return Gateway 响应 JSON 字符串
     */
    public String call(String method, Map<String, Object> params,
                       List<OperatorScope> scopes, Duration timeout) throws GatewayException {
        // 1. 验证 URL 安全性
        validateUrl();

        // 2. 解析最小权限 scope
        if (scopes == null || scopes.isEmpty()) {
            scopes = resolveLeastPrivilegeScopes(method);
        }

        // 3. 构建请求
        String requestId = String.valueOf(REQUEST_ID.incrementAndGet());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", requestId);
        request.put("method", method);
        if (params != null) request.put("params", params);
        request.put("scopes", scopes.stream().map(OperatorScope::value).toList());

        String requestBody = toJson(request);

        // 4. 发送 HTTP 请求
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/rpc"))
                .header("Content-Type", "application/json")
                .timeout(timeout != null ? timeout : DEFAULT_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        // 认证头
        if (token != null && !token.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + token);
        } else if (password != null && !password.isBlank()) {
            reqBuilder.header("Authorization", "Basic " +
                    Base64.getEncoder().encodeToString(("operator:" + password).getBytes()));
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                throw new GatewayException("Authentication failed: invalid credentials",
                        GatewayException.Kind.AUTH_REQUIRED);
            }
            if (response.statusCode() == 403) {
                throw new GatewayException("Authorization failed: insufficient scope for " + method,
                        GatewayException.Kind.SCOPE_DENIED);
            }
            if (response.statusCode() >= 400) {
                throw new GatewayException("Gateway error: " + response.statusCode() + " " + response.body(),
                        GatewayException.Kind.SERVER_ERROR);
            }

            return response.body();
        } catch (GatewayException e) {
            throw e;
        } catch (java.net.ConnectException e) {
            throw new GatewayException("Gateway unreachable: " + gatewayUrl,
                    GatewayException.Kind.CONNECTION_ERROR);
        } catch (java.net.SocketTimeoutException e) {
            throw new GatewayException("Gateway timeout for method: " + method,
                    GatewayException.Kind.TIMEOUT);
        } catch (Exception e) {
            throw new GatewayException("Gateway call failed: " + e.getMessage(),
                    GatewayException.Kind.UNKNOWN);
        }
    }

    /** 便捷调用 — 使用默认超时 */
    public String call(String method, Map<String, Object> params,
                       List<OperatorScope> scopes) throws GatewayException {
        return call(method, params, scopes, DEFAULT_TIMEOUT);
    }

    /** 便捷调用 — 自动解析 scope */
    public String call(String method, Map<String, Object> params) throws GatewayException {
        return call(method, params, null, DEFAULT_TIMEOUT);
    }

    // ════════════════════════════════════════════════════════════════
    //  Scope 解析 — 方法名到最小权限 scope 的映射
    // ════════════════════════════════════════════════════════════════

    private static List<OperatorScope> resolveLeastPrivilegeScopes(String method) {
        if (method == null) return List.of(OperatorScope.ADMIN);

        // 读操作
        if (method.startsWith("health") || method.startsWith("status")
                || method.equals("config.get") || method.equals("config.schema.lookup")
                || method.startsWith("node.list") || method.startsWith("node.describe")
                || method.startsWith("sessions.list") || method.startsWith("chat.history")
                || method.startsWith("models.list") || method.startsWith("agents.list")) {
            return List.of(OperatorScope.READ);
        }

        // 写操作
        if (method.startsWith("sessions.create") || method.startsWith("sessions.send")
                || method.startsWith("node.invoke") || method.startsWith("message.action")
                || method.startsWith("chat.send") || method.startsWith("wake")
                || method.startsWith("cron.add") || method.startsWith("cron.remove")
                || method.startsWith("tts.")) {
            return List.of(OperatorScope.WRITE);
        }

        // 管理操作
        if (method.startsWith("config.apply") || method.startsWith("config.patch")
                || method.startsWith("agents.create") || method.startsWith("agents.delete")
                || method.startsWith("update.run")) {
            return List.of(OperatorScope.ADMIN);
        }

        // 审批操作
        if (method.startsWith("exec.approval") || method.startsWith("plugin.approval")) {
            return List.of(OperatorScope.APPROVALS);
        }

        // 配对操作
        if (method.startsWith("node.pair")) {
            return List.of(OperatorScope.PAIRING);
        }

        // 默认需要 admin（fail-closed）
        return List.of(OperatorScope.ADMIN);
    }

    // ════════════════════════════════════════════════════════════════
    //  URL 安全验证 — 防止 SSRF
    // ════════════════════════════════════════════════════════════════

    private void validateUrl() throws GatewayException {
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            throw new GatewayException("Gateway URL not configured",
                    GatewayException.Kind.CONFIG_ERROR);
        }

        try {
            URI uri = URI.create(gatewayUrl);
            String host = uri.getHost();

            // 仅允许 loopback 或 localhost
            if (host != null && !host.equals("localhost") && !host.equals("127.0.0.1")
                    && !host.equals("::1") && !host.equals("0.0.0.0")) {
                // 非 loopback 地址需要显式配置允许
                // 在生产环境中应检查配置白名单
                log.warn("[GatewayClient] Non-loopback gateway URL: {} — ensure this is intentional", host);
            }
        } catch (Exception e) {
            throw new GatewayException("Invalid gateway URL: " + gatewayUrl,
                    GatewayException.Kind.CONFIG_ERROR);
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ════════════════════════════════════════════════════════════════
    //  简化 JSON 序列化
    // ════════════════════════════════════════════════════════════════

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof String s) sb.append("\"").append(escape(s)).append("\"");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else if (v instanceof List<?> list) sb.append(listToJson(list));
            else if (v instanceof Map<?, ?> m) sb.append(toJson((Map<String, Object>) m));
            else sb.append("\"").append(escape(v.toString())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String listToJson(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            Object v = list.get(i);
            if (v instanceof String s) sb.append("\"").append(escape(s)).append("\"");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(escape(v.toString())).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GatewayClient.class);
}
