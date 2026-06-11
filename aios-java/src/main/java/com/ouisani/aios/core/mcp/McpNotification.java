package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * MCP 协议通知 — JSON-RPC 2.0 通知结构（无 id，不需要响应）。
 * <p>
 * OS 类比: 信号（signal）——单向通知，不期望返回值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpNotification(
        String jsonrpc,
        String method,
        Map<String, Object> params
) {
    public McpNotification(String method, Map<String, Object> params) {
        this("2.0", method, params);
    }

    public McpNotification(String method) {
        this("2.0", method, null);
    }
}
