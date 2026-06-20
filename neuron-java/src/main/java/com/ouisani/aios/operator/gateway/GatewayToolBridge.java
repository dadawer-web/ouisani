package com.ouisani.aios.operator.gateway;

import java.util.Map;

/**
 * Gateway 工具桥接层 — 对标 OpenClaw 的 callGatewayTool。
 * <p>
 * 所有内置工具通过此桥接层与 Gateway 通信。
 * 统一处理认证、Scope 解析、超时、重试。
 * <p>
 * OS 类比：相当于系统调用的入口 — 所有用户态程序必须通过
 * 系统调用接口访问内核服务，不能直接操作硬件。
 */
public class GatewayToolBridge {

    private final GatewayClient client;
    private final long defaultTimeoutMs;

    public GatewayToolBridge(GatewayClient client) {
        this(client, 30000);
    }

    public GatewayToolBridge(GatewayClient client, long defaultTimeoutMs) {
        this.client = client;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * 工具调用 Gateway 的核心方法。
     * <p>
     * 对标 OpenClaw 的 callGatewayTool(method, opts, params, extra)。
     *
     * @param method Gateway RPC 方法名
     * @param params 方法参数
     * @return Gateway 响应
     * @throws GatewayException 调用失败
     */
    public String call(String method, Map<String, Object> params) throws GatewayException {
        return client.call(method, params, null, java.time.Duration.ofMillis(defaultTimeoutMs));
    }

    /**
     * 带显式 Scope 的调用。
     */
    public String callScoped(String method, Map<String, Object> params,
                             java.util.List<OperatorScope> scopes) throws GatewayException {
        return client.call(method, params, scopes, java.time.Duration.ofMillis(defaultTimeoutMs));
    }

    /**
     * 带重试的调用 — 对可恢复错误自动重试。
     *
     * @param maxRetries 最大重试次数
     */
    public String callWithRetry(String method, Map<String, Object> params,
                                int maxRetries) throws GatewayException {
        GatewayException lastError = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return call(method, params);
            } catch (GatewayException e) {
                lastError = e;
                if (!e.isRetryable()) throw e;
                if (attempt < maxRetries) {
                    long delay = (long) Math.min(1000 * Math.pow(2, attempt), 10000);
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new GatewayException("Interrupted during retry", GatewayException.Kind.UNKNOWN);
                    }
                }
            }
        }
        throw lastError;
    }

    public GatewayClient client() { return client; }
}
