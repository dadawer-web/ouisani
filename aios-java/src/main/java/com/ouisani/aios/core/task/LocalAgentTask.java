package com.ouisani.aios.core.task;

import com.ouisani.aios.core.tool.QueryEngine;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地 Agent 任务 — 对标 Claude Code 的 LocalAgentTask。
 * <p>
 * 在后台异步执行一个子 Agent（通过 QueryEngine 推理循环），
 * 支持进度追踪和中断控制。
 * <p>
 * OS 类比：相当于 Linux 的 fork() — 创建子进程执行独立任务。
 */
public class LocalAgentTask implements AiosTask {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentTask.class);

    private final TaskHandle handle;
    private final String prompt;
    private final String agentId;
    private final String workingDir;
    private final AiosSdk sdk;
    private final AtomicReference<TaskStatus> status = new AtomicReference<>(TaskStatus.PENDING);
    private final StringBuilder resultBuilder = new StringBuilder();
    private volatile Thread execThread;
    private volatile int toolUseCount = 0;
    private volatile int tokenCount = 0;

    public LocalAgentTask(String prompt, String agentId, String workingDir, AiosSdk sdk) {
        this.handle = TaskHandle.generate(TaskType.LOCAL_AGENT);
        this.prompt = prompt;
        this.agentId = agentId;
        this.workingDir = workingDir;
        this.sdk = sdk;
    }

    /**
     * 启动异步 Agent 执行。
     */
    public void start() {
        if (!status.compareAndSet(TaskStatus.PENDING, TaskStatus.RUNNING)) {
            log.warn("[LocalAgentTask] Cannot start task in state: {}", status.get());
            return;
        }

        execThread = new Thread(() -> {
            try {
                log.info("[LocalAgentTask] Starting agent task {} with prompt length={}", taskId(), prompt.length());
                System.out.printf("[LocalAgentTask] ├─ Starting: %s%n", description());

                QueryEngine engine = new QueryEngine(sdk, agentId, workingDir);
                String result = engine.query(prompt);

                resultBuilder.append(result);
                status.set(TaskStatus.COMPLETED);

                log.info("[LocalAgentTask] Task {} completed. Result length={}", taskId(), result.length());
                System.out.printf("[LocalAgentTask] └─ Completed: %s (%d chars)%n", taskId(), result.length());
            } catch (Exception e) {
                status.set(TaskStatus.FAILED);
                resultBuilder.append("[ERROR] ").append(e.getMessage());
                log.error("[LocalAgentTask] Task {} failed: {}", taskId(), e.getMessage());
            } finally {
                handle.cleanUp();
            }
        }, "agent-task-" + taskId());

        execThread.setDaemon(true);
        execThread.start();
    }

    @Override public String name() { return "LocalAgent"; }
    @Override public TaskType type() { return TaskType.LOCAL_AGENT; }
    @Override public String taskId() { return handle.taskId(); }
    @Override public TaskStatus status() { return status.get(); }
    @Override public String description() {
        return "Agent: " + (prompt.length() > 60 ? prompt.substring(0, 60) + "..." : prompt);
    }

    @Override
    public void kill() {
        if (execThread != null && execThread.isAlive()) {
            execThread.interrupt();
            status.set(TaskStatus.KILLED);
            log.info("[LocalAgentTask] Task {} killed", taskId());
        }
        handle.cleanUp();
    }

    @Override
    public String result() {
        return resultBuilder.toString();
    }

    public int getToolUseCount() { return toolUseCount; }
    public int getTokenCount() { return tokenCount; }
    public String getAgentId() { return agentId; }
}
