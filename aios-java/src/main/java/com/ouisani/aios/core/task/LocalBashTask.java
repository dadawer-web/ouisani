package com.ouisani.aios.core.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地 Shell 任务 — 对标 Claude Code 的 LocalShellTask。
 * <p>
 * 后台执行 Shell 命令，支持超时控制和 Stall Watchdog。
 * <p>
 * OS 类比：相当于 Linux 的后台进程 (bg job)。
 */
public class LocalBashTask implements AiosTask {

    private static final Logger log = LoggerFactory.getLogger(LocalBashTask.class);
    private static final int STALL_CHECK_INTERVAL_MS = 5000;
    private static final int STALL_TIMEOUT_MS = 45000;

    private final TaskHandle handle;
    private final String command;
    private final int timeoutSeconds;
    private final String workingDir;
    private final AtomicReference<TaskStatus> status = new AtomicReference<>(TaskStatus.PENDING);
    private final StringBuilder output = new StringBuilder();
    private volatile Process process;
    private volatile long lastOutputTime = System.currentTimeMillis();
    private volatile Thread watchdogThread;

    public LocalBashTask(String command, int timeoutSeconds, String workingDir) {
        this.handle = TaskHandle.generate(TaskType.LOCAL_BASH);
        this.command = command;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 120;
        this.workingDir = workingDir;
    }

    public LocalBashTask(String command, String workingDir) {
        this(command, 120, workingDir);
    }

    /**
     * 启动后台执行。
     */
    public void start() {
        if (!status.compareAndSet(TaskStatus.PENDING, TaskStatus.RUNNING)) {
            log.warn("[LocalBashTask] Cannot start task in state: {}", status.get());
            return;
        }

        Thread execThread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                if (workingDir != null) pb.directory(new java.io.File(workingDir));
                pb.redirectErrorStream(true);

                process = pb.start();
                lastOutputTime = System.currentTimeMillis();

                // 启动 Stall Watchdog
                startWatchdog();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        lastOutputTime = System.currentTimeMillis();
                    }
                }

                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                stopWatchdog();

                if (!finished) {
                    process.destroyForcibly();
                    status.set(TaskStatus.FAILED);
                    output.append("\n[TIMEOUT] Command timed out after ").append(timeoutSeconds).append("s");
                    log.warn("[LocalBashTask] Task {} timed out", taskId());
                } else {
                    int exitCode = process.exitValue();
                    status.set(exitCode == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED);
                    log.info("[LocalBashTask] Task {} completed with exit code {}", taskId(), exitCode);
                }
            } catch (Exception e) {
                status.set(TaskStatus.FAILED);
                output.append("\n[ERROR] ").append(e.getMessage());
                log.error("[LocalBashTask] Task {} failed: {}", taskId(), e.getMessage());
            } finally {
                handle.cleanUp();
            }
        }, "bash-task-" + taskId());

        execThread.setDaemon(true);
        execThread.start();
    }

    private void startWatchdog() {
        watchdogThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(STALL_CHECK_INTERVAL_MS);
                    long elapsed = System.currentTimeMillis() - lastOutputTime;
                    if (elapsed > STALL_TIMEOUT_MS) {
                        log.warn("[LocalBashTask] Stall detected for task {} (no output for {}ms)", taskId(), elapsed);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "bash-watchdog-" + taskId());
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    private void stopWatchdog() {
        if (watchdogThread != null) watchdogThread.interrupt();
    }

    @Override public String name() { return "LocalShell"; }
    @Override public TaskType type() { return TaskType.LOCAL_BASH; }
    @Override public String taskId() { return handle.taskId(); }
    @Override public TaskStatus status() { return status.get(); }
    @Override public String description() { return "Shell: " + (command.length() > 60 ? command.substring(0, 60) + "..." : command); }

    @Override
    public void kill() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            status.set(TaskStatus.KILLED);
            log.info("[LocalBashTask] Task {} killed", taskId());
        }
        stopWatchdog();
        handle.cleanUp();
    }

    @Override
    public String result() {
        return output.toString();
    }

    public String getCommand() { return command; }
}
