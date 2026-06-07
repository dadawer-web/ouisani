package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.compact.CompactService;
import com.ouisani.aios.core.context.ClaudeMdLoader;
import com.ouisani.aios.core.context.SystemPromptBuilder;
import com.ouisani.aios.core.dream.AutoDreamService;
import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.memory.SessionMemoryService;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.plugin.WebSearchTool;
import com.ouisani.aios.core.swarm.CoordinatorMode;
import com.ouisani.aios.core.swarm.InProcessWorker;
import com.ouisani.aios.core.task.DreamTask;
import com.ouisani.aios.core.task.TaskScheduler;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.core.tool.QueryEngine;
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

            // ── Claude Code: QueryEngine 工具增强代码生成 ──
            String codePrompt = buildCodePrompt(node, context);

            String code;
            if (!ToolRegistry.instance().all().isEmpty()) {
                code = queryEngine.query(codePrompt);
            } else {
                code = sdk.think(this.agentId, codePrompt);
            }

            sdk.writeFile(this.agentId, "/factory/" + node.instanceId() + ".py", code);

            // ── 遥测：记录节点生成 ──
            TelemetryService.instance().logEvent("node_forged", Map.of(
                    "node_id", node.instanceId(),
                    "role", node.role(),
                    "code_length", code.length()
            ));

            shellScriptBuilder.append("export NODE_ID=").append(node.instanceId()).append("\n");
            shellScriptBuilder.append("export INPUT_TOPIC=").append(node.subscribeTopic()).append("\n");
            shellScriptBuilder.append("export OUTPUT_TOPIC=").append(node.publishTopic()).append("\n");
            shellScriptBuilder.append("python /factory/").append(node.instanceId()).append(".py &\n");

            agentfileBuilder.append("SPAWN ").append(node.instanceId()).append(" 1\n");

            System.out.printf("[Mother Agent]   ├─ Node '%s' forged → %d chars%n",
                    node.instanceId(), code.length());

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

        log.info("[Mother Agent] Claude Code capabilities initialized");
    }

    /**
     * 构建代码生成 Prompt — 集成 RAG + CLAUDE.md + BaseAgent 模板。
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

        // 代码生成指令
        prompt.append("你是一个程序员。系统已经为你提供了强壮的基类 /factory/templates/BaseAgent.py。")
              .append("请直接使用 Python 的继承机制：\n")
              .append("1. import BaseAgent\n")
              .append("2. 继承它并重写 process_data(self, data) 方法以实现功能：[").append(node.role()).append("]。\n")
              .append("3. 不要写主循环，不要写文件读写，底层框架已经全部做好了！只输出纯代码！\n")
              .append("4. 如果需要查看现有代码或搜索文件，你可以使用 file_read、grep、glob 等工具。");

        return prompt.toString();
    }

    private long estimateTokensFromHistory() {
        // 粗略估算
        return sessionMemory.getAllSections().values().stream()
                .mapToLong(s -> s == null ? 0 : s.length() * 4L / 3)
                .sum();
    }
}
