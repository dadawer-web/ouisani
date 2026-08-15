package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.permission.SpawnPrivilegeContext;
import com.ouisani.aios.core.task.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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

        int currentDepth = DelegationGuard.currentDepth();

        // ── 派生预算预检查（深度 + 广度 + 全局总数，借鉴 1 优雅降级）──
        // 三维超限都返回原因字符串，AgentTool 据此降级为 in-context（不拒绝、不 spawn）。
        // 旧实现：DelegationGuard.enter 抛 DelegationException → AgentTool 返回 fail
        //   → 母体拿到"委托被拒绝"错误 → 可能触发 MAX_SELF_HEAL_RETRIES 自愈白烧 token。
        // 新实现：返回 ok + 明确的"在上下文内完成"指令，LLM 直接用其他工具干活，省一轮自愈。
        String spawnLimit = DelegationGuard.checkSpawnAllowed();
        if (spawnLimit != null) {
            return degradeToInContext(input, currentDepth, spawnLimit);
        }

        // ── SoA 类型切换主动告知（层 B·优化）──
        // 子 agent 将处于叶子层（currentDepth+1 == maxDepth）时，在其 prompt 前注入叶子约束，
        // 让 LLM 知道自己不应再派生，减少无效的 agent 工具调用往返。
        // 即使 LLM 仍尝试调 agent 工具，上方 checkSpawnAllowed 会兜底降级。
        // 用 final 变量捕获，供下方 async/sync 两个分支的 lambda 与方法调用统一引用。
        final String effectivePrompt = (currentDepth + 1 == DelegationGuard.maxDepth())
                ? injectLeafConstraint(prompt)
                : prompt;

        // ── enter: 原子计数 + 兜底检查 + 自委托/防环 ──
        // 兜底：防 checkSpawnAllowed 与 enter 间的 TOCTOU 竞态，或自委托/防环错误。
        // 统一降级为 in-context（借鉴 1 精神：任何无法派生的情况都返回 ok 让母体自己干）。
        DelegationGuard.DelegationContext delegationCtx;
        try {
            delegationCtx = DelegationGuard.enter(parentAgentId, agentId);
        } catch (DelegationGuard.DelegationException e) {
            log.warn("[AgentTool] enter 兜底拒绝，降级为 in-context: {}", e.getMessage());
            return degradeToInContext(input, currentDepth, "enter-guard");
        }

        // 保存父 Agent 的 cgroup,用于子 Agent 继承 (token 消耗计入父预算)
        CgroupNode parentCgroup = CgroupManager.instance().currentCgroup();

        log.info("[AgentTool] 正在启动子 Agent: blueprint={}, background={}, parent={}, depth={}",
                input.blueprintPath().isEmpty() ? "(generic)" : input.blueprintPath(),
                input.runInBackground(), parentAgentId, delegationCtx.depth());
        System.out.printf("[AgentTool] ├─ 正在启动子 Agent: blueprint=%s, parent=%s, depth=%d%n",
                input.blueprintPath().isEmpty() ? "(generic)" : input.blueprintPath(),
                parentAgentId, delegationCtx.depth());

        if (input.runInBackground()) {
            // 异步执行 — 强制沙箱隔离
            TaskScheduler.SandboxAgentTask task = TaskScheduler.instance().submitAgentTask(
                    effectivePrompt, agentId, context.workingDir(), context.sdk());

            return ToolOutput.ok("Agent launched in SANDBOX. Task ID: " + task.taskId()
                    + "\nBlueprint: " + (input.blueprintPath().isEmpty() ? "(generic)" : input.blueprintPath())
                    + "\nDescription: " + input.description()
                    + "\nStatus: " + task.status()
                    + "\nOutput routed to EventBus: sys.sandbox.agent." + task.taskId());
        } else {
            // ── 同步执行 — fresh-child-session 模式（借鉴 OpenScience task.ts）──
            // <b>Fresh context 契约</b>：本分支 line 135 通过 `new QueryEngine(...)` 拉起子 Agent，
            // QueryEngine 的 HistoryCompressor 是 per-instance 字段（QueryEngine 构造函数内
            // `new HistoryCompressor(8000, ...)` 初始化），子 Agent 不继承父 Agent 的 compact 历史。
            // 这保证了 reviewer 等 fresh-child 的 blindness —— 不被父上下文污染。
            // （ReviewerRunner R2 gate 复用同一契约，见其类 javadoc。）
            //
            // 结果回流走 <task_result> 压缩协议（SubagentResultFormatter）：超阈值长结果截断为
            // head+tail+省略计数，防止父 Agent 上下文被冗长子 Agent 输出污染。
            //
            // 同步执行 — 使用 waitpid() 阻塞原语
            // 1. 在 WaitRegistry 中注册子 Agent
            AgentWaitRegistry.instance().registerChild(agentId, parentAgentId);

            // 2. 在虚拟线程中启动子 Agent 的 QueryEngine
            Thread.startVirtualThread(() -> {
                try {
                    // 继承父 Agent 的委托上下文 (深度+1, 链+当前节点)
                    DelegationGuard.activate(delegationCtx);

                    // 绑定父 Agent 的 cgroup,使子 Agent 的 token 消耗计入父预算
                    // 这堵住了"通过委托绕过 OOM"的漏洞:子 Agent 烧的 token 从父配额扣
                    if (parentCgroup != null) {
                        CgroupManager.instance().bindToCurrentThread(parentCgroup);
                        log.debug("[AgentTool] 子 Agent {} 绑定到父 cgroup (预算计入父 Agent)", agentId);
                    }

                    // ── LIM 攻击面闭合（Gap A）：spawn 强制权限非递增 ──
                    // 子 agent 继承父的有效 PermissionProfile，杜绝「父被降权时子拿全新 DEFAULT = spawn 即升级」。
                    // SpawnPrivilegeContext 是 InheritableThreadLocal，Thread.startVirtualThread 创建子线程时
                    // 快照父线程值，故此处 current() 拿到的是父 agent 的有效 profile（由父 QueryEngine.executeTool 发布）。
                    // null/empty（父为 DEFAULT 或 headless 直测）→ 传 empty() → applyProfile no-op → 子保持 DEFAULT（零回归）。
                    PermissionProfile parentProfile = SpawnPrivilegeContext.current();
                    QueryEngine engine = new QueryEngine(context.sdk(), agentId, context.workingDir(),
                            List.of(),
                            parentProfile == null ? PermissionProfile.empty() : parentProfile);
                    String result = engine.query(effectivePrompt);
                    AgentWaitRegistry.instance().completeChild(agentId, result);
                } catch (Exception e) {
                    log.error("[AgentTool] 子 Agent 执行异常: agentId={}, error={}", agentId, e.getMessage());
                    AgentWaitRegistry.instance().failChild(agentId, e.getMessage());
                } finally {
                    // 清理子线程的 ThreadLocal,防止虚拟线程复用时泄漏
                    DelegationGuard.clear();
                    CgroupManager.instance().unbindFromCurrentThread();
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
            // 结果回流走 <task_result> 压缩协议 —— 包装+截断，防止父 Agent 上下文污染
            String formatted = SubagentResultFormatter.format(agentId, input.description(), result);
            return ToolOutput.ok(formatted);
        }
    }

    /**
     * SoA 类型切换降级 — 派生预算超限时，不拒绝、不 spawn，
     * 返回 in-context 指令让母体直接用其他工具完成子任务。
     * <p>
     * 借鉴 SoA (self-organized-agent) 的 ChildAgent 模式：深度/广度/总数边界处的 agent
     * 不再派生子智能体，而是直接产出。本方法是其等价简化——不 spawn 叶子子 Agent，
     * 而是让当前 agent 在自己的上下文内完成，省一次虚拟线程 + QueryEngine 实例。
     * <p>
     * 相对旧实现（抛 DelegationException → 返回 fail）的关键改进：返回 {@code ok}
     * 而非 {@code fail}，母体 LLM 拿到的是"在上下文内完成"的明确指令而非"委托失败"错误，
     * 避免触发 {@code MAX_SELF_HEAL_RETRIES} 自愈重试白烧 token。
     *
     * @param reason 超限原因："depth" / "breadth" / "total" / "enter-guard"
     */
    static ToolOutput degradeToInContext(Input input, int currentDepth, String reason) {
        log.info("[AgentTool] 派生降级 (reason={}, depth={}/{}) → in-context: task='{}'",
                reason, currentDepth, DelegationGuard.maxDepth(), input.description());
        System.out.printf("[AgentTool] ├─ 派生降级: reason=%s, depth=%d/%d, task='%s' → in-context%n",
                reason, currentDepth, DelegationGuard.maxDepth(), input.description());
        return ToolOutput.ok("【系统·派生降级】原因: " + reason
                + " | 当前委托深度 " + currentDepth + " / 上限 " + DelegationGuard.maxDepth()
                + "（借鉴 SoA ChildAgent 模式：叶子节点不再派生，直接在上下文内完成）。"
                + "请勿再次调用 agent 工具，直接使用 bash/file_write/web_search 等工具"
                + "在当前上下文内完成以下子任务：\n\n"
                + input.prompt());
    }

    /**
     * SoA 类型切换主动告知（层 B）— 子 agent 将处于叶子层时，在其 prompt 前注入叶子约束，
     * 让 LLM 知道自己不应再派生，减少无效的 agent 工具调用往返。
     * <p>
     * 对应 SoA {@code generate_agent} 中 {@code depth+1 == max_depth} 时生成 ChildAgent
     * 的类型切换：在子 Agent 启动前就告知它"你是叶子"，从源头减少递归派生企图。
     * 即使 LLM 无视约束仍尝试调 agent 工具，层 A 会兜底降级。
     */
    static String injectLeafConstraint(String prompt) {
        log.info("[AgentTool] 子 Agent 将处于叶子层，已注入叶子约束（SoA ChildAgent 模式）");
        return "【系统·叶子节点约束】你处于最大派生深度，禁止使用 agent 工具派生子智能体。"
                + "如需分解子任务，请在当前上下文内用 bash/file_write/web_search 等工具直接完成。\n\n"
                + prompt;
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
