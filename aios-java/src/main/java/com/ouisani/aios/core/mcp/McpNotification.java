package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

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
