package com.ouisani.aios.core.network;

import com.ouisani.aios.core.a2a.A2aFederation;
import com.ouisani.aios.core.a2a.A2aMessage;
import com.ouisani.aios.core.a2a.A2aNodeDescriptor;
import com.ouisani.aios.core.a2a.A2aProtocol;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.ipc.VariablePool;
import com.ouisani.aios.core.trace.TraceSpan;
import com.ouisani.aios.core.trace.TracingManager;
import com.ouisani.aios.core.workspace.ProjectWorkspaceManager;
import com.ouisani.aios.core.workspace.ProjectWorkspaceManager.ProjectWorkspace;
import com.ouisani.aios.user.apps.omnifactory.OmniMotherAgent;
import com.ouisani.aios.user.apps.omnifactory.OperatorAgent;
import com.ouisani.aios.user.apps.omnifactory.TopologyCompiler;
import com.ouisani.aios.user.apps.omnifactory.WorkflowEngine;
import com.ouisani.aios.user.apps.omnifactory.WorkflowManifest;
import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;
import com.ouisani.aios.user.sdk.AbstractAgent;
import com.ouisani.aios.user.sdk.AiosSdk;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.*;
import java.io.IOException;

/**
 * 应用网络网关 — 将外部 UI 桥接到 AIOS 应用的标准输入/输出。
 * <p>
 * OS 类比：相当于 Unix 的管道重定向 + inetd —
 * 创建动态 WebSocket 路由 {@code /api/app/{app_name}/stream}：
 * <ul>
 *   <li><b>输入桥接：</b>WebSocket 消息 → VFS 写入 {@code /proc/apps/{appName}/stdin}</li>
 *   <li><b>输出桥接：</b>EventBus 订阅 {@code app_stdout_{appName}} → WebSocket 推送</li>
 * </ul>
 *
 * <h3>前端示例：</h3>
 * <pre>
 * const ws = new WebSocket("ws://localhost:8080/api/app/data_pipeline/stream?token=${AIOS_GATEWAY_SECRET}");
 * ws.onmessage = (e) => console.log("App output:", e.data);
 * ws.send(JSON.stringify({command: "run", args: ["--verbose"]}));
 * </pre>
 */
public class AppGateway {

    private static final Logger log = LoggerFactory.getLogger(AppGateway.class);

    private static final AppGateway INSTANCE = new AppGateway();

    public static AppGateway getInstance() { return INSTANCE; }

    /** 跟踪每个应用的已连接客户端，用于清理 */
    private static final ConcurrentHashMap<String, Set<WsContext>> appClients = new ConcurrentHashMap<>();

    /** 可视化大屏观察者 — 接收内核自愈告警等高优先级信号 */
    private static final Set<WsContext> dashboardObservers = ConcurrentHashMap.newKeySet();

    /** Gson 实例 — 用于状态同步通道的 JSON 序列化/反序列化 */
    private static final com.google.gson.Gson gson = new com.google.gson.Gson();

    public static void attachTo(Javalin app) {
        app.ws("/api/app/{app_name}/stream", ws -> {
            ws.onConnect(ctx -> {
                String appName = ctx.pathParam("app_name");

                // Auth check
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[Gateway] 未授权的 WebSocket 连接尝试，应用: {}", appName);
                    System.out.printf("  🚫 [Gateway] 未授权连接被拒绝，应用: %s%n", appName);
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
                        log.debug("[Gateway] 向应用 {} 的客户端推送 stdout 失败: {}", appName, e.getMessage());
                    }
                });

                log.info("[Gateway] 外部 UI 已连接到应用: {}", appName);
                System.out.printf("  📡 [Gateway] 外部 UI 已连接到应用: %s%n", appName);
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
                    log.debug("[Gateway] 已注入 {} 字节到应用 {} 的 stdin", message.length(), appName);
                } catch (Exception e) {
                    log.warn("[Gateway] 向应用 {} 写入 stdin 失败: {}", appName, e.getMessage());
                    System.out.printf("  ⚠ [Gateway] 应用 %s 的 stdin 写入失败: %s%n", appName, e.getMessage());
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
                log.info("[Gateway] 外部 UI 已断开与应用 {} 的连接", appName);
                System.out.printf("  📡 [Gateway] 外部 UI 已断开与应用 %s 的连接%n", appName);
            });

