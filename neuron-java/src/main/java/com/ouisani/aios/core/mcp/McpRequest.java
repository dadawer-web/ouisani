package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 协议请求 — JSON-RPC 2.0 请求结构。
 * <p>
 * OS 类比: syscall 调用请求——包含方法名（等同 syscall 编号）和参数。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpRequest {
    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";
    @JsonProperty("id")
    private String id;
    @JsonProperty("method")
    private String method;
    @JsonProperty("params")
    private JsonNode params;

    public McpRequest() {}

    public McpRequest(String id, String method, JsonNode params) {
        this.id = id;
        this.method = method;
        this.params = params;
    }

    public McpRequest(String id, String method) {
        this(id, method, null);
    }

    public String getJsonrpc() { return jsonrpc; }
    public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public JsonNode getParams() { return params; }
    public void setParams(JsonNode params) { this.params = params; }
}
