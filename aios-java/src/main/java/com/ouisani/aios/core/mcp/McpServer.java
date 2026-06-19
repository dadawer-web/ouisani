package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MCP 协议服务器 — AIOS 内核的 Model Context Protocol 实现。
 * <p>
 * 提供标准 MCP 接口，允许外部 AI 客户端通过 JSON-RPC 2.0 协议
 * 访问 AIOS 内核能力，包括：
 * <ul>
 *   <li>VFS 资源读写（resources/list, resources/read）</li>
 *   <li>WASM 沙箱执行（tools/call: execute_wasm_sandbox）</li>
 *   <li>VFS 节点操作（tools/call: vfs_read, vfs_write）</li>
 * </ul>
 * <p>
 * OS 类比: 内核的 /proc 和 /dev 接口——外部程序通过标准协议访问内核状态。
 *
 * @see McpRequest
 * @see McpResponse
 */
public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "aios-kernel";
    private static final String SERVER_VERSION = "0.1.0";

    private final GraalWasmSandbox wasmSandbox;
    private volatile boolean initialized = false;

    private final List<Map<String, Object>> registeredTools = new ArrayList<>();

    public McpServer() {
        this(null);
    }

    public McpServer(GraalWasmSandbox wasmSandbox) {
        this.wasmSandbox = wasmSandbox;
        registerBuiltinTools();
    }

    private void registerBuiltinTools() {
        registeredTools.add(Map.of(
                "name", "execute_wasm_sandbox",
                "description", "Execute a WebAssembly module in the AIOS sandbox. Input: base64-encoded WASM binary and function name.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "wasm_base64", Map.of("type", "string", "description", "Base64-encoded WASM binary"),
                                "function_name", Map.of("type", "string", "description", "Entry function name to call", "default", "main")
                        ),
                        "required", List.of("wasm_base64")
                )
        ));

        registeredTools.add(Map.of(
                "name", "vfs_read",
                "description", "Read data from an AIOS Virtual File System node.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "VFS path to read (e.g. /dev/semantic)")
                        ),
                        "required", List.of("path")
                )
        ));

        registeredTools.add(Map.of(
                "name", "vfs_write",
                "description", "Write data to an AIOS Virtual File System node.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "VFS path to write to"),
                                "data", Map.of("type", "string", "description", "Data to write")
                        ),
                        "required", List.of("path", "data")
                )
        ));

        log.info("[McpServer] 已注册 {} 个内置工具", registeredTools.size());
    }

    public McpResponse handleRequest(McpRequest req) {
        if (req == null || req.getMethod() == null) {
            return McpResponse.error(null, McpError.INVALID_REQUEST, "Invalid request");
        }

        log.debug("[McpServer] Handling request: method={}, id={}", req.getMethod(), req.getId());

        return switch (req.getMethod()) {
            case "initialize" -> handleInitialize(req);
            case "notifications/initialized" -> handleNotificationInitialized(req);
            case "resources/list" -> handleResourcesList(req);
            case "resources/read" -> handleResourcesRead(req);
            case "tools/list" -> handleToolsList(req);
            case "tools/call" -> handleToolsCall(req);
            case "ping" -> handlePing(req);
            default -> McpResponse.error(req.getId(), McpError.METHOD_NOT_FOUND,
                    "Method not found: " + req.getMethod());
        };
    }

    private McpResponse handleInitialize(McpRequest req) {
        initialized = true;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("resources", Map.of("subscribe", true, "listChanged", true));
        capabilities.put("tools", Map.of("listChanged", true));
        capabilities.put("logging", Map.of());
        result.put("capabilities", capabilities);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.put("serverInfo", serverInfo);

        log.info("[McpServer] Initialized: protocol={}, server={}/{}", PROTOCOL_VERSION, SERVER_NAME, SERVER_VERSION);
        return McpResponse.success(req.getId(), objectMapper.valueToTree(result));
    }

    private McpResponse handleNotificationInitialized(McpRequest req) {
        log.info("[McpServer] Client confirmed initialization");
        return McpResponse.success(req.getId(), objectMapper.valueToTree(Map.of()));
    }

    private McpResponse handleResourcesList(McpRequest req) {
        List<Map<String, Object>> resources = new ArrayList<>();
        Map<String, VfsNode> pathTree = getPathTree();

        for (Map.Entry<String, VfsNode> entry : pathTree.entrySet()) {
            String path = entry.getKey();
            VfsNode node = entry.getValue();

            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("uri", "vfs://" + path);
            resource.put("name", extractName(path));
            resource.put("description", node.nodeType().name() + " node at " + path);

            Map<String, Object> annotations = new LinkedHashMap<>();
            annotations.put("audience", List.of("assistant"));
            resource.put("annotations", annotations);

            resources.add(resource);
        }

        Map<String, Object> result = Map.of("resources", resources);
        log.debug("[McpServer] Listed {} resources", resources.size());
        return McpResponse.success(req.getId(), objectMapper.valueToTree(result));
    }

    @SuppressWarnings("unchecked")
    private McpResponse handleResourcesRead(McpRequest req) {
        JsonNode paramsNode = req.getParams();
        if (paramsNode == null || !paramsNode.has("uri")) {
            return McpResponse.error(req.getId(), McpError.INVALID_PARAMS, "Missing 'uri' parameter");
        }

        String uri = paramsNode.get("uri").asText();
        if (!uri.startsWith("vfs://")) {
            return McpResponse.error(req.getId(), McpError.INVALID_PARAMS, "Invalid URI scheme: " + uri);
        }

        String path = uri.substring("vfs://".length());
        Optional<VfsNode> nodeOpt = VfsManager.instance().resolve(path);

        if (nodeOpt.isEmpty()) {
            return McpResponse.error(req.getId(), McpError.INVALID_PARAMS, "Resource not found: " + uri);
        }

        VfsNode node = nodeOpt.get();
        String content = node.read();

        Map<String, Object> textContent = new LinkedHashMap<>();
        textContent.put("uri", uri);
        textContent.put("mimeType", "text/plain");
        textContent.put("text", content != null ? content : "");

        Map<String, Object> result = Map.of("contents", List.of(textContent));
        return McpResponse.success(req.getId(), objectMapper.valueToTree(result));
    }

    private McpResponse handleToolsList(McpRequest req) {
        Map<String, Object> result = Map.of("tools", List.copyOf(registeredTools));
        log.debug("[McpServer] Listed {} tools", registeredTools.size());
        return McpResponse.success(req.getId(), objectMapper.valueToTree(result));
    }

    @SuppressWarnings("unchecked")
    private McpResponse handleToolsCall(McpRequest req) {
        JsonNode paramsNode = req.getParams();
        if (paramsNode == null || !paramsNode.has("name")) {
            return McpResponse.error(req.getId(), McpError.INVALID_PARAMS, "Missing 'name' parameter");
        }

        String toolName = paramsNode.get("name").asText();
        Map<String, Object> arguments = new HashMap<>();
        if (paramsNode.has("arguments")) {
            arguments = objectMapper.convertValue(paramsNode.get("arguments"), Map.class);
        }

        log.info("[McpServer] Tool call: name={}, args={}", toolName, arguments.keySet());

        return switch (toolName) {
            case "execute_wasm_sandbox" -> handleWasmExecution(req, arguments);
            case "vfs_read" -> handleVfsRead(req, arguments);
            case "vfs_write" -> handleVfsWrite(req, arguments);
            default -> McpResponse.error(req.getId(), McpError.METHOD_NOT_FOUND,
                    "Unknown tool: " + toolName);
        };
    }

    private McpResponse handleWasmExecution(McpRequest req, Map<String, Object> arguments) {
        if (wasmSandbox == null) {
            return McpResponse.error(req.getId(), McpError.INTERNAL_ERROR,
                    "WASM sandbox not initialized");
        }

        String wasmBase64 = (String) arguments.get("wasm_base64");
        String functionName = (String) arguments.getOrDefault("function_name", "main");

        if (wasmBase64 == null || wasmBase64.isEmpty()) {
            return McpResponse.error(req.getId(), McpError.INVALID_PARAMS,
                    "Missing 'wasm_base64' parameter");
        }

        try {
            byte[] wasmBytes = Base64.getDecoder().decode(wasmBase64);
            Value result = wasmSandbox.execute(wasmBytes, functionName);

            String resultStr;
            if (result.isNumber()) {
                resultStr = String.valueOf(result.asInt());
            } else if (result.isString()) {
                resultStr = result.asString();
            } else {
                resultStr = result.toString();
            }

            Map<String, Object> textContent = Map.of(
                    "type", "text",
                    "text", "WASM execution result (" + functionName + "): " + resultStr
            );

            log.info("[McpServer] WASM executed: function={}, result={}", functionName, resultStr);
            return McpResponse.success(req.getId(), objectMapper.valueToTree(Map.of(
                    "content", List.of(textContent),
                    "isError", false
            )));

        } catch (IllegalArgumentException e) {
            return McpResponse.error(req.getId(), McpError.INVALID_PARAMS,
                    "Invalid base64 WASM data: " + e.getMessage());
        } catch (Exception e) {
            log.error("[McpServer] WASM 执行失败: {}", e.getMessage());
            Map<String, Object> errorContent = Map.of(
                    "type", "text",
                    "text", "WASM execution error: " + e.getMessage()
            );
            return McpResponse.success(req.getId(), objectMapper.valueToTree(Map.of(
                    "content", List.of(errorContent),
                    "isError", true
            )));
        }
    }

    private McpResponse handleVfsRead(McpRequest req, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        if (path == null) {
            return McpResponse.error(req.getId(), McpError.INVALID_PARAMS, "Missing 'path' parameter");
        }

        Optional<VfsNode> nodeOpt = VfsManager.instance().resolve(path);
        if (nodeOpt.isEmpty()) {
            Map<String, Object> errorContent = Map.of(
                    "type", "text",
                    "text", "VFS node not found: " + path
            );
            return McpResponse.success(req.getId(), objectMapper.valueToTree(Map.of(
                    "content", List.of(errorContent),
                    "isError", true
            )));
        }

        String content = nodeOpt.get().read();
        Map<String, Object> textContent = Map.of(
                "type", "text",
                "text", content != null ? content : ""
        );
        return McpResponse.success(req.getId(), objectMapper.valueToTree(Map.of(
                "content", List.of(textContent),
                "isError", false
        )));
    }

    private McpResponse handleVfsWrite(McpRequest req, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        String data = (String) arguments.get("data");
        if (path == null || data == null) {
            return McpResponse.error(req.getId(), McpError.INVALID_PARAMS, "Missing 'path' or 'data' parameter");
        }

        Optional<VfsNode> nodeOpt = VfsManager.instance().resolve(path);
        if (nodeOpt.isEmpty()) {
            Map<String, Object> errorContent = Map.of(
                    "type", "text",
                    "text", "VFS node not found: " + path
            );
            return McpResponse.success(req.getId(), objectMapper.valueToTree(Map.of(
                    "content", List.of(errorContent),
                    "isError", true
            )));
        }

        boolean success = nodeOpt.get().write(data);
        Map<String, Object> textContent = Map.of(
                "type", "text",
                "text", "VFS write to " + path + ": " + (success ? "SUCCESS" : "FAILED")
        );
        return McpResponse.success(req.getId(), objectMapper.valueToTree(Map.of(
                "content", List.of(textContent),
                "isError", !success
        )));
    }

    private McpResponse handlePing(McpRequest req) {
        return McpResponse.success(req.getId(), objectMapper.valueToTree(Map.of()));
    }

    public String handleRawJson(String json) {
        try {
            McpRequest request = objectMapper.readValue(json, McpRequest.class);
            McpResponse response = handleRequest(request);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("[McpServer] 解析请求失败: {}", e.getMessage());
            try {
                return objectMapper.writeValueAsString(
                        McpResponse.error(null, McpError.PARSE_ERROR, "Parse error: " + e.getMessage()));
            } catch (Exception ignored) {
                return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}";
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, VfsNode> getPathTree() {
        try {
            var field = VfsManager.class.getDeclaredField("pathTree");
            field.setAccessible(true);
            return (Map<String, VfsNode>) field.get(VfsManager.instance());
        } catch (Exception e) {
            log.warn("[McpServer] 无法访问 VfsManager pathTree: {}", e.getMessage());
            return Map.of();
        }
    }

    private String extractName(String path) {
        if (path == null || path.isEmpty()) return "/";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return path;
        String name = path.substring(lastSlash + 1);
        return name.isEmpty() ? path : name;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
