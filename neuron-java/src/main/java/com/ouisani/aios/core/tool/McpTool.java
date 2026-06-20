package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.mcp.McpClientRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * MCP 工具调用 — 通过 MCP (Model Context Protocol) 协议调用外部工具。
 * <p>
 * 查找 McpClientRegistry 中已注册的 MCP 服务器，将工具调用请求
 * 路由到目标服务器并返回结果。
 * <p>
 * OS 类比：相当于 Linux 的 ioctl 系统调用 — 对已注册的设备驱动
 * （MCP 服务器）发送控制命令（工具调用），由驱动程序负责具体执行。
 * <p>
 * 调用流程：
 * 1. 根据 server_name 在 McpClientRegistry 中查找连接
 * 2. 将 JSON 字符串格式的 arguments 解析为 Map
 * 3. 调用 McpClientRegistry.callTool() 执行工具
 * 4. 返回执行结果
 */
public class McpTool implements Tool<McpTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(McpTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 工具输入参数。
     *
     * @param server_name MCP 服务器名称（在 McpClientRegistry 中注册的标识）
     * @param tool_name   要调用的工具名称（MCP 服务器暴露的工具名）
     * @param arguments   工具调用参数，JSON 字符串格式
     */
    public record Input(
            String server_name,
            String tool_name,
            String arguments
    ) implements ToolInput {

        public Input {
            if (server_name == null || server_name.isBlank()) {
                throw new IllegalArgumentException("server_name 不能为空");
            }
            if (tool_name == null || tool_name.isBlank()) {
                throw new IllegalArgumentException("tool_name 不能为空");
            }
            if (arguments == null) {
                arguments = "{}";
            }
        }

        @Override
        public String toJson() {
            return "{\"server_name\":\"" + server_name.replace("\"", "\\\"")
                    + "\",\"tool_name\":\"" + tool_name.replace("\"", "\\\"")
                    + "\",\"arguments\":" + arguments + "}";
        }
    }

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "MCP (Model Context Protocol) 工具调用";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\","
                + "\"properties\":{"
                + "\"server_name\":{\"type\":\"string\",\"description\":\"MCP 服务器名称\"},"
                + "\"tool_name\":{\"type\":\"string\",\"description\":\"要调用的 MCP 工具名称\"},"
                + "\"arguments\":{\"type\":\"string\",\"description\":\"工具调用参数（JSON 字符串格式）\"}"
                + "},"
                + "\"required\":[\"server_name\",\"tool_name\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            McpClientRegistry registry = McpClientRegistry.instance();

            // ── 检查服务器是否已注册 ──
            if (!registry.hasServer(input.server_name())) {
                String available = String.join(", ", registry.serverNames());
                return ToolOutput.fail("MCP 服务器 '" + input.server_name() + "' 未注册。"
                        + "可用服务器: [" + available + "]");
            }

            // ── 检查服务器连接状态 ──
            var connOpt = registry.getConnection(input.server_name());
            if (connOpt.isPresent()) {
                var state = connOpt.get().state();
                if (state != McpClientRegistry.ConnectionState.CONNECTED) {
                    return ToolOutput.fail("MCP 服务器 '" + input.server_name()
                            + "' 未连接，当前状态: " + state);
                }
            }

            // ── 解析 arguments JSON 字符串为 Map ──
            Map<String, Object> argsMap;
            try {
                argsMap = MAPPER.readValue(input.arguments(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return ToolOutput.fail("arguments JSON 解析失败: " + e.getMessage()
                        + "，原始输入: " + input.arguments());
            }

            // ── 调用 MCP 工具 ──
            log.info("[McpTool] 调用 MCP 工具: server={}, tool={}, args={}",
                    input.server_name(), input.tool_name(), argsMap.keySet());

            Object result = registry.callTool(input.server_name(), input.tool_name(), argsMap);

            // ── 处理返回结果 ──
            if (result == null) {
                return ToolOutput.ok("MCP 工具调用完成，无返回值");
            }

            // 检查结果中是否包含错误
            if (result instanceof Map<?, ?> map) {
                Object error = map.get("error");
                if (error != null) {
                    return ToolOutput.fail("MCP 工具调用失败: " + error);
                }
            }

            // 将结果序列化为文本
            String resultText;
            try {
                resultText = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            } catch (Exception e) {
                resultText = result.toString();
            }

            return ToolOutput.ok(resultText);

        } catch (Exception e) {
            log.error("[McpTool] MCP 工具调用异常", e);
            return ToolOutput.fail("MCP 工具调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean readOnly() {
        // MCP 工具可能是读操作也可能是写操作，保守起见返回 false
        return false;
    }

    @Override
    public String prompt() {
        return "使用 mcp 工具调用 MCP (Model Context Protocol) 服务器上的工具。"
                + "需要指定 server_name（服务器名称）、tool_name（工具名称）和 arguments（JSON 格式参数）。"
                + "确保目标 MCP 服务器已注册且处于连接状态。";
    }
}
