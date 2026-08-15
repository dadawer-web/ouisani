package com.ouisani.aios.core.network;

import com.ouisani.aios.core.permission.ToolPermissionChannel;
import com.ouisani.aios.core.permission.ToolPermissionChannel.ApprovalResponse;
import com.ouisani.aios.core.mission.MissionManager;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 工具权限审批流路由 — 把 {@link ToolPermissionChannel#CHANNEL} 上的 ASK 请求桥接到前端，
 * 并接收前端回填的审批决策。
 * <p>
 * <b>双向通道</b>（一个 WS 端点承担两个方向，借鉴 {@link HitlStateRoutes} 的 onMessage 分发）：
 * <ul>
 *   <li><b>后端 → 前端</b>：onConnect 时 {@link EventBus#subscribe} 订阅
 *       {@link ToolPermissionChannel#CHANNEL}，handler 把 payload 原样 {@code ctx.send} 给该客户端。
 *       此订阅使 {@code subscriberCount > 0}，激活 QueryEngine 的阻塞审批路径
 *      （无订阅时 QueryEngine fallback 自动放行，零回归）。</li>
 *   <li><b>前端 → 后端</b>：onMessage 解析 {@code {type:"permission_response", requestId, decision}}
 *       → {@link ToolPermissionChannel#respond} 唤醒阻塞的 CompletableFuture。</li>
 * </ul>
 * <p>
 * <b>生命周期</b>：每个 WS 连接在 onConnect 注册 handler，在 onClose/onError 批量
 * {@link EventBus#unsubscribe} 注销，防止连接断开后 EventBus 继续推送导致 OOM
 * （与 {@link SystemAlertRoutes} 同构）。
 * <p>
 * OS 类比：Linux 的 {@code /dev/hid} — 用户态输入设备，内核把需要人介入的事件上报，
 * 用户态回填输入后内核继续执行。
 */
final class PermissionApprovalRoutes {

    private static final Logger log = LoggerFactory.getLogger(PermissionApprovalRoutes.class);

    /** 已连接的审批流客户端 */
    private static final Set<io.javalin.websocket.WsContext> clients = ConcurrentHashMap.newKeySet();

    /** 每个会话注册的 EventBus handler，断开时注销 */
    private static final Map<String, Consumer<String>> subscriptions = new ConcurrentHashMap<>();

    private PermissionApprovalRoutes() {}

    static void attachTo(Javalin app) {
        app.ws("/api/permission/stream", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[PermissionApproval] 未授权连接被拒绝");
                    ctx.session.close();
                    return;
                }

                String sessionId = ctx.sessionId();
                clients.add(ctx);

                // 订阅审批请求通道 —— handler 闭包捕获 ctx，把 payload 转发给该客户端
                Consumer<String> handler = payload -> {
                    if (!ctx.session.isOpen()) return;
                    try {
                        recordMissionApproval(payload);
                        ctx.send(payload);
                    } catch (Exception e) {
                        log.debug("[PermissionApproval] 推送审批请求失败: {}", e.getMessage());
                    }
                };
                EventBus.instance().subscribe(ToolPermissionChannel.CHANNEL, handler);
                subscriptions.put(sessionId, handler);

                log.info("[PermissionApproval] 客户端已连接并订阅审批流。总数: {}, sessionId: {}",
                        clients.size(), sessionId);
                System.out.printf("  🔒 [PermissionApproval] 客户端已订阅工具审批流。总数: %d%n", clients.size());
            });

            ws.onMessage(ctx -> {
                String msg = ctx.message();
                try {
                    var jsonObj = com.google.gson.JsonParser.parseString(msg).getAsJsonObject();
                    String type = jsonObj.has("type") ? jsonObj.get("type").getAsString() : "";

                    if ("PING".equals(type) || (msg != null && msg.contains("\"PING\""))) {
                        ctx.send("{\"type\":\"PONG\"}");
                        return;
                    }
                    if ("permission_response".equals(type)) {
                        String requestId = jsonObj.get("requestId").getAsString();
                        String decision = jsonObj.has("decision")
                                ? jsonObj.get("decision").getAsString() : "ALLOW_ONCE";
                        String actionDigest = jsonObj.has("actionDigest") && !jsonObj.get("actionDigest").isJsonNull()
                                ? jsonObj.get("actionDigest").getAsString() : null;
                        ApprovalResponse resp = ApprovalResponse.safeValueOf(decision);
                        boolean success = ToolPermissionChannel.respond(requestId, resp, actionDigest);
                        if (success) MissionManager.instance().resolveApproval(requestId);
                        ctx.send("{\"type\":\"permission_response_ack\",\"success\":" + success
                                + ",\"requestId\":\"" + requestId + "\"}");
                        log.info("[PermissionApproval] 收到审批回填: requestId={}, decision={}, success={}",
                                requestId, resp, success);
                        return;
                    }
                    ctx.send("{\"type\":\"error\",\"message\":\"Unknown type: " + type + "\"}");
                } catch (Exception e) {
                    log.error("[PermissionApproval] 消息解析失败: {}", e.getMessage());
                    ctx.send("{\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
                }
            });

            ws.onClose(ctx -> {
                String sessionId = ctx.sessionId();
                clients.remove(ctx);
                unsubscribe(sessionId);
                log.info("[PermissionApproval] 客户端已断开。总数: {}", clients.size());
            });

            ws.onError(ctx -> {
                String sessionId = ctx.sessionId();
                clients.remove(ctx);
                unsubscribe(sessionId);
                log.warn("[PermissionApproval] 错误: {}",
                        ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });
        });

        log.info("[PermissionApproval] 工具权限审批流 WebSocket 已挂载: /api/permission/stream");
        System.out.println("  ✓ [PermissionApproval] 工具权限审批流 WebSocket: /api/permission/stream");
    }

    /** Persist a permission request in the linked Mission before it reaches the UI. */
    private static void recordMissionApproval(String payload) {
        try {
            var json = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            String requestId = value(json, "requestId");
            String workflowId = value(json, "workflowId");
            if (requestId == null || workflowId == null) return;
            String traceId = value(json, "traceId");
            MissionManager.Mission mission = MissionManager.instance()
                    .ensureForRun(workflowId, workflowId, traceId, workflowId);
            MissionManager.instance().addApproval(mission.missionId(), requestId,
                    value(json, "description"), value(json, "toolName"), value(json, "target"),
                    workflowId, traceId);
        } catch (Exception ignored) {
            // Approval delivery must never fail because the optional continuity
            // read-model cannot parse a request.
        }
    }

    private static String value(com.google.gson.JsonObject json, String field) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) return null;
        String value = json.get(field).getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    /** 注销指定会话的 EventBus handler，防止内存泄漏。 */
    private static void unsubscribe(String sessionId) {
        Consumer<String> handler = subscriptions.remove(sessionId);
        if (handler != null) {
            EventBus.instance().unsubscribe(ToolPermissionChannel.CHANNEL, handler);
            log.info("[PermissionApproval] 已注销审批流处理器，会话: {}", sessionId);
        }
    }
}
