package com.ouisani.aios.core.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.snapshot.ProcessSnapshot;
import com.ouisani.aios.core.snapshot.SnapshotManager;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 迁移路由 — 从 SyscallServer 抽取的双向流式迁移 WebSocket 通道。
 * <p>
 * 提供 Agent 热迁移的流式接口：客户端通过 WebSocket 发起 checkpoint/restore，
 * 服务端序列化 Agent 快照并以 Base64 形式回传，或接收远端快照并在本地恢复。
 * <p>
 * OS 类比：Linux 的 CRIU (Checkpoint/Restore In Userspace) — 进程冻结、
 * 序列化、跨节点传输、恢复执行的完整生命周期。
 */
final class MigrationRoutes {

    private static final Logger log = LoggerFactory.getLogger(MigrationRoutes.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private MigrationRoutes() {}

    /**
     * 挂载迁移 WebSocket 路由到 Javalin 应用。
     *
     * <ul>
     *   <li>WS /ws/migration — 双向流式迁移通道</li>
     * </ul>
     *
     * @param app       Javalin 应用实例
     * @param scheduler 任务调度器，用于按 PID 查找待迁移的 Agent
     */
    static void attachTo(Javalin app, TaskScheduler scheduler) {
        // ── Migration WebSocket: 双向流式迁移通道 ──
        app.ws("/ws/migration", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[Migration WS] 被拒绝: 无效 Token");
                    ctx.session.close();
                    return;
                }
                log.info("[Migration WS] 客户端已连接，准备热迁移");
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
                    log.error("[Migration WS] 错误: {}", e.getMessage());
                    try {
                        ctx.send("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    } catch (Exception ignored) {}
                }
            });

            ws.onClose(ctx -> {
                log.info("[Migration WS] 客户端已断开");
            });

            ws.onError(ctx -> {
                Throwable err = ctx.error();
                if (err != null) {
                    log.warn("[Migration WS] 错误: {}", err.getMessage());
                }
            });
        });

        log.info("[Syscall Gateway] 迁移 WebSocket 已挂载: /ws/migration");
        System.out.println("  ✓ [Syscall Gateway] 迁移 WebSocket: /ws/migration");
    }

    /**
     * 简单的 JSON 字符串转义。
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
