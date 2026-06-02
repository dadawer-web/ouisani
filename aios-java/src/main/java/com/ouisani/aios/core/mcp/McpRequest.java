package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

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
