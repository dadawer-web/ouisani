package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.task.TaskScheduler;
import com.ouisani.aios.core.task.TaskStatus;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 工具 — 对标 Claude Code 的 AgentTool。
 * <p>
 * 生成子 Agent 执行独立任务，支持：
 * - 同步执行（等待结果）
 * - 异步执行（后台运行）
 * - 内置 Agent 类型（Explore, Plan）
 * <p>
 * OS 类比：相当于 Linux 的 fork() + exec() — 创建子进程执行独立任务。
 */
public class AgentTool implements Tool<AgentTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    /** 内置 Agent 类型 */
    public enum BuiltinAgentType {
        EXPLORE("Explore", "Explore codebase to answer questions about architecture, patterns, and implementation"),
        PLAN("Plan", "Create a detailed implementation plan for a feature or task");

        private final String name;
        private final String description;

        BuiltinAgentType(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String agentName() { return name; }
        public String agentDescription() { return description; }
    }

    public record Input(
            String prompt,
            String subagentType,
            boolean runInBackground,
            String description
    ) implements ToolInput {
        public Input {
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt required");
            if (subagentType == null) subagentType = "";
            if (description == null) description = "";
        }

        public Input(String prompt) { this(prompt, "", false, ""); }

        @Override public String toJson() {
            return "{\"prompt\":\"" + prompt.replace("\"", "\\\"")
                    + "\",\"subagent_type\":\"" + subagentType
                    + "\",\"run_in_background\":" + runInBackground + "}";
        }
    }

    @Override public String name() { return "agent"; }

    @Override public String description() {
        return "Launch a sub-agent to perform a task. Supports built-in agent types (Explore, Plan) and custom prompts. Can run synchronously or in background.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\",\"description\":\"The task description for the sub-agent\"},\"subagent_type\":{\"type\":\"string\",\"description\":\"Built-in agent type: Explore or Plan\"},\"run_in_background\":{\"type\":\"boolean\",\"description\":\"Run asynchronously (default false)\"},\"description\":{\"type\":\"string\",\"description\":\"Short description of the task\"}},\"required\":[\"prompt\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        String agentId = "sub_" + System.currentTimeMillis();
        String prompt = buildPrompt(input);

        log.info("[AgentTool] Launching sub-agent: type={}, background={}", input.subagentType(), input.runInBackground());
        System.out.printf("[AgentTool] ├─ Launching sub-agent: %s%n", input.subagentType().isEmpty() ? "custom" : input.subagentType());

        if (input.runInBackground()) {
            // 异步执行 — 强制沙箱隔离
            TaskScheduler.SandboxAgentTask task = TaskScheduler.instance().submitAgentTask(
                    prompt, agentId, context.workingDir(), context.sdk());

            return ToolOutput.ok("Agent launched in SANDBOX. Task ID: " + task.taskId()
                    + "\nDescription: " + input.description()
                    + "\nStatus: " + task.status()
                    + "\nOutput routed to EventBus: sys.sandbox.agent." + task.taskId());
        } else {
            // 同步执行 — 使用 QueryEngine
            QueryEngine engine = new QueryEngine(context.sdk(), agentId, context.workingDir());
            String result = engine.query(prompt);
            return ToolOutput.ok(result);
        }
    }

    /**
     * 根据 Agent 类型构建完整 Prompt。
     */
    private String buildPrompt(Input input) {
        if (input.subagentType().isEmpty()) {
            return input.prompt();
        }

        return switch (input.subagentType().toUpperCase()) {
            case "EXPLORE" -> """
                    You are an Explore agent. Your job is to thoroughly explore the codebase to answer questions.
                    
                    Guidelines:
                    - Use file_read, grep, and glob tools to investigate the codebase
                    - Provide specific file paths and line numbers in your findings
                    - Summarize architecture, patterns, and key implementation details
                    - Be thorough but concise
                    
                    Task: """ + input.prompt();

            case "PLAN" -> """
                    You are a Plan agent. Your job is to create detailed implementation plans.
                    
                    Guidelines:
                    - Explore the relevant code first using file_read, grep, glob
                    - Identify all files that need to be modified
                    - Break down the implementation into clear, ordered steps
                    - Consider edge cases and potential issues
                    - Provide specific code changes where possible
                    
                    Task: """ + input.prompt();

            default -> input.prompt();
        };
    }

    @Override public boolean readOnly() { return false; }

    @Override public String prompt() {
        return """
                Use the agent tool to delegate complex tasks to sub-agents. Built-in types:
                - Explore: for codebase exploration and architecture analysis
                - Plan: for creating detailed implementation plans
                For simple tasks, handle them directly with other tools instead of spawning an agent.""";
    }
}
