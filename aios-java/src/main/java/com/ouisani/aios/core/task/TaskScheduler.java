package com.ouisani.aios.core.task;

import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务调度器 — 对标 Claude Code 的任务调度逻辑。
 * <p>
 * 负责：
 * - 后台 Shell 任务调度
 * - Agent 子任务调度
 * - Dream 任务调度（空闲时触发）
 * - 定期清理已终止任务
 * <p>
 * OS 类比：相当于 Linux 的 schedule() 调度器 + kthread 工作队列。
 */
public class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    private static final TaskScheduler INSTANCE = new TaskScheduler();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "aios-task-scheduler");
        t.setDaemon(true);
        return t;
    });

    private TaskScheduler() {
        // 定期清理已终止任务（每 60 秒）
        scheduler.scheduleAtFixedRate(() -> {
            try {
                TaskRegistry.instance().cleanup();
            } catch (Exception e) {
                log.warn("[TaskScheduler] Cleanup failed: {}", e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    public static TaskScheduler instance() { return INSTANCE; }

    /**
     * 提交后台 Shell 任务。
     */
    public LocalBashTask submitBashTask(String command, int timeoutSeconds, String workingDir) {
        LocalBashTask task = new LocalBashTask(command, timeoutSeconds, workingDir);
        TaskRegistry.instance().register(task);
        task.start();
        log.info("[TaskScheduler] Submitted bash task: {}", task.taskId());
        return task;
    }

    /**
     * 提交 Agent 子任务。
     */
    public LocalAgentTask submitAgentTask(String prompt, String agentId, String workingDir, AiosSdk sdk) {
        LocalAgentTask task = new LocalAgentTask(prompt, agentId, workingDir, sdk);
        TaskRegistry.instance().register(task);
        task.start();
        log.info("[TaskScheduler] Submitted agent task: {}", task.taskId());
        return task;
    }

    /**
     * 提交 Dream 任务（空闲时反思整合）。
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
}
