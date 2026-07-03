package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.mcp.McpClientRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 动态 MCP 工具 — 将单个 MCP 服务器工具包装为带强类型 I/O 契约的 Tool。
 * <p>
 * 借鉴 Apix 的 {@code mcp_tool.py} 中将 MCP 工具视为普通 Tool 挂载的设计，
 * 并增强为<b>每个 MCP 工具独立注册</b>到 ToolRegistry，携带 I/O 端口契约。
 * <p>
 * <b>与 {@link McpTool} 的区别</b>：
 * <ul>
 *   <li>{@code McpTool} — 统一调度器（黑盒），所有 MCP 调用走一个 "mcp" 工具入口，
 *       LLM 需要指定 server_name + tool_name，无 I/O 契约</li>
 *   <li>{@code DynamicMcpTool} — 每个 MCP 工具独立注册为 {@code mcp__{server}__{tool}}，
 *       从 inputSchema 自动推导 InputPort，LLM 可像调用原生工具一样调用，
 *       支持 DAG 流水线类型校验</li>
 * </ul>
 * <p>
 * <b>I/O 契约推导</b>：
 * <ul>
 *   <li>InputPort：从 MCP 工具的 {@code inputSchema.properties} 自动推导，
 *       每个属性对应一个 Port，dataType 映射为 DataTypes 中的标准类型</li>
 *   <li>OutputPort：单端口 {@code result}，dataType 为 {@code any}（MCP 不定义输出 schema）</li>
 * </ul>
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code modprobe} — 加载内核模块（MCP 工具）后，
 * 模块自动注册自己的设备接口（I/O 端口），用户态程序通过标准接口调用。
 *
 * @see McpToolBridge
 * @see McpTool
 */
public class DynamicMcpTool implements Tool<DynamicMcpTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(DynamicMcpTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String toolName;
    private final String fullToolName;
    private final String serverName;
    private final String description;
    private final String inputSchemaJson;
    private final List<Port> inputPorts;
    private final List<Port> outputPorts;

    /**
     * 创建动态 MCP 工具。
     *
     * @param serverName     MCP 服务器名
     * @param toolName       工具名（MCP 服务器暴露的名称）
     * @param description    工具描述
     * @param inputSchemaJson 输入 schema（JSON Schema 格式字符串）
     */
    public DynamicMcpTool(String serverName, String toolName, String description, String inputSchemaJson) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.fullToolName = "mcp__" + serverName + "__" + toolName;
        this.description = description != null ? description : "MCP tool: " + toolName;
        this.inputSchemaJson = inputSchemaJson != null ? inputSchemaJson : "{}";
        this.inputPorts = deriveInputPorts(this.inputSchemaJson);
        this.outputPorts = List.of(new Port("result", DataTypes.ANY, "MCP 工具返回结果"));
    }

    /**
     * 从 JSON Schema 推导输入端口 — 将每个 property 映射为一个 Port。
     */
    private static List<Port> deriveInputPorts(String schemaJson) {
        List<Port> ports = new ArrayList<>();
        try {
            JsonNode schema = MAPPER.readTree(schemaJson);
            JsonNode properties = schema.path("properties");
            JsonNode required = schema.path("required");

            if (properties.isObject()) {
                properties.fields().forEachRemaining(entry -> {
                    String propName = entry.getKey();
                    JsonNode propDef = entry.getValue();
                    String propType = mapJsonSchemaType(propDef.path("type").asText("string"));
                    String propDesc = propDef.path("description").asText("");
                    boolean isRequired = required.isArray() && containsText(required, propName);
                    ports.add(new Port(propName, propType, propDesc, isRequired));
                });
            }
        } catch (Exception e) {
            log.warn("[DynamicMcpTool] 输入端口推导失败，使用通用端口: error={}", e.getMessage());
            ports.add(new Port("arguments", DataTypes.ANY, "工具参数（JSON）"));
        }
        return ports;
    }

    /** JSON Schema 类型 → DataTypes 映射 */
    private static String mapJsonSchemaType(String jsonType) {
        return switch (jsonType) {
            case "string" -> DataTypes.PLAIN_TEXT;
            case "number", "integer" -> DataTypes.PLAIN_TEXT;
            case "boolean" -> DataTypes.PLAIN_TEXT;
            case "object" -> DataTypes.JSON_DATA;
            case "array" -> DataTypes.STRING_LIST;
            default -> DataTypes.ANY;
        };
    }

    private static boolean containsText(JsonNode array, String text) {
        for (JsonNode node : array) {
            if (text.equals(node.asText())) return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  Tool 接口实现
    // ════════════════════════════════════════════════════════════════

    @Override
    public String name() {
        return fullToolName;
    }

    @Override
    public String description() {
        return description + " (via MCP server: " + serverName + ")";
    }

    @Override
    public String inputSchema() {
        return inputSchemaJson;
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            McpClientRegistry registry = McpClientRegistry.instance();

            if (!registry.hasServer(serverName)) {
                return ToolOutput.fail("MCP 服务器 '" + serverName + "' 未注册");
            }

            // 解析参数 JSON
            Map<String, Object> argsMap;
            try {
                argsMap = MAPPER.readValue(input.arguments(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return ToolOutput.fail("参数 JSON 解析失败: " + e.getMessage());
            }

            log.info("[DynamicMcpTool] 调用 MCP 工具: server={}, tool={}", serverName, toolName);

            Object result = registry.callTool(serverName, toolName, argsMap);

            if (result == null) {
                return ToolOutput.ok("MCP 工具调用完成，无返回值");
            }

            String resultText;
            try {
                resultText = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            } catch (Exception e) {
                resultText = result.toString();
            }

            return ToolOutput.ok(resultText);

        } catch (Exception e) {
            log.error("[DynamicMcpTool] MCP 工具调用异常: tool={}", fullToolName, e);
            return ToolOutput.fail("MCP 工具调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String prompt() {
        return "MCP 工具 '" + toolName + "' (服务器: " + serverName + ")。"
                + " 参数格式: JSON 字符串，包含工具所需的输入参数。";
    }

    // ── 强类型 I/O 契约 ──

    @Override
    public List<Port> inputPorts() {
        return inputPorts;
    }

    @Override
    public List<Port> outputPorts() {
        return outputPorts;
    }

    /**
     * 工具输入参数。
     *
     * @param arguments JSON 字符串格式的工具参数
     */
    public record Input(String arguments) implements ToolInput {
        public Input {
            if (arguments == null) arguments = "{}";
        }

        @Override
        public String toJson() {
            return "{\"arguments\":" + arguments + "}";
        }
    }

    /** 获取 MCP 服务器名 */
    public String serverName() {
        return serverName;
    }

    /** 获取原始工具名 */
    public String originalToolName() {
        return toolName;
    }
}