            ws.onError(ctx -> {
                String appName = ctx.pathParam("app_name");
                Set<WsContext> clients = appClients.get(appName);
                if (clients != null) {
                    clients.remove(ctx);
                }
                Throwable err = ctx.error();
                if (err != null) {
                    log.warn("[Gateway] 应用 {} 的 WebSocket 错误: {}", appName, err.getMessage());
                }
            });
        });

        log.info("[Gateway] App Gateway 已挂载: /api/app/{app_name}/stream");
        System.out.println("  ✓ [Gateway] App Gateway 已挂载: /api/app/{app_name}/stream");

        // ════════════════════════════════════════════════════════════════
        //  Dashboard Alert WebSocket — 内核自愈告警实时推送通道
        // ════════════════════════════════════════════════════════════════
        app.ws("/api/dashboard/alerts", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[Gateway] 未授权的 Dashboard WebSocket 连接尝试");
                    ctx.session.close();
                    return;
                }
                dashboardObservers.add(ctx);
                log.info("[Gateway] Dashboard 观察者已连接");
                System.out.println("  📡 [Gateway] Dashboard 观察者已连接");
            });

            ws.onClose(ctx -> {
                dashboardObservers.remove(ctx);
                log.info("[Gateway] Dashboard 观察者已断开");
            });

            ws.onError(ctx -> {
                dashboardObservers.remove(ctx);
                Throwable err = ctx.error();
                if (err != null) {
                    log.warn("[Gateway] Dashboard WebSocket 错误: {}", err.getMessage());
                }
            });
        });

        // 订阅内核自愈告警频道 → 广播给所有大屏观察者
        EventBus.instance().subscribe("sys.human_intervention_required", AppGateway::broadcastToDashboards);

        // 将系统心跳和底层日志桥接到前端可视化大屏
        EventBus.instance().subscribe("sys.telemetry.metrics", AppGateway::broadcastToDashboards);
        EventBus.instance().subscribe("sys.eventbus.logs", AppGateway::broadcastToDashboards);

        // 订阅可观测性事件通道 — 邮件飞梭、自愈重试等动效数据源
        EventBus.instance().subscribe("sys.telemetry.events", AppGateway::broadcastToDashboards);

        log.info("[Gateway] 系统告警通道已开启。准备向 Dashboard 推送高优先级救援信号。");
        System.out.println("[Gateway] 系统告警通道已开启。准备向 Dashboard 推送高优先级救援信号。");

        // ════════════════════════════════════════════════════════════════
        //  God Hand Protocol — 前端参数热补丁控制通道
        // ════════════════════════════════════════════════════════════════
        app.ws("/api/app/god_hand/control", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[Gateway] 未授权的 God Hand WebSocket 连接尝试");
                    ctx.session.close();
                    return;
                }
                log.info("[Gateway] God Hand 控制通道已连接");
                System.out.println("[Gateway] God Hand 控制通道已连接");
            });

            ws.onMessage(ctx -> {
                String message = ctx.message();
                try {
                    String action = extractJsonField(message, "action");
                    if ("HOT_PATCH_PARAM".equals(action)) {
                        String targetNode = extractJsonField(message, "targetNode");
                        String paramsJson = extractJsonObject(message, "params");

                        if (targetNode == null || targetNode.isBlank()) {
                            log.warn("[Gateway] HOT_PATCH_PARAM 缺少 targetNode");
                            return;
                        }

                        // 1. 将新参数覆盖写入 VFS 配置文件
                        String configPath = "/factory/configs/" + targetNode + ".json";
                        AiosSdk.getInstance().writeFile("god_hand", configPath,
                                paramsJson != null ? paramsJson : "{}");

                        // 2. 向 EventBus 广播参数变更通知
                        EventBus.instance().broadcast("sys.control." + targetNode, "CONFIG_UPDATED");

                        System.out.printf("[Gateway] God Hand: HOT_PATCH_PARAM → %s。配置已写入 %s。EventBus 已通知。%n",
                                targetNode, configPath);
                        log.info("[Gateway] God Hand: HOT_PATCH_PARAM 节点 '{}'。配置: {}", targetNode, configPath);
                    }
                } catch (Exception e) {
                    log.warn("[Gateway] 处理 God Hand 消息失败: {}", e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                log.info("[Gateway] God Hand 控制通道已断开");
            });

            ws.onError(ctx -> {
                Throwable err = ctx.error();
                if (err != null) {
                    log.warn("[Gateway] God Hand WebSocket 错误: {}", err.getMessage());
                }
            });
        });

        log.info("[Gateway] God Hand Protocol 已挂载: /api/app/god_hand/control");
        System.out.println("[Gateway] God Hand Protocol 已挂载: /api/app/god_hand/control");

        // ════════════════════════════════════════════════════════════════
        //  God Hand Protocol V2 — /api/workflow/control 热机干涉通道
        // ════════════════════════════════════════════════════════════════
        app.ws("/api/workflow/control", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[Gateway] 未授权的 Workflow 控制通道 WebSocket 连接尝试");
                    ctx.session.close();
                    return;
                }
                log.info("[Gateway] Workflow 控制通道已连接");
                System.out.println("[Gateway] Workflow 控制通道已连接");

                // 启动心跳保活，防止空闲超时断开
                scheduleWorkflowHeartbeat(ctx);
            });

            ws.onMessage(ctx -> {
                String message = ctx.message();
                // 响应 ping 心跳
                if ("__ping__".equals(message)) {
                    try { ctx.send("__pong__"); } catch (Exception ignored) {}
                    return;
                }
                try {
                    String action = extractJsonField(message, "action");
                    if ("HOT_PATCH_PARAM".equals(action)) {
                        String targetNode = extractJsonField(message, "targetNode");
                        String paramsJson = extractJsonObject(message, "params");

                        if (targetNode == null || targetNode.isBlank()) {
                            log.warn("[Gateway] HOT_PATCH_PARAM 缺少 targetNode");
                            return;
                        }

                        // 强制覆写 VFS 配置文件
                        String configPath = "/factory/configs/" + targetNode + ".json";
                        VfsManager.instance().resolve(configPath)
                                .ifPresent(node -> node.write(paramsJson != null ? paramsJson : "{}"));

                        // 触发系统级广播
                        EventBus.instance().broadcast("sys.control." + targetNode, "CONFIG_UPDATED");

                        System.out.printf("[Gateway] God Hand Protocol 已启动。已热补丁节点 %s 的 VFS 配置。%n",
                                targetNode);
                        log.info("[Gateway] God Hand Protocol 已启动。已热补丁节点 '{}' 的 VFS 配置。", targetNode);
                    }
                } catch (Exception e) {
                    log.warn("[Gateway] 处理 Workflow 控制消息失败: {}", e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                log.info("[Gateway] Workflow 控制通道已断开");
            });

            ws.onError(ctx -> {
                Throwable err = ctx.error();
                if (err != null) {
                    log.warn("[Gateway] Workflow 控制通道 WebSocket 错误: {}", err.getMessage());
                }
            });
        });

        log.info("[Gateway] Workflow 控制通道已挂载: /api/workflow/control");
        System.out.println("[Gateway] Workflow 控制通道已挂载: /api/workflow/control");

        // ════════════════════════════════════════════════════════════════
        //  POST /api/workflow/compile — 两段式生成：第一段，动态拓扑编译
        // ════════════════════════════════════════════════════════════════
        app.post("/api/workflow/compile", ctx -> {
            String payload = ctx.body();
            System.out.printf("[App Gateway] 收到拓扑编译请求。大小: %d 字节。%n", payload.length());
            log.info("[App Gateway] 收到拓扑编译请求。大小: {} 字节。", payload.length());

            try {
                // 解析请求 JSON
                String prompt = extractJsonField(payload, "prompt");
                if (prompt == null || prompt.isBlank()) {
                    ctx.status(400);
                    ctx.contentType("application/json");
                    ctx.result("{\"status\":\"error\",\"message\":\"缺少 'prompt' 字段\"}");
                    return;
                }

                // 解析 enabledSkills 数组
                List<String> enabledSkills = extractJsonStringArray(payload, "enabledSkills");
                if (enabledSkills.isEmpty()) {
                    // 自动扫描物理硬盘中的技能
                    java.nio.file.Path skillsDir = java.nio.file.Paths.get("aios_skills");
                    if (java.nio.file.Files.exists(skillsDir)) {
                        try (var stream = java.nio.file.Files.list(skillsDir)) {
                            stream.filter(p -> p.toString().endsWith(".py") && !p.getFileName().toString().startsWith("__"))
                                  .forEach(p -> enabledSkills.add("skills." + p.getFileName().toString().replace(".py", "")));
                        }
                    }
                    // 强制挂载原生特权技能
                    enabledSkills.add("ComputerUseTool");
                    enabledSkills.add("BashTool");
                    log.info("[App Gateway] 自动发现 {} 个技能用于编译", enabledSkills.size());
                }

                // 解析 enabledRoles 数组
                List<String> enabledRoles = extractJsonStringArray(payload, "enabledRoles");

                System.out.printf("[App Gateway] 正在编译拓扑: prompt='%s...', skills=%s, roles=%s%n",
                        prompt.substring(0, Math.min(prompt.length(), 50)), enabledSkills, enabledRoles);
                log.info("[App Gateway] 正在编译拓扑: skills={}, roles={}", enabledSkills, enabledRoles);

                // 调用 TopologyCompiler 编译拓扑
                String topologyJson = TopologyCompiler.compileTopology(prompt, enabledSkills, enabledRoles);

                // 如果编译结果中没有 agentType，从前端 payload 中提取，默认 "omni"
                if (!topologyJson.contains("\"agentType\"")) {
                    String agentType = extractJsonField(payload, "agentType");
                    if (agentType == null || agentType.isBlank()) agentType = "omni";
                    int lastBrace = topologyJson.lastIndexOf('}');
                    if (lastBrace > 0) {
                        topologyJson = topologyJson.substring(0, lastBrace)
                                       + ", \"agentType\": \"" + agentType + "\"}";
                    } else {
                        // JSON 格式异常（无闭合花括号），直接追加
                        topologyJson = "{\"agentType\": \"" + agentType + "\"}";
                    }
                }

                ctx.contentType("application/json");
                ctx.result(topologyJson);
                System.out.printf("[App Gateway] 拓扑编译完成。响应大小: %d 字节。%n",
                        topologyJson.length());
            } catch (Exception e) {
                System.out.printf("[App Gateway] 拓扑编译失败: %s%n", e.getMessage());
                log.error("[App Gateway] 拓扑编译失败", e);
                ctx.status(500);
                ctx.contentType("application/json");
                ctx.result("{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        });

        // CORS 预检支持
        app.options("/api/workflow/compile", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        log.info("[App Gateway] 拓扑编译 API 已挂载: POST /api/workflow/compile");
        System.out.println("  ✓ [App Gateway] 拓扑编译 API: POST /api/workflow/compile");

        // ════════════════════════════════════════════════════════════════
        //  POST /api/workflow/deploy — 前端可视化大屏 → AIOS 内核
        // ════════════════════════════════════════════════════════════════
        app.post("/api/workflow/deploy", ctx -> {
            String payload = ctx.body();
            System.out.printf("[App Gateway] 收到来自 Dashboard 的 Omni-Workflow 负载。大小: %d 字节。%n",
                    payload.length());
            log.info("[App Gateway] 收到来自 Dashboard 的 Omni-Workflow 负载。大小: {} 字节。", payload.length());

            try {
                // 解析 JSON → WorkflowManifest
                WorkflowManifest manifest = parseWorkflowManifest(payload);

                System.out.printf("[App Gateway] 已解析工作流 '%s'，包含 %d 个节点。%n",
                        manifest.workflowName(), manifest.nodes().size());
                log.info("[App Gateway] 已解析工作流 '{}'，包含 {} 个节点。",
                        manifest.workflowName(), manifest.nodes().size());

                // 通过 DAG 引擎并发调度 — 无依赖节点自动并行执行
                // 不再直接 spawn 单个 OmniMotherAgent 串行处理所有节点
                // DAG 引擎会为每个节点动态创建 Agent（OmniMotherAgent 或 OperatorAgent），
                // 利用 CompletableFuture + 虚拟线程实现拓扑级并发
                //
                // 异步启动 DAG 引擎，避免阻塞 HTTP 请求线程
                Thread.startVirtualThread(() -> {
                    try {
                        WorkflowEngine.getInstance().executeDag(
                                manifest.nodes(),
                                manifest.workflowName(),
                                manifest.enabledSkills(),
                                manifest.enabledRoles()
                        );
                        System.out.printf("[App Gateway] DAG 引擎执行完成，工作流: %s%n", manifest.workflowName());
                    } catch (Exception e) {
                        System.err.printf("[App Gateway] DAG 引擎执行异常: %s%n", e.getMessage());
                        log.error("[App Gateway] DAG 引擎执行异常", e);
                    }
                });

                System.out.printf("[App Gateway] DAG 引擎已异步启动，%d 个节点将按拓扑并发执行。%n", manifest.nodes().size());
                log.info("[App Gateway] DAG 引擎已异步启动，{} 个节点将按拓扑并发执行。", manifest.nodes().size());

                ctx.contentType("application/json");
                ctx.result("{\"status\":\"success\",\"message\":\"创世进程已启动\"}");
                System.out.println("[App Gateway] 外部 API 已就绪。准备接收 N 节点拓扑。");
            } catch (Exception e) {
                System.out.printf("[App Gateway] 解析工作流负载失败: %s%n", e.getMessage());
                log.error("[App Gateway] 解析工作流负载失败", e);
                ctx.status(400);
                ctx.contentType("application/json");
                ctx.result("{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        });

        // CORS 预检支持 — 允许前端跨域访问
        app.options("/api/workflow/deploy", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // ════════════════════════════════════════════════════════════════
        //  GET /api/registry/catalogs — 动态读取本地武器库和角色库
        // ════════════════════════════════════════════════════════════════
        app.get("/api/registry/catalogs", ctx -> {
            Map<String, List<Map<String, String>>> result = new HashMap<>();
            List<Map<String, String>> roles = new ArrayList<>();
            List<Map<String, String>> skills = new ArrayList<>();
            try {
                // 使用相对路径，兼容 Docker 和本地运行
                java.nio.file.Path rolesDir = java.nio.file.Paths.get("aios_roles");
                if (java.nio.file.Files.exists(rolesDir)) {
                    java.nio.file.Files.list(rolesDir).filter(p -> p.toString().endsWith(".yaml")).forEach(p -> {
                        Map<String, String> role = new HashMap<>();
                        String name = p.getFileName().toString().replace(".yaml", "");
                        role.put("id", name);
                        role.put("name", name);
                        role.put("desc", "系统认知角色");
                        role.put("icon", "🧠");
                        roles.add(role);
                    });
                }
                java.nio.file.Path skillsDir = java.nio.file.Paths.get("aios_skills");
                if (java.nio.file.Files.exists(skillsDir)) {
                    java.nio.file.Files.list(skillsDir).filter(p -> p.toString().endsWith(".py") && !p.getFileName().toString().startsWith("__")).forEach(p -> {
                        Map<String, String> skill = new HashMap<>();
                        String name = p.getFileName().toString().replace(".py", "");
                        skill.put("id", name);
                        skill.put("name", name);
                        skill.put("desc", "本地可用工具");
                        skill.put("icon", "⚙️");
                        skills.add(skill);
                    });
                }
            } catch (Exception e) {
                log.error("[Gateway] 目录扫描失败", e);
            }
            result.put("roles", roles);
            result.put("skills", skills);
            ctx.json(result);
        });

        // CORS 预检支持
        app.options("/api/registry/catalogs", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // 为所有 /api/* 路由添加 CORS 头
        app.before("/api/*", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        });

        log.info("[App Gateway] 工作流部署 API 已挂载: POST /api/workflow/deploy");
        System.out.println("  ✓ [App Gateway] 工作流部署 API: POST /api/workflow/deploy");

        // ════════════════════════════════════════════════════════════════
        //  SIGSTOP/SIGCONT 进程控制 API（借鉴 Agent Zero 的 Pause/Resume）
        // ════════════════════════════════════════════════════════════════
        app.post("/api/process/{pid}/pause", ctx -> {
            int pid = Integer.parseInt(ctx.pathParam("pid"));
            AiosSdk.getInstance().sendSignal(pid, SignalType.SIGSTOP);
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"paused\",\"pid\":" + pid + "}");
        });

        app.post("/api/process/{pid}/resume", ctx -> {
            int pid = Integer.parseInt(ctx.pathParam("pid"));
            AiosSdk.getInstance().sendSignal(pid, SignalType.SIGCONT);
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"resumed\",\"pid\":" + pid + "}");
        });

        app.post("/api/process/{pid}/nudge", ctx -> {
            int pid = Integer.parseInt(ctx.pathParam("pid"));
            String nudgedPrompt = ctx.body();
            AiosSdk.getInstance().sendSignal(pid, SignalType.SIGUSR1);
            // 将 nudge 内容写入共享内存，SIGUSR1 处理时会读取
            VariablePool.getInstance().set(
                    VariablePool.Scope.TASK,
                    String.valueOf(pid), "nudge", nudgedPrompt
            );
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"nudged\",\"pid\":" + pid + "}");
        });

        app.get("/api/process/{pid}/snapshot", ctx -> {
            int pid = Integer.parseInt(ctx.pathParam("pid"));
            com.ouisani.aios.core.AgentTask task = VfsManager.instance().getTaskScheduler() != null
                    ? VfsManager.instance().getTaskScheduler().getTask(pid) : null;
            if (task != null) {
                ctx.contentType("application/json");
                ctx.result("{\"pid\":" + pid
                        + ",\"status\":\"" + task.status() + "\""
                        + ",\"paused\":" + task.isPaused()
                        + ",\"priority\":\"" + task.processPriority() + "\""
                        + ",\"gasUsed\":" + task.gasUsed()
                        + ",\"budget\":" + task.budget()
                        + "}");
            } else {
                ctx.status(404).result("{\"error\":\"Process not found\"}");
            }
        });

        log.info("[App Gateway] 进程控制 API 已挂载: /api/process/{pid}/pause|resume|nudge|snapshot");
        System.out.println("  ✓ [App Gateway] 进程控制 API: /api/process/{pid}/pause|resume|nudge|snapshot");

        // ════════════════════════════════════════════════════════════════
        //  项目工作区 API（借鉴 Agent Zero 的 Projects 系统）
        // ════════════════════════════════════════════════════════════════
        app.post("/api/workspace/create", ctx -> {
            String body = ctx.body();
            String name = extractJsonField(body, "name");
            String quotaStr = extractJsonField(body, "quota");
            long quota = quotaStr != null ? Long.parseLong(quotaStr) : 100000L;

            ProjectWorkspace ws = ProjectWorkspaceManager.getInstance().createWorkspace(name, quota);
            ctx.contentType("application/json");
            ctx.result("{\"projectId\":\"" + ws.projectId() + "\",\"name\":\"" + ws.projectName()
                    + "\",\"vfsRoot\":\"" + ws.vfsRoot() + "\",\"quota\":" + ws.tokenQuota() + "}");
        });

        app.get("/api/workspace/list", ctx -> {
            Collection<ProjectWorkspace> workspaces = ProjectWorkspaceManager.getInstance().listWorkspaces();
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (ProjectWorkspace ws : workspaces) {
                if (!first) sb.append(",");
                sb.append("{\"projectId\":\"").append(ws.projectId())
                  .append("\",\"name\":\"").append(ws.projectName())
                  .append("\",\"vfsRoot\":\"").append(ws.vfsRoot())
                  .append("\",\"quota\":").append(ws.tokenQuota()).append("}");
                first = false;
            }
            sb.append("]");
            ctx.contentType("application/json");
            ctx.result(sb.toString());
        });

        app.delete("/api/workspace/{projectId}", ctx -> {
            String projectId = ctx.pathParam("projectId");
            boolean ok = ProjectWorkspaceManager.getInstance().destroyWorkspace(projectId);
            ctx.contentType("application/json");
            ctx.result("{\"success\":" + ok + "}");
        });

        log.info("[App Gateway] 项目工作区 API 已挂载: /api/workspace/create|list|{projectId}");
        System.out.println("  ✓ [App Gateway] 项目工作区 API: /api/workspace/create|list|{projectId}");

        // ════════════════════════════════════════════════════════════════
        //  Human-in-the-Loop 恢复 API
        // ════════════════════════════════════════════════════════════════
        app.get("/api/recovery/pending", ctx -> {
            var pending = com.ouisani.aios.core.recovery.RecoveryOrchestrator.instance()
                    .getPendingHumanInterventions();
            StringBuilder json = new StringBuilder("{\"pending\":[");
            boolean first = true;
            for (var entry : pending.entrySet()) {
                if (!first) json.append(",");
                var req = entry.getValue();
                json.append(String.format(
                        "{\"nodeId\":\"%s\",\"workflowId\":\"%s\",\"diagnosis\":\"%s\",\"timestamp\":%d}",
                        req.nodeId().replace("\"", "\\\""),
                        req.workflowId().replace("\"", "\\\""),
                        req.diagnosis().replace("\"", "'").replace("\n", " "),
                        req.timestamp()
                ));
                first = false;
            }
            json.append("]}");
            ctx.contentType("application/json");
            ctx.result(json.toString());
        });

        // CORS 预检支持
        app.options("/api/recovery/pending", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        app.post("/api/recovery/{nodeId}/resume", ctx -> {
            String nodeId = ctx.pathParam("nodeId");
            // 读取请求体作为人类指导
            String body = ctx.body();
            // 从 JSON 中提取 guidance
            String guidance = "";
            Matcher guidanceMatcher = Pattern.compile("\"guidance\"\\s*:\\s*\"([^\"]*?)\"").matcher(body);
            if (guidanceMatcher.find()) guidance = guidanceMatcher.group(1);

            boolean success = com.ouisani.aios.core.recovery.RecoveryOrchestrator.instance()
                    .resumeFromHumanIntervention(nodeId, guidance);

            String resp = String.format("{\"success\":%b,\"nodeId\":\"%s\"}", success, nodeId);
            ctx.contentType("application/json");
            ctx.status(success ? 200 : 500);
            ctx.result(resp);
        });

        // CORS 预检支持
        app.options("/api/recovery/{nodeId}/resume", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        log.info("[App Gateway] Human-in-the-Loop 恢复 API 已挂载: /api/recovery/pending, /api/recovery/{nodeId}/resume");
        System.out.println("  ✓ [App Gateway] Human-in-the-Loop 恢复 API: /api/recovery/pending, /api/recovery/{nodeId}/resume");

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
            String nodeId = extractJsonField(body, "nodeId");
            String endpoint = extractJsonField(body, "endpoint");
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

        // ════════════════════════════════════════════════════════════════
        //  Cross-Validation — 多模型对抗与交叉审查（借鉴 OmniGent Debby & Polly）
        // ════════════════════════════════════════════════════════════════

        app.post("/api/cross-validation/run", ctx -> {
            String body = ctx.body();
            var jsonObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            String task = jsonObj.get("task").getAsString();
            String providerA = jsonObj.has("providerA") ? jsonObj.get("providerA").getAsString() : "smart_model";
                String providerB = jsonObj.has("providerB") ? jsonObj.get("providerB").getAsString() : "fast_model";
            int maxRounds = jsonObj.has("maxRounds") ? jsonObj.get("maxRounds").getAsInt() : 3;

            // 异步执行，避免阻塞 HTTP 请求
            Thread.startVirtualThread(() -> {
                try {
                    var result = com.ouisani.aios.user.apps.omnifactory.CrossValidationTemplate.execute(
                            task, providerA, providerB, maxRounds);
                    com.ouisani.aios.core.network.EventBus.instance().broadcast(
                            "cross_validation.result",
                            new com.google.gson.Gson().toJson(result));
                } catch (Exception e) {
                    log.error("[App Gateway] Cross-Validation 执行失败: {}", e.getMessage());
                }
            });

            ctx.result("{\"status\":\"started\",\"message\":\"Cross-Validation 已启动，结果将通过 EventBus 推送\"}")
               .contentType("application/json");
        });

        // 获取可用的 LLM Provider 列表（用于前端选择对抗模型）
        app.get("/api/cross-validation/providers", ctx -> {
            var router = com.ouisani.aios.core.llm.LlmRouterHolder.get();
            if (router == null) {
                ctx.result("{\"providers\":[]}").contentType("application/json");
                return;
            }
            var backends = router.getBackends();
            StringBuilder json = new StringBuilder("{\"providers\":[");
            boolean first = true;
            for (var entry : backends.entrySet()) {
                if (!first) json.append(",");
                json.append(String.format("{\"name\":\"%s\",\"core\":\"%s\"}",
                        entry.getKey(), entry.getValue().computeCore()));
                first = false;
            }
            json.append("]}");
            ctx.result(json.toString()).contentType("application/json");
        });

        log.info("[App Gateway] Cross-Validation API 已挂载: /api/cross-validation/run, /api/cross-validation/providers");
        System.out.println("  ✓ [App Gateway] Cross-Validation API: /api/cross-validation/run, /api/cross-validation/providers");

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

    // ════════════════════════════════════════════════════════════════
    //  WebSocket 心跳保活 — 防止空闲超时断开
    // ════════════════════════════════════════════════════════════════

    /** Workflow 控制通道的心跳任务引用，用于在连接断开时取消 */
    private static final ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>> workflowHeartbeats = new ConcurrentHashMap<>();

    /**
     * 为 Workflow 控制通道启动定时心跳 ping，防止 WebSocket 因空闲超时被断开。
     * 心跳间隔 2 分钟，远小于 5 分钟的 idle timeout。
     */
    private static void scheduleWorkflowHeartbeat(WsContext ctx) {
        String sessionId = ctx.sessionId();
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "workflow-heartbeat-" + sessionId);
            t.setDaemon(true);
            return t;
        });
        var future = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (ctx.session.isOpen()) {
                    ctx.send("__ping__");
                } else {
                    workflowHeartbeats.remove(sessionId);
                    scheduler.shutdown();
                }
            } catch (Exception e) {
                workflowHeartbeats.remove(sessionId);
                scheduler.shutdown();
            }
        }, 2, 2, java.util.concurrent.TimeUnit.MINUTES);
        workflowHeartbeats.put(sessionId, future);
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON 解析器 — 正则提取，兼容前端各种格式
    // ════════════════════════════════════════════════════════════════

    /**
     * 将前端传来的 JSON 解析为 WorkflowManifest。
     * <p>
     * 预期格式：
     * <pre>
     * {
     *   "workflowName": "my_workflow",
     *   "nodes": [
     *     {
     *       "instanceId": "agent_1",
     *       "blueprintId": "spider_agent",
     *       "role": "爬取数据",
     *       "subscribeTopic": "",
     *       "publishTopic": "topic_agent_1_agent_2",
     *       "userParams": {}
     *     }
     *   ]
     * }
     * </pre>
     */
    private static WorkflowManifest parseWorkflowManifest(String json) {
        String workflowName = extractJsonField(json, "workflowName");
        if (workflowName == null || workflowName.isBlank()) {
            workflowName = "dashboard_workflow";
        }

        // 提取 nodes 数组部分
        String nodesArray = extractJsonArray(json, "nodes");
        if (nodesArray == null || nodesArray.isBlank()) {
            throw new IllegalArgumentException("负载中缺少 'nodes' 数组或数组为空");
        }

        // 逐个解析节点对象 — 使用安全的深度感知分割器
        List<WorkflowNode> nodes = new ArrayList<>();
        List<String> rawNodes = splitJsonObjectsSafe(nodesArray);
        for (String obj : rawNodes) {
            String instanceId = extractJsonField(obj, "instanceId");
            String blueprintId = extractJsonField(obj, "blueprintId");
            String role = extractJsonField(obj, "role");
            String executor = extractJsonField(obj, "executor");
            String subscribeTopic = extractJsonField(obj, "subscribeTopic");
            String publishTopic = extractJsonField(obj, "publishTopic");
            Map<String, String> userParams = extractUserParams(obj);

            if (instanceId != null && !instanceId.isBlank()) {
                WorkflowNode node = new WorkflowNode(
                        instanceId.trim(),
                        role != null ? role.trim() : "",
                        blueprintId != null ? blueprintId.trim() : instanceId.trim(),
                        userParams,
                        subscribeTopic != null ? subscribeTopic.trim() : "",
                        publishTopic != null ? publishTopic.trim() : "",
                        executor != null ? executor.trim() : "omni"
                );

                // 解析 upstreamDependencies — 决定哪些节点可以并发执行
                String depsArray = extractJsonArray(obj, "upstreamDependencies");
                if (depsArray != null && !depsArray.isBlank()) {
                    for (String dep : depsArray.split(",")) {
                        String trimmed = dep.trim().replaceAll("[\"\\[\\]\\s]", "");
                        if (!trimmed.isEmpty()) {
                            node.addDependency(trimmed);
                        }
                    }
                }

                nodes.add(node);
            }
        }

        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("负载中未找到有效节点");
        }

        // 解析 enabledSkills / enabledRoles 数组
        List<String> enabledSkills = extractJsonStringArray(json, "enabledSkills");
        List<String> enabledRoles = extractJsonStringArray(json, "enabledRoles");

        // 解析 agentType（默认 "omni"）
        String agentType = extractJsonField(json, "agentType");
        if (agentType == null || agentType.isBlank()) agentType = "omni";

        return new WorkflowManifest(workflowName, nodes, enabledSkills, enabledRoles, agentType);
    }

    /**
     * 从 JSON 中提取指定 key 对应的字符串数组。
     * <p>
     * 例如：{"enabledSkills": ["skills.web_scraper", "skills.file_ops"]}
     */
    private static List<String> extractJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String arrayContent = extractJsonArray(json, key);
        if (arrayContent == null || arrayContent.isBlank()) return result;

        Pattern stringPattern = Pattern.compile("\"([^\"]+)\"");
        Matcher matcher = stringPattern.matcher(arrayContent);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * 从 JSON 中提取指定 key 的字符串值。
     */
    private static String extractJsonField(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        // 尝试无引号格式
        Pattern rawP = Pattern.compile("\"" + key + "\"\\s*:\\s*([^,}\\s]+)");
        Matcher rawM = rawP.matcher(json);
        if (rawM.find()) return rawM.group(1).trim();
        return null;
    }

    /**
     * 从 JSON 中提取指定 key 对应的对象内容（含花括号）。
     * 用于提取 HOT_PATCH_PARAM 中的 params 字段。
     */
    private static String extractJsonObject(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        int start = m.start() + m.group().length() - 1, depth = 0, pos = start;
        boolean inStr = false, esc = false;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (esc) esc = false;
            else if (c == '\\') esc = true;
            else if (c == '"') inStr = !inStr;
            else if (!inStr) {
                if (c == '{') depth++; else if (c == '}') {
                    depth--; if (depth == 0) return json.substring(start, pos + 1);
                }
            }
            pos++;
        }
        return null;
    }

    /**
     * 从 JSON 中提取指定 key 对应的数组内容（不含方括号）。
     */
    public static String extractJsonArray(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        int start = m.end(), depth = 1, pos = start;
        boolean inStr = false, esc = false;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (esc) esc = false;
            else if (c == '\\') esc = true;
            else if (c == '"') inStr = !inStr;
            else if (!inStr) {
                if (c == '[') depth++; else if (c == ']') depth--;
            }
            pos++;
        }
        return depth == 0 ? json.substring(start, pos - 1) : null;
    }

    /**
     * 从 JSON 对象中提取 userParams 字典。
     */
    private static Map<String, String> extractUserParams(String jsonObj) {
        Map<String, String> params = new LinkedHashMap<>();
        String content = extractJsonObject(jsonObj, "userParams");
        if (content == null || content.length() < 2) return params;
        content = content.substring(1, content.length() - 1);
        Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher kvMatcher = kvPattern.matcher(content);
        while (kvMatcher.find()) {
            params.put(kvMatcher.group(1), kvMatcher.group(2));
        }
        return params;
    }

    /**
     * 安全分割 JSON 数组中的对象 — 无视字符串内的花括号干扰。
     * <p>
     * 使用深度感知的状态机遍历，正确处理转义字符和字符串内的花括号，
     * 避免正则表达式在嵌套 JSON 或字符串含花括号时误匹配。
     */
    public static List<String> splitJsonObjectsSafe(String jsonArrayInner) {
        List<String> objects = new ArrayList<>();
        if (jsonArrayInner == null) return objects;
        int depth = 0, objStart = -1;
        boolean inString = false, escape = false;
        for (int pos = 0; pos < jsonArrayInner.length(); pos++) {
            char c = jsonArrayInner.charAt(pos);
            if (escape) { escape = false; }
            else if (c == '\\') { escape = true; }
            else if (c == '"') { inString = !inString; }
            else if (!inString) {
                if (c == '{') {
                    if (depth == 0) objStart = pos;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart != -1) {
                        objects.add(jsonArrayInner.substring(objStart, pos + 1));
                        objStart = -1;
                    }
                }
            }
        }
        return objects;
    }

    /**
     * 语义搜索 — 由 IntentRouter 的 SEMANTIC_SEARCH 意图分发调用。
     * <p>
     * 通过 Jina Search 搜索互联网，返回结果摘要。
     *
     * @param query 搜索查询
     * @return 搜索结果
     */
    public String handleSemanticSearch(String query) {
        try {
            String result = com.ouisani.aios.core.plugin.WebSearchTool.searchForAgent(query);
            return result.isEmpty() ? "未找到搜索结果。" : result;
        } catch (Exception e) {
            log.warn("[Gateway] 语义搜索失败: {}", e.getMessage());
            return "语义搜索不可用: " + e.getMessage();
        }
    }

    /**
     * 将内核自愈告警广播给所有已连接的可视化大屏。
     * <p>
     * 当 AutoMedic 熔断触发 {@code sys.human_intervention_required} 事件时，
     * 此方法将告警 JSON 实时推送给前端大屏，实现 Human-in-the-Loop 介入。
     *
     * @param payload 告警事件数据
     */
    private static void broadcastToDashboards(Object payload) {
        String message = payload != null ? payload.toString() : "{}";
        int sent = 0;
        Iterator<WsContext> it = dashboardObservers.iterator();
        while (it.hasNext()) {
            WsContext ctx = it.next();
            try {
                if (ctx.session.isOpen()) {
                    ctx.send(message);
                    sent++;
                } else {
                    it.remove();
                }
            } catch (Exception e) {
                log.debug("[Gateway] 向 Dashboard 观察者推送告警失败: {}", e.getMessage());
                it.remove();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Registry Catalogs — 已迁移到路由内联，使用相对路径
    // ════════════════════════════════════════════════════════════════
}
