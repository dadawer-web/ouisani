package com.ouisani.aios.core.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.mcp.McpServer;
import com.ouisani.aios.vfs.WebSocketNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class SyscallServer {

    private static final Logger log = LoggerFactory.getLogger(SyscallServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final TaskScheduler scheduler;
    private final McpServer mcpServer;
    private final AtomicInteger pidCounter = new AtomicInteger(1);
    private final Map<String, WebSocketNode> wsNodes = new ConcurrentHashMap<>();
    private final Map<String, SseClient> mcpSessions = new ConcurrentHashMap<>();
    private Javalin app;

    public SyscallServer(TaskScheduler scheduler) {
        this(scheduler, null);
    }

    public SyscallServer(TaskScheduler scheduler, McpServer mcpServer) {
        this.scheduler = scheduler;
        this.mcpServer = mcpServer;
    }

    public void start(int port) {
        app = Javalin.create(config -> {
            config.jetty.modifyServer(server -> {
                QueuedThreadPool threadPool = (QueuedThreadPool) server.getThreadPool();
                threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
            });
        });

        app.post("/syscall/spawn", this::handleSpawn);

        app.sse("/kernel/stream", client -> {
            client.keepAlive();
            EventBus.instance().register(client);
            client.sendEvent("connected", "{\"status\":\"ok\",\"message\":\"AIOS kernel stream active\"}");
        });

        app.ws("/ws/dev/{nodeName}", ws -> {
            ws.onConnect(ctx -> {
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

        app.start(port);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════╗");
        System.out.printf("  ║     ⚡ [Syscall Gateway] Listening on port %-4d         ║%n", port);
        System.out.println("  ║           with Java 21 Virtual Threads                  ║");
        System.out.println("  ║                                                          ║");
        System.out.println("  ║   POST /syscall/spawn   → Agent spawn endpoint           ║");
        System.out.println("  ║   SSE  /kernel/stream   → Real-time kernel event bus     ║");
        System.out.println("  ║   WS   /ws/dev/{name}   → Full-duplex VFS bridge         ║");
        System.out.println("  ║   SSE  /mcp/sse         → MCP protocol SSE channel       ║");
        System.out.println("  ║   POST /mcp/message     → MCP JSON-RPC message endpoint  ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        log.info("[Syscall Gateway] Listening on port {} with Virtual Threads", port);
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

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
