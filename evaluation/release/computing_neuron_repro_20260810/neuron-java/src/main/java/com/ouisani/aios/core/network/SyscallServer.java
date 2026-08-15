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
                    log.warn("[WebSocket Monitor] 发送到客户端失败，正在移除: {}", e.getMessage());
                }
            }
        });
    }

    public void start(int port) {
        app = Javalin.create(config -> {
            config.jetty.modifyServer(server -> {
                QueuedThreadPool threadPool = (QueuedThreadPool) server.getThreadPool();
                threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());

                // HTTP idle timeout 设置为 5 分钟
                for (var connector : server.getConnectors()) {
                    if (connector instanceof org.eclipse.jetty.server.ServerConnector sc) {
                        sc.setIdleTimeout(300000);
                    }
                }
            });

            // WebSocket idle timeout 设置为 10 分钟 — 防止 Dashboard/Workflow 控制通道因空闲被断开
            config.jetty.modifyWebSocketServletFactory(wsFactory -> {
                wsFactory.setIdleTimeout(java.time.Duration.ofMinutes(10));
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
                log.warn("[API Gateway] 连接被拒绝: 缺少或无效的安全 Token。path={}", path);
                System.out.printf("  🚫 [API Gateway] 连接被拒绝: 缺少或无效的安全 Token。path=%s%n", path);
                ctx.header("Access-Control-Allow-Origin", "*");
                ctx.status(401).result("未授权访问 AIOS 内核");
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
                    log.warn("[API Gateway] WebSocket /ws/monitor 被拒绝: 无效 Token");
                    System.out.println("  🚫 [API Gateway] WebSocket /ws/monitor 被拒绝: 无效 Token");
                    ctx.session.close();
                    return;
                }

                monitorClients.add(ctx);
                log.info("[WebSocket] Dashboard 已连接。客户端总数: {}", monitorClients.size());
                System.out.printf("  📡 [WebSocket] Dashboard 已连接。客户端总数: %d%n", monitorClients.size());
            });

            ws.onClose(ctx -> {
                monitorClients.remove(ctx);
                log.info("[WebSocket] Dashboard 已断开。客户端总数: {}", monitorClients.size());
            });

            ws.onError(ctx -> {
                monitorClients.remove(ctx);
                Throwable err = ctx.error();
                if (err != null) {
                    log.warn("[WebSocket] Dashboard 错误: {}", err.getMessage());
                }
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
                log.error("[Snapshot API] 创建失败: {}", e.getMessage());
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
                log.error("[Snapshot API] 恢复失败: {}", e.getMessage());
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

                log.info("[Migration API] Checkpoint 已准备: PID={}, size={} bytes", pid, data.length);

            } catch (Exception e) {
                log.error("[Migration API] Checkpoint 失败: {}", e.getMessage());
                ctx.status(500).result("Checkpoint 失败: " + e.getMessage());
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

                log.info("[Migration API] 已从远程恢复: sourceNode={}, origPID={}, newPID={}",
                        snapshot.sourceNode(), snapshot.pid(), restored.pid());

            } catch (Exception e) {
                log.error("[Migration API] 恢复失败: {}", e.getMessage());
                ctx.status(500).result("恢复失败: " + e.getMessage());
            }
        });

        // ── Migration WebSocket: 双向流式迁移通道 (extracted → MigrationRoutes) ──
        MigrationRoutes.attachTo(app, scheduler);

        app.sse("/kernel/stream", client -> {
            client.keepAlive();
            EventBus.instance().register(client);
            client.sendEvent("connected", "{\"status\":\"ok\",\"message\":\"AIOS 内核流已激活\"}");
        });

        app.ws("/ws/dev/{nodeName}", ws -> {
            ws.onConnect(ctx -> {
                // Auth check for WebSocket
                String token = ctx.queryParam("token");
                if (!authManager.verifyToken(token)) {
                    log.warn("[API Gateway] WebSocket /ws/dev 被拒绝: 无效 Token");
                    System.out.println("  🚫 [API Gateway] WebSocket /ws/dev 被拒绝: 无效 Token");
                    ctx.session.close();
                    return;
                }

                String nodeName = ctx.pathParam("nodeName");
                String vfsPath = "/dev/ws/" + nodeName;
                log.info("[WS] 客户端正在连接: nodeName={}, vfsPath={}", nodeName, vfsPath);

                WebSocketNode node = wsNodes.computeIfAbsent(vfsPath, p -> {
                    WebSocketNode n = new WebSocketNode(p);
                    VfsManager.instance().mount("/dev/ws", nodeName, n);
                    log.info("[WS] VFS 已挂载: {}", vfsPath);
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
                    log.debug("[WS] 收到消息: nodeName={}, len={}", nodeName, message.length());
                }
            });

            ws.onClose(ctx -> {
                String nodeName = ctx.pathParam("nodeName");
                String vfsPath = "/dev/ws/" + nodeName;
                log.info("[WS] 客户端正在断开: nodeName={}", nodeName);

                WebSocketNode node = wsNodes.remove(vfsPath);
                if (node != null) {
                    node.detachWsContext();
                    VfsManager.instance().unmount(vfsPath);
                    log.info("[WS] VFS 已卸载: {}", vfsPath);
                }
                EventBus.instance().broadcast("ws_disconnect",
                        "{\"nodeName\":\"" + nodeName + "\",\"reason\":\"" + escapeJson(ctx.reason() != null ? ctx.reason() : "closed") + "\"}");
            });

            ws.onError(ctx -> {
                String nodeName = ctx.pathParam("nodeName");
                Throwable err = ctx.error();
                if (err != null) {
                    log.error("[WS] nodeName={} 上发生错误: {}", nodeName, err.getMessage());
                }
            });
        });

        // ── Remote Device WebSocket: dynamic mount/unmount into /dev/remote/ (extracted → RemoteDeviceRoutes) ──
        RemoteDeviceRoutes.attachTo(app, remoteDevices);

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
                    log.warn("[Display Server] WebSocket /ws/display 被拒绝: 无效 Token");
                    ctx.session.close();
                    return;
                }
                displayClients.add(ctx);
                log.info("[Display Server] 客户端已连接: total={}", displayClients.size());

                // Send current DOM state on connect (like loading the current framebuffer)
                try {
                    java.util.Optional<com.ouisani.aios.core.VfsNode> domNode =
                            VfsManager.instance().resolve("/dev/gui/dom");
                    if (domNode.isPresent() && domNode.get() instanceof com.ouisani.aios.vfs.GuiDomNode gui) {
                        String currentDom = gui.read();
                        ctx.send("{\"type\":\"init\",\"dom\":" + currentDom + "}");
                    }
                } catch (Exception e) {
                    log.warn("[Display Server] 发送初始 DOM 状态失败: {}", e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                displayClients.remove(ctx);
                log.info("[Display Server] 客户端已断开: total={}", displayClients.size());
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
                    log.warn("[Display Server] WebSocket /ws/gui/action 被拒绝: 无效 Token");
                    ctx.session.close();
                    return;
                }
                log.info("[Display Server] 操作客户端已连接");
            });

            ws.onMessage(ctx -> {
                String actionPayload = ctx.message();
                log.debug("[Display Server] 收到操作事件: len={}", actionPayload.length());

                // Route the action to GuiActionNode
                try {
                    java.util.Optional<com.ouisani.aios.core.VfsNode> actionNode =
                            VfsManager.instance().resolve("/dev/gui/action");
                    if (actionNode.isPresent()) {
                        actionNode.get().write(actionPayload);
                    }
                } catch (Exception e) {
                    log.error("[Display Server] 操作路由失败: {}", e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                log.info("[Display Server] 操作客户端已断开");
            });

            ws.onError(ctx -> {
                log.warn("[Display Server] 操作 WebSocket 错误");
            });
        });

        app.sse("/mcp/sse", client -> {
            client.keepAlive();
            String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            mcpSessions.put(sessionId, client);

            String endpointUrl = "/mcp/message?sessionId=" + sessionId;
            client.sendEvent("endpoint", endpointUrl);
            log.info("[MCP/SSE] 会话已连接: id={}, endpoint={}", sessionId, endpointUrl);

            client.onClose(() -> {
                mcpSessions.remove(sessionId);
                log.info("[MCP/SSE] 会话已断开: id={}", sessionId);
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
            log.debug("[MCP/POST] 已收到: sessionId={}, bodyLen={}", sessionId, requestBody.length());

            try {
                String jsonResponse = mcpServer.handleRawJson(requestBody);

                sseClient.sendEvent("message", jsonResponse);
                log.debug("[MCP/SSE] 已推送响应: sessionId={}, responseLen={}", sessionId, jsonResponse.length());

                ctx.status(202);
                ctx.result("{\"status\":\"accepted\"}");
            } catch (Exception e) {
                log.error("[MCP/POST] 处理错误: sessionId={}, error={}", sessionId, e.getMessage());
                ctx.status(500);
                ctx.result("{\"error\":\"内部 MCP 错误\"}");
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
                log.error("[Kernel Status] 序列化失败: {}", e.getMessage());
                ctx.result("{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // ── App Gateway: bridge external UIs to application stdin/stdout ──
        AppGateway.attachTo(app);

        // ── System Stream: 全局系统状态监控 WebSocket (extracted → SystemStreamRoutes) ──
        SystemStreamRoutes.attachTo(app, systemStreamClients, systemStreamSubscriptions);

        // ── EventBus 日志桥接：将内核事件统一转发到 sys.eventbus.logs 频道 ──
        // 前端订阅 sys.eventbus.logs 即可收到所有 EVENT_BUS_LOG 格式的事件
        // 注意：sys.dag.events 不在此列 —— SystemStreamRoutes 已直接订阅该频道并按
        // DAG_EVENT 类型分流（前端展示为 · NODE_STARTED · 等）。若再桥接到 sys.eventbus.logs，
        // 同一事件会被前端收两次（一次 dag.NODE_STARTED，一次 [sys.dag.events] 兜底），
        // 导致 activity 缓冲区被重复行占满，NODE_SUCCEEDED 等完成事件被截断不可见。
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
        System.out.printf("  ║     ⚡ [Syscall Gateway] 正在监听端口 %-4d         ║%n", port);
        System.out.println("  ║           使用 Java 21 Virtual Threads                  ║");
        System.out.println("  ║                                                          ║");
        System.out.println("  ║   POST /syscall/spawn   → Agent 生成端点                  ║");
        System.out.println("  ║   SSE  /kernel/stream   → 实时内核事件总线                ║");
        System.out.println("  ║   WS   /ws/monitor      → Dashboard 指标 WebSocket        ║");
        System.out.println("  ║   WS   /ws/dev/{name}   → 全双工 VFS 桥接                ║");
        System.out.println("  ║   WS   /ws/remote/{id}  → 远程设备自动挂载               ║");
        System.out.println("  ║   SSE  /mcp/sse         → MCP 协议 SSE 通道              ║");
        System.out.println("  ║   POST /mcp/message     → MCP JSON-RPC 消息端点          ║");
        System.out.println("  ║   WS   /api/app/{name}/stream → App 标准输入/输出网关    ║");
        System.out.println("  ║   POST /snapshot/create → 冻结 Agent (checkpoint)        ║");
        System.out.println("  ║   POST /snapshot/restore→ 从快照恢复 Agent               ║");
        System.out.println("  ║   POST /migration/checkpoint → 准备热迁移                ║");
        System.out.println("  ║   POST /migration/restore    → 接收迁移的 Agent          ║");
        System.out.println("  ║   WS   /ws/migration    → 热迁移 WebSocket               ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        log.info("[Syscall Gateway] 正在监听端口 {}，使用 Virtual Threads", port);
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
                log.info("[Agent#{}] 正在执行，prompt: {}", pid,
                        capturedPrompt.length() > 80 ? capturedPrompt.substring(0, 80) + "..." : capturedPrompt);
                EventBus.instance().broadcast("agent_log",
                        "{\"pid\":" + pid + ",\"message\":\"Agent 正在执行\",\"prompt\":\""
                                + escapeJson(capturedPrompt.length() > 60 ? capturedPrompt.substring(0, 60) + "..." : capturedPrompt) + "\"}");
            });

            log.info("[Syscall Gateway] 已生成 agent_id={}", pid);

            EventBus.instance().broadcast("agent_spawn",
                    "{\"pid\":" + pid + ",\"type\":\"" + typeStr + "\",\"cgroup\":\"" + cgroup + "\"}");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("agent_id", pid);
            response.put("status", "SPAWNED");
            response.put("type", typeStr);
            ctx.contentType("application/json");
            ctx.result(objectMapper.writeValueAsString(response));

        } catch (IllegalArgumentException e) {
            log.error("[Syscall Gateway] 错误请求: {}", e.getMessage());
            ctx.status(400);
            ctx.contentType("application/json");
            try {
                Map<String, String> err = new LinkedHashMap<>();
                err.put("error", e.getMessage());
                ctx.result(objectMapper.writeValueAsString(err));
            } catch (Exception ignored) {
                ctx.result("{\"error\":\"错误请求\"}");
            }

        } catch (Exception e) {
            log.error("[Syscall Gateway] 内部错误: {}", e.getMessage(), e);
            ctx.status(500);
            ctx.contentType("application/json");
            try {
                Map<String, String> err = new LinkedHashMap<>();
                err.put("error", "内部服务器错误");
                ctx.result(objectMapper.writeValueAsString(err));
            } catch (Exception ignored) {
                ctx.result("{\"error\":\"内部服务器错误\"}");
            }
        }
    }

    public void stop() {
        if (app != null) {
            app.stop();
            log.info("[Syscall Gateway] 服务器已停止");
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
