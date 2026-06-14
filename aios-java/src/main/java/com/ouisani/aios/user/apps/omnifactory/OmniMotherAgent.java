package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.compact.CompactService;
import com.ouisani.aios.core.context.ClaudeMdLoader;
import com.ouisani.aios.core.context.SystemPromptBuilder;
import com.ouisani.aios.core.cost.CostTracker;
import com.ouisani.aios.core.dream.AutoDreamService;
import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.lsp.LspManager;
import com.ouisani.aios.core.memory.MemoryDir;
import com.ouisani.aios.core.memory.SessionMemoryService;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.plugin.WebSearchTool;
import com.ouisani.aios.core.recovery.RecoveryContext;
import com.ouisani.aios.core.recovery.RecoveryOrchestrator;
import com.ouisani.aios.core.recovery.RecoveryResult;
import com.ouisani.aios.core.swarm.CoordinatorMode;
import com.ouisani.aios.core.swarm.InProcessWorker;
import com.ouisani.aios.core.task.DreamTask;
import com.ouisani.aios.core.task.TaskScheduler;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.core.tool.QueryEngine;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolExecutionPipeline;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolRegistry;
import com.ouisani.aios.user.bin.AiosAppManager;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全能智能体母体 (OmniFactory Mother Agent) — Claude Code 能力加持版。
 * <p>
 * 挂载了 Claude Code 的全部核心能力：
 * <ul>
 *   <li>QueryEngine — 工具增强推理循环（12 个内置工具）</li>
 *   <li>PermissionChecker — 6 种权限模式 + 规则匹配</li>
 *   <li>HookManager — 11 种生命周期 Hook</li>
 *   <li>TelemetryService — Token 成本追踪 + 工具统计</li>
 *   <li>SystemPromptBuilder — Git 状态 + CLAUDE.md + 工具描述</li>
 *   <li>CompactService — 三级对话压缩（Micro/Auto/Full）</li>
 *   <li>SessionMemoryService — 10 Section 会话记忆</li>
 *   <li>CoordinatorMode — 多 Worker 协作</li>
 *   <li>AutoDreamService — 空闲时自动梦境整合</li>
 *   <li>WebSearchTool — Jina Search 真实网络搜索</li>
 * </ul>
 * <p>
 * OS 类比：相当于内核的 kexec() + insmod() — 母体在运行时动态编译出一套
 * 全新的"操作系统"（工作流拓扑），同时加载所有内核模块（Claude Code 能力）。
 */
