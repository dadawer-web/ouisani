package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MCP 协议错误 — JSON-RPC 2.0 错误结构。
 * <p>
 * OS 类比: errno 错误码——标准化的错误标识，包含 code 和 message。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpError(
        int code,
        String message,
        Object data
) {
    public McpError(int code, String message) {
        this(code, message, null);
    }

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}
