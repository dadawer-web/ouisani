package com.ouisani.aios.core.network;

import com.ouisani.aios.core.a2a.A2aFederation;
import com.ouisani.aios.core.a2a.A2aMessage;
import com.ouisani.aios.core.a2a.A2aNodeDescriptor;
import com.ouisani.aios.core.a2a.A2aProtocol;
import com.ouisani.aios.core.trace.TraceSpan;
import com.ouisani.aios.core.trace.TracingManager;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 管理类路由集合 — 从 AppGateway 抽取的后台管理与可观测性 API。
 * <p>
 * 包含五类路由：
 * <ul>
 *   <li>A2A 通信协议端点 — 跨节点 Agent 消息传递</li>
 *   <li>MCP 服务器管理 — 挂载/卸载/列举 MCP 服务</li>
 *   <li>Skill 动态插拔 — 运行时激活/停用行为准则技能</li>
 *   <li>Tracing Span 管理 — 查询/刷新/状态</li>
 *   <li>Handoff 管理 — LLM 驱动的 Agent 切换</li>
 * </ul>
 * 所有路由均为无状态，不依赖 AppGateway 实例状态。
 * <p>
 * OS 类比：Linux 的 /proc/sys 接口 — 内核管理子系统的统一暴露点。
 */
final class ManagementRoutes {

    private static final Logger log = LoggerFactory.getLogger(ManagementRoutes.class);

    private ManagementRoutes() {}

