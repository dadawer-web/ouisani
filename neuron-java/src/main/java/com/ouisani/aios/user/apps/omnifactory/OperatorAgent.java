package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.compact.CompactService;
import com.ouisani.aios.core.context.ClaudeMdLoader;
import com.ouisani.aios.core.cost.CostTracker;
import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.memory.MemoryDir;
import com.ouisani.aios.core.memory.SessionMemoryService;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.core.tool.QueryEngine;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolRegistry;
import com.ouisani.aios.operator.PluginLoader;
import com.ouisani.aios.operator.PluginRegistry;
import com.ouisani.aios.operator.ToolAssembler;
import com.ouisani.aios.operator.ToolAssemblyContext;
import com.ouisani.aios.operator.channel.ChannelRegistry;
import com.ouisani.aios.operator.gateway.GatewayClient;
import com.ouisani.aios.operator.gateway.GatewayToolBridge;
import com.ouisani.aios.operator.secrets.SecretRef;
import com.ouisani.aios.operator.secrets.SecretsSnapshot;
import com.ouisani.aios.operator.session.AgentMessage;
import com.ouisani.aios.operator.session.CompactionService;
import com.ouisani.aios.operator.session.SessionContext;
import com.ouisani.aios.operator.session.SessionManager;
import com.ouisani.aios.operator.tools.BrowserTool;
import com.ouisani.aios.operator.tools.ComputerUseTool;
import com.ouisani.aios.operator.tools.DesktopGuiTool;
import com.ouisani.aios.operator.tools.GatewayControlTool;
import com.ouisani.aios.operator.tools.MessageTool;
import com.ouisani.aios.operator.tools.NodesTool;
import com.ouisani.aios.operator.vision.VisionService;
import com.ouisani.aios.vfs.ChromeBridgeNode;
import com.ouisani.aios.user.bridge.rpa.HostRpaManager;
import com.ouisani.aios.user.bridge.rpa.SecurityToken;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作员母体 (Operator Agent) — 物理交互与即时工具调用的第二母体。
 * <p>
 * 与 OmniMotherAgent（代码生成母体）不同，OperatorAgent 不写任何代码，
 * 而是直接通过 ReAct 循环操作真实的计算机系统来完成任务。
 * <p>
 * 核心差异：
 * <ul>
 *   <li>OmniMotherAgent：生成 Python 脚本 → 部署 → 运行（异步、批量）</li>
 *   <li>OperatorAgent：观察 → 思考 → 行动（同步、即时、交互式）</li>
 * </ul>
 * <p>
 * 适用场景：
 * <ul>
 *   <li>需要鼠标/键盘交互的 GUI 自动化任务</li>
 *   <li>需要即时反馈的终端操作任务</li>
 *   <li>需要截图验证的视觉任务</li>
 *   <li>需要多步骤物理交互的复杂操作</li>
 * </ul>
 */
