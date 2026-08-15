package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.mcp.McpClientRegistry;
import com.ouisani.aios.core.mcp.McpClientRegistry.McpToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具桥接器 — 发现 MCP 服务器工具并动态注册为带 I/O 契约的 Tool。
 * <p>
 * 借鉴 Apix 的 {@code mcp_tool.py} 中 {@code load_all_mcp_tools} 的设计，
 * 适配 Java ToolRegistry 架构。核心能力：
 * <ul>
 *   <li><b>工具发现</b>：从已连接的 MCP 服务器获取 {@code tools/list}，发现所有可用工具</li>
 *   <li><b>动态注册</b>：将每个 MCP 工具包装为 {@link DynamicMcpTool}，
 *       从 inputSchema 自动推导 I/O 端口，注册到 {@link ToolRegistry}</li>
 *   <li><b>强类型契约</b>：注册的工具携带 InputPort/OutputPort，
 *       可参与 DAG 流水线类型校验（与 WorkflowNode 的 Port 同构）</li>
 * </ul>
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code udev} — 内核检测到新设备（MCP 服务器）后，
 * 自动加载设备驱动（DynamicMcpTool），创建设备节点（ToolRegistry 注册项），
 * 用户态程序通过标准接口（Tool 调用）使用设备。
 * <p>
 * <b>使用方式</b>：
 * <pre>{@code
 * // 发现并注册所有已连接 MCP 服务器的工具
 * McpToolBridge.instance().discoverAndRegisterAll();
 *
 * // 注册特定服务器的工具
 * McpToolBridge.instance().discoverAndRegister("weather");
 *
 * // 注销特定服务器的工具
 * McpToolBridge.instance().unregisterServer("weather");
 * }</pre>
 *
 * @see DynamicMcpTool
 * @see ToolRegistry
 * @see McpClientRegistry
 */
public class McpToolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    private static final class Holder {
        static final McpToolBridge INSTANCE = new McpToolBridge();
    }

    public static McpToolBridge instance() {
        return Holder.INSTANCE;
    }

    /** serverName → 已注册的工具全名集合（用于注销时查找） */
    private final ConcurrentHashMap<String, Set<String>> registeredTools = new ConcurrentHashMap<>();

    private McpToolBridge() {}

    /**
     * 发现并注册所有已连接 MCP 服务器的工具。
     * <p>
     * 遍历 McpClientRegistry 中所有已连接的服务器，
     * 为每个服务器的每个工具创建 DynamicMcpTool 并注册到 ToolRegistry。
     *
     * @return 总共注册的工具数量
     */
    public int discoverAndRegisterAll() {
        int total = 0;
        for (String serverName : McpClientRegistry.instance().serverNames()) {
            total += discoverAndRegister(serverName);
        }
        if (total > 0) {
            log.info("[McpToolBridge] 共注册 {} 个 MCP 工具（跨 {} 个服务器）",
                    total, registeredTools.size());
        }
        return total;
    }

    /**
     * 发现并注册指定 MCP 服务器的工具。
     *
     * @param serverName MCP 服务器名
     * @return 注册的工具数量
     */
    public int discoverAndRegister(String serverName) {
        McpClientRegistry registry = McpClientRegistry.instance();

        if (!registry.hasServer(serverName)) {
            log.warn("[McpToolBridge] 服务器 '{}' 未注册，跳过", serverName);
            return 0;
        }

        // 获取服务器的工具列表
        List<McpToolDef> tools;
        try {
            var connOpt = registry.getConnection(serverName);
            if (connOpt.isEmpty()) {
                log.warn("[McpToolBridge] 服务器 '{}' 无连接信息", serverName);
                return 0;
            }
            tools = connOpt.get().tools();
        } catch (Exception e) {
            log.warn("[McpToolBridge] 获取服务器 '{}' 工具列表失败: {}", serverName, e.getMessage());
            return 0;
        }

        if (tools.isEmpty()) {
            log.debug("[McpToolBridge] 服务器 '{}' 无可用工具", serverName);
            return 0;
        }

        // 先注销旧工具（避免重复注册）
        unregisterServer(serverName);

        Set<String> registered = ConcurrentHashMap.newKeySet();
        int count = 0;

        for (McpToolDef toolDef : tools) {
            try {
                DynamicMcpTool tool = new DynamicMcpTool(
                        serverName,
                        toolDef.name(),
                        toolDef.description(),
                        toolDef.inputSchema()
                );

                ToolRegistry.instance().register(tool);
                registered.add(tool.name());
                count++;

                log.debug("[McpToolBridge] 已注册 MCP 工具: {} (server={}, ports={})",
                        tool.name(), serverName, tool.inputPorts().size());
            } catch (Exception e) {
                log.warn("[McpToolBridge] 注册工具失败: server={}, tool={}, error={}",
                        serverName, toolDef.name(), e.getMessage());
            }
        }

        registeredTools.put(serverName, registered);
        log.info("[McpToolBridge] 服务器 '{}' 注册了 {} 个 MCP 工具", serverName, count);
        return count;
    }

    /**
     * 注销指定 MCP 服务器的所有工具。
     *
     * @param serverName MCP 服务器名
     * @return 注销的工具数量
     */
    public int unregisterServer(String serverName) {
        Set<String> tools = registeredTools.remove(serverName);
        if (tools == null || tools.isEmpty()) {
            return 0;
        }

        for (String toolName : tools) {
            ToolRegistry.instance().unregister(toolName);
        }

        log.info("[McpToolBridge] 服务器 '{}' 的 {} 个工具已注销", serverName, tools.size());
        return tools.size();
    }

    /**
     * 注销所有已注册的 MCP 工具。
     */
    public void unregisterAll() {
        List<String> servers = new ArrayList<>(registeredTools.keySet());
        for (String server : servers) {
            unregisterServer(server);
        }
    }

    /**
     * 获取指定服务器已注册的工具名列表。
     *
     * @param serverName MCP 服务器名
     * @return 工具全名列表
     */
    public Set<String> getRegisteredToolNames(String serverName) {
        return registeredTools.getOrDefault(serverName, Set.of());
    }

    /**
     * 获取所有已注册工具的 MCP 服务器名列表。
     *
     * @return 服务器名列表
     */
    public Set<String> getRegisteredServers() {
        return Set.copyOf(registeredTools.keySet());
    }

    /**
     * 判断指定工具是否为动态注册的 MCP 工具。
     *
     * @param toolName 工具全名
     * @return 是动态 MCP 工具返回 true
     */
    public boolean isDynamicMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }
}
