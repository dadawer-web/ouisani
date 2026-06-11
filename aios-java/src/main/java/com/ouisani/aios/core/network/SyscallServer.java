package com.ouisani.aios.core.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.mcp.McpServer;
import com.ouisani.aios.core.snapshot.ProcessSnapshot;
import com.ouisani.aios.core.snapshot.SnapshotManager;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.vfs.WebSocketNode;
import com.ouisani.aios.vfs.RemoteDeviceMountNode;
import com.ouisani.aios.vfs.DeviceOfflineException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.javalin.websocket.WsContext;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * AIOS 系统调用网关服务器 — 基于 Javalin 的 HTTP/WebSocket 服务器，
 * 提供内核系统调用的网络入口。
 * <p>
 * OS 类比：相当于内核的 syscall 接口 + /proc + /dev 的网络暴露层 —
 * 外部程序通过 HTTP POST 发起系统调用，通过 WebSocket 访问设备节点，
 * 通过 SSE 订阅内核事件流。
 * <p>
 * 主要端点：
 * <ul>
 *   <li>POST /syscall/spawn — Agent 生成端点</li>
 *   <li>POST /syscall/exec — 通用系统调用执行端点</li>
 *   <li>SSE  /kernel/stream — 实时内核事件总线</li>
 *   <li>WS   /ws/monitor — 仪表盘指标 WebSocket</li>
 *   <li>WS   /ws/dev/{name} — 全双工 VFS 桥接</li>
 *   <li>WS   /ws/remote/{id} — 远程设备自动挂载</li>
 *   <li>WS   /ws/display — 语义显示服务器（渲染推送）</li>
 *   <li>WS   /ws/gui/action — GUI 输入事件</li>
 *   <li>SSE  /mcp/sse — MCP 协议 SSE 通道</li>
 *   <li>POST /mcp/message — MCP JSON-RPC 消息端点</li>
 *   <li>POST /snapshot/create — 创建进程快照</li>
 *   <li>POST /snapshot/restore — 从快照恢复进程</li>
 *   <li>POST /migration/checkpoint — 准备热迁移</li>
 *   <li>POST /migration/restore — 接收迁移的 Agent</li>
 *   <li>WS   /ws/migration — 热迁移 WebSocket</li>
 * </ul>
 */
public class SyscallServer {

