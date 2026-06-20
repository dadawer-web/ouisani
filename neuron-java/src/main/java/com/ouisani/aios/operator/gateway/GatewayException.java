package com.ouisani.aios.operator.gateway;

/**
 * Gateway 异常 — 对标 OpenClaw 的 GatewayTransportError + CredentialsRequiredError。
 */
public class GatewayException extends Exception {

    public enum Kind {
        /** 连接错误（Gateway 不可达） */
        CONNECTION_ERROR,
        /** 超时 */
        TIMEOUT,
        /** 认证失败（缺少凭据） */
        AUTH_REQUIRED,
        /** 授权失败（scope 不足） */
        SCOPE_DENIED,
        /** 服务器错误（4xx/5xx） */
        SERVER_ERROR,
        /** 配置错误（URL 无效等） */
        CONFIG_ERROR,
        /** 未知错误 */
        UNKNOWN
    }

    private final Kind kind;

    public GatewayException(String message, Kind kind) {
        super(message);
        this.kind = kind;
    }

    public GatewayException(String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    /** 是否可重试 */
    public boolean isRetryable() {
        return kind == Kind.CONNECTION_ERROR || kind == Kind.TIMEOUT;
    }
}
