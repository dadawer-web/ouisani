package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
