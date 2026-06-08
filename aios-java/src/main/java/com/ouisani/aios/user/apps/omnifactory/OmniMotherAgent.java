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
import com.ouisani.aios.core.swarm.CoordinatorMode;
import com.ouisani.aios.core.swarm.InProcessWorker;
import com.ouisani.aios.core.task.DreamTask;
import com.ouisani.aios.core.task.TaskScheduler;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.core.tool.QueryEngine;
import com.ouisani.aios.core.tool.ToolExecutionPipeline;
import com.ouisani.aios.core.tool.ToolRegistry;
import com.ouisani.aios.user.bin.AiosAppManager;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // ── Claude Code 能力模块 ──
    private QueryEngine queryEngine;
    private SessionMemoryService.SessionMemory sessionMemory;
    private CompactService.AutoCompactState compactState;
    private CoordinatorMode coordinator;

    public OmniMotherAgent(WorkflowManifest manifest) {
        super("Omni-Mother", ProcessPriority.REALTIME, 100000);
        this.manifest = manifest;
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

        StringBuilder shellScriptBuilder = new StringBuilder();

        for (WorkflowNode node : manifest.nodes()) {
            // ── God Hand Protocol: 预创建配置文件 ──
            sdk.writeFile(this.agentId, "/factory/configs/" + node.instanceId() + ".json", "{}");

            // ── RAG: Jina Search 真实搜索 ──
            String searchIntent = queryEngine.query(
                    "为了编写这个 Python 节点：[" + node.role() + "]，你需要查阅什么 API 文档吗？"
                    + "请给出一个精准的搜索词。如果完全不需要，请回复 'NONE'。");

            String context = "";
            if (!"NONE".equalsIgnoreCase(searchIntent.trim())) {
                System.out.println("[Mother Agent]   ├─ Jina Search: " + searchIntent);
                context = WebSearchTool.searchForAgent(searchIntent);
                TelemetryService.instance().logEvent("jina_search", Map.of(
                        "node", node.instanceId(), "query", searchIntent));
            }

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
            shellScriptBuilder.append("python /factory/").append(node.instanceId()).append(".py &\n");

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
    //  Claude Code 能力初始化
    // ════════════════════════════════════════════════════════════════

    private void initializeClaudeCodeCapabilities() {
        // ── 1. QueryEngine — 工具增强推理循环 ──
        this.queryEngine = new QueryEngine(sdk, this.agentId, workingDir);

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

        log.info("[Mother Agent] Claude Code capabilities initialized (15 modules)");
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
              .append("3. 极其重要：写完代码后，你必须使用 bash 工具执行 'python /factory/")
              .append(node.instanceId()).append(".py' 来测试是否存在语法错误或缺包 (ModuleNotFoundError)。\n")
              .append("4. 如果 bash 返回报错，请使用 file_edit 或 bash (pip install) 修复问题，再次运行测试。\n")
              .append("5. 只有当测试运行没有报错时，你才能回复纯文本：'NODE_VERIFIED_AND_READY'。");

        return prompt.toString();
    }

    private long estimateTokensFromHistory() {
        // 粗略估算
        return sessionMemory.getAllSections().values().stream()
                .mapToLong(s -> s == null ? 0 : s.length() * 4L / 3)
                .sum();
    }
}
