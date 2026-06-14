package com.ouisani.aios.core.network;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.user.apps.omnifactory.OmniMotherAgent;
import com.ouisani.aios.user.apps.omnifactory.OperatorAgent;
import com.ouisani.aios.user.apps.omnifactory.TopologyCompiler;
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
 * const ws = new WebSocket("ws://localhost:8080/api/app/data_pipeline/stream?token=AIOS-SUPER-SECRET-KEY");
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

        // ════════════════════════════════════════════════════════════════
        //  Dashboard Alert WebSocket — 内核自愈告警实时推送通道
        // ════════════════════════════════════════════════════════════════
        app.ws("/api/dashboard/alerts", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[Gateway] Unauthorized dashboard WebSocket connection attempt");
                    ctx.session.close();
                    return;
                }
                dashboardObservers.add(ctx);
                log.info("[Gateway] Dashboard observer connected");
                System.out.println("  📡 [Gateway] Dashboard observer connected");
            });

            ws.onClose(ctx -> {
                dashboardObservers.remove(ctx);
                log.info("[Gateway] Dashboard observer disconnected");
            });

            ws.onError(ctx -> {
                dashboardObservers.remove(ctx);
                log.warn("[Gateway] Dashboard WebSocket error: {}",
                        ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });
        });

        // 订阅内核自愈告警频道 → 广播给所有大屏观察者
        EventBus.instance().subscribe("sys.human_intervention_required", AppGateway::broadcastToDashboards);

        // 将系统心跳和底层日志桥接到前端可视化大屏
        EventBus.instance().subscribe("sys.telemetry.metrics", AppGateway::broadcastToDashboards);
        EventBus.instance().subscribe("sys.eventbus.logs", AppGateway::broadcastToDashboards);

        // 订阅可观测性事件通道 — 邮件飞梭、自愈重试等动效数据源
        EventBus.instance().subscribe("sys.telemetry.events", AppGateway::broadcastToDashboards);

        log.info("[Gateway] System Alert Channel opened. Ready to push high-priority rescue signals to dashboard.");
        System.out.println("[Gateway] System Alert Channel opened. Ready to push high-priority rescue signals to dashboard.");

        // ════════════════════════════════════════════════════════════════
        //  God Hand Protocol — 前端参数热补丁控制通道
        // ════════════════════════════════════════════════════════════════
        app.ws("/api/app/god_hand/control", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[Gateway] Unauthorized God Hand WebSocket connection attempt");
                    ctx.session.close();
                    return;
                }
                log.info("[Gateway] God Hand control channel connected");
                System.out.println("[Gateway] God Hand control channel connected");
            });

            ws.onMessage(ctx -> {
                String message = ctx.message();
                try {
                    String action = extractJsonField(message, "action");
                    if ("HOT_PATCH_PARAM".equals(action)) {
                        String targetNode = extractJsonField(message, "targetNode");
                        String paramsJson = extractJsonObject(message, "params");

                        if (targetNode == null || targetNode.isBlank()) {
                            log.warn("[Gateway] HOT_PATCH_PARAM missing targetNode");
                            return;
                        }

                        // 1. 将新参数覆盖写入 VFS 配置文件
                        String configPath = "/factory/configs/" + targetNode + ".json";
                        AiosSdk.getInstance().writeFile("god_hand", configPath,
                                paramsJson != null ? paramsJson : "{}");

                        // 2. 向 EventBus 广播参数变更通知
                        EventBus.instance().broadcast("sys.control." + targetNode, "CONFIG_UPDATED");

                        System.out.printf("[Gateway] God Hand: HOT_PATCH_PARAM → %s. Config written to %s. EventBus notified.%n",
                                targetNode, configPath);
                        log.info("[Gateway] God Hand: HOT_PATCH_PARAM for node '{}'. Config: {}", targetNode, configPath);
                    }
                } catch (Exception e) {
                    log.warn("[Gateway] Failed to process God Hand message: {}", e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                log.info("[Gateway] God Hand control channel disconnected");
            });

            ws.onError(ctx -> {
                log.warn("[Gateway] God Hand WebSocket error: {}",
                        ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });
        });

        log.info("[Gateway] God Hand Protocol attached: /api/app/god_hand/control");
        System.out.println("[Gateway] God Hand Protocol attached: /api/app/god_hand/control");

        // ════════════════════════════════════════════════════════════════
        //  God Hand Protocol V2 — /api/workflow/control 热机干涉通道
        // ════════════════════════════════════════════════════════════════
        app.ws("/api/workflow/control", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[Gateway] Unauthorized workflow control WebSocket connection attempt");
                    ctx.session.close();
                    return;
                }
                log.info("[Gateway] Workflow control channel connected");
                System.out.println("[Gateway] Workflow control channel connected");
            });

            ws.onMessage(ctx -> {
                String message = ctx.message();
                try {
                    String action = extractJsonField(message, "action");
                    if ("HOT_PATCH_PARAM".equals(action)) {
                        String targetNode = extractJsonField(message, "targetNode");
                        String paramsJson = extractJsonObject(message, "params");

                        if (targetNode == null || targetNode.isBlank()) {
                            log.warn("[Gateway] HOT_PATCH_PARAM missing targetNode");
                            return;
                        }

                        // 强制覆写 VFS 配置文件
                        String configPath = "/factory/configs/" + targetNode + ".json";
                        VfsManager.instance().resolve(configPath)
                                .ifPresent(node -> node.write(paramsJson != null ? paramsJson : "{}"));

                        // 触发系统级广播
                        EventBus.instance().broadcast("sys.control." + targetNode, "CONFIG_UPDATED");

                        System.out.printf("[Gateway] God Hand Protocol engaged. Hot-patched VFS config for node %s.%n",
                                targetNode);
                        log.info("[Gateway] God Hand Protocol engaged. Hot-patched VFS config for node '{}'.", targetNode);
                    }
                } catch (Exception e) {
                    log.warn("[Gateway] Failed to process workflow control message: {}", e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                log.info("[Gateway] Workflow control channel disconnected");
            });

            ws.onError(ctx -> {
                log.warn("[Gateway] Workflow control WebSocket error: {}",
                        ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });
        });

        log.info("[Gateway] Workflow control channel attached: /api/workflow/control");
        System.out.println("[Gateway] Workflow control channel attached: /api/workflow/control");

        // ════════════════════════════════════════════════════════════════
        //  POST /api/workflow/compile — 两段式生成：第一段，动态拓扑编译
        // ════════════════════════════════════════════════════════════════
        app.post("/api/workflow/compile", ctx -> {
            String payload = ctx.body();
            System.out.printf("[App Gateway] Received topology compile request. Size: %d bytes.%n", payload.length());
            log.info("[App Gateway] Received topology compile request. Size: {} bytes.", payload.length());

            try {
                // 解析请求 JSON
                String prompt = extractJsonField(payload, "prompt");
                if (prompt == null || prompt.isBlank()) {
                    ctx.status(400);
                    ctx.contentType("application/json");
                    ctx.result("{\"status\":\"error\",\"message\":\"Missing 'prompt' field\"}");
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
                    log.info("[App Gateway] Auto-discovered {} skills for compilation", enabledSkills.size());
                }

                // 解析 enabledRoles 数组
                List<String> enabledRoles = extractJsonStringArray(payload, "enabledRoles");

                System.out.printf("[App Gateway] Compiling topology: prompt='%s...', skills=%s, roles=%s%n",
                        prompt.substring(0, Math.min(prompt.length(), 50)), enabledSkills, enabledRoles);
                log.info("[App Gateway] Compiling topology: skills={}, roles={}", enabledSkills, enabledRoles);

                // 调用 TopologyCompiler 编译拓扑
                String topologyJson = TopologyCompiler.compileTopology(prompt, enabledSkills, enabledRoles);

                // 如果编译结果中没有 agentType，从前端 payload 中提取，默认 "omni"
                if (!topologyJson.contains("\"agentType\"")) {
                    String agentType = extractJsonField(payload, "agentType");
                    if (agentType == null || agentType.isBlank()) agentType = "omni";
                    topologyJson = topologyJson.substring(0, topologyJson.lastIndexOf('}'))
                                   + ", \"agentType\": \"" + agentType + "\"}";
                }

                ctx.contentType("application/json");
                ctx.result(topologyJson);
                System.out.printf("[App Gateway] Topology compiled successfully. Response size: %d bytes.%n",
                        topologyJson.length());
            } catch (Exception e) {
                System.out.printf("[App Gateway] Topology compile failed: %s%n", e.getMessage());
                log.error("[App Gateway] Topology compile failed", e);
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

        log.info("[App Gateway] Topology Compile API attached: POST /api/workflow/compile");
        System.out.println("  ✓ [App Gateway] Topology Compile API: POST /api/workflow/compile");

        // ════════════════════════════════════════════════════════════════
        //  POST /api/workflow/deploy — 前端可视化大屏 → AIOS 内核
        // ════════════════════════════════════════════════════════════════
        app.post("/api/workflow/deploy", ctx -> {
            String payload = ctx.body();
            System.out.printf("[App Gateway] Received Omni-Workflow payload from dashboard. Size: %d bytes.%n",
                    payload.length());
            log.info("[App Gateway] Received Omni-Workflow payload from dashboard. Size: {} bytes.", payload.length());

            try {
                // 解析 JSON → WorkflowManifest
                WorkflowManifest manifest = parseWorkflowManifest(payload);

                System.out.printf("[App Gateway] Parsed workflow '%s' with %d nodes.%n",
                        manifest.workflowName(), manifest.nodes().size());
                log.info("[App Gateway] Parsed workflow '{}' with {} nodes.",
                        manifest.workflowName(), manifest.nodes().size());

                // 通过内核调度器拉起母体智能体（按节点 executor 动态路由）
                // 将节点按 executor 分组，每组拉起对应的母体
                List<WorkflowNode> omniNodes = new ArrayList<>();
                List<WorkflowNode> operatorNodes = new ArrayList<>();
                for (WorkflowNode node : manifest.nodes()) {
                    if ("operator".equalsIgnoreCase(node.executor())) {
                        operatorNodes.add(node);
                    } else {
                        omniNodes.add(node);
                    }
                }

                TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();

                // 拉起 OmniMotherAgent 处理逻辑/代码节点
                if (!omniNodes.isEmpty()) {
                    WorkflowManifest omniManifest = new WorkflowManifest(
                            manifest.workflowName() + "_omni", omniNodes,
                            manifest.enabledSkills(), manifest.enabledRoles(), "omni");
                    AbstractAgent omni = new OmniMotherAgent(omniManifest);
                    omni.spawn(scheduler);
                    System.out.printf("[App Gateway] Igniting OmniMotherAgent for %d logic nodes...%n", omniNodes.size());
                    log.info("[App Gateway] Igniting OmniMotherAgent for {} logic nodes", omniNodes.size());
                }

                // 拉起 OperatorAgent 处理物理操作节点
                if (!operatorNodes.isEmpty()) {
                    WorkflowManifest operatorManifest = new WorkflowManifest(
                            manifest.workflowName() + "_operator", operatorNodes,
                            manifest.enabledSkills(), manifest.enabledRoles(), "operator");
                    AbstractAgent operator = new OperatorAgent(operatorManifest);
                    operator.spawn(scheduler);
                    System.out.printf("[App Gateway] Igniting OperatorAgent for %d physical nodes...%n", operatorNodes.size());
                    log.info("[App Gateway] Igniting OperatorAgent for {} physical nodes", operatorNodes.size());
                }

                ctx.contentType("application/json");
                ctx.result("{\"status\":\"success\",\"message\":\"Genesis Process Initiated\"}");
                System.out.println("[App Gateway] External API engaged. Ready to receive N-Node topologies.");
            } catch (Exception e) {
                System.out.printf("[App Gateway] Failed to parse workflow payload: %s%n", e.getMessage());
                log.error("[App Gateway] Failed to parse workflow payload", e);
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
                log.error("[Gateway] Catalog scan failed", e);
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

        log.info("[App Gateway] Workflow Deploy API attached: POST /api/workflow/deploy");
        System.out.println("  ✓ [App Gateway] Workflow Deploy API: POST /api/workflow/deploy");
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
            throw new IllegalArgumentException("Missing or empty 'nodes' array in payload");
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
                nodes.add(new WorkflowNode(
                        instanceId.trim(),
                        role != null ? role.trim() : "",
                        blueprintId != null ? blueprintId.trim() : instanceId.trim(),
                        userParams,
                        subscribeTopic != null ? subscribeTopic.trim() : "",
                        publishTopic != null ? publishTopic.trim() : "",
                        executor != null ? executor.trim() : "omni"
                ));
            }
        }

        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("No valid nodes found in payload");
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
            return result.isEmpty() ? "No search results found." : result;
        } catch (Exception e) {
            log.warn("[Gateway] Semantic search failed: {}", e.getMessage());
            return "Semantic search unavailable: " + e.getMessage();
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
                log.debug("[Gateway] Failed to push alert to dashboard observer: {}", e.getMessage());
                it.remove();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Registry Catalogs — 已迁移到路由内联，使用相对路径
    // ════════════════════════════════════════════════════════════════
}
