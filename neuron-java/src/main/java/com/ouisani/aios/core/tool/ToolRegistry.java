package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.security.GuardrailEngine;
import com.ouisani.aios.core.security.builtin.CodeSyntaxGuardrail;
import com.ouisani.aios.core.security.builtin.PromptInjectionGuardrail;
import com.ouisani.aios.core.security.builtin.SensitiveDataGuardrail;
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
     * <p>
     * 强类型 I/O 契约校验：如果工具未声明 inputPorts / outputPorts，
     * 会输出 WARN 日志提醒开发者补全声明（不阻止注册，向后兼容）。
     */
    public <I extends ToolInput> void register(Tool<I> tool) {
        if (tools.containsKey(tool.name())) {
            log.warn("[ToolRegistry] 工具 '{}' 已注册，正在覆盖", tool.name());
        }
        tools.put(tool.name(), tool);

        // ── 强类型 I/O 契约校验 ──
        if (tool.hasIOContract()) {
            String inputTypes = tool.inputPorts().stream()
                    .map(p -> p.name() + ":" + p.type())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
            String outputTypes = tool.outputPorts().stream()
                    .map(p -> p.name() + ":" + p.type())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
            log.info("[ToolRegistry] 已注册工具: {} | I/O 契约: IN[{}] → OUT[{}]",
                    tool.name(), inputTypes, outputTypes);
        } else {
            log.warn("[ToolRegistry] 工具 '{}' 未声明 I/O 契约 (inputPorts/outputPorts 为空)，" +
                    "建议补全声明以启用流水线类型检查", tool.name());
        }
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
     * 注销工具 — 从注册表中移除指定工具。
     * <p>
     * 主要用于动态注册的 MCP 工具在服务器断开时清理。
     *
     * @param name 工具名
     * @return 被移除的工具，不存在返回 null
     */
    public Tool<? extends ToolInput> unregister(String name) {
        Tool<? extends ToolInput> removed = tools.remove(name);
        if (removed != null) {
            log.info("[ToolRegistry] 工具已注销: {}", name);
        }
        return removed;
    }

    /**
     * 生成工具列表的 JSON 描述，供 LLM 理解可用的工具集。
     * 格式兼容 OpenAI function calling schema。
     * <p>
     * 强类型 I/O 契约：同时展示每个工具的 inputPorts / outputPorts，
     * 让 LLM 知道工具之间如何衔接（吃进去什么，吐出来什么）。
     */
    public String toolsDescription() {
        StringBuilder sb = new StringBuilder();
        for (Tool<? extends ToolInput> tool : tools.values()) {
            sb.append("## ").append(tool.name()).append("\n");
            sb.append(tool.description()).append("\n");
            sb.append("Input Schema: ").append(tool.inputSchema()).append("\n");
            // 强类型 I/O 契约展示
            if (tool.hasIOContract()) {
                sb.append("Input Ports: ");
                sb.append(tool.inputPorts().stream()
                        .map(p -> p.name() + "(" + p.type() + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none")).append("\n");
                sb.append("Output Ports: ");
                sb.append(tool.outputPorts().stream()
                        .map(p -> p.name() + "(" + p.type() + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none")).append("\n");
            }
            sb.append("\n");
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
        // ── Handoff 工具（LLM 驱动的 Agent 切换，参考 OpenAI Agents Python Handoff） ──
        // 与 DAG 拓扑互补：DAG 用于确定性流水线，Handoff 用于不确定性探索性任务
        reg.register(new HandoffTool());
        // WebSearchTool 已有独立实现 (com.ouisani.aios.core.plugin.WebSearchTool)
        // ── 以下工具已迁移至用户空间 (omnifactory.tools)，内核不再全局注册 ──
        // TodoWriteTool, NotebookEditTool, PlanModeTool, TaskTool, SkillTool
        // 由 OmniMotherAgent 在用户空间按需注册

        // ── 三级护栏体系注册（参考 OpenAI Agents Python Guardrail 设计） ──
        // 与现有 SyscallFilter（拦截器模式）互补：Guardrail 覆盖 Agent 输入/输出/工具调用三阶段
        GuardrailEngine guardrailEngine = GuardrailEngine.instance();
        guardrailEngine.registerInputGuardrail(new PromptInjectionGuardrail());
        guardrailEngine.registerOutputGuardrail(new CodeSyntaxGuardrail());
        guardrailEngine.registerToolGuardrail(new SensitiveDataGuardrail());
        log.info("[Guardrail] 三级护栏体系已注册：Input(PromptInjection) + Output(CodeSyntax) + Tool(SensitiveData)");
        System.out.println("[Guardrail] 三级护栏体系已注册：Input(PromptInjection) + Output(CodeSyntax) + Tool(SensitiveData)");

        log.info("[Kernel] 高级认知工具已迁移至 OmniMother 用户空间。");
        log.info("[Syscall] 阻塞性人机交互工具已移除。异步 EventBus 模式已强制执行。");
        log.info("[ToolRegistry] {} 个内核内置工具已注册", reg.tools.size());
        System.out.println("[Kernel] 高级认知工具已迁移至 OmniMother 用户空间。");
        System.out.println("[Syscall] 阻塞性人机交互工具已移除。异步 EventBus 模式已强制执行。");
        System.out.println("[ToolRegistry] " + reg.tools.size() + " 个内核内置工具已注册");
    }
}
