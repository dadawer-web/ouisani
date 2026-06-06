package com.ouisani.aios.core.network;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application Network Gateway — bridges external UIs to AIOS application stdin/stdout.
 * <p>
 * Creates a dynamic WebSocket route: {@code /api/app/{app_name}/stream}
 * <ul>
 *   <li><b>Input bridge:</b> WebSocket messages → VFS write to {@code /proc/apps/{appName}/stdin}</li>
 *   <li><b>Output bridge:</b> EventBus subscription {@code app_stdout_{appName}} → WebSocket push</li>
 * </ul>
 *
 * <h3>Example (frontend):</h3>
 * <pre>
 * const ws = new WebSocket("ws://localhost:8080/api/app/data_pipeline/stream?token=AIOS-SUPER-SECRET-KEY");
 * ws.onmessage = (e) => console.log("App output:", e.data);
 * ws.send(JSON.stringify({command: "run", args: ["--verbose"]}));
 * </pre>
 */
public class AppGateway {

    private static final Logger log = LoggerFactory.getLogger(AppGateway.class);

    /** Track connected clients per app for cleanup. */
    private static final ConcurrentHashMap<String, Set<WsContext>> appClients = new ConcurrentHashMap<>();

    public static void attachTo(Javalin app) {
        app.ws("/api/app/{app_name}/stream", ws -> {
            ws.onConnect(ctx -> {
                String appName = ctx.pathParam("app_name");

                // Auth check
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[Gateway] Unauthorized WebSocket connection attempt for app: {}", appName);
                    System.out.printf("  🚫 [Gateway] Unauthorized connection rejected for app: %s%n", appName);
                    ctx.session.close();
                    return;
                }

                // Register client
                appClients.computeIfAbsent(appName, k -> ConcurrentHashMap.newKeySet()).add(ctx);

                // Subscribe to EventBus for stdout push from this app
                String eventKey = "app_stdout_" + appName;
                EventBus.instance().subscribe(eventKey, data -> {
                    try {
                        if (ctx.session.isOpen()) {
                            ctx.send(data);
                        }
                    } catch (Exception e) {
                        log.debug("[Gateway] Failed to push stdout to client for app {}: {}", appName, e.getMessage());
                    }
                });

                log.info("[Gateway] External UI connected to application: {}", appName);
                System.out.printf("  📡 [Gateway] External UI connected to application: %s%n", appName);
            });

            ws.onMessage(ctx -> {
                String appName = ctx.pathParam("app_name");
                String message = ctx.message();

                // Bridge: inject external input into VFS stdin pipe via syscall
                String stdinPath = "/proc/apps/" + appName + "/stdin";
                try {
                    SyscallRequest writeReq = new SyscallRequest("vfs.write",
                            java.util.Map.of("path", stdinPath, "data", message));
                    SyscallDispatcher.getInstance().execute("app_gateway", writeReq);
                    log.debug("[Gateway] Injected {} bytes into stdin for app: {}", message.length(), appName);
                } catch (Exception e) {
                    log.warn("[Gateway] Failed to write to stdin for app {}: {}", appName, e.getMessage());
                    System.out.printf("  ⚠ [Gateway] stdin write failed for app %s: %s%n", appName, e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                String appName = ctx.pathParam("app_name");
                Set<WsContext> clients = appClients.get(appName);
                if (clients != null) {
                    clients.remove(ctx);
                    if (clients.isEmpty()) {
                        appClients.remove(appName);
                    }
                }
                log.info("[Gateway] External UI disconnected from application: {}", appName);
                System.out.printf("  📡 [Gateway] External UI disconnected from application: %s%n", appName);
            });

            ws.onError(ctx -> {
                String appName = ctx.pathParam("app_name");
                Set<WsContext> clients = appClients.get(appName);
                if (clients != null) {
                    clients.remove(ctx);
                }
                log.warn("[Gateway] WebSocket error for app {}: {}", appName,
                        ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });
        });

        log.info("[Gateway] App Gateway attached: /api/app/{app_name}/stream");
        System.out.println("  ✓ [Gateway] App Gateway attached: /api/app/{app_name}/stream");
    }
}
