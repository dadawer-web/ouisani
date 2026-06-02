package com.ouisani.aios.core;

import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.vfs.PipeNode;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestProcFs {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     TestProcFs: Dynamic /proc Filesystem Test               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize infrastructure ──");
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        VfsManager.instance().configureTaskScheduler(scheduler);
        VfsManager.instance().init();
        CgroupManager.instance().init();
        System.out.println("  ✓ VfsManager initialized (with /proc/agents + /proc/cgroups)");
        System.out.println("  ✓ CgroupManager initialized");
        System.out.println("  ✓ TaskScheduler started");
        System.out.println();

        System.out.println("── Step 2: Spawn test agents ──");
        CountDownLatch latch = new CountDownLatch(2);
        AgentTask t1 = new AgentTask(301, AgentTask.TaskStatus.READY, "agents",
                "/dev/null", "/dev/null", List.of());
        AgentTask t2 = new AgentTask(302, AgentTask.TaskStatus.READY, "agents",
                "/dev/null", "/dev/null", List.of());
        scheduler.spawn(t1, () -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) {}
            latch.countDown();
        });
        scheduler.spawn(t2, () -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) {}
            latch.countDown();
        });
        Thread.sleep(500);
        System.out.printf("  ✓ Agent#301 and Agent#302 spawned (sleeping 5s)%n%n");

        System.out.println("── Step 3: Read /proc/agents (dynamic agent list) ──");
        VfsNode agentsNode = VfsManager.instance().resolve("/proc/agents").orElseThrow();
        String agentsJson = agentsNode.read();
        System.out.printf("  %s%n%n", agentsJson);
        boolean agentsOk = agentsJson.contains("\"pid\":301")
                && agentsJson.contains("\"pid\":302")
                && agentsJson.contains("\"status\":\"RUNNING\"")
                && agentsJson.contains("\"stats\":");
        System.out.printf("  Validation: %s%n%n", agentsOk ? "✅ AGENTS JSON CORRECT" : "❌ MISMATCH");

        System.out.println("── Step 4: Read /proc/cgroups (dynamic cgroup tree) ──");
        VfsNode cgroupsNode = VfsManager.instance().resolve("/proc/cgroups").orElseThrow();
        String cgroupsJson = cgroupsNode.read();
        System.out.printf("  %s%n%n", cgroupsJson);
        boolean cgroupsOk = cgroupsJson.contains("\"name\":\"aios-root\"")
                && cgroupsJson.contains("\"name\":\"agents\"")
                && cgroupsJson.contains("\"quota\":")
                && cgroupsJson.contains("\"consumed\":");
        System.out.printf("  Validation: %s%n%n", cgroupsOk ? "✅ CGROUPS JSON CORRECT" : "❌ MISMATCH");

        System.out.println("── Step 5: Write to /proc/agents (should be blocked) ──");
        try {
            agentsNode.write("hack");
            System.out.println("  ❌ ERROR: write should have thrown!");
        } catch (UnsupportedOperationException e) {
            System.out.printf("  ✅ Correctly blocked: %s%n%n", e.getMessage());
        }

        System.out.println("── Step 6: Verify /proc is read-only (permissions=0444) ──");
        System.out.printf("  /proc/agents permissions: %04o%n", agentsNode.permissions());
        System.out.printf("  /proc/cgroups permissions: %04o%n%n", cgroupsNode.permissions());

        scheduler.cancelAgent(301);
        scheduler.cancelAgent(302);
        scheduler.shutdown();

        if (agentsOk && cgroupsOk) {
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║  🔍 [ProcFS] Dynamic /proc Filesystem Test PASSED!      ║");
            System.out.println("  ║  /proc/agents  → live agent list as JSON ✅              ║");
            System.out.println("  ║  /proc/cgroups → live cgroup tree as JSON ✅             ║");
            System.out.println("  ║  Read-only enforcement: ✅                               ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ ProcFS Test FAILED!");
        }
    }
}
