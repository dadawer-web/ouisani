package com.ouisani.aios.core.network;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HITL 与状态同步路由 — 从 AppGateway 抽取的前端交互通道。
 * <p>
 * 包含两类路由：
 * <ul>
 *   <li>Human-in-the-Loop + FrontendTool — 借鉴 CopilotKit 的人机协作与前端工具调用</li>
 *   <li>双向状态同步 — 前端状态与 Agent 状态双向同步</li>
 * </ul>
 * 所有路由均为无状态，不依赖 AppGateway 实例状态。
 * <p>
 * OS 类比：Linux 的 /dev/input — 用户态与内核态的输入事件通道。
 */
final class HitlStateRoutes {

    private static final Logger log = LoggerFactory.getLogger(HitlStateRoutes.class);

    /** Gson 实例 — 用于状态同步通道的 JSON 序列化/反序列化 */
    private static final com.google.gson.Gson gson = new com.google.gson.Gson();

    private HitlStateRoutes() {}

    /**
     * 挂载 HITL 与状态同步路由到 Javalin 应用。
     */
    static void attachTo(Javalin app) {
        // ════════════════════════════════════════════════════════════════
        //  Human-in-the-Loop + Frontend Tool — 借鉴 CopilotKit
        // ════════════════════════════════════════════════════════════════

        // HITL: 获取待处理的人类响应请求
        app.get("/api/hitl/pending", ctx -> {
            var pending = com.ouisani.aios.core.tool.HumanResponseTool.getPendingRequests();
            StringBuilder json = new StringBuilder("{\"pending\":[");
            boolean first = true;
            for (var entry : pending.entrySet()) {
                if (!first) json.append(",");
                json.append(String.format("{\"requestId\":\"%s\",\"agentId\":\"%s\"}",
                        entry.getKey(), entry.getValue()));
                first = false;
            }
            json.append("]}");
            ctx.result(json.toString()).contentType("application/json");
        });

        // HITL: 提交人类响应
        app.post("/api/hitl/{requestId}/respond", ctx -> {
            String requestId = ctx.pathParam("requestId");
            String body = ctx.body();
            // 从 JSON 中提取 response 字段
            String response = "";
            try {
                var jsonObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                if (jsonObj.has("response")) {
                    response = jsonObj.get("response").getAsString();
                }
            } catch (Exception e) {
                response = body; // 非 JSON，直接用 body
            }

            boolean success = com.ouisani.aios.core.tool.HumanResponseTool.submitResponse(requestId, response);
            ctx.result("{\"success\":" + success + "}").contentType("application/json");
            ctx.status(success ? 200 : 404);
        });

        // FrontendTool: 注册前端工具
        app.post("/api/frontend/tool/register", ctx -> {
            String body = ctx.body();
            var jsonObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            String toolName = jsonObj.get("toolName").getAsString();
            String schema = jsonObj.has("schema") ? jsonObj.get("schema").toString() : "{}";
            com.ouisani.aios.core.tool.FrontendTool.registerFrontendTool(toolName, schema);
            ctx.result("{\"success\":true}").contentType("application/json");
        });

        // FrontendTool: 注销前端工具
        app.post("/api/frontend/tool/unregister", ctx -> {
            String body = ctx.body();
            var jsonObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            String toolName = jsonObj.get("toolName").getAsString();
            com.ouisani.aios.core.tool.FrontendTool.unregisterFrontendTool(toolName);
            ctx.result("{\"success\":true}").contentType("application/json");
        });

        // FrontendTool: 提交工具调用结果
        app.post("/api/frontend/tool/{callId}/result", ctx -> {
            String callId = ctx.pathParam("callId");
            String body = ctx.body();
            String result = "";
            try {
                var jsonObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                if (jsonObj.has("result")) {
                    result = jsonObj.get("result").getAsString();
                }
            } catch (Exception e) {
                result = body;
            }

            boolean success = com.ouisani.aios.core.tool.FrontendTool.submitResult(callId, result);
            ctx.result("{\"success\":" + success + "}").contentType("application/json");
            ctx.status(success ? 200 : 404);
        });

        // FrontendTool: 列出已注册的前端工具
        app.get("/api/frontend/tool/list", ctx -> {
            var tools = com.ouisani.aios.core.tool.FrontendTool.getRegisteredTools();
            StringBuilder json = new StringBuilder("{\"tools\":[");
            boolean first = true;
            for (var entry : tools.entrySet()) {
                if (!first) json.append(",");
                json.append(String.format("{\"name\":\"%s\",\"schema\":%s}",
                        entry.getKey(), entry.getValue()));
                first = false;
            }
            json.append("]}");
            ctx.result(json.toString()).contentType("application/json");
        });

        // WebSocket: HITL + FrontendTool 双向通道
        app.ws("/api/agent/interaction", ws -> {
            ws.onConnect(ctx -> {
                log.info("[App Gateway] Agent 交互 WebSocket 已连接: {}", ctx.sessionId());
            });

            ws.onMessage(ctx -> {
                String msg = ctx.message();
                try {
                    var jsonObj = com.google.gson.JsonParser.parseString(msg).getAsJsonObject();
                    String type = jsonObj.get("type").getAsString();

                    switch (type) {
                        case "hitl_response" -> {
                            // 人类响应
                            String requestId = jsonObj.get("requestId").getAsString();
                            String response = jsonObj.get("response").getAsString();
                            boolean success = com.ouisani.aios.core.tool.HumanResponseTool.submitResponse(requestId, response);
                            ctx.send("{\"type\":\"hitl_response_ack\",\"success\":" + success + "}");
                        }
                        case "frontend_tool_result" -> {
                            // 前端工具结果
                            String callId = jsonObj.get("callId").getAsString();
                            String result = jsonObj.get("result").getAsString();
                            boolean success = com.ouisani.aios.core.tool.FrontendTool.submitResult(callId, result);
                            ctx.send("{\"type\":\"frontend_tool_result_ack\",\"success\":" + success + "}");
                        }
                        case "register_frontend_tool" -> {
                            // 注册前端工具
                            String toolName = jsonObj.get("toolName").getAsString();
                            String schema = jsonObj.has("schema") ? jsonObj.get("schema").toString() : "{}";
                            com.ouisani.aios.core.tool.FrontendTool.registerFrontendTool(toolName, schema);
                            ctx.send("{\"type\":\"register_ack\",\"toolName\":\"" + toolName + "\"}");
                        }
                        case "unregister_frontend_tool" -> {
                            String toolName = jsonObj.get("toolName").getAsString();
                            com.ouisani.aios.core.tool.FrontendTool.unregisterFrontendTool(toolName);
                            ctx.send("{\"type\":\"unregister_ack\",\"toolName\":\"" + toolName + "\"}");
                        }
                        default -> {
                            ctx.send("{\"type\":\"error\",\"message\":\"Unknown type: " + type + "\"}");
                        }
                    }
                } catch (Exception e) {
                    log.error("[App Gateway] 交互 WebSocket 消息解析失败: {}", e.getMessage());
                    ctx.send("{\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
                }
            });

            ws.onClose(ctx -> {
                log.info("[App Gateway] Agent 交互 WebSocket 已断开: {}", ctx.sessionId());
            });
        });

        log.info("[App Gateway] HITL + FrontendTool 交互通道已挂载: /api/agent/interaction");
        System.out.println("  ✓ [App Gateway] HITL + FrontendTool 交互通道: /api/agent/interaction");

        // ════════════════════════════════════════════════════════════════
        //  双向状态同步 — 借鉴 CopilotKit 的前端状态与 Agent 状态双向同步
        // ════════════════════════════════════════════════════════════════

        // WebSocket: 状态同步通道
        app.ws("/api/state/sync", ws -> {
            ws.onConnect(ctx -> {
                String sessionId = ctx.sessionId();
                com.ouisani.aios.core.network.StateSyncChannel.instance().sessionConnected(sessionId);
                log.info("[App Gateway] 状态同步 WebSocket 已连接: {}", sessionId);
                // 发送欢迎消息
                ctx.send("{\"type\":\"connected\",\"sessionId\":\"" + sessionId + "\"}");
            });

            ws.onMessage(ctx -> {
                String msg = ctx.message();
                String sessionId = ctx.sessionId();
                try {
                    var jsonObj = com.google.gson.JsonParser.parseString(msg).getAsJsonObject();
                    String type = jsonObj.get("type").getAsString();

                    switch (type) {
                        case "state_update" -> {
                            // 前端 → Agent 状态更新
                            String key = jsonObj.get("key").getAsString();
                            Object value = gson.fromJson(jsonObj.get("value"), Object.class);
                            com.ouisani.aios.core.network.StateSyncChannel.instance()
                                    .handleFrontendStateUpdate(sessionId, key, value);
                            ctx.send("{\"type\":\"state_update_ack\",\"key\":\"" + key + "\",\"success\":true}");
                        }
                        case "get_snapshot" -> {
                            // 前端请求状态快照
                            String snapshot = com.ouisani.aios.core.network.StateSyncChannel.instance()
                                    .getSessionSnapshot(sessionId);
                            ctx.send(snapshot);
                        }
                        default -> {
                            ctx.send("{\"type\":\"error\",\"message\":\"Unknown type: " + type + "\"}");
                        }
                    }
                } catch (Exception e) {
                    log.error("[App Gateway] 状态同步消息解析失败: {}", e.getMessage());
                    ctx.send("{\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
                }
            });

            ws.onClose(ctx -> {
                String sessionId = ctx.sessionId();
                com.ouisani.aios.core.network.StateSyncChannel.instance().sessionDisconnected(sessionId);
                log.info("[App Gateway] 状态同步 WebSocket 已断开: {}", sessionId);
            });
        });

        // REST: 获取状态同步通道状态
        app.get("/api/state/status", ctx -> {
            int sessions = com.ouisani.aios.core.network.StateSyncChannel.instance().getConnectedSessionCount();
            ctx.result("{\"connectedSessions\":" + sessions + "}").contentType("application/json");
        });

        log.info("[App Gateway] 双向状态同步通道已挂载: /api/state/sync, /api/state/status");
        System.out.println("  ✓ [App Gateway] 双向状态同步通道: /api/state/sync, /api/state/status");

    }
}
