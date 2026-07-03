package com.ouisani.aios.core.task;

import com.ouisani.aios.core.tool.ToolSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Dream 任务 — 对标 Claude Code 的 DreamTask。
 * <p>
 * 自动思考/整合任务：让 Agent 在空闲时进行反思、知识整合、
 * 记忆巩固等"做梦"活动，提升长期智能。
 * <p>
 * OS 类比：相当于 Linux 的 idle 线程 — CPU 空闲时执行内核整理工作
 * （如页回收、inode 回收、RCU 宽期检查等）。
 */
public class DreamTask implements AiosTask {

    private static final Logger log = LoggerFactory.getLogger(DreamTask.class);

    private final TaskHandle handle;
    private final String agentId;
    private final ToolSdk sdk;
    private final AtomicReference<TaskStatus> status = new AtomicReference<>(TaskStatus.PENDING);
    private final StringBuilder insights = new StringBuilder();
    private volatile Thread dreamThread;

    /** Dream 提示词模板 */
    private static final String DREAM_PROMPT = """
            你现在处于"做梦"模式。请回顾你最近的交互历史，进行以下思考：
            
            1. **模式识别**：你是否发现了用户需求中的重复模式？
            2. **知识整合**：你学到了哪些新的 API 用法或最佳实践？
            3. **自我改进**：你有哪些可以改进的地方？
            4. **记忆巩固**：哪些信息值得长期记住？
            
            请以简洁的 JSON 格式输出你的思考结果：
            {"patterns": [...], "learnings": [...], "improvements": [...], "memories": [...]}
            """;

    public DreamTask(String agentId, ToolSdk sdk) {
        this.handle = TaskHandle.generate(TaskType.DREAM);
        this.agentId = agentId;
        this.sdk = sdk;
    }

    /**
     * 启动 Dream 任务。
     */
    public void start() {
        if (!status.compareAndSet(TaskStatus.PENDING, TaskStatus.RUNNING)) return;

        dreamThread = new Thread(() -> {
            try {
                log.info("[DreamTask] Agent {} entering dream state", agentId);
                System.out.printf("[DreamTask] Agent %s dreaming...%n", agentId);

                String result = sdk.think(agentId, DREAM_PROMPT);
                insights.append(result);

                status.set(TaskStatus.COMPLETED);
                log.info("[DreamTask] Agent {} dream completed", agentId);
            } catch (Exception e) {
                status.set(TaskStatus.FAILED);
                insights.append("[DREAM ERROR] ").append(e.getMessage());
                log.error("[DreamTask] Agent {} dream failed: {}", agentId, e.getMessage());
            } finally {
                handle.cleanUp();
            }
        }, "dream-task-" + taskId());

        dreamThread.setDaemon(true);
        dreamThread.start();
    }

    @Override public String name() { return "Dream"; }
    @Override public TaskType type() { return TaskType.DREAM; }
    @Override public String taskId() { return handle.taskId(); }
    @Override public TaskStatus status() { return status.get(); }
    @Override public String description() { return "Dream: reflective consolidation for " + agentId; }

    @Override
    public void kill() {
        if (dreamThread != null && dreamThread.isAlive()) {
            dreamThread.interrupt();
            status.set(TaskStatus.KILLED);
        }
        handle.cleanUp();
    }

    @Override
    public String result() { return insights.toString(); }
}
