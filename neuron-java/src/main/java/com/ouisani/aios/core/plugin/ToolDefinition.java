package com.ouisani.aios.core.plugin;

import java.util.Map;

/**
 * 工具定义 — 动态可加载工具（内核模块）的 JSON Schema 描述符。
 * <p>
 * 在传统 OS 中，每个内核模块暴露一组符号（函数、ioctl）供用户空间调用。
 * 在 AIOS 中，每个工具模块暴露一个 {@link ToolDefinition}，描述：
 * <ul>
 *   <li><b>name</b> — 模块的 syscall 名称（如 "web_search"）</li>
 *   <li><b>description</b> — 自然语言描述，供 LLM 理解工具用途</li>
 *   <li><b>parameters</b> — 工具输入参数的 JSON Schema</li>
 *   <li><b>type</b> — 执行后端类型（WASM、MCP、Docker、Native）</li>
 *   <li><b>tokenCost</b> — 加载到上下文窗口时的预估 Token 开销</li>
 * </ul>
 * <p>
 * <h3>OS 类比: /proc/modules</h3>
 * 就像 {@code cat /proc/modules} 列出所有已加载内核模块及其大小和依赖数，
 * {@code ToolDefinition} 是 Agent 查询其活跃工具链时出现的元数据条目。
 * <p>
 * <h3>Token 经济学</h3>
 * 每个已加载的工具消耗 LLM 上下文窗口中的 Token（其 JSON Schema 必须注入到 prompt 中）。
 * {@code tokenCost} 字段使 Agent 能够做出明智的工具加载决策——类似于内核跟踪模块内存占用。
 *
 * @see PluginManager
 * @see AgentToolContext
 */
public record ToolDefinition(
        /** Unique tool name (e.g., "web_search", "image_gen", "mcp.weather.get_forecast"). */
        String name,

        /** Natural language description — used for semantic matching and LLM prompt. */
        String description,

        /** JSON Schema of input parameters (Function Calling format). */
        Map<String, Object> parameters,

        /** Execution backend type. */
        ToolType type,

        /** Estimated token overhead when loaded into LLM context. */
        int tokenCost,

        /** Source identifier (e.g., "wasm:math", "mcp:weather", "docker:python"). */
        String source
) {
    /**
     * Tool execution backend type.
     */
    public enum ToolType {
        /** Local WASM plugin (insmod from .wasm file). */
        WASM,
        /** Remote MCP server tool. */
        MCP,
        /** Docker sandbox execution. */
        DOCKER,
        /** Native Java tool (built-in kernel syscall). */
        NATIVE
    }

    /**
     * Create a minimal tool definition with defaults.
     */
    public ToolDefinition {
        if (parameters == null) parameters = Map.of();
        if (type == null) type = ToolType.NATIVE;
        if (source == null) source = type.name().toLowerCase() + ":" + name;
        if (tokenCost <= 0) tokenCost = estimateTokenCost(description, parameters);
    }

    /**
     * Generate the Function Calling JSON fragment for this tool.
     * <p>
     * This is the format that gets injected into the LLM's prompt
     * when the tool is loaded via sys_insmod.
     */
    public String toFunctionSchema() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(name).append("\"");
        sb.append(",\"description\":\"").append(escapeJson(description)).append("\"");

        if (!parameters.isEmpty()) {
            sb.append(",\"parameters\":{");
            sb.append("\"type\":\"object\",\"properties\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":");
                sb.append(valueToJson(entry.getValue()));
                first = false;
            }
            sb.append("}}");
        }
        sb.append("}");
        return sb.toString();
    }

    private static int estimateTokenCost(String desc, Map<String, Object> params) {
        // Rough estimate: ~4 chars per token
        int descTokens = desc != null ? desc.length() / 4 : 0;
        int paramTokens = params.size() * 10; // ~10 tokens per parameter
        return descTokens + paramTokens + 20; // overhead for JSON structure
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static String valueToJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":").append(valueToJson(e.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        return "\"" + escapeJson(v.toString()) + "\"";
    }
}
