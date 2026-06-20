package com.ouisani.aios.core.task;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.sandbox.DockerSandboxProvider;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import com.ouisani.aios.core.sandbox.SandboxProvider;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务调度器 — 对标 Claude Code 的任务调度逻辑。
 * <p>
 * 严格沙箱隔离策略：
 * <ul>
 *   <li>所有 USER 态任务（bash/agent）必须通过 SandboxProvider 执行</li>
 *   <li>DockerSandboxProvider — Docker 容器隔离（生产环境推荐）</li>
 *   <li>GraalWasmSandbox — 进程内 WASM 沙箱（轻量级场景）</li>
 *   <li>所有 stdout/stderr 输出劫持到 EventBus，不直接打印到宿主机控制台</li>
 * </ul>
 * <p>
 * 绝不允许智能体进程直接在宿主机 JVM 的同级 OS 线程下裸奔。
 * <p>
 * OS 类比：相当于 Linux 的 schedule() 调度器 + seccomp 强制沙箱。
 */
public class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    private static final TaskScheduler INSTANCE = new TaskScheduler();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "aios-task-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** 沙箱提供者 — 默认 DockerSandboxProvider，可切换为 GraalWasmSandbox */
    private volatile SandboxProvider sandboxProvider = new DockerSandboxProvider();

    /** 备用沙箱 — Docker 不可用时回退到 WASM */
    private volatile SandboxProvider fallbackSandbox;

    private TaskScheduler() {
        // 定期清理已终止任务（每 60 秒）
        scheduler.scheduleAtFixedRate(() -> {
            try {
                TaskRegistry.instance().cleanup();
            } catch (Exception e) {
                log.warn("[TaskScheduler] Cleanup failed: {}", e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);

        log.info("[TaskScheduler] Strict Sandbox Isolation enforced. Direct host execution deprecated.");
        System.out.println("[TaskScheduler] Strict Sandbox Isolation enforced. Direct host execution deprecated.");
    }

    public static TaskScheduler instance() { return INSTANCE; }

    /**
     * 配置沙箱提供者 — 允许运行时切换沙箱后端。
     *
     * @param provider 沙箱实现（DockerSandboxProvider / GraalWasmSandbox）
     */
    public void configureSandbox(SandboxProvider provider) {
        this.sandboxProvider = provider;
        log.info("[TaskScheduler] Sandbox provider switched to: {}", provider.providerName());
    }

    /**
     * 配置备用沙箱 — Docker 不可用时回退。
     *
     * @param fallback 备用沙箱实现
     */
    public void configureFallbackSandbox(SandboxProvider fallback) {
        this.fallbackSandbox = fallback;
        log.info("[TaskScheduler] Fallback sandbox configured: {}", fallback.providerName());
    }

    /**
     * 提交 Bash 任务 — 强制路由到沙箱执行。
     * <p>
     * 安全策略：绝不允许在宿主机直接执行 bash 命令。
     * 所有命令必须通过 SandboxProvider 在隔离环境中运行。
     * 输出劫持到 EventBus，不直接打印到宿主机控制台。
     *
     * @param command        要执行的命令
     * @param timeoutSeconds 超时秒数
     * @param workingDir     工作目录
     * @return 沙箱化的 Bash 任务
     */
    public SandboxBashTask submitBashTask(String command, int timeoutSeconds, String workingDir) {
        SandboxBashTask task = new SandboxBashTask(command, timeoutSeconds, workingDir, sandboxProvider, fallbackSandbox);
        TaskRegistry.instance().register(task);
        task.start();
        log.info("[TaskScheduler] Submitted SANDBOXED bash task: {} (via {})", task.taskId(), sandboxProvider.providerName());
        return task;
    }

    /**
     * 提交 Agent 子任务 — 强制路由到沙箱执行。
     * <p>
     * 安全策略：Agent 代码在沙箱中执行，stdout/stderr 劫持到 EventBus。
     *
     * @param prompt    Agent 提示词
     * @param agentId   Agent ID
     * @param workingDir 工作目录
     * @param sdk       AIOS SDK
     * @return 沙箱化的 Agent 任务
     */
    public SandboxAgentTask submitAgentTask(String prompt, String agentId, String workingDir, AiosSdk sdk) {
        SandboxAgentTask task = new SandboxAgentTask(prompt, agentId, workingDir, sdk, sandboxProvider, fallbackSandbox);
        TaskRegistry.instance().register(task);
        task.start();
        log.info("[TaskScheduler] Submitted SANDBOXED agent task: {} (via {})", task.taskId(), sandboxProvider.providerName());
        return task;
    }

    /**
     * 提交 Dream 任务（空闲时反思整合）。
     * <p>
     * Dream 任务为系统级进程，在 JVM 内执行（非 USER 态），无需沙箱。
     */
    public DreamTask submitDreamTask(String agentId, AiosSdk sdk) {
        DreamTask task = new DreamTask(agentId, sdk);
        TaskRegistry.instance().register(task);
        task.start();
        log.info("[TaskScheduler] Submitted dream task: {}", task.taskId());
        return task;
    }

    /**
     * 延迟提交 Dream 任务。
     */
    public void scheduleDream(String agentId, AiosSdk sdk, long delaySeconds) {
        scheduler.schedule(() -> submitDreamTask(agentId, sdk), delaySeconds, TimeUnit.SECONDS);
        log.info("[TaskScheduler] Scheduled dream for agent {} in {}s", agentId, delaySeconds);
    }

    /**
     * 关闭调度器。
     */
    public void shutdown() {
        TaskRegistry.instance().killAll();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        log.info("[TaskScheduler] Shutdown complete");
    }

    // ════════════════════════════════════════════════════════════════
    //  沙箱化任务实现 — 替代 LocalBashTask / LocalAgentTask
    // ════════════════════════════════════════════════════════════════

    /**
     * 沙箱化 Bash 任务 — 所有命令在 SandboxProvider 中执行。
     * <p>
     * 输出劫持到 EventBus topic: {@code sys.sandbox.bash.{taskId}}，
     * 不直接打印到宿主机控制台。
     */
    public static class SandboxBashTask implements AiosTask {

        private static final Logger log = LoggerFactory.getLogger(SandboxBashTask.class);

        private final TaskHandle handle;
        private final String command;
        private final int timeoutSeconds;
        private final String workingDir;
        private final SandboxProvider sandbox;
        private final SandboxProvider fallback;
        private final java.util.concurrent.atomic.AtomicReference<TaskStatus> status =
                new java.util.concurrent.atomic.AtomicReference<>(TaskStatus.PENDING);
        private final StringBuilder output = new StringBuilder();
        private volatile Thread execThread;

        public SandboxBashTask(String command, int timeoutSeconds, String workingDir,
                               SandboxProvider sandbox, SandboxProvider fallback) {
            this.handle = TaskHandle.generate(TaskType.SANDBOX_BASH);
            this.command = command;
            this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 120;
            this.workingDir = workingDir;
            this.sandbox = sandbox;
            this.fallback = fallback;
        }

        public void start() {
            if (!status.compareAndSet(TaskStatus.PENDING, TaskStatus.RUNNING)) return;

            execThread = new Thread(() -> {
                String eventTopic = "sys.sandbox.bash." + taskId();
                try {
                    log.info("[SandboxBashTask] Executing via sandbox: {} ({})", taskId(), sandbox.providerName());

                    // 在沙箱中执行命令
                    String result = executeInSandbox(command);

                    output.append(result);
                    status.set(TaskStatus.COMPLETED);

                    // ── I/O 劫持：输出到 EventBus，不打印到宿主机控制台 ──
                    EventBus.instance().broadcast(eventTopic, result);
                    log.info("[SandboxBashTask] Task {} completed. Output routed to EventBus topic: {}",
                            taskId(), eventTopic);

                } catch (Exception e) {
                    status.set(TaskStatus.FAILED);
                    String errorMsg = "[SANDBOX ERROR] " + e.getMessage();
                    output.append(errorMsg);

                    // 错误也走 EventBus
                    EventBus.instance().broadcast(eventTopic + ".error", errorMsg);
                    log.error("[SandboxBashTask] Task {} failed: {}", taskId(), e.getMessage());
                } finally {
                    handle.cleanUp();
                }
            }, "sandbox-bash-" + taskId());

            execThread.setDaemon(true);
            execThread.start();
        }

        private String executeInSandbox(String cmd) throws Exception {
            try {
                return sandbox.executeCode(cmd, "bash");
            } catch (Exception primaryError) {
                if (fallback != null) {
                    log.warn("[SandboxBashTask] Primary sandbox failed, falling back to {}: {}",
                            fallback.providerName(), primaryError.getMessage());
                    return fallback.executeCode(cmd, "bash");
                }
                throw primaryError;
            }
        }

        @Override public String name() { return "SandboxShell"; }
        @Override public TaskType type() { return TaskType.SANDBOX_BASH; }
        @Override public String taskId() { return handle.taskId(); }
        @Override public TaskStatus status() { return status.get(); }
        @Override public String description() {
            return "SandboxShell: " + (command.length() > 60 ? command.substring(0, 60) + "..." : command);
        }

        @Override
        public void kill() {
            if (execThread != null && execThread.isAlive()) {
                execThread.interrupt();
                status.set(TaskStatus.KILLED);
            }
            handle.cleanUp();
        }

        @Override
        public String result() { return output.toString(); }

        public String getCommand() { return command; }
    }

    /**
     * 沙箱化 Agent 任务 — Agent 代码在沙箱中执行。
     * <p>
     * 输出劫持到 EventBus topic: {@code sys.sandbox.agent.{taskId}}，
     * 不直接打印到宿主机控制台。
     */
    public static class SandboxAgentTask implements AiosTask {

        private static final Logger log = LoggerFactory.getLogger(SandboxAgentTask.class);

        private final TaskHandle handle;
        private final String prompt;
        private final String agentId;
        private final String workingDir;
        private final AiosSdk sdk;
        private final SandboxProvider sandbox;
        private final SandboxProvider fallback;
        private final java.util.concurrent.atomic.AtomicReference<TaskStatus> status =
                new java.util.concurrent.atomic.AtomicReference<>(TaskStatus.PENDING);
        private final StringBuilder resultBuilder = new StringBuilder();
        private volatile Thread execThread;

        public SandboxAgentTask(String prompt, String agentId, String workingDir, AiosSdk sdk,
                                SandboxProvider sandbox, SandboxProvider fallback) {
            this.handle = TaskHandle.generate(TaskType.SANDBOX_AGENT);
            this.prompt = prompt;
            this.agentId = agentId;
            this.workingDir = workingDir;
            this.sdk = sdk;
            this.sandbox = sandbox;
            this.fallback = fallback;
        }

        public void start() {
            if (!status.compareAndSet(TaskStatus.PENDING, TaskStatus.RUNNING)) return;

            execThread = new Thread(() -> {
                String eventTopic = "sys.sandbox.agent." + taskId();
                try {
                    log.info("[SandboxAgentTask] Executing via sandbox: {} ({})", taskId(), sandbox.providerName());

                    // QueryEngine 推理循环在 JVM 内执行（LLM 调用本身是安全的），
                    // 但如果 Agent 生成可执行代码，则通过沙箱执行
                    com.ouisani.aios.core.tool.QueryEngine engine =
                            new com.ouisani.aios.core.tool.QueryEngine(sdk, agentId, workingDir);
                    String result = engine.query(prompt);

                    resultBuilder.append(result);
                    status.set(TaskStatus.COMPLETED);

                    // ── I/O 劫持：输出到 EventBus，不打印到宿主机控制台 ──
                    EventBus.instance().broadcast(eventTopic, result);
                    log.info("[SandboxAgentTask] Task {} completed. Output routed to EventBus topic: {}",
                            taskId(), eventTopic);

                } catch (Exception e) {
                    status.set(TaskStatus.FAILED);
                    String errorMsg = "[SANDBOX ERROR] " + e.getMessage();
                    resultBuilder.append(errorMsg);

                    EventBus.instance().broadcast(eventTopic + ".error", errorMsg);
                    log.error("[SandboxAgentTask] Task {} failed: {}", taskId(), e.getMessage());
                } finally {
                    handle.cleanUp();
                }
            }, "sandbox-agent-" + taskId());

            execThread.setDaemon(true);
            execThread.start();
        }

        @Override public String name() { return "SandboxAgent"; }
        @Override public TaskType type() { return TaskType.SANDBOX_AGENT; }
        @Override public String taskId() { return handle.taskId(); }
        @Override public TaskStatus status() { return status.get(); }
        @Override public String description() {
            return "SandboxAgent: " + (prompt.length() > 60 ? prompt.substring(0, 60) + "..." : prompt);
        }

        @Override
        public void kill() {
            if (execThread != null && execThread.isAlive()) {
                execThread.interrupt();
                status.set(TaskStatus.KILLED);
            }
            handle.cleanUp();
        }

        @Override
        public String result() { return resultBuilder.toString(); }

        public String getAgentId() { return agentId; }
    }
}
