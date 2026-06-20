package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 协议通知 — JSON-RPC 2.0 通知结构（无 id，不需要响应）。
 * <p>
 * OS 类比: 信号（signal）——单向通知，不期望返回值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpNotification {
    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";
    @JsonProperty("method")
    private String method;
    @JsonProperty("params")
    private JsonNode params;

    public McpNotification() {}

    public McpNotification(String method, JsonNode params) {
        this.jsonrpc = "2.0";
        this.method = method;
        this.params = params;
    }

    public McpNotification(String method) {
        this(method, null);
    }

    public String getJsonrpc() { return jsonrpc; }
    public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public JsonNode getParams() { return params; }
    public void setParams(JsonNode params) { this.params = params; }
}
