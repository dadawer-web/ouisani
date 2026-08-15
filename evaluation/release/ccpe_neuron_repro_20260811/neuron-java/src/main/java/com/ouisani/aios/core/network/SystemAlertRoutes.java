package com.ouisani.aios.core.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 系统告警流路由 — 把后端高价值告警频道桥接到前端。
 * <p>
 * 与 {@link SystemStreamRoutes}（系统指标/日志/DAG 流）互补：后者只转发
 * {@code sys.telemetry.metrics} / {@code sys.eventbus.logs} / {@code sys.dag.events}，
 * 而<b>内核崩溃、看门狗紧急停止、死信队列、成本告警、工作流挂起、安全漏洞、agent 心跳</b>
 * 这批频道此前没有任何 WS 端点订阅转发，前端完全看不到。本路由补齐这个缺口。
 * <p>
 * <b>包装频道名</b>：{@link EventBus#subscribe} 的 handler 收到的是裸 payload（不含频道名），
 * 而这批频道的 payload 又没有统一的 {@code type} 字段，前端无法区分来源。故转发时包装成
 * {@code {"channel":"<name>","message":<payload>}}，前端按 channel 分类着色。
 * <p>
 * <b>生命周期</b>：每个 WS 连接在 onConnect 时为所有频道注册捕获频道名的 lambda，在
 * onClose/onError 时批量 {@link EventBus#unsubscribe} 注销，防止连接断开后 EventBus 继续推送导致 OOM。
 * <p>
 * OS 类比：Linux 的 {@code /dev/kmsg} + {@code dmesg --level=emerg,alert,crit} —
 * 用户态读取内核高优先级事件流。
 */
final class SystemAlertRoutes {

    private static final Logger log = LoggerFactory.getLogger(SystemAlertRoutes.class);

    /** 待桥接的告警频道清单 —— 与前端 alertsStore 的 severity 映射一一对应 */
    private static final String[] CHANNELS = {
            // critical
            "sys.kernel.panic", "emergency_halt",
            // warning
            "sys.dlq.entry_added", "sys.cost.warning",
            "sys.workflow.suspended", "sys.security.vulnerability_found",
            // info
            "sys.dlq.retry_requested", "sys.dlq.dismissed", "sys.dlq.resolved",
            "sys.workflow.node_resumed", "sys.security.audit_complete",
            "agent.heartbeat",
    };

    /** 已连接的告警流客户端 */
    private static final Set<WsContext> clients = ConcurrentHashMap.newKeySet();

    /** 每个会话注册的 EventBus handler 列表，断开时批量注销 */
    private static final Map<String, List<Consumer<String>>> subscriptions = new ConcurrentHashMap<>();

    private SystemAlertRoutes() {}

    static void attachTo(Javalin app) {
        app.ws("/api/system/alerts", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[SystemAlerts] 未授权连接被拒绝");
                    ctx.session.close();
                    return;
                }

                String sessionId = ctx.sessionId();
                clients.add(ctx);

                // 为每个频道注册捕获频道名的 lambda —— Consumer<String> 收不到频道名，必须闭包捕获
                List<Consumer<String>> handlers = new ArrayList<>();
                for (String channel : CHANNELS) {
                    Consumer<String> handler = payload -> {
                        if (!ctx.session.isOpen()) return;
                        try {
                            JsonObject wrapped = new JsonObject();
                            wrapped.addProperty("channel", channel);
                            // payload 是 EventBus 广播的 JSON 字符串；解析为 JSON 嵌套，避免二次转义
                            try {
                                wrapped.add("message", JsonParser.parseString(payload));
                            } catch (Exception parseEx) {
                                // 非 JSON payload（纯字符串），原样塞入
                                wrapped.addProperty("message", payload);
                            }
                            ctx.send(wrapped.toString());
                        } catch (Exception e) {
                            log.debug("[SystemAlerts] 推送 {} 失败: {}", channel, e.getMessage());
                        }
                    };
                    EventBus.instance().subscribe(channel, handler);
                    handlers.add(handler);
                }
                subscriptions.put(sessionId, handlers);

                log.info("[SystemAlerts] 客户端已连接并注册 {} 个 EventBus 监听器。总数: {}, sessionId: {}",
                        CHANNELS.length, clients.size(), sessionId);
                System.out.printf("  📡 [SystemAlerts] 客户端已连接并注册 %d 个告警监听器。总数: %d%n",
                        CHANNELS.length, clients.size());
            });

            ws.onClose(ctx -> {
                String sessionId = ctx.sessionId();
                clients.remove(ctx);
                unsubscribeAll(sessionId);
                log.info("[SystemAlerts] 客户端已断开。总数: {}", clients.size());
            });

            ws.onError(ctx -> {
                String sessionId = ctx.sessionId();
                clients.remove(ctx);
                unsubscribeAll(sessionId);
                log.warn("[SystemAlerts] 错误: {}", ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });

            // 心跳：前端 PING → 回 PONG，防止 Idle Timeout
            ws.onMessage(ctx -> {
                String msg = ctx.message();
                if (msg != null && msg.contains("\"PING\"")) {
                    ctx.send("{\"type\":\"PONG\"}");
                }
            });
        });

        log.info("[SystemAlerts] 系统告警流 WebSocket 已挂载: /api/system/alerts");
        System.out.println("  ✓ [SystemAlerts] 系统告警流 WebSocket: /api/system/alerts");
    }

    /** 注销指定会话的所有 EventBus handler，防止内存泄漏 */
    private static void unsubscribeAll(String sessionId) {
        List<Consumer<String>> handlers = subscriptions.remove(sessionId);
        if (handlers == null) return;
        for (int i = 0; i < handlers.size() && i < CHANNELS.length; i++) {
            EventBus.instance().unsubscribe(CHANNELS[i], handlers.get(i));
        }
        log.info("[SystemAlerts] 已注销 {} 个 EventBus 处理器，会话: {}", handlers.size(), sessionId);
    }
}
