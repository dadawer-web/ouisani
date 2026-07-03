package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.task.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 工具 — 对标 Claude Code 的 AgentTool。
 * <p>
 * 生成子 Agent 执行独立任务，支持：
 * - 同步执行（等待结果）
 * - 异步执行（后台运行）
 * - VFS 蓝图驱动拉起（唯一合法途径）
 * <p>
 * OS 类比：相当于 Linux 的 fork() + exec() — 创建子进程执行独立任务。
 * 但 exec() 的参数不是硬编码的程序名，而是指向文件系统中可执行文件的路径。
 * 同理，AgentTool 只接受 VFS 蓝图路径，不接受硬编码的枚举常量。
 * <p>
 * 绝不包含 BuiltinAgentType 枚举 — 内核不认识任何具体的业务智能体。
 * 要拉起一个 Agent，唯一的合法途径是传入 VFS 蓝图路径或 Docker 镜像名。
 */
public class AgentTool implements Tool<AgentTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    static {
        log.info("[Kernel] 硬编码 BuiltinAgentType 已移除。严格 VFS 驱动生成已强制执行。");
        System.out.println("[Kernel] 硬编码 BuiltinAgentType 已移除。严格 VFS 驱动生成已强制执行。");
    }

    /**
     * Agent 工具输入 — 纯 VFS 蓝图驱动，零硬编码。
     *
     * @param prompt          任务描述（必填）
     * @param blueprintPath   VFS 蓝图路径（如 /factory/blueprints/spider.json）或 Docker 镜像名
     * @param runInBackground 是否异步执行
     * @param description     简短任务描述
     */
    public record Input(
            String prompt,
            String blueprintPath,
            boolean runInBackground,
            String description
    ) implements ToolInput {
        public Input {
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt required");
            if (blueprintPath == null) blueprintPath = "";
            if (description == null) description = "";
        }

        public Input(String prompt) { this(prompt, "", false, ""); }

        @Override public String toJson() {
            return "{\"prompt\":\"" + prompt.replace("\"", "\\\"")
                    + "\",\"blueprint_path\":\"" + blueprintPath.replace("\"", "\\\"")
                    + "\",\"run_in_background\":" + runInBackground
                    + ",\"description\":\"" + description.replace("\"", "\\\"") + "\"}";
        }
    }

    @Override public String name() { return "agent"; }

    @Override public String description() {
        return "Launch a sub-agent to perform a task. The agent is defined by a VFS blueprint path or Docker image. No hardcoded agent types.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"prompt\":{\"type\":\"string\",\"description\":\"The task description for the sub-agent\"},"
                + "\"blueprint_path\":{\"type\":\"string\",\"description\":\"VFS blueprint path (e.g. /factory/blueprints/spider.json) or Docker image name. If empty, a generic agent is spawned.\"},"
                + "\"run_in_background\":{\"type\":\"boolean\",\"description\":\"Run asynchronously (default false)\"},"
                + "\"description\":{\"type\":\"string\",\"description\":\"Short description of the task\"}"
                + "},\"required\":[\"prompt\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        String agentId = "sub_" + System.currentTimeMillis();
        String prompt = buildPrompt(input);

        // 提取父 Agent ID（从 context 或使用默认值）
        String parentAgentId = context.agentId() != null ? context.agentId() : "unknown_parent";

        log.info("[AgentTool] 正在启动子 Agent: blueprint={}, background={}, parent={}",
                input.blueprintPath().isEmpty() ? "(generic)" : input.blueprintPath(),
                input.runInBackground(), parentAgentId);
        System.out.printf("[AgentTool] ├─ 正在启动子 Agent: blueprint=%s, parent=%s%n",
                input.blueprintPath().isEmpty() ? "(generic)" : input.blueprintPath(), parentAgentId);

        if (input.runInBackground()) {
            // 异步执行 — 强制沙箱隔离
            TaskScheduler.SandboxAgentTask task = TaskScheduler.instance().submitAgentTask(
                    prompt, agentId, context.workingDir(), context.sdk());

            return ToolOutput.ok("Agent launched in SANDBOX. Task ID: " + task.taskId()
                    + "\nBlueprint: " + (input.blueprintPath().isEmpty() ? "(generic)" : input.blueprintPath())
                    + "\nDescription: " + input.description()
                    + "\nStatus: " + task.status()
                    + "\nOutput routed to EventBus: sys.sandbox.agent." + task.taskId());
        } else {
            // 同步执行 — 使用 waitpid() 阻塞原语
            // 1. 在 WaitRegistry 中注册子 Agent
            AgentWaitRegistry.instance().registerChild(agentId, parentAgentId);

            // 2. 在虚拟线程中启动子 Agent 的 QueryEngine
            Thread.startVirtualThread(() -> {
                try {
                    QueryEngine engine = new QueryEngine(context.sdk(), agentId, context.workingDir());
                    String result = engine.query(prompt);
                    AgentWaitRegistry.instance().completeChild(agentId, result);
                } catch (Exception e) {
                    log.error("[AgentTool] 子 Agent 执行异常: agentId={}, error={}", agentId, e.getMessage());
                    AgentWaitRegistry.instance().failChild(agentId, e.getMessage());
                }
            });

            // 3. 父 Agent 阻塞等待子 Agent 完成 — 对标 waitpid()
            //    虚拟线程被挂起，释放 CPU，直到子 Agent 完成
            //    默认超时 5 分钟，防止僵尸进程永久阻塞
            log.info("[AgentTool] 父 Agent {} 正在等待子 Agent {} 完成 (waitpid)...", parentAgentId, agentId);
            String result = AgentWaitRegistry.instance().waitForChild(agentId, 300_000);

            if (result == null) {
                return ToolOutput.fail("子 Agent 执行超时或失败: " + agentId);
            }

            log.info("[AgentTool] 子 Agent {} 已完成，结果长度: {}", agentId, result.length());
            return ToolOutput.ok(result);
        }
    }

    /**
     * 根据 VFS 蓝图构建完整 Prompt。
     * <p>
     * 如果 blueprintPath 非空：
     * 1. 尝试从 VFS 读取蓝图 JSON
     * 2. 将蓝图内容注入 prompt 作为 Agent 的角色定义
     * 3. 如果 VFS 中不存在，视为 Docker 镜像名（由沙箱处理）
     * <p>
     * 如果 blueprintPath 为空：使用通用 Agent prompt
     */
    private String buildPrompt(Input input) {
        if (input.blueprintPath().isEmpty()) {
            // 通用 Agent — 无蓝图，纯 prompt 驱动
            return input.prompt();
        }

        // 尝试从 VFS 读取蓝图
        String blueprintContent = VfsManager.instance().readText(input.blueprintPath());
        if (blueprintContent != null) {
            log.info("[AgentTool] Blueprint 已从 VFS 加载: {} ({} bytes)",
                    input.blueprintPath(), blueprintContent.length());
            return "你是一个由蓝图定义的智能体。蓝图内容如下：\n"
                    + "```json\n" + blueprintContent + "\n```\n\n"
                    + "请严格按照蓝图中的角色定义和工具列表执行任务。\n\n"
                    + "任务：" + input.prompt();
        }

        // VFS 中不存在 — 可能是 Docker 镜像名
        log.info("[AgentTool] Blueprint 在 VFS 中未找到，视为 Docker 镜像: {}", input.blueprintPath());
        return input.prompt();
    }

    @Override public boolean readOnly() { return false; }

    @Override public String prompt() {
        return """
                Use the agent tool to delegate complex tasks to sub-agents.
                Agents are defined by VFS blueprint paths (e.g. /factory/blueprints/spider.json) or Docker images.
                If no blueprint is specified, a generic agent is spawned with the given prompt.
                For simple tasks, handle them directly with other tools instead of spawning an agent.""";
    }
}
