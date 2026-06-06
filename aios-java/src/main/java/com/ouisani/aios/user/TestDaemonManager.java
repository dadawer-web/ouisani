package com.ouisani.aios.user;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.user.container.AgentImageConfig;
import com.ouisani.aios.user.container.ContainerRuntime;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestDaemonManager {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   TestDaemonManager: AIOS Systemd E2E Test                  ║");
        System.out.println("║   Desired State Reconciliation — Daemon Keep-Alive          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize infrastructure ──");
        VfsManager.instance().init();
        CgroupManager.instance().init();
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        System.out.println("  ✓ VfsManager initialized");
        System.out.println("  ✓ CgroupManager initialized");
        System.out.println("  ✓ TaskScheduler started");
        System.out.println();

        System.out.println("── Step 2: Create ContainerRuntime + DaemonManager ──");
        ContainerRuntime runtime = new ContainerRuntime(scheduler);
        DaemonManager systemd = new DaemonManager(runtime);
        System.out.println("  ✓ ContainerRuntime created");
        System.out.println("  ✓ DaemonManager (Systemd) created");
        System.out.println();

        System.out.println("── Step 3: Register daemon 'watchdog_agent' ──");
        AgentImageConfig config = AgentImageConfig.builder()
                .baseImage("aios/graalwasm")
                .tokenLimit(5000)
                .wasmPath("/bin/agent.wasm")
                .entrypoint("main")
                .build();
        systemd.registerService("watchdog_agent", config);
        System.out.println();

        System.out.println("── Step 4: Start reconciler ──");
        systemd.startReconciler();
        System.out.println();

        System.out.println("── Step 5: Wait for initial startup, then kill the daemon ──");
        Thread.sleep(2000);

        ContainerRuntime.ContainerContext ctx = runtime.getContainer("watchdog_agent");
        if (ctx != null) {
            AgentTask task = ctx.task();
            System.out.printf("  Daemon 'watchdog_agent' current status: %s%n", task.status());
            System.out.println("  💀 Simulating crash: setting status to KILLED");
            task.setStatus(AgentTask.TaskStatus.KILLED);
        } else {
            System.out.println("  ⚠ Container context not found (may have already completed)");
        }
        System.out.println();

        System.out.println("── Step 6: Wait for reconciler to detect and restart ──");
        Thread.sleep(5000);

        int restartCount = systemd.getRestartCount("watchdog_agent");
        System.out.printf("  Restart count for 'watchdog_agent': %d%n", restartCount);
        boolean autoRestarted = restartCount > 0;
        System.out.printf("  Auto-restart: %s%n%n", autoRestarted ? "✅ DETECTED & RESTARTED" : "❌ NOT TRIGGERED");

        System.out.println("── Step 7: Verify daemon is running again ──");
        ContainerRuntime.ContainerContext ctxAfter = runtime.getContainer("watchdog_agent");
        if (ctxAfter != null) {
            System.out.printf("  Daemon 'watchdog_agent' status after reconcile: %s%n", ctxAfter.task().status());
        }
        System.out.println();

        System.out.println("── Step 8: Kill it AGAIN to test resilience ──");
        if (ctxAfter != null) {
            ctxAfter.task().setStatus(AgentTask.TaskStatus.OOM_KILLED);
            System.out.println("  💀 Second kill: OOM_KILLED");
        }
        Thread.sleep(5000);

        int restartCount2 = systemd.getRestartCount("watchdog_agent");
        System.out.printf("  Restart count after second kill: %d%n", restartCount2);
        boolean doubleRestart = restartCount2 >= 2;
        System.out.printf("  Double restart: %s%n%n", doubleRestart ? "✅ RESILIENT" : "❌ FAILED");

        System.out.println("── Step 9: Unregister daemon ──");
        systemd.unregisterService("watchdog_agent");
        System.out.printf("  Watched daemons after unregister: %s%n%n", systemd.watchedDaemons());

        systemd.stopReconciler();
        scheduler.shutdown();

        if (autoRestarted && doubleRestart) {
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║  🔄 [Systemd] Daemon Keep-Alive Test PASSED!             ║");
            System.out.println("  ║                                                          ║");
            System.out.println("  ║  Desired State → Reconcile → Auto-Restart ✅             ║");
            System.out.println("  ║  Kill → Detect → Restart → Kill → Restart ✅             ║");
            System.out.println("  ║  Kubernetes-style control loop in action! 🌟             ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ Daemon Keep-Alive Test FAILED!");
            System.out.printf("     autoRestarted=%b, doubleRestart=%b%n", autoRestarted, doubleRestart);
        }
    }
}