public class OperatorAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(OperatorAgent.class);

    private final WorkflowManifest manifest;
    private final String workingDir;

    // ── Dify 内存上下文（可选，DAG 引擎注入） ──
    private WorkflowContext context;

    // ── 能力模块 ──
    private QueryEngine queryEngine;
    private SessionMemoryService.SessionMemory sessionMemory;
    private CompactService.AutoCompactState compactState;

    // ── OpenClaw 插件系统 ──
    private PluginRegistry pluginRegistry;
    private PluginLoader pluginLoader;

    // ── OpenClaw 会话管理 ──
    private SessionManager openClawSession;

    // ── OpenClaw Gateway ──
    private GatewayToolBridge gatewayBridge;

    // ── OpenClaw 渠道注册表 ──
    private ChannelRegistry channelRegistry;

    // ── OpenClaw 密钥快照 ──
    private SecretsSnapshot secretsSnapshot;

    // ── RPA 物理操作令牌 ──
    private SecurityToken rpaToken;

    // ── 视觉服务 — 截图→多模态模型→屏幕描述 ──
    private VisionService visionService;

    /** 操作员 System Prompt — 极度干练的物理交互认知 */
    private static final String OPERATOR_SYSTEM_PROMPT_TEMPLATE =
            "你是一个高级系统操作员 (System Operator)。你不写任何代码，你的任务是直接操作真实的计算机系统来完成用户的指令。\n"
            + "【核心能力】：\n"
            + "- computer_use: 控制宿主机的鼠标、键盘和屏幕截图（物理操作）\n"
            + "- BashTool: 执行终端命令\n"
            + "- FileReadTool/FileWriteTool/FileEditTool: 文件操作\n"
            + "- nodes/message/gateway: 远程节点控制与消息通信\n"
            + "\n"
            + "【Computer Use 操作铁律】：\n"
            + "1. 你处于一个 [观察 -> 思考 -> 行动] 的 ReAct 循环中。\n"
            + "2. 当你不确定屏幕状态时，必须首先调用 computer_use(action='screenshot') 获取视觉反馈。\n"
            + "3. 发现目标后，计算其 X/Y 坐标，调用 computer_use(action='click_at', x=..., y=...) 进行点击。\n"
            + "4. 需要输入文字时，调用 computer_use(action='type_text', text='...')。\n"
            + "5. 需要快捷键时，调用 computer_use(action='key_combo', ctrl=true, keyCode=67) 等。\n"
            + "6. 一步一步来，绝对不要在一次回复中猜测连续点击的坐标。每次操作后截图验证。\n"
            + "7. 如果操作失败，截图确认状态后调整策略，不要盲目重试。\n"
            + "8. 任务完成后，回复 TASK_COMPLETED 并附上操作摘要。\n"
            + "%s"; // 运行时注入渠道+密钥信息

    /** 原有构造函数：兼容 AppGateway 等旧调用方 */
    public OperatorAgent(WorkflowManifest manifest) {
        super("Operator", ProcessPriority.REALTIME, 100000);
        this.manifest = manifest;
        this.workingDir = System.getProperty("user.dir");
    }

    /** Dify 风格构造函数：DAG 引擎按节点级调度时使用 */
    public OperatorAgent(WorkflowNode node, WorkflowContext context) {
        super("Operator", ProcessPriority.REALTIME, 100000);
        this.manifest = new WorkflowManifest(
                context.getWorkflowId() + "_" + node.instanceId(),
                List.of(node), List.of(), List.of(), node.executor());
        this.context = context;
        this.workingDir = System.getProperty("user.dir");
    }

    @Override
    protected void onStart() {
        long startTime = System.currentTimeMillis();

        // ════════════════════════════════════════════════════════════════
        //  Phase 0: 内核初始化 — 挂载操作员能力
        // ════════════════════════════════════════════════════════════════
        initializeOperatorCapabilities();

        System.out.println("[Operator Agent] ══════════════════════════════════════════");
        System.out.println("[Operator Agent] Physical Interaction Mode: ONLINE");
        System.out.println("[Operator Agent] Task nodes: " + manifest.nodes().size());
        System.out.println("[Operator Agent] Tools: " + ToolRegistry.instance().all().size() + " 工具已注册");
        System.out.println("[Operator Agent] ══════════════════════════════════════════");
        log.info("[Operator Agent] Initialized | Nodes: {} | Tools: {}",
                manifest.nodes().size(), ToolRegistry.instance().all().size());

        // ════════════════════════════════════════════════════════════════
        //  Phase 1: ReAct Loop — 逐节点即时执行
        // ════════════════════════════════════════════════════════════════
        for (WorkflowNode node : manifest.nodes()) {
            System.out.println("[Operator Agent] 正在执行节点: " + node.instanceId()
                    + " | Role: " + node.role());

            // 构建操作员 Prompt — 注入节点职责和上下文
            String operatorPrompt = buildOperatorPrompt(node);

            // ── OpenClaw Session: 记录用户消息 ──
            openClawSession.appendMessage(AgentMessage.user(operatorPrompt));

            // ── CostTracker: 记录推理开始 ──
            long nodeStartTokens = CostTracker.instance().getTotalTokens();

            // ── QueryEngine ReAct 循环 — LLM 自主调用工具直到任务完成 ──
            String result = queryEngine.query(operatorPrompt, buildRuntimeSystemPrompt());

            // ── OpenClaw Session: 记录助手回复 ──
            openClawSession.appendMessage(AgentMessage.assistant(result));

            // ── OpenClaw Session: 检查是否需要压缩 ──
            SessionContext ctx = openClawSession.buildSessionContext();
            long estimatedTokens = CompactionService.estimateTokens(ctx.messages());
            if (CompactionService.shouldCompact(estimatedTokens, 128000,
                    CompactionService.CompactionSettings.DEFAULT)) {
                System.out.println("[Operator Agent] Context approaching limit ("
                        + estimatedTokens + " tokens), compaction recommended");
                log.info("[Operator Agent] Compaction recommended: {} tokens", estimatedTokens);
            }

            // ── CostTracker: 记录本节点消耗 ──
            long nodeTokens = CostTracker.instance().getTotalTokens() - nodeStartTokens;
            CostTracker.CostLevel costLevel = CostTracker.instance().checkThreshold();

            // ── MemoryDir: 将操作记录保存到跨会话记忆 ──
            MemoryDir.instance().save(new MemoryDir.MemoryEntry(
                    node.instanceId(), MemoryDir.MemoryType.PROJECT,
                    "Role: " + node.role() + " | Completed: " + result.contains("TASK_COMPLETED"),
                    System.currentTimeMillis(), new String[]{"operator", node.instanceId()}
            ));

            // ── 遥测：记录节点执行 ──
            TelemetryService.instance().logEvent("node_operated", Map.of(
                    "node_id", node.instanceId(),
                    "role", node.role(),
                    "completed", result.contains("TASK_COMPLETED")
            ));

            System.out.printf("[Operator Agent]   ├─ Node '%s' executed. Completed: %s%n",
                    node.instanceId(), result.contains("TASK_COMPLETED"));

            // ── Dify 内存总线：将节点输出提交到全局上下文，供下游节点收割 ──
            if (this.context != null) {
                Map<String, Object> outputs = new HashMap<>();
                outputs.put("result_text", result);
                this.context.commitNodeOutput(node.instanceId(), outputs);
                log.info("[OperatorAgent] 节点 '{}' 输出已提交至内存总线", node.instanceId());
            }

            // ── SessionMemory: 记录操作到会话记忆 ──
            sessionMemory.setSection(SessionMemoryService.Section.FILES_AND_FUNCTIONS,
                    sessionMemory.getSection(SessionMemoryService.Section.FILES_AND_FUNCTIONS)
                            + "\n- [Operator] " + node.instanceId() + ": " + node.role());
            sessionMemory.incrementToolCalls();
        }

        // ════════════════════════════════════════════════════════════════
        //  Phase 2: 完成 — 汇报与清理
        // ════════════════════════════════════════════════════════════════
        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("[Operator Agent] All nodes executed. Elapsed: " + elapsed + "ms");
        log.info("[Operator Agent] All {} nodes executed in {}ms", manifest.nodes().size(), elapsed);

        // ── SessionMemory: 更新工作日志 ──
        sessionMemory.setSection(SessionMemoryService.Section.WORKLOG,
                "Executed " + manifest.nodes().size() + " operator nodes for: " + manifest.workflowName());
        sessionMemory.setSection(SessionMemoryService.Section.CURRENT_STATE,
                "All operator tasks completed for workflow: " + manifest.workflowName());

        // ── Hook: SessionEnd ──
        HookManager.instance().trigger(HookManager.HookEvent.SESSION_END, Map.of(
                "agent_id", this.agentId,
                "workflow", manifest.workflowName(),
                "nodes", manifest.nodes().size(),
                "elapsed_ms", elapsed
        ));

        // ── Telemetry: 会话结束 ──
        TelemetryService.instance().logEvent("session_end", Map.of(
                "agent_id", this.agentId,
                "workflow", manifest.workflowName(),
                "node_count", manifest.nodes().size(),
                "elapsed_ms", elapsed,
                "total_tokens", CostTracker.instance().getTotalTokens()
        ));

        // ── CostTracker: 成本报告 ──
        CostTracker.instance().formatReport();
    }

    @Override
    protected void onMessage(String msg) {
        System.out.println("[Operator Agent] Received message: " + msg);
        // ── OpenClaw Session: 记录入站消息 ──
        openClawSession.appendMessage(AgentMessage.user(msg));
        // 操作员收到消息时，直接作为新任务注入 QueryEngine
        String result = queryEngine.query(msg, buildRuntimeSystemPrompt());
        // ── OpenClaw Session: 记录助手回复 ──
        openClawSession.appendMessage(AgentMessage.assistant(result));
        System.out.println("[Operator Agent] Message processed. Result length: " + result.length());
    }

    // ════════════════════════════════════════════════════════════════
    //  Team Mailbox: Actor Mode 任务处理
    // ════════════════════════════════════════════════════════════════

    /**
     * Actor 模式任务处理 — DAG 引擎通过 TeamRegistry 派发 TaskPayload 时调用。
     * <p>
     * 与 {@link #onStart()} 的区别：
     * <pre>
     *   onStart()     — 旧模式，由 run() → onStart() 调用，执行完自动退出
     *   handleTask()  — Actor 模式，由 startEventLoop() → handleTask() 调用，
     *                   执行完毕后填写回执单，唤醒 DAG 引擎
     * </pre>
     */
    @Override
    protected void handleTask(Object rawPayload) {
        if (!(rawPayload instanceof com.ouisani.aios.core.team.TaskPayload payload)) {
            log.error("[OperatorAgent] 收到无效负载类型: {}", rawPayload.getClass().getSimpleName());
            return;
        }

        try {
            log.info("[OperatorAgent] Executing DAG task: node={}", payload.node().instanceId());

            // 复用 onStart() 中的全部业务逻辑
            // onStart() 内部会调用 initializeOperatorCapabilities() + 遍历节点 + 写入内存总线
            onStart();

            log.info("[OperatorAgent] Task complete. Signing receipt for node: {}", payload.node().instanceId());
            // 业务办完，填写回执单，唤醒正在等待的 DAG 主引擎！
            payload.completionReceipt().complete(null);

        } catch (Exception e) {
            log.error("[OperatorAgent] 节点任务失败: {}", payload.node().instanceId(), e);
            // 异常也要填写回执单（异常版），否则 DAG 引擎会死锁
            payload.completionReceipt().completeExceptionally(e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部方法
    // ════════════════════════════════════════════════════════════════

    /** OpenClaw 插件目录 — 与 aios_skills 同级 */
    private static final String OPENCLAW_PLUGINS_DIR = com.ouisani.aios.core.config.AiosPaths.openclawPluginsDir();
    /** OpenClaw 会话目录 */
    private static final String OPENCLAW_SESSIONS_DIR = com.ouisani.aios.core.config.AiosPaths.openclawSessionsDir();

    /** 构建运行时 System Prompt — 注入渠道信息和密钥状态 */
    private String buildRuntimeSystemPrompt() {
        StringBuilder extra = new StringBuilder();

        // ── 注入可用渠道信息 ──
        if (channelRegistry != null && channelRegistry.size() > 0) {
            extra.append("\n【可用消息渠道】：\n");
            for (var entry : channelRegistry.all()) {
                extra.append("  - ").append(entry.id());
                if (!entry.aliases().isEmpty()) extra.append(" (别名: ").append(entry.aliases()).append(")");
                extra.append(": ").append(entry.name());
                if (entry.markdownCapable()) extra.append(" [支持Markdown]");
                extra.append("\n");
            }
        }

        // ── 注入密钥状态 ──
        if (secretsSnapshot != null && secretsSnapshot.secretKeys().size() > 0) {
            extra.append("\n【已加载的 API 密钥】：\n");
            for (String key : secretsSnapshot.secretKeys()) {
                // 只显示 provider 名，不泄露密钥值
                String provider = key.contains(":") ? key.split(":")[1] : key;
                extra.append("  - ").append(provider).append(": 已就绪\n");
            }
        }

        return String.format(OPERATOR_SYSTEM_PROMPT_TEMPLATE, extra.toString());
    }

    private void initializeOperatorCapabilities() {
        // ── 0. OpenClaw 插件系统 — 扫描并加载插件 ──
        this.pluginRegistry = new PluginRegistry();
        this.pluginLoader = new PluginLoader(pluginRegistry, Paths.get(OPENCLAW_PLUGINS_DIR));
        int pluginCount = pluginLoader.scanAndLoad();
        System.out.println("[Operator Agent] OpenClaw 插件已加载: " + pluginCount);

        // ── 1. OpenClaw 会话管理 — 创建/恢复会话 ──
        Path sessionDir = Paths.get(OPENCLAW_SESSIONS_DIR);
        try {
            this.openClawSession = SessionManager.continueRecent(workingDir, sessionDir);
        } catch (Exception e) {
            log.warn("[Operator Agent] Failed to resume session, creating new: {}", e.getMessage());
            this.openClawSession = SessionManager.create(workingDir, sessionDir);
        }
        this.openClawSession.appendSessionInfo(manifest.workflowName());
        System.out.println("[Operator Agent] OpenClaw session: " + openClawSession.getSessionId());

        // ── 1.5. OpenClaw Gateway — 初始化 Gateway 连接 ──
        String gatewayUrl = System.getenv().getOrDefault("OPENCLAW_GATEWAY_URL", "http://localhost:4440");
        String gatewayToken = System.getenv().getOrDefault("OPENCLAW_GATEWAY_TOKEN", "");
        String gatewayPassword = System.getenv().getOrDefault("OPENCLAW_GATEWAY_PASSWORD", "");
        GatewayClient gatewayClient = new GatewayClient(gatewayUrl, gatewayToken, gatewayPassword);
        this.gatewayBridge = new GatewayToolBridge(gatewayClient);
        System.out.println("[Operator Agent] OpenClaw Gateway: " + gatewayUrl);

        // ── 1.6. OpenClaw 渠道注册表 — 注册内置渠道 ──
        this.channelRegistry = new ChannelRegistry();
        channelRegistry.register(new ChannelRegistry.ChannelEntry(
                "webchat", "Web Chat", List.of("web", "chat"), true,
                true, true, true, false, "openclaw-core"));
        channelRegistry.register(new ChannelRegistry.ChannelEntry(
                "telegram", "Telegram", List.of("tg"), true,
                true, true, true, true, "openclaw-telegram"));
        channelRegistry.register(new ChannelRegistry.ChannelEntry(
                "discord", "Discord", List.of("dc"), true,
                true, true, true, false, "openclaw-discord"));
        System.out.println("[Operator Agent] OpenClaw 通道已注册: " + channelRegistry.size());

        // ── 1.7. OpenClaw 密钥快照 — 解析运行时密钥 ──
        List<SecretRef> secretRefs = new ArrayList<>();
        // 从环境变量自动发现常见 Provider 密钥
        for (String envKey : List.of("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "DEEPSEEK_API_KEY",
                "GOOGLE_API_KEY", "GROQ_API_KEY")) {
            if (System.getenv(envKey) != null) {
                secretRefs.add(SecretRef.parse("env:llm:" + envKey));
            }
        }
        this.secretsSnapshot = SecretsSnapshot.prepare(secretRefs, Map.of());
        if (!secretsSnapshot.warnings().isEmpty()) {
            System.out.println("[Operator Agent] Secrets warnings: " + secretsSnapshot.warnings());
        }
        System.out.println("[Operator Agent] OpenClaw secrets: " + secretsSnapshot.secretKeys().size() + " resolved");

        // ── 1.8. RPA 物理操作令牌 — 签发 SYS_ADMIN 令牌 ──
        try {
            HostRpaManager rpaManager = HostRpaManager.getInstance();
            if (rpaManager.isAvailable()) {
                this.rpaToken = rpaManager.issueSysAdminToken("OperatorAgent:" + manifest.workflowName());
                System.out.println("[Operator Agent] RPA: SYS_ADMIN Token 已签发 — 物理控制已启用");
            } else {
                System.out.println("[Operator Agent] RPA: HostRpaManager 不可用（无头模式？）— 物理控制已禁用");
            }
        } catch (Exception e) {
            System.out.println("[Operator Agent] RPA: Token 签发失败 — " + e.getMessage());
        }

        // ── 1.9. VisionService — 从 LlmRouter 获取多模态 Provider ──
        try {
            com.ouisani.aios.core.llm.LlmProvider multimodalProvider =
                    com.ouisani.aios.core.llm.LlmRouterHolder.getProvider("multimodal");

            // Fallback: 如果没有独立的 multimodal provider，使用主 LLM（mimo-v2.5-pro 本身就是多模态模型）
            if (multimodalProvider == null || !multimodalProvider.isAvailable()) {
                com.ouisani.aios.core.llm.LlmRouter router = com.ouisani.aios.core.llm.LlmRouterHolder.get();
                if (router != null) {
                    multimodalProvider = router.getProvider("smart_model");
                    if (multimodalProvider != null) {
                        System.out.println("[Operator Agent] VisionService: 使用主 LLM 作为多模态回退 ("
                                + multimodalProvider.name() + ")");
                    }
                }
            }

            if (multimodalProvider != null && multimodalProvider.isAvailable()) {
                this.visionService = new VisionService(multimodalProvider);
                System.out.println("[Operator Agent] VisionService: 在线 — multimodal provider '"
                        + multimodalProvider.name() + "' connected");
            } else {
                System.out.println("[Operator Agent] VisionService: 已禁用 — no multimodal provider registered");
            }
        } catch (Exception e) {
            System.out.println("[Operator Agent] VisionService: Failed to initialize — " + e.getMessage());
        }

        // ── 2. 工具装配 — 内核工具 + 插件工具 + 策略过滤 ──
        // overnight 上下文感知：若当前线程在 overnight run 中（InheritableThreadLocal 继承自 coordinator），
        // 用 DONT_ASK 权限画像构造，把散落在 prompt 的硬约束收编到结构化规则层强制。
        List<Tool<? extends ToolInput>> operatorTools = buildOperatorToolList();
        com.ouisani.aios.core.permission.PermissionProfile overnightProfile =
                com.ouisani.aios.core.overnight.OvernightRunner.getCurrentPermissionProfile();
        this.queryEngine = overnightProfile != null
                ? new QueryEngine(sdk, this.agentId, workingDir, operatorTools, overnightProfile)
                : new QueryEngine(sdk, this.agentId, workingDir, operatorTools);

        // ── 2. SessionMemory — 会话记忆 ──
        this.sessionMemory = new SessionMemoryService.SessionMemory();
        sessionMemory.setSection(SessionMemoryService.Section.SESSION_TITLE,
                "OperatorAgent: " + manifest.workflowName());
        sessionMemory.setSection(SessionMemoryService.Section.TASK_SPECIFICATION,
                "Execute " + manifest.nodes().size() + " operator nodes");

        // ── 3. Compact — 压缩状态 ──
        this.compactState = new CompactService.AutoCompactState();

        // ── 4. Hook: SessionStart ──
        HookManager.instance().trigger(HookManager.HookEvent.SESSION_START, Map.of(
                "agent_id", this.agentId,
                "workflow", manifest.workflowName(),
                "nodes", manifest.nodes().size(),
                "mode", "operator"
        ));

        // ── 5. Telemetry: 会话开始事件 ──
        TelemetryService.instance().logEvent("session_start", Map.of(
                "agent_id", this.agentId,
                "workflow", manifest.workflowName(),
                "node_count", manifest.nodes().size(),
                "mode", "operator"
        ));

        // ── 6. CLAUDE.md — 加载项目指令 ──
        List<ClaudeMdLoader.MemoryFileInfo> claudeMds = ClaudeMdLoader.loadAll(workingDir);
        if (!claudeMds.isEmpty()) {
            System.out.println("[Operator Agent] CLAUDE.md 已加载: " + claudeMds.size() + " files");
        }

        // ── 7. CostTracker — 成本追踪 ──
        CostTracker.instance().reset();

        // ── 8. MemoryDir — 跨会话记忆 ──
        MemoryDir.instance().scan();

        log.info("[Operator Agent] 能力已初始化 (9 modules, {} plugins)", pluginCount);
    }

    /**
     * 构建操作员专属工具列表 — 通过 OpenClaw ToolAssembler 动态装配。
     * <p>
     * 装配策略：
     * 1. 内核全局工具（Bash, File, Grep 等）
     * 2. OpenClaw 插件注册的工具
     * 3. Operator 模式下自动排除认知工具（TodoWrite, PlanMode 等）
     * 4. 应用策略过滤（白名单/黑名单）
     */
    private List<Tool<? extends ToolInput>> buildOperatorToolList() {
        ToolAssemblyContext.Builder builder = ToolAssemblyContext.builder()
                .agentId(this.agentId)
                .workingDir(workingDir)
                .operatorMode(true)
                .pluginRegistry(pluginRegistry);

        // ── Computer Use — 物理操作宿主机的核心工具 ──
        if (rpaToken != null) {
            ComputerUseTool computerUseTool = new ComputerUseTool(rpaToken);

            // ── 注入 VisionService — 截图后自动调用多模态模型理解屏幕 ──
            if (visionService != null) {
                computerUseTool.setVisionService(visionService);
                System.out.println("[Operator Agent] ComputerUseTool: MOUNTED with VisionService (mimo-v2.5)");
            } else {
                System.out.println("[Operator Agent] ComputerUseTool: 已挂载但 VisionService 不可用 (no multimodal provider)");
            }

            builder.extraTool(computerUseTool);
        } else {
            System.out.println("[Operator Agent] ComputerUseTool: 已跳过 — 无 RPA Token (headless or token issue)");
        }

        // ── Browser Control — DOM 级浏览器自动化（对标 OpenClaw Browser Control） ──
        try {
            var bridgeNode = com.ouisani.aios.core.VfsManager.instance().resolve("/dev/host/browser");
            if (bridgeNode.isPresent() && bridgeNode.get() instanceof ChromeBridgeNode chromeBridge) {
                BrowserTool browserTool = new BrowserTool(chromeBridge);
                // 注册响应回调：浏览器扩展执行完命令后，通过此回调通知 BrowserTool
                chromeBridge.setResponseCallback(browserTool::onBrowserResponse);
                builder.extraTool(browserTool);
                System.out.println("[Operator Agent] BrowserTool: 已挂载 (Chrome extension bridge at /dev/host/browser)");
            } else {
                System.out.println("[Operator Agent] BrowserTool: 已跳过 — ChromeBridgeNode not mounted");
            }
        } catch (Exception e) {
            System.out.println("[Operator Agent] BrowserTool: 已跳过 — " + e.getMessage());
        }

        // ── Desktop GUI — 双引擎桌面控件定位（无障碍 API + VLM 视觉降级） ──
        if (rpaToken != null && visionService != null) {
            DesktopGuiTool desktopTool = new DesktopGuiTool(visionService, rpaToken);
            builder.extraTool(desktopTool);
            System.out.println("[Operator Agent] DesktopGuiTool: MOUNTED (Dual-Engine: Accessibility API + VLM Vision)");
        } else {
            System.out.println("[Operator Agent] DesktopGuiTool: 已跳过 — 需要 RPA Token + VisionService");
        }

        // OpenClaw 内置工具 — 通过 Gateway Bridge 与 OpenClaw 通信
        builder.extraTool(new NodesTool(gatewayBridge))
               .extraTool(new MessageTool(gatewayBridge))
               .extraTool(new GatewayControlTool(gatewayBridge));

        ToolAssemblyContext context = builder.build();

        List<Tool<? extends ToolInput>> tools = ToolAssembler.assemble(context);
        System.out.println("[Operator Agent] Tools assembled: " + tools.size()
                + " (operator mode, cognitive tools excluded, ComputerUse + OpenClaw tools included)");
        return tools;
    }

    /**
     * 构建操作员 Prompt — 将节点职责转化为即时操作指令。
     */
    private String buildOperatorPrompt(WorkflowNode node) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你现在需要执行以下操作任务：\n\n");
        prompt.append("【节点 ID】：").append(node.instanceId()).append("\n");
        prompt.append("【职责描述】：").append(node.role()).append("\n\n");

        // 注入用户参数
        if (node.userParams() != null && !node.userParams().isEmpty()) {
            prompt.append("【用户参数】：\n");
            node.userParams().forEach((k, v) -> {
                // ── Dify 变量解析：从内存总线动态替换 {{nodeId.variable}} 引用 ──
                String resolvedValue = v;
                if (context != null) {
                    Object resolved = context.resolveValue(v);
                    resolvedValue = resolved != null ? resolved.toString() : v;
                }
                prompt.append("  - ").append(k).append(": ").append(resolvedValue).append("\n");
            });
            prompt.append("\n");
        }

        // 注入 I/O 通道信息
        if (node.subscribeTopic() != null && !node.subscribeTopic().isBlank()) {
            prompt.append("【输入通道】：").append(node.subscribeTopic()).append("\n");
        }
        if (node.publishTopic() != null && !node.publishTopic().isBlank()) {
            prompt.append("【输出通道】：").append(node.publishTopic()).append("\n");
        }

        prompt.append("\n请立即开始执行上述任务。记住：你是一个操作员，直接操作系统，不要写代码。\n");
        prompt.append("完成后回复 TASK_COMPLETED 并附上操作摘要。");

        return prompt.toString();
    }
}
