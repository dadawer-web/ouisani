package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * MCP 协议请求 — JSON-RPC 2.0 请求结构。
 * <p>
 * OS 类比: syscall 调用请求——包含方法名（等同 syscall 编号）和参数。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpRequest(
        String jsonrpc,
        @JsonProperty("id") Object id,
        String method,
        Map<String, Object> params
) {
    public McpRequest(Object id, String method, Map<String, Object> params) {
        this("2.0", id, method, params);
    }

    public McpRequest(Object id, String method) {
        this("2.0", id, method, null);
    }
}
