package com.ouisani.aios.core.plugin;

import java.util.Map;

/**
 * Tool Definition — the JSON Schema descriptor for a dynamically
 * loadable tool (kernel module).
 * <p>
 * In a traditional OS, each kernel module exposes a set of symbols
 * (functions, ioctls) that user-space can call. In AIOS, each tool
 * module exposes a {@link ToolDefinition} that describes:
 * <ul>
 *   <li><b>name</b> — the module's syscall name (e.g., "web_search")</li>
 *   <li><b>description</b> — natural language description for LLM understanding</li>
 *   <li><b>parameters</b> — JSON Schema of the tool's input parameters</li>
 *   <li><b>type</b> — the execution backend (WASM, MCP, Docker, Native)</li>
 *   <li><b>tokenCost</b> — estimated token overhead when loaded into context</li>
 * </ul>
 * <p>
 * <h3>OS Analogy: /proc/modules</h3>
 * Just as {@code cat /proc/modules} lists all loaded kernel modules
 * with their size and dependency count, a {@code ToolDefinition} is
 * the metadata entry that appears when an Agent queries its active
 * tool chain.
 * <p>
 * <h3>Token Economy</h3>
 * Each loaded tool consumes tokens in the LLM's context window
 * (its JSON Schema must be included in the prompt). The
 * {@code tokenCost} field enables the Agent to make informed
 * decisions about which tools to keep loaded — analogous to how
 * a kernel tracks module memory footprint.
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