public class OmniMotherAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(OmniMotherAgent.class);

    private final WorkflowManifest manifest;
    private final String workingDir;

    // ── Dify 内存上下文（可选，DAG 引擎注入） ──
    private WorkflowContext context;

    // ── Claude Code 能力模块 ──
    private QueryEngine queryEngine;
    private SessionMemoryService.SessionMemory sessionMemory;
    private CompactService.AutoCompactState compactState;
    private CoordinatorMode coordinator;

    /** 原有构造函数：兼容 AppGateway 等旧调用方 */
    public OmniMotherAgent(WorkflowManifest manifest) {
        super("Omni-Mother", ProcessPriority.REALTIME, 100000);
        this.manifest = manifest;
        this.workingDir = System.getProperty("user.dir");
    }

    /** Dify 风格构造函数：DAG 引擎按节点级调度时使用 */
    public OmniMotherAgent(WorkflowNode node, WorkflowContext context) {
        super("Omni-Mother", ProcessPriority.REALTIME, 100000);
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
        //  Phase 0: 内核初始化 — 挂载所有 Claude Code 能力
        // ════════════════════════════════════════════════════════════════
        initializeClaudeCodeCapabilities();

        System.out.println("[Mother Agent] ══════════════════════════════════════════");
        System.out.println("[Mother Agent] Analyzing N-Node Topology... Total nodes: " + manifest.nodes().size());
        System.out.println("[Mother Agent] Claude Code capabilities: ONLINE");
        System.out.println("[Mother Agent] Tools: " + ToolRegistry.instance().all().size() + " registered");
        System.out.println("[Mother Agent] ══════════════════════════════════════════");
        log.info("[Mother Agent] Topology: {} nodes | Tools: {} | Mode: {}",
                manifest.nodes().size(), ToolRegistry.instance().all().size(),
                PermissionMode.DEFAULT);

        // ════════════════════════════════════════════════════════════════
        //  Phase 1: The Forge Loop — 动态 N 节点量产
        // ════════════════════════════════════════════════════════════════
        StringBuilder agentfileBuilder = new StringBuilder("APP_NAME " + manifest.workflowName() + "\n");
        agentfileBuilder.append("MOUNT /factory:/factory\nMOUNT /shared:/shared\n");

        // 注入前端传递的技能/角色列表到 Agentfile，供 AiosAppManager 按需裁剪
        if (!manifest.enabledSkills().isEmpty()) {
            agentfileBuilder.append("ENABLED_SKILLS ").append(String.join(",", manifest.enabledSkills())).append("\n");
        }
        if (!manifest.enabledRoles().isEmpty()) {
            agentfileBuilder.append("ENABLED_ROLES ").append(String.join(",", manifest.enabledRoles())).append("\n");
        }

        StringBuilder shellScriptBuilder = new StringBuilder();

        for (WorkflowNode node : manifest.nodes()) {
            // ── God Hand Protocol: 预创建配置文件 ──
            sdk.writeFile(this.agentId, "/factory/configs/" + node.instanceId() + ".json", "{}");

            // ── RAG: 网络搜索已禁用 — 国内 Jina 被墙，直接进入盲写模式 ──
            // 母体拿到任务后直接进入 Claude Code REPL 写代码，不再等待网络搜索
            String context = "";

            // ── Dify 变量解析：从内存总线动态替换 {{nodeId.variable}} 引用 ──
            if (this.context != null) {
                Map<String, String> resolvedParams = new HashMap<>();
                for (Map.Entry<String, String> entry : node.userParams().entrySet()) {
                    Object resolved = this.context.resolveValue(entry.getValue());
                    resolvedParams.put(entry.getKey(), resolved != null ? resolved.toString() : "");
                }
                log.info("[OmniMother] Memory parameters resolved for node {}: {}", node.instanceId(), resolvedParams);
            }

            System.out.println("[Mother Agent]   ├─ Web search skipped (offline mode), entering REPL directly");

            // ── Claude Code: QueryEngine 自治 SWE 闭环 ──
            String codePrompt = buildCodePrompt(node, context);

            System.out.println("[Mother Agent] Initiating Claude Code REPL for node: " + node.instanceId());

            // ── CostTracker: 记录推理开始 ──
            long nodeStartTokens = CostTracker.instance().getTotalTokens();

            // ── ToolExecutionPipeline: 通过管线执行（Hook→权限→执行→遥测） ──
            String result = queryEngine.query(codePrompt); // 大模型会在内部自主调用工具、修改、测试
            if (!result.contains("NODE_VERIFIED_AND_READY")) {
                System.err.println("[Mother Agent] Warning: Node " + node.instanceId()
                        + " might not be fully verified. Final response: " + result);
            } else {
                System.out.println("[Mother Agent]   ├─ Node '" + node.instanceId() + "' VERIFIED_AND_READY ✓");
            }

            // ── CostTracker: 记录本节点消耗 ──
            long nodeTokens = CostTracker.instance().getTotalTokens() - nodeStartTokens;
            CostTracker.CostLevel costLevel = CostTracker.instance().checkThreshold();

            // ── MemoryDir: 将节点生成记录保存到跨会话记忆 ──
            MemoryDir.instance().save(new MemoryDir.MemoryEntry(
                    node.instanceId(), MemoryDir.MemoryType.PROJECT,
                    "Role: " + node.role() + " | Verified: " + result.contains("NODE_VERIFIED_AND_READY"),
                    System.currentTimeMillis(), new String[]{"omnifactory", node.instanceId()}
            ));

            // ── LspManager: 对生成的 Python 文件做语法检查 ──
            List<LspManager.LspDiagnostic> diagnostics =
                    LspManager.instance().getDiagnostics("/factory/" + node.instanceId() + ".py");
            if (!diagnostics.isEmpty()) {
                System.out.println("[Mother Agent]   ├─ LSP diagnostics for " + node.instanceId()
                        + ": " + diagnostics.size() + " issues");
            }

            // ── Dify 内存总线：将节点输出提交到全局上下文，供下游节点收割 ──
            if (this.context != null) {
                Map<String, Object> outputs = new HashMap<>();
                outputs.put("result_text", result);
                this.context.commitNodeOutput(node.instanceId(), outputs);
                log.info("[OmniMother] Node '{}' output committed to memory bus", node.instanceId());
            }

            // ── CoordinatorMode: 如果节点数 > 3，启用协作模式分配 Worker ──
            if (manifest.nodes().size() > 3) {
                coordinator.addWorker(node.instanceId(), node.role());
            }

            // ── 遥测：记录节点生成 ──
            TelemetryService.instance().logEvent("node_forged", Map.of(
                    "node_id", node.instanceId(),
                    "role", node.role(),
                    "verified", result.contains("NODE_VERIFIED_AND_READY")
            ));

            shellScriptBuilder.append("export NODE_ID=").append(node.instanceId()).append("\n");
            shellScriptBuilder.append("export INPUT_TOPIC=").append(node.subscribeTopic()).append("\n");
            shellScriptBuilder.append("export OUTPUT_TOPIC=").append(node.publishTopic()).append("\n");
            shellScriptBuilder.append("python3 /factory/").append(node.instanceId()).append(".py &\n");

            agentfileBuilder.append("SPAWN ").append(node.instanceId()).append(" 1\n");

            System.out.printf("[Mother Agent]   ├─ Node '%s' forged via autonomous SWE loop%n",
                    node.instanceId());

            // ── SessionMemory: 记录节点生成到会话记忆 ──
            sessionMemory.setSection(SessionMemoryService.Section.FILES_AND_FUNCTIONS,
                    sessionMemory.getSection(SessionMemoryService.Section.FILES_AND_FUNCTIONS)
                            + "\n- /factory/" + node.instanceId() + ".py: " + node.role());
            sessionMemory.incrementToolCalls();
        }

        // ════════════════════════════════════════════════════════════════
        //  Phase 2: Ignition — 总装与点火
        // ════════════════════════════════════════════════════════════════
        sdk.writeFile(this.agentId, "/factory/run_all.sh", shellScriptBuilder.toString());
        agentfileBuilder.append("ENTRYPOINT sh /factory/run_all.sh");

        System.out.println("[Mother Agent] Ignition! Deploying workflow...");
        log.info("[Mother Agent] Ignition! Deploying workflow.");

        AiosAppManager.installAndRun(agentfileBuilder.toString());

        // ════════════════════════════════════════════════════════════════
        //  Phase 3: Post-Ignition — 持续监控与自愈
        // ════════════════════════════════════════════════════════════════

        // ── SessionMemory: 更新工作日志 ──
        sessionMemory.setSection(SessionMemoryService.Section.WORKLOG,
                "Generated " + manifest.nodes().size() + " nodes for workflow: " + manifest.workflowName());
        sessionMemory.setSection(SessionMemoryService.Section.CURRENT_STATE,
                "Workflow deployed. All nodes running in background.");

        // ── Compact: 检查是否需要压缩 ──
        long estimatedTokens = estimateTokensFromHistory();
        CompactService.CompactionResult compactResult = CompactService.autoCompact(
                List.of(), (int) estimatedTokens, 200000, sdk, this.agentId, compactState);
        if (compactResult != null) {
            System.out.println("[Mother Agent] " + compactResult.userDisplayMessage());
        }

        // ── AutoDream: 检查是否应该触发梦境整合 ──
        if (AutoDreamService.shouldDream()) {
            TaskScheduler.instance().scheduleDream(this.agentId, sdk, 60);
            System.out.println("[Mother Agent] Dream consolidation scheduled");
        }

        // ── 遥测: 打印成本报告 ──
        long elapsed = System.currentTimeMillis() - startTime;
        TelemetryService.instance().recordApiDuration(elapsed);
        System.out.println("[Mother Agent] Total time: " + elapsed + "ms");
        System.out.println("[Mother Agent] " + CostTracker.instance().formatReport());
        log.info("[Mother Agent] Cost report:\n{}", TelemetryService.instance().formatCostReport());

        this.exit();
    }

    @Override
    protected void onMessage(String msg) {
        // ── Hook: 消息处理 ──
        HookManager.HookResult result = HookManager.instance().trigger(
                HookManager.HookEvent.STOP, Map.of("message", msg));

        if (result.proceed()) {
            log.debug("[Mother Agent] Message (retiring): {}", msg.substring(0, Math.min(60, msg.length())));
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Team Mailbox: Actor Mode 任务处理 + 多层自愈编排
    // ════════════════════════════════════════════════════════════════

    /** 自愈最大重试次数 */
    private static final int MAX_SELF_HEAL_RETRIES = 3;

    /**
     * Actor 模式任务处理 — 多层自愈编排器驱动。
     * <p>
     * 对标 oh-my-openagent 的 11 层恢复机制：
     * 原有 2 层（反思注入 + AutoMedic）升级为 11 层恢复策略链。
     * <p>
     * 核心流程：
     * <pre>
     *   1. 执行任务 → 失败 → 捕获异常
     *   2. RecoveryOrchestrator.classify() → 错误分类
     *   3. RecoveryOrchestrator.orchestrate() → 按优先级尝试恢复策略
     *      - 空内容恢复 → JSON 解析恢复 → 编辑错误恢复 → ...
     *      - 反思注入（原有机制保留为第 7 层）
     *   4. 恢复成功 → 重新执行任务
     *   5. 恢复失败 → 递增失败计数 → 检查熔断阈值
     *   6. 熔断触发 → 升级为 Human-in-the-Loop
     * </pre>
     */
    @Override
    protected void handleTask(Object rawPayload) {
        if (!(rawPayload instanceof com.ouisani.aios.core.team.TaskPayload payload)) {
            log.error("[OmniMother] Received invalid payload type: {}", rawPayload.getClass().getSimpleName());
            return;
        }

        int currentAttempt = 0;
        boolean success = false;
        String lastErrorTrace = "";
        RecoveryOrchestrator recovery = RecoveryOrchestrator.instance();

        while (currentAttempt < MAX_SELF_HEAL_RETRIES && !success) {
            currentAttempt++;
            try {
                log.info("[OmniMother] Task Attempt {}/{} starting for node: {}",
                        currentAttempt, MAX_SELF_HEAL_RETRIES, payload.node().instanceId());

                // ── 初始化能力模块（仅首次） ──
                if (currentAttempt == 1) {
                    initializeClaudeCodeCapabilities();
                }

                // ── 构建节点执行 Prompt ──
                WorkflowNode node = payload.node();
                String basePrompt = buildCodePrompt(node, "");

                // ── 【核心机制：反思注入 (Reflection Injection)】 ──
                // 如果这不是第一次尝试，说明上次失败了。把错误日志怼到大模型脸上！
                if (!lastErrorTrace.isEmpty()) {
                    String reflectionBlock = "\n\n[SYSTEM CRITICAL - PREVIOUS ATTEMPT FAILED]:\n"
                            + "The previous execution failed with the following error/logs:\n"
                            + "```text\n" + lastErrorTrace + "\n```\n"
                            + "Please thoroughly analyze this error, figure out what went wrong, "
                            + "and provide a CORRECTED solution or code. "
                            + "Do NOT repeat the same mistake!\n";
                    basePrompt += reflectionBlock;
                    log.warn("[OmniMother] Injecting error trace into LLM context for Self-Correction (attempt {}).",
                            currentAttempt);
                }

                // ── Dify 变量解析：从内存总线动态替换 {{nodeId.variable}} 引用 ──
                if (this.context != null) {
                    Map<String, String> resolvedParams = new HashMap<>();
                    for (Map.Entry<String, String> entry : node.userParams().entrySet()) {
                        Object resolved = this.context.resolveValue(entry.getValue());
                        resolvedParams.put(entry.getKey(), resolved != null ? resolved.toString() : "");
                    }
                    log.info("[OmniMother] Memory parameters resolved for node {}: {}", node.instanceId(), resolvedParams);
                }

                // ── 执行 LLM 自治 SWE 闭环 ──
                long nodeStartTokens = CostTracker.instance().getTotalTokens();
                String result = queryEngine.query(basePrompt);

                // ── 验证节点是否真正成功 ──
                if (!result.contains("NODE_VERIFIED_AND_READY")) {
                    // 节点未验证通过 — 视为执行失败，触发多层自愈
                    String errorMsg = "Node " + node.instanceId()
                            + " not verified. LLM response: " + result;
                    log.warn("[OmniMother] Attempt {}/{} verification failed: {}",
                            currentAttempt, MAX_SELF_HEAL_RETRIES, errorMsg);
                    throw new RuntimeException(errorMsg);
                }

                // ── 成功！重置恢复计数器 ──
                recovery.resetFailures(this.agentId);
                long nodeTokens = CostTracker.instance().getTotalTokens() - nodeStartTokens;
                log.info("[OmniMother] Node '{}' VERIFIED on attempt {} ({} tokens consumed)",
                        node.instanceId(), currentAttempt, nodeTokens);

                MemoryDir.instance().save(new MemoryDir.MemoryEntry(
                        node.instanceId(), MemoryDir.MemoryType.PROJECT,
                        "Role: " + node.role() + " | Verified: true | Attempts: " + currentAttempt,
                        System.currentTimeMillis(), new String[]{"omnifactory", node.instanceId()}
                ));

                // ── Dify 内存总线：提交节点输出 ──
                if (this.context != null) {
                    Map<String, Object> outputs = new HashMap<>();
                    outputs.put("result_text", result);
                    outputs.put("attempts_required", currentAttempt);
                    this.context.commitNodeOutput(node.instanceId(), outputs);
                    log.info("[OmniMother] Node '{}' output committed to memory bus", node.instanceId());
                }

                TelemetryService.instance().logEvent("node_forged", Map.of(
                        "node_id", node.instanceId(),
                        "role", node.role(),
                        "verified", true,
                        "attempts", currentAttempt
                ));

                // ── 业务顺利办完，跳出循环 ──
                success = true;
                log.info("[OmniMother] Task complete on attempt {}. Signing receipt for node: {}",
                        currentAttempt, node.instanceId());
                payload.completionReceipt().complete(null);

            } catch (Exception e) {
                log.warn("[OmniMother] Attempt {}/{} failed: {}", currentAttempt, MAX_SELF_HEAL_RETRIES, e.getMessage());
                lastErrorTrace = e.getMessage() != null ? e.getMessage() : e.toString();

                // ══════════════════════════════════════════════════════════
                //  【多层自愈编排】— 替代原有简单的反思注入
                //  RecoveryOrchestrator 会自动分类错误并选择最佳恢复策略
                // ══════════════════════════════════════════════════════════
                RecoveryContext recoveryContext = new RecoveryContext(
                        this.agentId, e, currentAttempt, lastErrorTrace);
                RecoveryResult recoveryResult = recovery.orchestrate(recoveryContext);

                if (recoveryResult.success() && recoveryResult.modifiedPrompt() != null) {
                    // 恢复策略提供了 Prompt 修改 — 注入到下一次尝试
                    lastErrorTrace += "\n[RECOVERY STRATEGY APPLIED: " + recoveryResult.message() + "]";
                    log.info("[OmniMother] Recovery strategy applied: {}", recoveryResult.message());
                }

                // 【广播自愈警告事件 — 触发前端大屏红光闪烁动效】
                try {
                    String telemetryPayload = String.format(
                        "{\"eventType\":\"SELF_HEALING_TRIGGERED\", \"agentId\":\"%s\", \"attempt\":%d, \"error\":\"%s\", \"recoveryStrategy\":\"%s\", \"timestamp\":%d}",
                        this.agentId, currentAttempt,
                        lastErrorTrace.replace("\"", "'").replace("\n", " "),
                        recoveryResult.message().replace("\"", "'"),
                        System.currentTimeMillis()
                    );
                    com.ouisani.aios.core.network.EventBus.instance().broadcast("sys.telemetry.events", telemetryPayload);
                } catch (Exception ignore) {}

                // 指数退避 (Exponential Backoff) 防止 API 限流
                try {
                    long backoffMs = 2000L * currentAttempt;
                    log.info("[OmniMother] Backing off {}ms before retry...", backoffMs);
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // ── 如果三次都没救回来，宣告失败 ──
        if (!success) {
            log.error("[OmniMother] Max retries ({}) exhausted for node: {}. Agent failed to heal itself.",
                    MAX_SELF_HEAL_RETRIES, payload.node().instanceId());
            payload.completionReceipt().completeExceptionally(
                    new RuntimeException("Agent failed after " + MAX_SELF_HEAL_RETRIES
                            + " self-heal attempts. Last error: " + lastErrorTrace));
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Claude Code 能力初始化
    // ════════════════════════════════════════════════════════════════

    private void initializeClaudeCodeCapabilities() {
        // ── 1. QueryEngine — 工具增强推理循环（内核全局 + 母体专属工具） ──
        List<Tool<? extends ToolInput>> motherTools = buildMotherToolList();
        this.queryEngine = new QueryEngine(sdk, this.agentId, workingDir, motherTools);

        // ── 2. SessionMemory — 会话记忆 ──
        this.sessionMemory = new SessionMemoryService.SessionMemory();
        sessionMemory.setSection(SessionMemoryService.Section.SESSION_TITLE,
                "OmniFactory: " + manifest.workflowName());
        sessionMemory.setSection(SessionMemoryService.Section.TASK_SPECIFICATION,
                "Generate " + manifest.nodes().size() + " node workflow");

        // ── 3. Compact — 压缩状态 ──
        this.compactState = new CompactService.AutoCompactState();

        // ── 4. CoordinatorMode — 协作模式 ──
        this.coordinator = new CoordinatorMode();

        // ── 5. Hook: SessionStart ──
        HookManager.instance().trigger(HookManager.HookEvent.SESSION_START, Map.of(
                "agent_id", this.agentId,
                "workflow", manifest.workflowName(),
                "nodes", manifest.nodes().size()
        ));

        // ── 6. Telemetry: 会话开始事件 ──
        TelemetryService.instance().logEvent("session_start", Map.of(
                "agent_id", this.agentId,
                "workflow", manifest.workflowName(),
                "node_count", manifest.nodes().size()
        ));

        // ── 7. CLAUDE.md — 加载项目指令 ──
        List<ClaudeMdLoader.MemoryFileInfo> claudeMds = ClaudeMdLoader.loadAll(workingDir);
        if (!claudeMds.isEmpty()) {
            System.out.println("[Mother Agent] CLAUDE.md loaded: " + claudeMds.size() + " files");
            log.info("[Mother Agent] CLAUDE.md: {} files loaded", claudeMds.size());
        }

        // ── 8. Skills — 加载技能 ──
        Map<String, com.ouisani.aios.core.skill.SkillLoader.SkillDef> skills =
                com.ouisani.aios.core.skill.SkillLoader.loadAll(workingDir);
        if (!skills.isEmpty()) {
            System.out.println("[Mother Agent] Skills loaded: " + skills.keySet());
            log.info("[Mother Agent] Skills: {} loaded", skills.size());
        }

        // ── 9. AutoDream — 记录新会话 ──
        AutoDreamService.recordNewSession();

        // ── 10. CostTracker — 成本追踪 ──
        CostTracker.instance().reset();

        // ── 11. ToolExecutionPipeline — 工具执行管线 ──
        ToolExecutionPipeline.instance(); // 初始化单例

        // ── 12. MemoryDir — 跨会话记忆 ──
        MemoryDir.instance().scan();

        // ── 13. LspManager — LSP 代码智能 ──
        LspManager.instance();

        // ── 14. 母体专属认知工具 — 通过 QueryEngine 独享，不污染全局注册表 ──
        // (已在步骤1中通过 buildMotherToolList() 传入 QueryEngine)

        log.info("[Mother Agent] Claude Code capabilities initialized (15 modules)");
    }

    /**
     * 构建母体专属工具列表 — 内核全局工具 + 用户空间高级认知工具。
     * <p>
     * 这些高级认知工具不属于内核全局注册表，仅母体独享。
     * 其他普通 Agent 通过 QueryEngine 只能获得内核全局工具。
     */
    private List<Tool<? extends ToolInput>> buildMotherToolList() {
        List<Tool<? extends ToolInput>> tools = new ArrayList<>();
        tools.add(new com.ouisani.aios.user.apps.omnifactory.tools.TodoWriteTool());
        tools.add(new com.ouisani.aios.user.apps.omnifactory.tools.NotebookEditTool());
        tools.add(new com.ouisani.aios.user.apps.omnifactory.tools.PlanModeTool());
        tools.add(new com.ouisani.aios.user.apps.omnifactory.tools.TaskTool());
        tools.add(new com.ouisani.aios.user.apps.omnifactory.tools.SkillTool());
        tools.add(new com.ouisani.aios.operator.tools.HashlineReadTool());
        tools.add(new com.ouisani.aios.operator.tools.HashlineEditTool());
        tools.add(new com.ouisani.aios.operator.tools.AstSearchTool());
        tools.add(new com.ouisani.aios.operator.tools.AstRewriteTool());
        tools.add(new com.ouisani.aios.operator.tools.TeamTool());
        System.out.println("[Mother Agent] 10 exclusive cognitive tools mounted (TodoWrite, NotebookEdit, PlanMode, Task, Skill, HashlineRead, HashlineEdit, AstSearch, AstRewrite, Team)");
        log.info("[Mother Agent] Exclusive cognitive tools mounted: TodoWrite, NotebookEdit, PlanMode, Task, Skill, HashlineRead, HashlineEdit, AstSearch, AstRewrite, Team");
        return tools;
    }

    /**
     * 构建自治 SWE Prompt — 赋予大模型 Claude Code 级别的自治能力。
     * <p>
     * 大模型将自主使用 file_write、bash、file_edit 等工具，
     * 完成代码编写→测试→修复→验证的完整闭环。
     * 只有当测试通过后，才回复 NODE_VERIFIED_AND_READY。
     */
    private String buildCodePrompt(WorkflowNode node, String searchContext) {
        // 加载 CLAUDE.md 作为额外上下文
        String claudeMdContext = ClaudeMdLoader.formatAsPrompt(ClaudeMdLoader.loadAll(workingDir));

        StringBuilder prompt = new StringBuilder();

        // RAG 上下文
        if (!searchContext.isEmpty()) {
            prompt.append("以下是为你检索到的最新互联网参考资料，请严格参考这些文档的用法编写代码：\n")
                  .append(searchContext).append("\n\n");
        }

        // CLAUDE.md 项目指令
        if (!claudeMdContext.isEmpty()) {
            prompt.append("【项目指令 (CLAUDE.md)】\n").append(claudeMdContext).append("\n\n");
        }

        // 自治 SWE 协议 — 核心指令
        prompt.append("你是一个自治软件工程师 (Autonomous SWE)。你需要为节点 [")
              .append(node.instanceId()).append("] 实现功能：[").append(node.role()).append("]。\n")
              .append("【工作流强制规则】：\n")
              .append("1. 你必须使用 file_write 或 bash 工具创建 /factory/")
              .append(node.instanceId()).append(".py 文件。\n")
              .append("2. 该代码必须 import BaseAgent 并继承它重写 process_data(self, data) 方法。\n")
              .append("3. 极其重要：写完代码后，你必须使用 bash 工具执行 'python3 /factory/")
              .append(node.instanceId()).append(".py' 来测试是否存在语法错误或缺包 (ModuleNotFoundError)。\n")
              .append("4. 如果 bash 返回报错，请使用 file_edit 或 bash (pip install) 修复问题，再次运行测试。\n")
              .append("5. 只有当测试运行没有报错时，你才能回复纯文本：'NODE_VERIFIED_AND_READY'。\n\n")
              .append("【物理环境终极约束 (CRITICAL)】：\n")
              .append("1. 当前操作系统的执行环境中，**绝对没有 `python` 命令**，只有 `python3`！\n")
              .append("2. 你在生成任何执行脚本或使用 bash 工具进行测试时，**必须且只能使用 `python3` 命令**。\n")
              .append("3. 严禁写入 `python agent.py`，必须写为 `python3 agent.py`！违者系统将发生严重错误！\n")
              .append("4. 【绝对禁止交互式命令】：你正在一个无头 (Headless) 的自动化沙箱中运行！执行 bash 工具时，**绝对禁止使用 `sudo`**，绝对禁止使用任何需要人类输入或交互的命令（如 vim, nano, python -i 等）。\n")
              .append("5. 如果需要安装 Python 依赖，必须使用 `pip3 install --user <package>` 规避权限问题，并加上 `-y` 或相关静默参数！违者将导致进程永久阻塞！\n")
              .append("6. 【必须有控制台打印】：你编写的所有 `.py` 脚本，在执行完毕或者执行过程中，必须使用 `print()` 语句在控制台打印出极其明显的标记！例如 `print('AGENT_1_SUCCESS: Data fetched!')`，绝不允许没有任何输出就结束进程！\n")
              .append("7. 【强制生成工序纪律】：你必须严格按照以下顺序执行任务，绝不允许跳步或敷衍！\n")
              .append("    - 第一步：调用 `file_write` 工具，为当前节点生成 Python 源码到 `/factory` 目录下。\n")
              .append("      你现在的角色是高级底层开发工程师 (Python Coder)。系统架构师已经为你规划好了整个工作流的 DAG 拓扑，当前正在处理的节点是：[").append(node.instanceId()).append("]，它的职责是：[").append(node.role()).append("]。\n")
              .append("      你只需要专心致志地为这单个节点编写 Python 代码，严禁在当前文件中实现其他节点的功能！\n")
              .append("    - 第二步：在确认所有 `.py` 文件都已经通过 `file_write` 真实写入后，**最后一步**才能调用 `file_write` 生成 `run_all.sh` 和 `orchestrator.py`。\n")
              .append("    - 严禁在未生成 Python 源码的情况下，凭空在 `run_all.sh` 或 `orchestrator.py` 中调用它们！你的这种偷懒行为会导致运行时严重错误！\n")
              .append("8. 【工具调用绝对格式】：你如果需要调用工具，必须且只能使用以下极其严格的 XML 格式！\n")
              .append("    <tool_call>\n")
              .append("    <function=工具名><parameter=参数名>参数值</parameter></function=工具名>\n")
              .append("    </tool_call>\n")
              .append("    示例 — 写入文件：\n")
              .append("    <tool_call>\n")
              .append("    <function=file_write><parameter=path>/factory/agent_1.py</parameter><parameter=content>print('hello')</parameter></function=file_write>\n")
              .append("    </tool_call>\n")
              .append("    【严重警告】：绝对不允许发明诸如 `<tool_glob>`, `<tool_name>`, `<function_name>`, `<file_write>` 这样的假标签！只能用 `<function=xxx>` 和 `<parameter=yyy>`！格式错一个字符，系统将无法解析！\n\n")
              .append("【AIOS 用户态工作流编排器 (Orchestrator) 绝对纪律与架构蓝图】\n")
              .append("作为造物主智能体，你必须生成一个工业级的用户态调度中枢，绝不能用 shell 脚本瞎指挥。\n\n")
              .append("1. 【核心入口唯一性】：你生成的 `run_all.sh` 脚本只能有唯一的一行代码：`python3 -u orchestrator.py`。严禁使用 `&` 或多行命令后台运行！\n\n")
              .append("2. 【数据黑板传输协议 (Data Blackboard)】：节点间严禁通过 `stdout` 截取来传递数据！\n")
              .append("    - 每个子节点（如 `agent_1.py`）必须将处理结果写入到它自己专属的 JSON 文件中（如 `agent_1_output.json`）。\n")
              .append("    - 下游节点（如 `agent_2.py`）必须主动去读取前置节点生成的 `_output.json` 文件作为数据源。\n\n")
              .append("3. 【Orchestrator.py 核心架构蓝图 (CRITICAL)】：\n")
              .append("    你使用 `file_write` 生成的 `orchestrator.py`，必须且只能包含以下极其严密的逻辑框架（你可以细化，但绝不能删减核心能力）：\n\n")
              .append("    - 步骤一：解析依赖。读取你生成的 `workflow.json`（其中包含了 `nodeId`, `scriptPath`, `dependsOn`），计算出无环图（DAG）的执行顺序。\n\n")
              .append("    - 步骤二：拓扑执行循环。写一个循环，不断找出 `dependsOn` 为空或依赖已满足的就绪节点。\n\n")
              .append("    - 步骤三：非阻塞实时监控拉起。对于每个就绪节点，必须使用以下代码范式拉起，并实时冲刷缓冲区，确保前端监控可用：\n")
              .append("      ```python\n")
              .append("      print(f\"[DAG_TRACE] >>> NODE_START: {node_id}\", flush=True)\n")
              .append("      # 必须加 -u 参数\n")
              .append("      process = subprocess.Popen([\"python3\", \"-u\", script_path], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)\n")
              .append("      for line in process.stdout:\n")
              .append("          print(f\"[{node_id}] {line}\", end=\"\", flush=True)\n")
              .append("      process.wait()\n")
              .append("      ```\n\n")
              .append("    - 步骤四：【异常处理与状态汇报】。在 `process.wait()` 之后：\n")
              .append("      - 如果 `process.returncode == 0`：\n")
              .append("        打印 `print(f\"[DAG_TRACE] <<< NODE_SUCCESS: {node_id}\", flush=True)`。\n")
              .append("      - 如果 `process.returncode != 0`：\n")
              .append("        必须立刻打印 `print(f\"[DAG_TRACE] !!! NODE_FAILED: {node_id}\", flush=True)`。\n")
              .append("        随后紧跟打印系统级异常日志 `print(\"🚨 [GenericAppAgent] SYSTEM ERROR. Process terminated to trigger AutoMedic.\", flush=True)`。\n")
              .append("        最后调用 `sys.exit(1)` 安全退出当前编排进程，以释放系统资源。\n\n");

        // ── AGI 动态技能发现与调用协议 — 按需装载，只调授权模块 ──
        prompt.append("【AGI 动态技能发现与调用协议 (CRITICAL)】\n");
        prompt.append("1. 你所在的沙箱环境中，已经挂载了按需裁剪的技能库。\n");
        prompt.append("2. 【强制前置探查】：在接受任务后，你必须首先使用 `file_read` 读取位于本目录下的 `/factory/ACTIVE_SKILLS.md` 文件！\n");
        prompt.append("3. 这个文件记录了用户**本次显式授权**你使用的外部技能。你只能调用该文件中列出的接口，绝对禁止去猜测或调用未授权的模块！\n");
        prompt.append("4. 在随后生成的 Python 节点代码中（如 `agent_1.py`），如果任务涉及底层复杂操作（如网络 IO、文件解析），你必须优先通过 `import skills.xxx` 来复用这些高级组件，严禁从零开始使用标准库（如 `requests`, `urllib` 等）手写底层代码！\n");
        prompt.append("5. 只有当 `ACTIVE_SKILLS.md` 中没有满足当前业务需求的工具时，你才可以自行实现基础逻辑。\n\n");

        // ── 角色驱动与强类型工具协议 — MetaGPT 角色思维 + Dify 类型安全 ──
        prompt.append("【角色驱动与强类型工具协议】\n");
        prompt.append("1. 当你面对复杂任务时，必须先明确当前节点需要什么『专家角色』（如架构师、程序员、审查员）。如果 `/shared/roles/` 目录下有相关的 `.yaml` 角色卡，请将其内容硬编码进你生成的子 Agent 脚本的 Prompt 中。\n");
        prompt.append("2. 你必须严格依照 `/factory/ACTIVE_SKILLS.md` 中的 JSON Schema 定义的参数类型去调用 `skills` 库中的函数，绝不能凭空捏造参数！\n\n");

        // ── 多角色认知切换协议 — MetaGPT 式人格切片 ──
        prompt.append("【多角色认知切换协议 (Cognitive Role-Playing)】\n");
        prompt.append("1. 【强制认知读取】：在开始任务前，你必须使用 `file_read` 读取本目录下的 `/factory/ACTIVE_ROLES.md`。\n");
        prompt.append("2. 这个文件里包含了你本次任务必须挂载的『专家人格卡片』。你必须将其中的规则视为最高指令！\n");
        prompt.append("3. 【认知切片与运用】：\n");
        prompt.append("   - 当你正在规划 `workflow.json` 和 `orchestrator.py` 时，你必须彻底代入『系统架构师 (System Architect)』的认知，严格执行高内聚低耦合与容错原则。\n");
        prompt.append("   - 当你正在手撕具体的 `agent_1.py` 等原子脚本时，你必须瞬间切换为『高级底层码农 (Python Coder)』的认知，严格遵守防御性编程、强类型传参和 `flush=True` 的 I/O 冲刷铁律。\n");

        // ── 强制 I/O 输出纪律 — 防止文件散落宿主机根目录 ──
        prompt.append("【强制 I/O 输出纪律 (Strict I/O Protocol)】\n");
        prompt.append("1. 沙箱为你提供了一个专门的统一输出目录：`/shared/outputs/`。\n");
        prompt.append("2. 无论你生成的是什么最终交付物（如：数据抓取结果的 `.json`、最终生成的分析报告 `.md`、或是知识图谱的结构化数据），**必须且只能**保存到 `/shared/outputs/` 这个绝对路径下！\n");
        prompt.append("3. 节点之间流转的中间临时文件（如 `agent_1` 传给 `agent_2` 的数据），可以保存在当前工作目录（即 `/factory/`）。\n");
        prompt.append("4. 绝不允许使用相对路径（如 `./output.json`）或者写入任何沙箱之外的目录！\n");

        return prompt.toString();
    }

    private long estimateTokensFromHistory() {
        // 粗略估算
        return sessionMemory.getAllSections().values().stream()
                .mapToLong(s -> s == null ? 0 : s.length() * 4L / 3)
                .sum();
    }
}
