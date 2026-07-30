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
                    String action = GatewayJsonParser.extractJsonField(message, "action");
                    if ("HOT_PATCH_PARAM".equals(action)) {
                        String targetNode = GatewayJsonParser.extractJsonField(message, "targetNode");
                        String paramsJson = GatewayJsonParser.extractJsonObject(message, "params");

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
                    String action = GatewayJsonParser.extractJsonField(message, "action");
                    if ("HOT_PATCH_PARAM".equals(action)) {
                        String targetNode = GatewayJsonParser.extractJsonField(message, "targetNode");
                        String paramsJson = GatewayJsonParser.extractJsonObject(message, "params");

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
                String prompt = GatewayJsonParser.extractJsonField(payload, "prompt");
                if (prompt == null || prompt.isBlank()) {
                    ctx.status(400);
                    ctx.contentType("application/json");
                    ctx.result("{\"status\":\"error\",\"message\":\"缺少 'prompt' 字段\"}");
                    return;
                }

                // 解析 enabledSkills 数组
                List<String> enabledSkills = GatewayJsonParser.extractJsonStringArray(payload, "enabledSkills");
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
                List<String> enabledRoles = GatewayJsonParser.extractJsonStringArray(payload, "enabledRoles");

                System.out.printf("[App Gateway] 正在编译拓扑: prompt='%s...', skills=%s, roles=%s%n",
                        prompt.substring(0, Math.min(prompt.length(), 50)), enabledSkills, enabledRoles);
                log.info("[App Gateway] 正在编译拓扑: skills={}, roles={}", enabledSkills, enabledRoles);

                // 调用 TopologyCompiler 编译拓扑
                String topologyJson = TopologyCompiler.compileTopology(prompt, enabledSkills, enabledRoles);

                // 如果编译结果中没有 agentType，从前端 payload 中提取，默认 "omni"
                if (!topologyJson.contains("\"agentType\"")) {
                    String agentType = GatewayJsonParser.extractJsonField(payload, "agentType");
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
                WorkflowManifest manifest = GatewayJsonParser.parseWorkflowManifest(payload);

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
            String name = GatewayJsonParser.extractJsonField(body, "name");
            String quotaStr = GatewayJsonParser.extractJsonField(body, "quota");
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

        ManagementRoutes.attachTo(app);

        HitlStateRoutes.attachTo(app);

        // P3：记忆查看器 — GET/PATCH/DELETE /api/memory/{agentId}[/{key}]
        // 依赖 VersionedMemoryStore.setPrimaryStore 在启动时注入；未注入时端点返回 503
        MemoryViewerRoutes.attachTo(app);

        // 普通对话 — POST /api/chat（SSE 逐 token，复用 LlmRouter + 记忆注入）
        ChatRoutes.attachTo(app);

        // 工作流产物 — GET /api/artifacts/{workflowId}[/file]（列出/读取 factory 产物文件）
        ArtifactRoutes.attachTo(app);

        // 系统告警流 — WS /api/system/alerts（内核崩溃/紧急停止/死信队列/成本告警/工作流挂起/安全/心跳）
        SystemAlertRoutes.attachTo(app);

        // 工具权限审批流 — WS /api/permission/stream（桥接 PermissionChecker ASK 到前端弹窗，支持 standing scoped approvals）
        PermissionApprovalRoutes.attachTo(app);
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

        VfsBridgeRoutes.attachTo(app);
    }

    // ════════════════════════════════════════════════════════════════
    //  WebSocket 心跳保活 — 防止空闲超时断开
    // ════════════════════════════════════════════════════════════════

    /** Workflow 控制通道的心跳任务引用，用于在连接断开时取消 */
    private static final ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>> workflowHeartbeats = new ConcurrentHashMap<>();

    /**
     * 为 Workflow 控制通道启动定时心跳 ping，防止 WebSocket 因空闲超时被断开。
     * 心跳间隔 30 秒，远小于 10 分钟的 WebSocket idle timeout。
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
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
        workflowHeartbeats.put(sessionId, future);
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
