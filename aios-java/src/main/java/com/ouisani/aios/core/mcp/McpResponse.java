package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 协议响应 — JSON-RPC 2.0 响应结构。
 * <p>
 * OS 类比: syscall 返回值的标准封装——成功时携带 result，失败时携带 error。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpResponse {
    @JsonProperty("jsonrpc")
    private String jsonrpc;
    @JsonProperty("id")
    private String id;
    @JsonProperty("result")
    private JsonNode result;
    @JsonProperty("error")
    private McpError error;

    public McpResponse() {}

    public static McpResponse success(String id, JsonNode result) {
        McpResponse r = new McpResponse();
        r.jsonrpc = "2.0";
        r.id = id;
        r.result = result;
        return r;
    }

    public static McpResponse error(String id, McpError error) {
        McpResponse r = new McpResponse();
        r.jsonrpc = "2.0";
        r.id = id;
        r.error = error;
        return r;
    }

    public static McpResponse error(String id, int code, String message) {
        return error(id, new McpError(code, message));
    }

    public String getJsonrpc() { return jsonrpc; }
    public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public JsonNode getResult() { return result; }
    public void setResult(JsonNode result) { this.result = result; }
    public McpError getError() { return error; }
    public void setError(McpError error) { this.error = error; }
    public boolean isError() { return error != null; }
}
