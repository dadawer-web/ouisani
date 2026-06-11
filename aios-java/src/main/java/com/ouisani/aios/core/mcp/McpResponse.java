package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MCP 协议响应 — JSON-RPC 2.0 响应结构。
 * <p>
 * OS 类比: syscall 返回值的标准封装——成功时携带 result，失败时携带 error。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpResponse(
        String jsonrpc,
        @JsonProperty("id") Object id,
        Object result,
        McpError error
) {
    public static McpResponse success(Object id, Object result) {
        return new McpResponse("2.0", id, result, null);
    }

    public static McpResponse error(Object id, McpError error) {
        return new McpResponse("2.0", id, null, error);
    }

    public static McpResponse error(Object id, int code, String message) {
        return new McpResponse("2.0", id, null, new McpError(code, message));
    }
}