    /**
     * 挂载所有管理类路由到 Javalin 应用。
     */
    static void attachTo(Javalin app) {
        // A2A 通信协议端点
        // ════════════════════════════════════════════════════════════════
        //  A2A 通信协议端点（借鉴 Agent Zero 的 A2A 协议）
        // ════════════════════════════════════════════════════════════════
        app.post(A2aProtocol.HTTP_ENDPOINT, ctx -> {
            String body = ctx.body();
            A2aMessage message = A2aMessage.fromJson(body);
            if (message != null) {
                A2aFederation.getInstance().handleIncomingMessage(message);
                ctx.result("{\"status\":\"received\"}");
            } else {
                ctx.status(400).result("{\"error\":\"Invalid A2A message\"}");
            }
        });

        app.get(A2aProtocol.DISCOVERY_ENDPOINT, ctx -> {
            Collection<A2aNodeDescriptor> nodes = A2aFederation.getInstance().getRemoteNodes();
            StringBuilder sb = new StringBuilder("{\"localNodeId\":\"")
                    .append(A2aFederation.getInstance().getLocalNodeId())
                    .append("\",\"remoteNodes\":[");
            boolean first = true;
            for (A2aNodeDescriptor node : nodes) {
                if (!first) sb.append(",");
                sb.append("{\"nodeId\":\"").append(node.getNodeId())
                  .append("\",\"endpoint\":\"").append(node.getEndpoint())
                  .append("\",\"capabilities\":").append(node.getCapabilities())
                  .append(",\"agents\":").append(node.getAvailableAgents().size())
                  .append("}");
                first = false;
            }
            sb.append("]}");
            ctx.contentType("application/json");
            ctx.result(sb.toString());
        });

        app.post("/api/a2a/register", ctx -> {
            String body = ctx.body();
            String nodeId = GatewayJsonParser.extractJsonField(body, "nodeId");
            String endpoint = GatewayJsonParser.extractJsonField(body, "endpoint");
            if (nodeId == null || endpoint == null) {
                ctx.status(400).result("{\"error\":\"Missing nodeId or endpoint\"}");
                return;
            }
            A2aNodeDescriptor descriptor = new A2aNodeDescriptor(nodeId, endpoint);
            A2aFederation.getInstance().registerRemoteNode(descriptor);
            ctx.result("{\"status\":\"registered\",\"nodeId\":\"" + nodeId + "\"}");
        });

        log.info("[App Gateway] A2A 通信协议端点已挂载");
        System.out.println("  ✓ [App Gateway] A2A 通信协议端点: /api/a2a/message, /api/a2a/discovery, /api/a2a/register");

        // MCP 服务器管理 API
        // ════════════════════════════════════════════════════════════════
        //  MCP 服务器管理 API — 列出/挂载/卸载 MCP 服务器
        // ════════════════════════════════════════════════════════════════

        // GET /api/mcp/servers — 列出已注册的 MCP 服务器
        app.get("/api/mcp/servers", ctx -> {
            var connections = com.ouisani.aios.core.mcp.McpClientRegistry.getInstance().allConnections();
            StringBuilder json = new StringBuilder("{\"servers\":[");
            boolean first = true;
            for (var conn : connections) {
                if (!first) json.append(",");
                json.append(String.format(
                        "{\"name\":\"%s\",\"state\":\"%s\",\"type\":\"%s\",\"url\":\"%s\"}",
                        conn.serverName(),
                        conn.state().name(),
                        conn.config().type(),
                        conn.config().url() != null ? conn.config().url() : ""));
                first = false;
            }
            json.append("]}");
            ctx.contentType("application/json");
            ctx.result(json.toString());
        });

        app.options("/api/mcp/servers", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // POST /api/mcp/mount — 手动挂载 MCP 服务器
        // body: {"name":"...", "transport":"stdio|http|sse", "command":["..."], "args":["..."], "url":"...", "headers":{...}}
        app.post("/api/mcp/mount", ctx -> {
            String body = ctx.body();
            try {
                var jsonObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                String name = jsonObj.has("name") ? jsonObj.get("name").getAsString() : null;
                if (name == null || name.isBlank()) {
                    ctx.status(400).result("{\"error\":\"Missing 'name' field\"}")
                       .contentType("application/json");
                    return;
                }
                String transport = jsonObj.has("transport") ? jsonObj.get("transport").getAsString() : "stdio";
                com.ouisani.aios.core.mcp.McpClientRegistry registry =
                        com.ouisani.aios.core.mcp.McpClientRegistry.getInstance();

                if ("stdio".equals(transport)) {
                    List<String> command = new ArrayList<>();
                    if (jsonObj.has("command") && jsonObj.get("command").isJsonArray()) {
                        jsonObj.get("command").getAsJsonArray().forEach(n -> command.add(n.getAsString()));
                    }
                    if (jsonObj.has("args") && jsonObj.get("args").isJsonArray()) {
                        jsonObj.get("args").getAsJsonArray().forEach(n -> command.add(n.getAsString()));
                    }
                    if (command.isEmpty()) {
                        ctx.status(400).result("{\"error\":\"stdio transport requires 'command' array\"}")
                           .contentType("application/json");
                        return;
                    }
                    registry.mountServer(name, command);
                } else if ("http".equals(transport) || "sse".equals(transport)) {
                    String url = jsonObj.has("url") ? jsonObj.get("url").getAsString() : null;
                    if (url == null || url.isBlank()) {
                        ctx.status(400).result("{\"error\":\"http/sse transport requires 'url' field\"}")
                           .contentType("application/json");
                        return;
                    }
                    Map<String, String> headers = new HashMap<>();
                    if (jsonObj.has("headers") && jsonObj.get("headers").isJsonObject()) {
                        jsonObj.get("headers").getAsJsonObject().entrySet()
                                .forEach(e -> headers.put(e.getKey(), e.getValue().getAsString()));
                    }
                    registry.mountHttpServer(name, url, headers);
                } else {
                    ctx.status(400).result("{\"error\":\"Unsupported transport: " + transport + "\"}")
                       .contentType("application/json");
                    return;
                }
                ctx.result("{\"success\":true,\"name\":\"" + name + "\",\"transport\":\"" + transport + "\"}")
                   .contentType("application/json");
            } catch (Exception e) {
                ctx.status(400)
                   .result("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}")
                   .contentType("application/json");
            }
        });

        app.options("/api/mcp/mount", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // POST /api/mcp/unmount/{name} — 卸载 MCP 服务器
        app.post("/api/mcp/unmount/{name}", ctx -> {
            String name = ctx.pathParam("name");
            com.ouisani.aios.core.mcp.McpClientRegistry.getInstance().unmountServer(name);
            ctx.result("{\"success\":true,\"name\":\"" + name + "\"}")
               .contentType("application/json");
        });

        app.options("/api/mcp/unmount/{name}", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        log.info("[App Gateway] MCP 管理 API 已挂载: /api/mcp/servers, /api/mcp/mount, /api/mcp/unmount/{name}");
        System.out.println("  ✓ [App Gateway] MCP 管理 API: /api/mcp/servers, /api/mcp/mount, /api/mcp/unmount/{name}");

        // Skill 动态插拔 API
        // ════════════════════════════════════════════════════════════════
        //  Skill 动态插拔 API — 运行时激活/停用行为准则技能
        // ════════════════════════════════════════════════════════════════

        // GET /api/skills — 列出所有技能及其激活状态
        app.get("/api/skills", ctx -> {
            var allSkills = com.ouisani.aios.core.skill.SkillLoader.getCached();
            var activeNames = com.ouisani.aios.core.skill.SkillLoader.getActiveSkillNames();
            StringBuilder json = new StringBuilder("{\"skills\":[");
            boolean first = true;
            for (var entry : allSkills.entrySet()) {
                if (!first) json.append(",");
                json.append(String.format(
                        "{\"name\":\"%s\",\"description\":\"%s\",\"source\":\"%s\",\"active\":%b}",
                        entry.getKey(),
                        entry.getValue().description() != null
                                ? entry.getValue().description().replace("\"", "'") : "",
                        entry.getValue().source().name(),
                        activeNames.contains(entry.getKey())
                ));
                first = false;
            }
            json.append("]}");
            ctx.contentType("application/json");
            ctx.result(json.toString());
        });

        app.options("/api/skills", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // POST /api/skills/{name}/activate — 激活技能
        app.post("/api/skills/{name}/activate", ctx -> {
            String name = ctx.pathParam("name");
            boolean success = com.ouisani.aios.core.skill.SkillLoader.activate(name);
            ctx.contentType("application/json");
            if (success) {
                ctx.result("{\"success\":true,\"name\":\"" + name + "\",\"action\":\"activated\"}");
            } else {
                ctx.status(404).result("{\"success\":false,\"error\":\"Skill not found: " + name + "\"}");
            }
        });

        app.options("/api/skills/{name}/activate", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // POST /api/skills/{name}/deactivate — 停用技能
        app.post("/api/skills/{name}/deactivate", ctx -> {
            String name = ctx.pathParam("name");
            boolean success = com.ouisani.aios.core.skill.SkillLoader.deactivate(name);
            ctx.contentType("application/json");
            if (success) {
                ctx.result("{\"success\":true,\"name\":\"" + name + "\",\"action\":\"deactivated\"}");
            } else {
                ctx.status(404).result("{\"success\":false,\"error\":\"Skill was not active: " + name + "\"}");
            }
        });

        app.options("/api/skills/{name}/deactivate", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        log.info("[App Gateway] Skill 动态插拔 API 已挂载: /api/skills, /api/skills/{name}/activate, /api/skills/{name}/deactivate");
        System.out.println("  ✓ [App Gateway] Skill 动态插拔 API: /api/skills, /api/skills/{name}/activate, /api/skills/{name}/deactivate");

        // Tracing Span 管理 API
        // ════════════════════════════════════════════════════════════════
        //  Tracing Span 管理 API — 查询/刷新/状态
        // ════════════════════════════════════════════════════════════════

        // GET /api/tracing/spans — 获取最近的 Span 列表（JSON）
        app.get("/api/tracing/spans", ctx -> {
            List<TraceSpan> spans = TracingManager.instance().getRecentSpans();
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (TraceSpan span : spans) {
                if (!first) json.append(",");
                json.append(span.toJson());
                first = false;
            }
            json.append("]");
            ctx.contentType("application/json");
            ctx.result(json.toString());
        });

        app.options("/api/tracing/spans", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // POST /api/tracing/flush — 强制刷新 Span 缓冲区
        app.post("/api/tracing/flush", ctx -> {
            TracingManager.instance().flush();
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"flushed\",\"recentSpanCount\":"
                    + TracingManager.instance().recentSpanCount() + "}");
        });

        app.options("/api/tracing/flush", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // GET /api/tracing/status — 获取 Tracing 状态
        app.get("/api/tracing/status", ctx -> {
            TracingManager tm = TracingManager.instance();
            StringBuilder json = new StringBuilder("{");
            json.append("\"enabled\":").append(!tm.isTracingDisabled());
            json.append(",\"processorCount\":").append(tm.processorCount());
            json.append(",\"exporterCount\":").append(tm.exporterCount());
            json.append(",\"recentSpanCount\":").append(tm.recentSpanCount());
            json.append("}");
            ctx.contentType("application/json");
            ctx.result(json.toString());
        });

        app.options("/api/tracing/status", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        log.info("[App Gateway] Tracing Span 管理 API 已挂载: /api/tracing/spans, /api/tracing/flush, /api/tracing/status");
        System.out.println("  ✓ [App Gateway] Tracing Span 管理 API: /api/tracing/spans, /api/tracing/flush, /api/tracing/status");

        // Handoff 管理 API
        // ════════════════════════════════════════════════════════════════
        //  Handoff 管理 API — LLM 驱动的 Agent 切换（参考 OpenAI Agents Python Handoff）
        // ════════════════════════════════════════════════════════════════

        // GET /api/handoff/targets — 获取可用的 Handoff 目标列表
        app.get("/api/handoff/targets", ctx -> {
            var targets = com.ouisani.aios.core.tool.HandoffManager.instance().getHandoffTargets();
            StringBuilder json = new StringBuilder("{\"targets\":[");
            boolean first = true;
            for (var t : targets) {
                if (!first) json.append(",");
                json.append(String.format(
                        "{\"agentId\":\"%s\",\"role\":\"%s\",\"description\":\"%s\"}",
                        t.agentId().replace("\"", "\\\""),
                        t.role().replace("\"", "\\\""),
                        t.description().replace("\"", "\\\"")));
                first = false;
            }
            json.append("]}");
            ctx.contentType("application/json");
            ctx.result(json.toString());
        });

        app.options("/api/handoff/targets", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // GET /api/handoff/history — 获取 Handoff 历史记录
        app.get("/api/handoff/history", ctx -> {
            var history = com.ouisani.aios.core.tool.HandoffManager.instance().getHandoffHistory();
            StringBuilder json = new StringBuilder("{\"history\":[");
            boolean first = true;
            for (var r : history) {
                if (!first) json.append(",");
                json.append(String.format(
                        "{\"source\":\"%s\",\"target\":\"%s\",\"reason\":\"%s\",\"contextSummary\":\"%s\",\"timestamp\":%d}",
                        r.source().replace("\"", "\\\""),
                        r.target().replace("\"", "\\\""),
                        r.reason().replace("\"", "\\\""),
                        r.contextSummary().replace("\"", "\\\"").replace("\n", " "),
                        r.timestamp()));
                first = false;
            }
            json.append("]}");
            ctx.contentType("application/json");
            ctx.result(json.toString());
        });

        app.options("/api/handoff/history", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // POST /api/handoff/register — 注册新的 Handoff 目标
        // body: {"agentId":"...", "role":"...", "description":"..."}
        app.post("/api/handoff/register", ctx -> {
            String body = ctx.body();
            try {
                var jsonObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                String agentId = jsonObj.has("agentId") ? jsonObj.get("agentId").getAsString() : null;
                if (agentId == null || agentId.isBlank()) {
                    ctx.status(400).result("{\"error\":\"Missing 'agentId' field\"}")
                       .contentType("application/json");
                    return;
                }
                String role = jsonObj.has("role") ? jsonObj.get("role").getAsString() : "";
                String description = jsonObj.has("description") ? jsonObj.get("description").getAsString() : "";
                com.ouisani.aios.core.tool.HandoffManager.instance()
                        .registerHandoffTarget(agentId, role, description);
                ctx.result("{\"success\":true,\"agentId\":\"" + agentId + "\"}")
                   .contentType("application/json");
            } catch (Exception e) {
                ctx.status(400)
                   .result("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}")
                   .contentType("application/json");
            }
        });

        app.options("/api/handoff/register", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        log.info("[App Gateway] Handoff 管理 API 已挂载: /api/handoff/targets, /api/handoff/history, /api/handoff/register");
        System.out.println("  ✓ [App Gateway] Handoff 管理 API: /api/handoff/targets, /api/handoff/history, /api/handoff/register");
    }
}
