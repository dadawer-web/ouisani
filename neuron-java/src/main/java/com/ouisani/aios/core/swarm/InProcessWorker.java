package com.ouisani.aios.core.swarm;

import com.ouisani.aios.core.tool.QueryEngine;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内 Worker Agent — 对标 Claude Code 的 InProcessRunner。
 * <p>
 * 在同进程内运行 Worker Agent（通过 AsyncLocalStorage 隔离上下文），
 * 支持：
 * - 运行-等待-运行循环
 * - 邮箱消息系统
 * - 优雅关闭
 * <p>
 * OS 类比：相当于 Linux 的内核线程 — 共享地址空间但有独立栈。
 */
public class InProcessWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(InProcessWorker.class);

    private final CoordinatorMode.WorkerIdentity identity;
    private final String initialPrompt;
    private final AiosSdk sdk;
    private final String workingDir;
    private final CoordinatorMode coordinator;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread workerThread;
    private final StringBuilder resultLog = new StringBuilder();

    public InProcessWorker(
            CoordinatorMode.WorkerIdentity identity,
            String initialPrompt,
            AiosSdk sdk,
            String workingDir,
            CoordinatorMode coordinator
    ) {
        this.identity = identity;
        this.initialPrompt = initialPrompt;
        this.sdk = sdk;
        this.workingDir = workingDir;
        this.coordinator = coordinator;
    }

    /**
     * 启动 Worker（fire-and-forget）。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        CoordinatorMode.WorkerState state = coordinator.registerWorker(identity);
        state.setIdle(false);
        state.setCurrentTask(initialPrompt);

        workerThread = new Thread(this, "worker-" + identity.agentName());
        workerThread.setDaemon(true);
        workerThread.start();

        log.info("[InProcessWorker] Started: {} @ {}", identity.agentName(), identity.teamName());
    }

    @Override
    public void run() {
        CoordinatorMode.WorkerState state = coordinator.getWorkers().stream()
                .filter(w -> w.identity().agentId().equals(identity.agentId()))
                .findFirst().orElse(null);

        try {
            // 初始任务
            String prompt = initialPrompt;
            while (running.get() && state != null && !state.isShutdownRequested()) {
                if (prompt == null || prompt.isEmpty()) {
                    // 等待新消息
                    state.setIdle(true);
                    prompt = waitForNextPrompt(state);
                    if (prompt == null) break;
                    state.setIdle(false);
                }

                // 执行任务
                log.info("[InProcessWorker] {} executing task", identity.agentName());
                QueryEngine engine = new QueryEngine(sdk, identity.agentId(), workingDir);
                String result = engine.query(prompt);
                resultLog.append("[Task] ").append(prompt, 0, Math.min(50, prompt.length())).append("\n");
                resultLog.append("[Result] ").append(result, 0, Math.min(100, result.length())).append("\n\n");

                state.setCurrentTask(null);
                prompt = null;
            }
        } catch (Exception e) {
            log.error("[InProcessWorker] {} failed: {}", identity.agentName(), e.getMessage());
        } finally {
            running.set(false);
            coordinator.unregisterWorker(identity.agentId());
            log.info("[InProcessWorker] {} stopped", identity.agentName());
        }
    }

    /**
     * 等待下一个 prompt — 轮询邮箱。
     */
    private String waitForNextPrompt(CoordinatorMode.WorkerState state) {
        for (int i = 0; i < 120; i++) { // 最多等 60 秒
            if (state.isShutdownRequested()) return null;

            List<String> messages = state.drainMessages();
            if (!messages.isEmpty()) return messages.get(0);

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null; // 超时
    }

    /**
     * 向 Worker 发送消息。
     */
    public void sendMessage(String message) {
        coordinator.getWorkers().stream()
                .filter(w -> w.identity().agentId().equals(identity.agentId()))
                .findFirst()
                .ifPresent(w -> w.enqueueMessage(message));
    }

    /**
     * 停止 Worker。
     */
    public void stop() {
        running.set(false);
        coordinator.unregisterWorker(identity.agentId());
        if (workerThread != null) workerThread.interrupt();
    }

    public String getResultLog() { return resultLog.toString(); }
    public boolean isRunning() { return running.get(); }
}
