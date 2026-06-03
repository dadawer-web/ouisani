package com.ouisani.aios.core.rtos;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hard Real-Time Watchdog Daemon for AIOS.
 * <p>
 * Runs a high-frequency (10ms interval) scheduler that scans all RUNNING
 * agent tasks. If a task has a non-zero {@code deadlineMs} and the current
 * time has exceeded it, the watchdog performs a hard real-time circuit break:
 * <ol>
 *   <li>Interrupts the virtual thread (breaks underlying network I/O)</li>
 *   <li>Marks the task as {@link AgentTask.TaskStatus#DEADLINE_EXCEEDED}</li>
 *   <li>Broadcasts an emergency halt event via {@link EventBus}</li>
 * </ol>
 */
public final class WatchdogDaemon {

    private static final Logger log = LoggerFactory.getLogger(WatchdogDaemon.class);

    private static final long CHECK_INTERVAL_MS = 10;

    private static final class Holder {
        static final WatchdogDaemon INSTANCE = new WatchdogDaemon();
    }

    public static WatchdogDaemon instance() {
        return Holder.INSTANCE;
    }

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private final AtomicLong totalKills = new AtomicLong(0);

    private WatchdogDaemon() {}

    /**
     * Start the watchdog daemon.
     *
     * @param taskScheduler the scheduler whose PCB will be monitored
     */
    public void start(TaskScheduler taskScheduler) {
        if (running) {
            log.warn("[WatchdogDaemon] Already running, ignoring start()");
            return;
        }

        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aios-watchdog");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                () -> checkDeadlines(taskScheduler),
                0, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("[WatchdogDaemon] Started with interval={}ms", CHECK_INTERVAL_MS);
    }

    /**
     * Stop the watchdog daemon.
     */
    public void stop() {
        if (!running) return;
        running = false;

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("[WatchdogDaemon] Stopped. Total deadline kills: {}", totalKills.get());
    }

    private void checkDeadlines(TaskScheduler taskScheduler) {
        try {
            long now = System.currentTimeMillis();
            Map<Integer, AgentTask> tasks = taskScheduler.activeTasks();

            for (Map.Entry<Integer, AgentTask> entry : tasks.entrySet()) {
                AgentTask task = entry.getValue();

                // Only check RUNNING tasks with a deadline set
                if (task.status() != AgentTask.TaskStatus.RUNNING) continue;

                long deadline = task.deadlineMs();
                if (deadline <= 0) continue;

                if (now > deadline) {
                    hardKill(taskScheduler, task, now, deadline);
                }
            }
        } catch (Exception e) {
            log.error("[WatchdogDaemon] Error during deadline check: {}", e.getMessage());
        }
    }

    private void hardKill(TaskScheduler taskScheduler, AgentTask task, long now, long deadline) {
        int pid = task.pid();
        long overshootMs = now - deadline;

        log.warn("[WatchdogDaemon] DEADLINE EXCEEDED! Agent#{} overshoot={}ms (deadline={}, now={})",
                pid, overshootMs, deadline, now);

        // 1. Mark task as DEADLINE_EXCEEDED
        task.setStatus(AgentTask.TaskStatus.DEADLINE_EXCEEDED);
        task.cancel();

        // 2. Interrupt the virtual thread (breaks underlying network I/O)
        taskScheduler.cancelAgent(pid);

        // 3. Broadcast emergency halt via EventBus
        String emergencyMsg = String.format(
                "[RTOS KERNEL] EMERGENCY HALT EXECUTED! Agent#%d exceeded deadline by %dms",
                pid, overshootMs);

        try {
            EventBus.instance().broadcast("emergency_halt", emergencyMsg);
        } catch (Exception e) {
            // EventBus may not be initialized in all test contexts
            log.debug("[WatchdogDaemon] EventBus broadcast failed (may not be initialized): {}", e.getMessage());
        }

        totalKills.incrementAndGet();

        log.info("[WatchdogDaemon] Hard kill complete for Agent#{}. Total kills: {}",
                pid, totalKills.get());
    }

    public boolean isRunning() {
        return running;
    }

    public long totalKills() {
        return totalKills.get();
    }
}