    private static final Logger log = LoggerFactory.getLogger(SyscallServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final TaskScheduler scheduler;
    private final McpServer mcpServer;
    private final AtomicInteger pidCounter = new AtomicInteger(1);
    private final Map<String, WebSocketNode> wsNodes = new ConcurrentHashMap<>();
    private final Map<String, RemoteDeviceMountNode> remoteDevices = new ConcurrentHashMap<>();
    private final Map<String, SseClient> mcpSessions = new ConcurrentHashMap<>();
    private final Set<WsContext> monitorClients = ConcurrentHashMap.newKeySet();
    /** 系统监控流客户端 — 接收 SYS_METRICS + EVENT_BUS_LOG */
    private final Set<WsContext> systemStreamClients = ConcurrentHashMap.newKeySet();
    /** 每个 WebSocket 连接对应的 EventBus 订阅处理器，用于断开时注销 */
    private final Map<String, List<Consumer<String>>> systemStreamSubscriptions = new ConcurrentHashMap<>();
    private Javalin app;

    public SyscallServer(TaskScheduler scheduler) {
        this(scheduler, null);
    }

    public SyscallServer(TaskScheduler scheduler, McpServer mcpServer) {
        this.scheduler = scheduler;
        this.mcpServer = mcpServer;

        // Subscribe to EventBus sys.telemetry.metrics channel and forward to WebSocket dashboard clients
        EventBus.instance().subscribe("sys.telemetry.metrics", payload -> {
            for (WsContext client : monitorClients) {
                try {
                    client.send(payload);
                } catch (Exception e) {
                    monitorClients.remove(client);
                    log.warn("[WebSocket Monitor] Failed to send to client, removing: {}", e.getMessage());
                }
            }
        });
    }

    public void start(int port) {
        app = Javalin.create(config -> {
            config.jetty.modifyServer(server -> {
                QueuedThreadPool threadPool = (QueuedThreadPool) server.getThreadPool();
                threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
            });
            // Static file hosting for web dashboard HTML
            config.staticFiles.add("src/main/resources/web", io.javalin.http.staticfiles.Location.EXTERNAL);

            // 全局 CORS 配置 — 确保浏览器跨域请求正常
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    it.anyHost();
                    it.allowCredentials = false;
                });
            });
        });

        // ── API Gateway Auth: global before-handler ──
        AuthManager authManager = AuthManager.instance();
        app.before(ctx -> {
            String path = ctx.path();

            // CORS preflight 由 Javalin CORS 插件自动处理，此处跳过
            if (io.javalin.http.HandlerType.OPTIONS.equals(ctx.method())) {
                return;
            }

            // Allow static resources (dashboard HTML/CSS/JS) without auth
            if (isStaticResource(path)) {
                return;
            }

            // Check Authorization header
            String authHeader = ctx.header("Authorization");
            String token = authManager.extractFromHeader(authHeader);

            // Fallback: check query parameter (for easy curl testing)
            if (token == null) {
                token = authManager.extractFromQuery(ctx.queryParam("token"));
            }

            // Verify token
            if (!authManager.verifyToken(token)) {
                log.warn("[API Gateway] Connection rejected due to missing or invalid security token. path={}", path);
                System.out.printf("  🚫 [API Gateway] Connection rejected due to missing or invalid security token. path=%s%n", path);
                ctx.header("Access-Control-Allow-Origin", "*");
                ctx.status(401).result("Unauthorized access to AIOS Kernel");
            }

            // 为所有通过认证的请求添加 CORS 头
            ctx.header("Access-Control-Allow-Origin", "*");
        });

        // ── WebSocket Monitor: real-time system metrics for dashboard ──
        app.ws("/ws/monitor", ws -> {
            ws.onConnect(ctx -> {
                // Auth check for WebSocket: verify token from query param
                String token = ctx.queryParam("token");
                if (!authManager.verifyToken(token)) {
                    log.warn("[API Gateway] WebSocket /ws/monitor rejected: invalid token");
                    System.out.println("  🚫 [API Gateway] WebSocket /ws/monitor rejected: invalid token");
                    ctx.session.close();
                    return;
                }

                monitorClients.add(ctx);
                log.info("[WebSocket] Dashboard connected. Total clients: {}", monitorClients.size());
                System.out.printf("  📡 [WebSocket] Dashboard connected. Total clients: %d%n", monitorClients.size());
            });

            ws.onClose(ctx -> {
                monitorClients.remove(ctx);
                log.info("[WebSocket] Dashboard disconnected. Total clients: {}", monitorClients.size());
            });

            ws.onError(ctx -> {
                monitorClients.remove(ctx);
                log.warn("[WebSocket] Dashboard error: {}", ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });
        });

        app.post("/syscall/spawn", this::handleSpawn);

        // Generic syscall execution endpoint for dashboard CLI
        app.post("/syscall/exec", ctx -> {
            ctx.contentType("application/json");
            try {
                String body = ctx.body();
                Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
                String action = (String) parsed.get("action");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) parsed.getOrDefault("params", Map.of());

                if (action == null || action.isEmpty()) {
                    ctx.status(400).result("{\"success\":false,\"error\":\"Missing 'action' field\"}");
                    return;
                }

                SyscallRequest request = new SyscallRequest(action, params);
                SyscallResponse response = SyscallDispatcher.getInstance().execute("dashboard_cli", request);
                ctx.result(objectMapper.writeValueAsString(response));
            } catch (Exception e) {
                ctx.status(500).result("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // ════════════════════════════════════════════════════════════
        //  Snapshot / Live Migration Endpoints
        // ════════════════════════════════════════════════════════════

        // 创建进程快照（冻结 + 序列化）
        app.post("/snapshot/create", ctx -> {
            ctx.contentType("application/json");
            try {
                Map<String, Object> body = objectMapper.readValue(ctx.body(), Map.class);
                int pid = ((Number) body.get("pid")).intValue();

                AgentTask task = scheduler.getTask(pid);
                if (task == null) {
                    ctx.status(404).result("{\"error\":\"PID not found: " + pid + "\"}");
                    return;
                }

                ProcessSnapshot snapshot = SnapshotManager.instance().createSnapshot(task);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("snapshotId", snapshot.snapshotId());
                response.put("pid", snapshot.pid());
                response.put("originalStatus", snapshot.taskStatus().name());
                response.put("cachedPages", snapshot.cachedPages().size());
                response.put("openHandles", snapshot.openHandles().size());
                response.put("journalTail", snapshot.journalTail().size());
                response.put("contextHistorySize", snapshot.contextHistory().size());
                ctx.result(objectMapper.writeValueAsString(response));

            } catch (Exception e) {
                log.error("[Snapshot API] Create failed: {}", e.getMessage());
                ctx.status(500).result("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });

        // 从快照恢复进程
        app.post("/snapshot/restore", ctx -> {
            ctx.contentType("application/json");
            try {
                Map<String, Object> body = objectMapper.readValue(ctx.body(), Map.class);
                String snapshotId = (String) body.get("snapshotId");

                ProcessSnapshot snapshot = SnapshotManager.instance().loadSnapshot(snapshotId);
                if (snapshot == null) {
                    ctx.status(404).result("{\"error\":\"Snapshot not found: " + snapshotId + "\"}");
                    return;
                }

                AgentTask restored = SnapshotManager.instance().restore(snapshot);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("newPid", restored.pid());
                response.put("snapshotId", snapshotId);
                response.put("status", restored.status().name());
                ctx.result(objectMapper.writeValueAsString(response));

            } catch (Exception e) {
                log.error("[Snapshot API] Restore failed: {}", e.getMessage());
                ctx.status(500).result("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });

        // 列出所有快照
        app.get("/snapshot/list", ctx -> {
            ctx.contentType("application/json");
            var snapshots = SnapshotManager.instance().listSnapshots();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", snapshots.size());
            response.put("snapshots", snapshots);
            ctx.result(objectMapper.writeValueAsString(response));
        });

        // 获取快照统计
        app.get("/snapshot/stats", ctx -> {
            ctx.contentType("text/plain");
            ctx.result(SnapshotManager.instance().getStatsReport());
        });

        // 热迁移：冻结 + 序列化，返回二进制数据供传输
        app.post("/migration/checkpoint", ctx -> {
            try {
                Map<String, Object> body = objectMapper.readValue(ctx.body(), Map.class);
                int pid = ((Number) body.get("pid")).intValue();

                AgentTask task = scheduler.getTask(pid);
                if (task == null) {
                    ctx.status(404).result("PID not found: " + pid);
                    return;
                }

                byte[] data = SnapshotManager.instance().prepareMigration(task);

                ctx.contentType("application/octet-stream");
                ctx.header("X-Snapshot-Size", String.valueOf(data.length));
                ctx.header("X-Original-PID", String.valueOf(pid));
                ctx.result(data);

                log.info("[Migration API] Checkpoint prepared: PID={}, size={} bytes", pid, data.length);

            } catch (Exception e) {
                log.error("[Migration API] Checkpoint failed: {}", e.getMessage());
                ctx.status(500).result("Checkpoint failed: " + e.getMessage());
            }
        });

        // 热迁移：接收二进制快照数据并恢复
        app.post("/migration/restore", ctx -> {
            try {
                byte[] data = ctx.bodyAsBytes();
                ProcessSnapshot snapshot = SnapshotManager.instance().deserializeFromTransfer(data);

                AgentTask restored = SnapshotManager.instance().restore(snapshot);

                ctx.contentType("application/json");
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("newPid", restored.pid());
                response.put("snapshotId", snapshot.snapshotId());
                response.put("sourceNode", snapshot.sourceNode());
                response.put("originalPid", snapshot.pid());
                response.put("status", "RESTORED");
                ctx.result(objectMapper.writeValueAsString(response));

                log.info("[Migration API] Restored from remote: sourceNode={}, origPID={}, newPID={}",
                        snapshot.sourceNode(), snapshot.pid(), restored.pid());

            } catch (Exception e) {
                log.error("[Migration API] Restore failed: {}", e.getMessage());
                ctx.status(500).result("Restore failed: " + e.getMessage());
            }
        });

        // ── Migration WebSocket: 双向流式迁移通道 ──
        app.ws("/ws/migration", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!authManager.verifyToken(token)) {
                    log.warn("[Migration WS] Rejected: invalid token");
                    ctx.session.close();
                    return;
                }
                log.info("[Migration WS] Client connected for live migration");
            });

            ws.onMessage(ctx -> {
                try {
                    String message = ctx.message();
                    Map<String, Object> parsed = objectMapper.readValue(message, Map.class);
                    String action = (String) parsed.get("action");

                    if ("checkpoint".equals(action)) {
                        int pid = ((Number) parsed.get("pid")).intValue();
                        AgentTask task = scheduler.getTask(pid);
                        if (task == null) {
                            ctx.send("{\"error\":\"PID not found: " + pid + "\"}");
                            return;
                        }

                        byte[] data = SnapshotManager.instance().prepareMigration(task);
                        String base64Data = Base64.getEncoder().encodeToString(data);

                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("action", "checkpoint_data");
                        response.put("snapshotId", "snap-" + pid + "-" + System.currentTimeMillis());
                        response.put("pid", pid);
                        response.put("data", base64Data);
                        response.put("size", data.length);
                        ctx.send(objectMapper.writeValueAsString(response));

                    } else if ("restore".equals(action)) {
                        String base64Data = (String) parsed.get("data");
                        byte[] data = Base64.getDecoder().decode(base64Data);

                        ProcessSnapshot snapshot = SnapshotManager.instance().deserializeFromTransfer(data);
                        AgentTask restored = SnapshotManager.instance().restore(snapshot);

                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("action", "restore_complete");
                        response.put("newPid", restored.pid());
                        response.put("snapshotId", snapshot.snapshotId());
                        response.put("sourceNode", snapshot.sourceNode());
                        ctx.send(objectMapper.writeValueAsString(response));
                    }

                } catch (Exception e) {
                    log.error("[Migration WS] Error: {}", e.getMessage());
                    try {
                        ctx.send("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    } catch (Exception ignored) {}
                }
            });

            ws.onClose(ctx -> {
                log.info("[Migration WS] Client disconnected");
            });

            ws.onError(ctx -> {
                log.warn("[Migration WS] Error: {}", ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });
        });

        app.sse("/kernel/stream", client -> {
            client.keepAlive();
            EventBus.instance().register(client);
            client.sendEvent("connected", "{\"status\":\"ok\",\"message\":\"AIOS kernel stream active\"}");
        });

        app.ws("/ws/dev/{nodeName}", ws -> {
            ws.onConnect(ctx -> {
                // Auth check for WebSocket
                String token = ctx.queryParam("token");
                if (!authManager.verifyToken(token)) {
                    log.warn("[API Gateway] WebSocket /ws/dev rejected: invalid token");
                    System.out.println("  🚫 [API Gateway] WebSocket /ws/dev rejected: invalid token");
                    ctx.session.close();
                    return;
                }

                String nodeName = ctx.pathParam("nodeName");
                String vfsPath = "/dev/ws/" + nodeName;
                log.info("[WS] Client connecting: nodeName={}, vfsPath={}", nodeName, vfsPath);

                WebSocketNode node = wsNodes.computeIfAbsent(vfsPath, p -> {
                    WebSocketNode n = new WebSocketNode(p);
                    VfsManager.instance().mount("/dev/ws", nodeName, n);
                    log.info("[WS] VFS mounted: {}", vfsPath);
                    return n;
                });

                node.attachWsContext(ctx);
                EventBus.instance().broadcast("ws_connect",
                        "{\"nodeName\":\"" + nodeName + "\",\"sessionId\":\"" + ctx.sessionId() + "\"}");
            });

            ws.onMessage(ctx -> {
                String nodeName = ctx.pathParam("nodeName");
                String vfsPath = "/dev/ws/" + nodeName;
                WebSocketNode node = wsNodes.get(vfsPath);
                if (node != null) {
                    String message = ctx.message();
                    node.onWsMessage(message);
                    log.debug("[WS] Message received: nodeName={}, len={}", nodeName, message.length());
                }
            });

            ws.onClose(ctx -> {
                String nodeName = ctx.pathParam("nodeName");
                String vfsPath = "/dev/ws/" + nodeName;
                log.info("[WS] Client disconnecting: nodeName={}", nodeName);

                WebSocketNode node = wsNodes.remove(vfsPath);
                if (node != null) {
                    node.detachWsContext();
                    VfsManager.instance().unmount(vfsPath);
                    log.info("[WS] VFS unmounted: {}", vfsPath);
                }
                EventBus.instance().broadcast("ws_disconnect",
                        "{\"nodeName\":\"" + nodeName + "\",\"reason\":\"" + escapeJson(ctx.reason() != null ? ctx.reason() : "closed") + "\"}");
            });

            ws.onError(ctx -> {
                String nodeName = ctx.pathParam("nodeName");
                log.error("[WS] Error on nodeName={}: {}", nodeName, ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });
        });

        // ── Remote Device WebSocket: dynamic mount/unmount into /dev/remote/ ──
        app.ws("/ws/remote/{deviceId}", ws -> {
            ws.onConnect(ctx -> {
                // Auth check
                String token = ctx.queryParam("token");
                if (!authManager.verifyToken(token)) {
                    log.warn("[API Gateway] WebSocket /ws/remote rejected: invalid token");
                    ctx.session.close();
                    return;
                }

                String deviceId = ctx.pathParam("deviceId");
                String deviceType = ctx.queryParam("type") != null ? ctx.queryParam("type") : "generic";

                log.info("[RemoteDevice] Device connecting: deviceId={}, type={}", deviceId, deviceType);

                // Mount or retrieve the RemoteDeviceMountNode from VFS
                RemoteDeviceMountNode node = VfsManager.instance().mountRemoteDevice(deviceId, deviceType);
                node.attachWsContext(ctx);

                remoteDevices.put(deviceId, node);

                EventBus.instance().broadcast("device_mount",
                        "{\"deviceId\":\"" + deviceId + "\",\"type\":\"" + deviceType
                                + "\",\"path\":\"/dev/remote/" + deviceId + "\"}");

                log.info("[RemoteDevice] Device mounted: deviceId={} → /dev/remote/{}", deviceId, deviceId);
                System.out.println("  \u001B[32m[RemoteDevice] Device '" + deviceId + "' connected and mounted at /dev/remote/" + deviceId + "\u001B[0m");
            });

            ws.onMessage(ctx -> {
                String deviceId = ctx.pathParam("deviceId");
                RemoteDeviceMountNode node = remoteDevices.get(deviceId);
                if (node != null) {
                    String message = ctx.message();
                    node.onWsMessage(message);
                    log.debug("[RemoteDevice] Message received: deviceId={}, len={}", deviceId, message.length());
                }
            });

            ws.onClose(ctx -> {
                String deviceId = ctx.pathParam("deviceId");
                log.info("[RemoteDevice] Device disconnecting: deviceId={}", deviceId);

                RemoteDeviceMountNode node = remoteDevices.remove(deviceId);
                if (node != null) {
                    node.detachWsContext();

                    // Unmount from VFS — the device is gone
                    VfsManager.instance().unmountRemoteDevice(deviceId);

                    log.info("[RemoteDevice] Device unmounted: deviceId={}", deviceId);
                }

                EventBus.instance().broadcast("device_unmount",
                        "{\"deviceId\":\"" + deviceId + "\",\"reason\":\""
                                + escapeJson(ctx.reason() != null ? ctx.reason() : "closed") + "\"}");
            });

            ws.onError(ctx -> {
                String deviceId = ctx.pathParam("deviceId");
                log.error("[RemoteDevice] Error on deviceId={}: {}", deviceId,
                        ctx.error() != null ? ctx.error().getMessage() : "unknown");

                RemoteDeviceMountNode node = remoteDevices.remove(deviceId);
                if (node != null) {
                    node.detachWsContext();
                    VfsManager.instance().unmountRemoteDevice(deviceId);
                }
            });
        });

        // ════════════════════════════════════════════════════════════
        //  Semantic Display Server: /ws/display (render push)
        // ════════════════════════════════════════════════════════════

        // Subscribe to ui_render events and forward to all connected display clients
        Set<io.javalin.websocket.WsContext> displayClients = ConcurrentHashMap.newKeySet();
        EventBus.instance().subscribe("ui_render", payload -> {
            for (io.javalin.websocket.WsContext client : displayClients) {
                try {
                    client.send(payload);
                } catch (Exception e) {
                    displayClients.remove(client);
                }
            }
        });

        app.ws("/ws/display", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!authManager.verifyToken(token)) {
                    log.warn("[Display Server] WebSocket /ws/display rejected: invalid token");
                    ctx.session.close();
                    return;
                }
                displayClients.add(ctx);
                log.info("[Display Server] Client connected: total={}", displayClients.size());

                // Send current DOM state on connect (like loading the current framebuffer)
                try {
                    java.util.Optional<com.ouisani.aios.core.VfsNode> domNode =
                            VfsManager.instance().resolve("/dev/gui/dom");
                    if (domNode.isPresent() && domNode.get() instanceof com.ouisani.aios.vfs.GuiDomNode gui) {
                        String currentDom = gui.read();
                        ctx.send("{\"type\":\"init\",\"dom\":" + currentDom + "}");
                    }
                } catch (Exception e) {
                    log.warn("[Display Server] Failed to send initial DOM state: {}", e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                displayClients.remove(ctx);
                log.info("[Display Server] Client disconnected: total={}", displayClients.size());
            });

            ws.onError(ctx -> {
                displayClients.remove(ctx);
            });
        });

        // ════════════════════════════════════════════════════════════
        //  Semantic Display Server: /ws/gui/action (input events)
        // ════════════════════════════════════════════════════════════

        app.ws("/ws/gui/action", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!authManager.verifyToken(token)) {
                    log.warn("[Display Server] WebSocket /ws/gui/action rejected: invalid token");
                    ctx.session.close();
                    return;
                }
                log.info("[Display Server] Action client connected");
            });

            ws.onMessage(ctx -> {
                String actionPayload = ctx.message();
                log.debug("[Display Server] Action event received: len={}", actionPayload.length());

                // Route the action to GuiActionNode
                try {
                    java.util.Optional<com.ouisani.aios.core.VfsNode> actionNode =
                            VfsManager.instance().resolve("/dev/gui/action");
                    if (actionNode.isPresent()) {
                        actionNode.get().write(actionPayload);
                    }
                } catch (Exception e) {
                    log.error("[Display Server] Action routing failed: {}", e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                log.info("[Display Server] Action client disconnected");
            });

            ws.onError(ctx -> {
                log.warn("[Display Server] Action WebSocket error");
            });
        });

        app.sse("/mcp/sse", client -> {
            client.keepAlive();
            String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            mcpSessions.put(sessionId, client);

            String endpointUrl = "/mcp/message?sessionId=" + sessionId;
            client.sendEvent("endpoint", endpointUrl);
            log.info("[MCP/SSE] Session connected: id={}, endpoint={}", sessionId, endpointUrl);

            client.onClose(() -> {
                mcpSessions.remove(sessionId);
                log.info("[MCP/SSE] Session disconnected: id={}", sessionId);
            });
        });

        app.post("/mcp/message", ctx -> {
            String sessionId = ctx.queryParam("sessionId");
            if (sessionId == null) {
                ctx.status(400);
                ctx.result("{\"error\":\"Missing sessionId\"}");
                return;
            }

            SseClient sseClient = mcpSessions.get(sessionId);
            if (sseClient == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"Session not found: " + sessionId + "\"}");
                return;
            }

            if (mcpServer == null) {
                ctx.status(503);
                ctx.result("{\"error\":\"MCP server not initialized\"}");
                return;
            }

            String requestBody = ctx.body();
            log.debug("[MCP/POST] Received: sessionId={}, bodyLen={}", sessionId, requestBody.length());

            try {
                String jsonResponse = mcpServer.handleRawJson(requestBody);

                sseClient.sendEvent("message", jsonResponse);
                log.debug("[MCP/SSE] Pushed response: sessionId={}, responseLen={}", sessionId, jsonResponse.length());

                ctx.status(202);
                ctx.result("{\"status\":\"accepted\"}");
            } catch (Exception e) {
                log.error("[MCP/POST] Processing error: sessionId={}, error={}", sessionId, e.getMessage());
                ctx.status(500);
                ctx.result("{\"error\":\"Internal MCP error\"}");
            }
        });

        // ── Kernel Status API — 前端状态栏轮询 ──
        app.get("/api/kernel/status", ctx -> {
            ctx.contentType("application/json");
            try {
                Map<String, Object> status = new LinkedHashMap<>();

                // 系统运行时间
                try {
                    long uptimeMs = com.ouisani.aios.core.tick.SystemTickGenerator.instance().uptimeMs();
                    status.put("uptimeMs", uptimeMs);
                    status.put("uptimeHuman", formatUptime(uptimeMs));
                } catch (Exception e) {
                    status.put("uptimeMs", 0);
                    status.put("uptimeHuman", "N/A");
                }

                // 活跃 Agent 数量
                int activeCount = 0;
                int runningCount = 0;
                int blockedCount = 0;
                try {
                    Map<Integer, AgentTask> activeTasks = scheduler.activeTasks();
                    for (AgentTask task : activeTasks.values()) {
                        if (task.status() == AgentTask.TaskStatus.RUNNING) runningCount++;
                        else if (task.status() == AgentTask.TaskStatus.BLOCKED) blockedCount++;
                        activeCount++;
                    }
                } catch (Exception ignored) {}
                status.put("activeAgents", activeCount);
                status.put("runningAgents", runningCount);
                status.put("blockedAgents", blockedCount);

                // Token 消耗
                long tokensUsed = 0;
                try {
                    com.ouisani.aios.core.cgroup.CgroupNode agentsCgroup =
                            com.ouisani.aios.core.cgroup.CgroupManager.instance().getNode("agents");
                    tokensUsed = agentsCgroup != null ? agentsCgroup.usage().consumed() : 0L;
                } catch (Exception ignored) {}
                status.put("tokensUsed", tokensUsed);

                // 看门狗状态
                boolean watchdogHealthy = false;
                long watchdogMs = -1;
                try {
                    com.ouisani.aios.core.rtos.WatchdogDaemon watchdog =
                            com.ouisani.aios.core.rtos.WatchdogDaemon.instance();
                    watchdogHealthy = watchdog.isSystemHealthy();
                    watchdogMs = watchdog.msSinceLastPing();
                } catch (Exception ignored) {}
                status.put("watchdogHealthy", watchdogHealthy);
                status.put("watchdogMsSinceLastPing", watchdogMs);

                // SysTick
                long currentTick = 0;
                try {
                    currentTick = com.ouisani.aios.core.tick.SystemTickGenerator.instance().currentTick();
                } catch (Exception ignored) {}
                status.put("systemTick", currentTick);

                // LLM Router 状态
                boolean llmAvailable = false;
                try {
                    llmAvailable = VfsManager.instance().getLlmProvider() != null;
                } catch (Exception ignored) {}
                status.put("llmAvailable", llmAvailable);

                ctx.result(objectMapper.writeValueAsString(status));
            } catch (Exception e) {
                log.error("[Kernel Status] Failed to serialize: {}", e.getMessage());
                ctx.result("{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // ── App Gateway: bridge external UIs to application stdin/stdout ──
        AppGateway.attachTo(app);

        // ── System Stream: 全局系统状态监控 WebSocket ──
        // 每个前端连接在 onConnect 时动态注册 EventBus 监听器，
        // 在 onClose 时注销，防止连接断开后 EventBus 继续推送导致 OOM。
        app.ws("/api/system/stream", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!authManager.verifyToken(token)) {
                    log.warn("[System Stream] Unauthorized connection rejected");
                    ctx.session.close();
                    return;
                }

                String sessionId = ctx.sessionId();
                systemStreamClients.add(ctx);

                // ── 为当前连接注册 EventBus 监听器 ──
                List<Consumer<String>> handlers = new ArrayList<>();

                // 订阅 sys.telemetry.metrics → 推送 SYS_METRICS（展平到顶层，前端直接读 data.cpuUsage）
                Consumer<String> metricsHandler = payload -> {
                    try {
                        if (ctx.session.isOpen()) {
                            ctx.send(payload);
                        }
                    } catch (Exception e) {
                        log.debug("[System Stream] Failed to push SYS_METRICS: {}", e.getMessage());
                    }
                };
                EventBus.instance().subscribe("sys.telemetry.metrics", metricsHandler);
                handlers.add(metricsHandler);

                // 订阅 sys.eventbus.logs → 推送 EVENT_BUS_LOG
                Consumer<String> logsHandler = payload -> {
                    try {
                        if (ctx.session.isOpen()) {
                            ctx.send(payload);
                        }
                    } catch (Exception e) {
                        log.debug("[System Stream] Failed to push EVENT_BUS_LOG: {}", e.getMessage());
                    }
                };
                EventBus.instance().subscribe("sys.eventbus.logs", logsHandler);
                handlers.add(logsHandler);

                // 保存此连接的所有 handler，断开时批量注销
                systemStreamSubscriptions.put(sessionId, handlers);

                log.info("[System Stream] Client connected with EventBus listeners. Total: {}, sessionId: {}",
                        systemStreamClients.size(), sessionId);
                System.out.printf("  📡 [System Stream] Client connected with EventBus listeners. Total: %d%n",
                        systemStreamClients.size());
            });

            ws.onClose(ctx -> {
                String sessionId = ctx.sessionId();
                systemStreamClients.remove(ctx);

                // ── 注销此连接的所有 EventBus 监听器，防止内存泄漏 ──
                List<Consumer<String>> handlers = systemStreamSubscriptions.remove(sessionId);
                if (handlers != null) {
                    String[] channels = {"sys.telemetry.metrics", "sys.eventbus.logs"};
                    for (int i = 0; i < handlers.size() && i < channels.length; i++) {
                        EventBus.instance().unsubscribe(channels[i], handlers.get(i));
                    }
                    log.info("[System Stream] Unsubscribed {} EventBus handlers for session: {}",
                            handlers.size(), sessionId);
                }

                log.info("[System Stream] Client disconnected. Total: {}", systemStreamClients.size());
                System.out.printf("  📡 [System Stream] Client disconnected. Total: %d%n",
                        systemStreamClients.size());
            });

            ws.onError(ctx -> {
                String sessionId = ctx.sessionId();
                systemStreamClients.remove(ctx);

                // 连接异常时也要注销监听器
                List<Consumer<String>> handlers = systemStreamSubscriptions.remove(sessionId);
                if (handlers != null) {
                    String[] channels = {"sys.telemetry.metrics", "sys.eventbus.logs"};
                    for (int i = 0; i < handlers.size() && i < channels.length; i++) {
                        EventBus.instance().unsubscribe(channels[i], handlers.get(i));
                    }
                    log.info("[System Stream] Unsubscribed {} EventBus handlers after error for session: {}",
                            handlers.size(), sessionId);
                }

                log.warn("[System Stream] Error: {}", ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });

            // ── 处理前端心跳 PING，回复 PONG 防止 Idle Timeout ──
            ws.onMessage(ctx -> {
                String msg = ctx.message();
                if (msg != null && msg.contains("\"PING\"")) {
                    ctx.send("{\"type\":\"PONG\"}");
                }
            });
        });

        // ── EventBus 日志桥接：将内核事件统一转发到 sys.eventbus.logs 频道 ──
        // 前端订阅 sys.eventbus.logs 即可收到所有 EVENT_BUS_LOG 格式的事件
        String[] logChannels = {"sig_tick", "emergency_halt", "sys.human_intervention_required",
                "sys.kernel.panic", "agent_spawn", "agent_log", "device_mount", "device_unmount",
                "ws_connect", "ws_disconnect", "spontaneous_idea", "ui_render", "ui_action"};
        for (String channel : logChannels) {
            EventBus.instance().subscribe(channel, payload -> {
                String logJson = "{\"type\":\"EVENT_BUS_LOG\",\"timestamp\":" + System.currentTimeMillis()
                        + ",\"topic\":\"" + channel + "\""
                        + ",\"payload\":" + (isJson(payload) ? payload : "\"" + escapeJson(payload) + "\"")
                        + "}";
                EventBus.instance().broadcast("sys.eventbus.logs", logJson);
            });
        }

        // ── Template Manager: inject BaseAgent.py into VFS ──
        com.ouisani.aios.user.apps.omnifactory.TemplateManager.initTemplates();

        // ── Tool Registry: register all builtin tools (Claude Code capability) ──
        com.ouisani.aios.core.tool.ToolRegistry.registerBuiltinTools();

        app.start(port);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════╗");
        System.out.printf("  ║     ⚡ [Syscall Gateway] Listening on port %-4d         ║%n", port);
        System.out.println("  ║           with Java 21 Virtual Threads                  ║");
        System.out.println("  ║                                                          ║");
        System.out.println("  ║   POST /syscall/spawn   → Agent spawn endpoint           ║");
        System.out.println("  ║   SSE  /kernel/stream   → Real-time kernel event bus     ║");
        System.out.println("  ║   WS   /ws/monitor      → Dashboard metrics WebSocket    ║");
        System.out.println("  ║   WS   /ws/dev/{name}   → Full-duplex VFS bridge         ║");
        System.out.println("  ║   WS   /ws/remote/{id}  → Remote device auto-mount       ║");
        System.out.println("  ║   SSE  /mcp/sse         → MCP protocol SSE channel       ║");
        System.out.println("  ║   POST /mcp/message     → MCP JSON-RPC message endpoint  ║");
        System.out.println("  ║   WS   /api/app/{name}/stream → App stdin/stdout gateway ║");
        System.out.println("  ║   POST /snapshot/create → Freeze agent (checkpoint)      ║");
        System.out.println("  ║   POST /snapshot/restore→ Restore agent from snapshot    ║");
        System.out.println("  ║   POST /migration/checkpoint → Prepare live migration    ║");
        System.out.println("  ║   POST /migration/restore    → Receive migrated agent    ║");
        System.out.println("  ║   WS   /ws/migration    → Live migration WebSocket       ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        log.info("[Syscall Gateway] Listening on port {} with Virtual Threads", port);
    }

    private static String formatUptime(long uptimeMs) {
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return String.format("%dh %dm", hours, minutes % 60);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds % 60);
        return String.format("%ds", seconds);
    }

    /**
     * 简单的 JSON 字符串转义。
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 判断字符串是否像 JSON（以 { 或 [ 开头）。
     */
    private static boolean isJson(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(0);
        return c == '{' || c == '[';
    }

    private void handleSpawn(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(ctx.body(), Map.class);

            String prompt = (String) body.getOrDefault("prompt", "");
            String typeStr = (String) body.getOrDefault("type", "LLM_CHAT");
            String cgroup = (String) body.getOrDefault("cgroup", "/aios/agents");
            int priority = body.containsKey("priority") ? ((Number) body.get("priority")).intValue() : 0;
            int gasLimit = body.containsKey("gas_limit") ? ((Number) body.get("gas_limit")).intValue() : 10000;

            int pid = pidCounter.getAndIncrement();
            AgentTask task = new AgentTask(
                    pid,
                    AgentTask.TaskStatus.READY,
                    cgroup,
                    "/dev/null",
                    "/dev/null",
                    new ArrayList<>()
            );
            task.setType(AgentTask.TaskType.valueOf(typeStr));
            task.setPayload(prompt);
            task.setPriority(priority);
            task.setGasLimit(gasLimit);

            String capturedPrompt = prompt;
            scheduler.spawn(task, () -> {
                log.info("[Agent#{}] Executing with prompt: {}", pid,
                        capturedPrompt.length() > 80 ? capturedPrompt.substring(0, 80) + "..." : capturedPrompt);
                EventBus.instance().broadcast("agent_log",
                        "{\"pid\":" + pid + ",\"message\":\"Agent executing\",\"prompt\":\""
                                + escapeJson(capturedPrompt.length() > 60 ? capturedPrompt.substring(0, 60) + "..." : capturedPrompt) + "\"}");
            });

            log.info("[Syscall Gateway] Spawned agent_id={}", pid);

            EventBus.instance().broadcast("agent_spawn",
                    "{\"pid\":" + pid + ",\"type\":\"" + typeStr + "\",\"cgroup\":\"" + cgroup + "\"}");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("agent_id", pid);
            response.put("status", "SPAWNED");
            response.put("type", typeStr);
            ctx.contentType("application/json");
            ctx.result(objectMapper.writeValueAsString(response));

        } catch (IllegalArgumentException e) {
            log.error("[Syscall Gateway] Bad request: {}", e.getMessage());
            ctx.status(400);
            ctx.contentType("application/json");
            try {
                Map<String, String> err = new LinkedHashMap<>();
                err.put("error", e.getMessage());
                ctx.result(objectMapper.writeValueAsString(err));
            } catch (Exception ignored) {
                ctx.result("{\"error\":\"Bad request\"}");
            }

        } catch (Exception e) {
            log.error("[Syscall Gateway] Internal error: {}", e.getMessage(), e);
            ctx.status(500);
            ctx.contentType("application/json");
            try {
                Map<String, String> err = new LinkedHashMap<>();
                err.put("error", "Internal server error");
                ctx.result(objectMapper.writeValueAsString(err));
            } catch (Exception ignored) {
                ctx.result("{\"error\":\"Internal server error\"}");
            }
        }
    }

    public void stop() {
        if (app != null) {
            app.stop();
            log.info("[Syscall Gateway] Server stopped");
        }
    }

    /**
     * Determine if a request path is a static resource that should bypass auth.
     * Static resources: /, *.html, *.css, *.js, *.ico, *.png, *.svg, etc.
     */
    private static boolean isStaticResource(String path) {
        if (path.equals("/") || path.equals("/index.html")) {
            return true;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".js")
                || lower.endsWith(".ico") || lower.endsWith(".png") || lower.endsWith(".svg")
                || lower.endsWith(".jpg") || lower.endsWith(".gif") || lower.endsWith(".woff2")
                || lower.endsWith(".ttf") || lower.endsWith(".map");
    }
}
