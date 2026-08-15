package com.ouisani.aios.core.network;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 系统流路由 — 从 SyscallServer 抽取的全局系统状态监控 WebSocket 通道。
 * <p>
 * 每个前端连接在 onConnect 时动态注册 EventBus 监听器，在 onClose 时注销，
 * 防止连接断开后 EventBus 继续推送导致 OOM。监听的频道包括：
 * <ul>
 *   <li>sys.telemetry.metrics — 系统指标（CPU、内存等）</li>
 *   <li>sys.eventbus.logs — 内核事件总线日志</li>
 *   <li>sys.dag.events — DAG 节点状态变更</li>
 * </ul>
 * 同时处理前端心跳 PING，回复 PONG 防止 Idle Timeout。
 * <p>
 * OS 类比：Linux 的 /proc + /dev/kmsg — 用户态读取内核运行时状态与日志流。
 */
final class SystemStreamRoutes {

    private static final Logger log = LoggerFactory.getLogger(SystemStreamRoutes.class);

    private SystemStreamRoutes() {}

    /**
     * 前端系统流订阅的 EventBus 频道列表。
     * <p>除 sys.telemetry.metrics（指标）、sys.app.stdout（应用输出）、sys.dag.events（DAG 状态）
     * 前端有专门处理外，其余频道前端统一当作 EVENT_BUS_LOG 显示，确保自愈/崩溃/恢复等关键事件可见。
     */
    private static final String[] STREAM_CHANNELS = {
            "sys.telemetry.metrics",       // 系统指标
            "sys.eventbus.logs",           // 内核事件总线日志
            "sys.dag.events",              // DAG 节点状态变更
            "sys.telemetry.events",        // 自愈/恢复/Core Dump（RecoveryOrchestrator）
            "sys.semantic.crash",          // 节点语义崩溃
            "sys.workflow.node_resumed",   // 节点恢复
            "sys.workflow.node_failed",    // 节点失败
            "sys.workflow.suspended",      // 工作流挂起
            "sys.dlq.entry_added",         // 死信队列
    };

    /**
     * 挂载系统流 WebSocket 路由到 Javalin 应用。
     *
     * <ul>
     *   <li>WS /api/system/stream — 全局系统状态监控 WebSocket</li>
     * </ul>
     *
     * @param app                        Javalin 应用实例
     * @param systemStreamClients        已连接的系统流客户端集合，由调用方持有并传入
     * @param systemStreamSubscriptions   每个会话对应的 EventBus 订阅处理器列表，用于断开时批量注销
     */
    static void attachTo(Javalin app,
                         Set<WsContext> systemStreamClients,
                         Map<String, List<Consumer<String>>> systemStreamSubscriptions) {
        // ── System Stream: 全局系统状态监控 WebSocket ──
        // 每个前端连接在 onConnect 时动态注册 EventBus 监听器，
        // 在 onClose 时注销，防止连接断开后 EventBus 继续推送导致 OOM。
        app.ws("/api/system/stream", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[System Stream] 未授权连接被拒绝");
                    ctx.session.close();
                    return;
                }

                String sessionId = ctx.sessionId();
                systemStreamClients.add(ctx);

                // ── 为当前连接注册 EventBus 监听器（循环订阅 STREAM_CHANNELS） ──
                // 每个频道一个 forward handler，payload 原样透传给前端；
                // 前端按 channel 字段映射类型，未识别频道兜底当作 EVENT_BUS_LOG 显示。
                List<Consumer<String>> handlers = new ArrayList<>();
                for (String channel : STREAM_CHANNELS) {
                    Consumer<String> handler = payload -> {
                        try {
                            if (ctx.session.isOpen()) {
                                // 包装成 {channel, message} 格式，前端据 channel 分流
                                ctx.send("{\"channel\":\"" + channel + "\",\"message\":"
                                        + (payload == null ? "null" : payload.startsWith("{") ? payload : ("\"" + payload.replace("\\", "\\\\").replace("\"", "\\\"") + "\""))
                                        + "}");
                            }
                        } catch (Exception e) {
                            log.debug("[System Stream] 推送频道 {} 失败: {}", channel, e.getMessage());
                        }
                    };
                    EventBus.instance().subscribe(channel, handler);
                    handlers.add(handler);
                }

                // 保存此连接的所有 handler，断开时批量注销
                systemStreamSubscriptions.put(sessionId, handlers);

                log.info("[System Stream] 客户端已连接并注册 EventBus 监听器。总数: {}, sessionId: {}",
                        systemStreamClients.size(), sessionId);
                System.out.printf("  📡 [System Stream] 客户端已连接并注册 EventBus 监听器。总数: %d%n",
                        systemStreamClients.size());
            });

            ws.onClose(ctx -> {
                String sessionId = ctx.sessionId();
                systemStreamClients.remove(ctx);

                // ── 注销此连接的所有 EventBus 监听器，防止内存泄漏 ──
                List<Consumer<String>> handlers = systemStreamSubscriptions.remove(sessionId);
                if (handlers != null) {
                    String[] channels = STREAM_CHANNELS;
                    for (int i = 0; i < handlers.size() && i < channels.length; i++) {
                        EventBus.instance().unsubscribe(channels[i], handlers.get(i));
                    }
                    log.info("[System Stream] 已注销 {} 个 EventBus 处理器，会话: {}",
                            handlers.size(), sessionId);
                }

                log.info("[System Stream] 客户端已断开。总数: {}", systemStreamClients.size());
                System.out.printf("  📡 [System Stream] 客户端已断开。总数: %d%n",
                        systemStreamClients.size());
            });

            ws.onError(ctx -> {
                String sessionId = ctx.sessionId();
                systemStreamClients.remove(ctx);

                // 连接异常时也要注销监听器
                List<Consumer<String>> handlers = systemStreamSubscriptions.remove(sessionId);
                if (handlers != null) {
                    String[] channels = STREAM_CHANNELS;
                    for (int i = 0; i < handlers.size() && i < channels.length; i++) {
                        EventBus.instance().unsubscribe(channels[i], handlers.get(i));
                    }
                    log.info("[System Stream] 错误后已注销 {} 个 EventBus 处理器，会话: {}",
                            handlers.size(), sessionId);
                }

                log.warn("[System Stream] 错误: {}", ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });

            // ── 处理前端心跳 PING，回复 PONG 防止 Idle Timeout ──
            ws.onMessage(ctx -> {
                String msg = ctx.message();
                if (msg != null && msg.contains("\"PING\"")) {
                    ctx.send("{\"type\":\"PONG\"}");
                }
            });
        });

        log.info("[Syscall Gateway] 系统流 WebSocket 已挂载: /api/system/stream");
        System.out.println("  ✓ [Syscall Gateway] 系统流 WebSocket: /api/system/stream");
    }
}
