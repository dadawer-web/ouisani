package com.ouisani.aios.core;

import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.TokenOomException;
import com.ouisani.aios.core.crash.SemanticCrashAnalyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    private final ConcurrentHashMap<Integer, AgentTask> pcb = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Thread> agentThreads = new ConcurrentHashMap<>();
    private final ExecutorService virtualThreadExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalSpawned = new AtomicLong(0);
    private final AtomicLong totalCompleted = new AtomicLong(0);
    private final AtomicLong totalCancelled = new AtomicLong(0);

    public TaskScheduler() {
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public int spawn(AgentTask task, Runnable agentLogic) {
        return spawn(task, agentLogic, null);
    }

    public int spawn(AgentTask task, Runnable agentLogic, String rootPath) {
        if (!running.get()) {
            throw new IllegalStateException("TaskScheduler is not running. Call start() first.");
        }

        int pid = task.pid();

        AgentTask existing = pcb.putIfAbsent(pid, task);
        if (existing != null) {
            log.warn("PID {} already exists in PCB, rejecting spawn", pid);
            throw new IllegalArgumentException("Duplicate PID: " + pid);
        }

        task.setStatus(AgentTask.TaskStatus.READY);
        totalSpawned.incrementAndGet();

        String effectiveRoot = (rootPath != null && !rootPath.isEmpty() && !rootPath.equals("/"))
                ? rootPath : null;

        Thread vt = Thread.ofVirtual()
                .name("agent-" + pid)
                .unstarted(() -> {
                    try {
                        if (effectiveRoot != null) {
                            VfsManager.AGENT_ROOT.set(effectiveRoot);
                            log.debug("Agent#{} bound to AGENT_ROOT={}", pid, effectiveRoot);
                        }

                        CgroupManager.instance().bindToCurrentThread(
                                CgroupManager.instance().getOrCreateAgentCgroup(pid));

                        task.setStatus(AgentTask.TaskStatus.RUNNING);
                        log.debug("Agent#{} virtual thread started", pid);

                        agentLogic.run();

                        if (Thread.interrupted()) {
                            task.cancel();
                            task.setStatus(AgentTask.TaskStatus.KILLED);
                            totalCancelled.incrementAndGet();
                            log.info("Agent#{} virtual thread interrupted", pid);
                        } else {
                            task.setStatus(AgentTask.TaskStatus.READY);
                            totalCompleted.incrementAndGet();
                            log.debug("Agent#{} virtual thread completed normally", pid);
                        }
                    } catch (TokenOomException e) {
                        task.setStatus(AgentTask.TaskStatus.OOM_KILLED);
                        totalCancelled.incrementAndGet();
                        System.out.printf("  ☠️ [CGROUP OOM] Agent#%d OOM_KILLED: %s%n", pid, e.getMessage());
                        log.error("[CGROUP OOM] Agent#{} killed by cgroup limit: {}", pid, e.getMessage());
                    } catch (Exception e) {
                        task.setStatus(AgentTask.TaskStatus.KILLED);
                        if (task.isCancelled()) {
                            totalCancelled.incrementAndGet();
                            log.info("Agent#{} virtual thread interrupted (cancelled)", pid);
                        } else {
                            totalCancelled.incrementAndGet();
                            log.error("Agent#{} virtual thread crashed: {}", pid, e.getMessage(), e);
                            String lastContext = extractLastContext(task);
                            SemanticCrashAnalyzer.instance().generateCoreDump(
                                    String.valueOf(pid), e, lastContext);
                        }
                    } catch (Throwable t) {
                        task.setStatus(AgentTask.TaskStatus.CRASHED);
                        totalCancelled.incrementAndGet();
                        log.error("[KERNEL PANIC] Agent#{} crashed with fatal throwable: {}", pid, t.getClass().getName());

                        String lastContext = extractLastContext(task);
                        SemanticCrashAnalyzer.instance().generateCoreDump(
                                String.valueOf(pid), t, lastContext);
                    } finally {
                        CgroupManager.instance().unbindFromCurrentThread();
                        if (effectiveRoot != null) {
                            VfsManager.AGENT_ROOT.remove();
                        }
                        agentThreads.remove(pid);
                        pcb.remove(pid, task);
                    }
                });

        agentThreads.put(pid, vt);
        vt.start();

        log.info("Agent#{} spawned on virtual thread", pid);
        return pid;
    }

    public boolean cancelAgent(int pid) {
        AgentTask task = pcb.get(pid);
        if (task == null) {
            log.warn("cancel_agent: PID {} not found in PCB", pid);
            return false;
        }

        task.cancel();
        task.setStatus(AgentTask.TaskStatus.KILLED);

        Thread vt = agentThreads.get(pid);
        if (vt != null) {
            vt.interrupt();
            log.info("Agent#{} virtual thread interrupted", pid);
        }

        agentThreads.remove(pid);
        pcb.remove(pid, task);

        return true;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("TaskScheduler started with virtual thread executor");
        }
    }

    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            log.info("TaskScheduler shutting down, interrupting {} active agents", agentThreads.size());

            for (Map.Entry<Integer, Thread> entry : agentThreads.entrySet()) {
                AgentTask task = pcb.get(entry.getKey());
                if (task != null) {
                    task.cancel();
                    task.setStatus(AgentTask.TaskStatus.KILLED);
                }
                entry.getValue().interrupt();
            }

            agentThreads.clear();
            pcb.clear();

            virtualThreadExecutor.close();
            log.info("TaskScheduler shutdown complete | spawned={} completed={} cancelled={}",
                    totalSpawned.get(), totalCompleted.get(), totalCancelled.get());
        }
    }

    public AgentTask getTask(int pid) {
        return pcb.get(pid);
    }

    public Set<Integer> activePids() {
        return Collections.unmodifiableSet(pcb.keySet());
    }

    public Map<Integer, AgentTask> activeTasks() {
        return Collections.unmodifiableMap(pcb);
    }

    public int activeCount() {
        return pcb.size();
    }

    public SchedulerStats stats() {
        return new SchedulerStats(
                totalSpawned.get(),
                totalCompleted.get(),
                totalCancelled.get(),
                pcb.size()
        );
    }

    public boolean isRunning() {
        return running.get();
    }

    private String extractLastContext(AgentTask task) {
        var history = task.contextHistory();
        if (history == null || history.isEmpty()) return "(no context history)";
        int size = history.size();
        int from = Math.max(0, size - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < size; i++) {
            if (i > from) sb.append(" | ");
            String entry = history.get(i);
            sb.append(entry.length() > 200 ? entry.substring(0, 200) + "..." : entry);
        }
        return sb.toString();
    }

    public record SchedulerStats(long totalSpawned, long totalCompleted, long totalCancelled, int activeCount) {
        @Override
        public String toString() {
            return "SchedulerStats{spawned=%d, completed=%d, cancelled=%d, active=%d}"
                    .formatted(totalSpawned, totalCompleted, totalCancelled, activeCount);
        }
    }
}
