package com.ouisani.aios.user;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.user.container.AgentImageConfig;
import com.ouisani.aios.user.container.ContainerRuntime;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DaemonManager {

    private final ConcurrentHashMap<String, AgentImageConfig> targetState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> restartCounts = new ConcurrentHashMap<>();
    private final ContainerRuntime runtime;
    private final ScheduledExecutorService reconciler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DaemonManager(ContainerRuntime runtime) {
        this.runtime = runtime;
        this.reconciler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().name("systemd-reconciler").unstarted(r);
            return t;
        });
    }

    public void registerService(String serviceName, AgentImageConfig config) {
        targetState.put(serviceName, config);
        restartCounts.putIfAbsent(serviceName, new AtomicInteger(0));
        System.out.printf("  [Systemd] Registered daemon '%s' (baseImage=%s, tokenLimit=%d)%n",
                serviceName, config.baseImage(), config.tokenLimit());
        runtime.runContainer(serviceName, config);
        System.out.printf("  [Systemd] Daemon '%s' started and under watch%n", serviceName);
    }

    public void unregisterService(String serviceName) {
        targetState.remove(serviceName);
        restartCounts.remove(serviceName);
        runtime.stopContainer(serviceName);
        System.out.printf("  [Systemd] Daemon '%s' unregistered and stopped%n", serviceName);
    }

    public void startReconciler() {
        if (running.compareAndSet(false, true)) {
            reconciler.scheduleAtFixedRate(this::reconcile, 3, 3, TimeUnit.SECONDS);
            System.out.println("  [Systemd] Reconciler started (scan interval: 3s)");
            System.out.println("  [Systemd] Watching daemons: " + targetState.keySet());
        }
    }

    public void stopReconciler() {
        if (running.compareAndSet(true, false)) {
            reconciler.shutdown();
            System.out.println("  [Systemd] Reconciler stopped");
        }
    }

    private void reconcile() {
        for (Map.Entry<String, AgentImageConfig> entry : targetState.entrySet()) {
            String serviceName = entry.getKey();
            AgentImageConfig config = entry.getValue();

            if (isDaemonDead(serviceName)) {
                int restarts = restartCounts.get(serviceName).incrementAndGet();
                System.out.printf("%n  ╔══════════════════════════════════════════════════════════════╗%n");
                System.out.printf("  ║  \u001B[31m[Systemd] Daemon '%s' crashed! Restarting to maintain%n", serviceName);
                System.out.printf("  ║  desired state... (restart #%d)\u001B[0m%n", restarts);
                System.out.printf("  ╚══════════════════════════════════════════════════════════════╝%n");

                try {
                    runtime.stopContainer(serviceName);
                } catch (Exception ignored) {
                }

                try {
                    runtime.runContainer(serviceName, config);
                    System.out.printf("  [Systemd] Daemon '%s' restarted successfully%n", serviceName);
                } catch (Exception e) {
                    System.out.printf("  [Systemd] Failed to restart '%s': %s%n", serviceName, e.getMessage());
                }
            }
        }
    }

    private boolean isDaemonDead(String serviceName) {
        ContainerRuntime.ContainerContext ctx = runtime.getContainer(serviceName);
        if (ctx == null) {
            return true;
        }
        AgentTask task = ctx.task();
        AgentTask.TaskStatus status = task.status();
        return status == AgentTask.TaskStatus.KILLED
                || status == AgentTask.TaskStatus.OOM_KILLED;
    }

    public Set<String> watchedDaemons() {
        return Collections.unmodifiableSet(targetState.keySet());
    }

    public int getRestartCount(String serviceName) {
        AtomicInteger count = restartCounts.get(serviceName);
        return count != null ? count.get() : 0;
    }

    public Map<String, AgentImageConfig> getTargetState() {
        return Collections.unmodifiableMap(targetState);
    }
}
