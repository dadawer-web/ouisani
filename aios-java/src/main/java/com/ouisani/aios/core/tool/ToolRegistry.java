package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心 — 集中管理所有可用工具，对标 Claude Code 的 tools.ts。
 * <p>
 * 提供：
 * - 工具注册与发现
 * - 按名称查找工具
 * - 生成工具列表供 LLM 调用
 * - 工具提示词聚合
 * <p>
 * OS 类比：相当于 Linux 的 sys_call_table — 内核启动时注册所有系统调用，
 * 用户空间通过编号（名称）查找并调用。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private static final ToolRegistry INSTANCE = new ToolRegistry();

    private final Map<String, Tool<? extends ToolInput>> tools = new ConcurrentHashMap<>();

    private ToolRegistry() {}

    public static ToolRegistry instance() {
        return INSTANCE;
    }

    /**
     * 注册一个工具。
     */
    public <I extends ToolInput> void register(Tool<I> tool) {
        if (tools.containsKey(tool.name())) {
            log.warn("[ToolRegistry] 工具 '{}' 已注册，正在覆盖", tool.name());
        }
        tools.put(tool.name(), tool);
        log.info("[ToolRegistry] 已注册工具: {} ({})", tool.name(), tool.description());
    }

    /**
     * 按名称查找工具。
     */
    @SuppressWarnings("unchecked")
    public <I extends ToolInput> Optional<Tool<I>> get(String name) {
        return Optional.ofNullable((Tool<I>) tools.get(name));
    }

    /**
     * 获取所有已注册工具。
     */
    public Collection<Tool<? extends ToolInput>> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 生成工具列表的 JSON 描述，供 LLM 理解可用的工具集。
     * 格式兼容 OpenAI function calling schema。
     */
    public String toolsDescription() {
        StringBuilder sb = new StringBuilder();
        for (Tool<? extends ToolInput> tool : tools.values()) {
            sb.append("## ").append(tool.name()).append("\n");
            sb.append(tool.description()).append("\n");
            sb.append("Input Schema: ").append(tool.inputSchema()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 聚合所有工具的系统提示词。
     */
    public String aggregatedPrompts() {
        StringBuilder sb = new StringBuilder();
        for (Tool<? extends ToolInput> tool : tools.values()) {
            String p = tool.prompt();
            if (p != null && !p.isBlank()) {
                sb.append("### ").append(tool.name()).append("\n");
                sb.append(p).append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * 注册所有内置工具 — 在内核启动时调用。
     */
    public static void registerBuiltinTools() {
        var reg = instance();
        // ── 内核级系统调用（全局注册） ──
        reg.register(new BashTool());
        reg.register(new FileReadTool());
        reg.register(new FileEditTool());
        reg.register(new FileWriteTool());
        reg.register(new GrepTool());
        reg.register(new GlobTool());
        reg.register(new WebFetchTool());
        reg.register(new AgentTool());
        // AskUserQuestionTool 已删除 — 阻塞式人类 I/O 违反异步 IPC 原则
        // 智能体如需与人类交互，必须通过 SendMessageTool 发送 type:user_prompt 到 EventBus UI 频道
        reg.register(new SendMessageTool());
        reg.register(new LspTool());
        reg.register(new ConfigTool());
        reg.register(new McpTool());
        reg.register(new StructuredExtractTool());
        reg.register(new DeterministicExtractTool());
        reg.register(new WebScrapeTool());
        reg.register(new HtmlToMarkdownTool());
        reg.register(new ContentPipelineTool());
        // ── Human-in-the-Loop + Frontend Tool（借鉴 CopilotKit） ──
        reg.register(new HumanResponseTool());
        reg.register(new FrontendTool());
        // WebSearchTool 已有独立实现 (com.ouisani.aios.core.plugin.WebSearchTool)
        // ── 以下工具已迁移至用户空间 (omnifactory.tools)，内核不再全局注册 ──
        // TodoWriteTool, NotebookEditTool, PlanModeTool, TaskTool, SkillTool
        // 由 OmniMotherAgent 在用户空间按需注册
        log.info("[Kernel] 高级认知工具已迁移至 OmniMother 用户空间。");
        log.info("[Syscall] 阻塞性人机交互工具已移除。异步 EventBus 模式已强制执行。");
        log.info("[ToolRegistry] {} 个内核内置工具已注册", reg.tools.size());
        System.out.println("[Kernel] 高级认知工具已迁移至 OmniMother 用户空间。");
        System.out.println("[Syscall] 阻塞性人机交互工具已移除。异步 EventBus 模式已强制执行。");
        System.out.println("[ToolRegistry] " + reg.tools.size() + " 个内核内置工具已注册");
    }
}
