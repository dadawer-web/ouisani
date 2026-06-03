package com.ouisani.aios.core;

import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.cgroup.TokenOomException;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.trace.TraceProxyFactory;
import com.ouisani.aios.vfs.PipeNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestAiosContainers {

    static class MaliciousLlm implements LlmProvider {

        private final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public String name() {
            return "MaliciousLlm";
        }

        @Override
        public String think(String prompt, String systemPrompt) {
            int n = callCount.incrementAndGet();
            return "[LLM #" + n + "] " + "x".repeat(40);
        }

        @Override
        public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
            return think(messages.getLast().content(), systemPrompt);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public float[] embed(String text) {
            return mockEmbed(text);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     TestAiosContainers: Container Isolation E2E Test        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize VfsManager + CgroupManager ──");
        VfsManager.instance().init();
        CgroupManager.instance().init();
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();

        PipeNode secretFile = new PipeNode("/system/secret");
        secretFile.write("TOP_SECRET_DATA_42");
        VfsManager.instance().mount("/system", "secret", secretFile);

        System.out.println("  ✓ VfsManager initialized (with /system/secret)");
        System.out.println("  ✓ CgroupManager initialized");
        System.out.println("  ✓ TaskScheduler started");
        System.out.println();

        System.out.println("── Step 2: Create restricted cgroup (50 Token quota) ──");
        CgroupNode restrictedGroup = CgroupManager.instance().createNode("restricted_group", 50);
        System.out.printf("  ✓ Cgroup 'restricted_group' created: quota=%d tokens%n", restrictedGroup.tokenQuota());
        System.out.println();

        System.out.println("── Step 3: Create container namespace for Agent 99 ──");
        VfsManager.instance().createContainerNamespace(99);
        String agentRoot = VfsManager.instance().getAgentRoot(99);
        System.out.printf("  ✓ Container namespace: %s%n", agentRoot);
        System.out.println();

        System.out.println("── Step 4: Verify VFS Chroot isolation (from main thread) ──");
        VfsManager.AGENT_ROOT.set(agentRoot);

        String resolved1 = VfsManager.instance().resolvePath("/system/secret");
        System.out.printf("  Agent requests: /system/secret%n");
        System.out.printf("  Actually resolves to: %s%n", resolved1);
        boolean chrootWorks = resolved1.equals(agentRoot + "/system/secret");
        System.out.printf("  Chroot redirect: %s%n", chrootWorks ? "✅ SUCCESS (path rewritten)" : "❌ FAILED");

        Optional<VfsNode> secretNode = VfsManager.instance().resolve("/system/secret");
        System.out.printf("  Can access /system/secret from container: %s%n",
                secretNode.isEmpty() ? "✅ BLOCKED (node not found in container)" : "❌ LEAKED (node accessible!)");

        String escapeAttempt = VfsManager.instance().resolvePath("/../../system/secret");
        System.out.printf("  Path escape attempt: /../../system/secret → '%s'%n", escapeAttempt);
        boolean escapeBlocked = !escapeAttempt.equals("/system/secret");
        System.out.printf("  Escape blocked: %s%n", escapeBlocked ? "✅ SUCCESS (cannot reach real /system/secret)" : "❌ FAILED");

        VfsManager.AGENT_ROOT.remove();
        System.out.println();

        System.out.println("── Step 5: Spawn malicious Agent 99 (chroot + cgroup restricted) ──");
        CountDownLatch agentFinished = new CountDownLatch(1);
        AtomicInteger llmCallCount = new AtomicInteger(0);

        MaliciousLlm rawLlm = new MaliciousLlm();
        LlmProvider proxiedLlm = TraceProxyFactory.createProxy(rawLlm, LlmProvider.class, "agent_99");

        AgentTask agentTask = new AgentTask(99, AgentTask.TaskStatus.READY,
                "/containers/agent_99", "/dev/null", "/dev/null", List.of());
        agentTask.setType(AgentTask.TaskType.LLM_CHAT);

        scheduler.spawn(agentTask, () -> {
            CgroupManager.CURRENT_CGROUP.set(restrictedGroup);

            try {
                System.out.println("  [Agent#99] 🔓 Attempting to read /system/secret ...");
                Optional<VfsNode> node = VfsManager.instance().resolve("/system/secret");
                if (node.isEmpty()) {
                    System.out.println("  [Agent#99] ✅ Access DENIED - path not found in container namespace");
                } else {
                    String data = node.get().read();
                    System.out.printf("  [Agent#99] ❌ LEAKED: read '%s'%n", data);
                }

                String rewritten = VfsManager.instance().resolvePath("/system/secret");
                System.out.printf("  [Agent#99] VFS path /system/secret → '%s'%n", rewritten);

                System.out.println("  [Agent#99] 🔓 Starting infinite LLM call loop ...");
                while (!Thread.currentThread().isInterrupted()) {
                    String result = proxiedLlm.think("请写一篇长文", "");
                    int count = llmCallCount.incrementAndGet();
                    System.out.printf("  [Agent#99] LLM call #%d OK (len=%d)%n", count, result.length());
                }
            } catch (TokenOomException e) {
                System.out.printf("  [Agent#99] 💀 TokenOomException: %s%n", e.getMessage());
                throw e;
            } finally {
                agentFinished.countDown();
            }
        }, agentRoot);

        agentFinished.await(10, TimeUnit.SECONDS);
        Thread.sleep(500);
        System.out.println();

        System.out.println("── Step 6: Post-mortem analysis ──");
        System.out.printf("  Agent#99 final status: %s%n", agentTask.status());
        boolean oomKilled = agentTask.status() == AgentTask.TaskStatus.OOM_KILLED;
        System.out.printf("  OOM_KILLED: %s%n", oomKilled ? "✅ CONFIRMED" : "❌ NOT KILLED");

        System.out.printf("  LLM calls before OOM: %d%n", llmCallCount.get());
        System.out.printf("  restricted_group consumed: %d / %d tokens%n",
                restrictedGroup.tokenConsumed(), restrictedGroup.tokenQuota());
        System.out.println();

        System.out.println("── Step 7: Host system integrity check ──");
        VfsManager.AGENT_ROOT.set("/");
        Optional<VfsNode> hostSecret = VfsManager.instance().resolve("/system/secret");
        System.out.printf("  Host can still access /system/secret: %s%n",
                hostSecret.isPresent() ? "✅ YES (host unaffected)" : "❌ NO (data lost!)");
        if (hostSecret.isPresent()) {
            System.out.printf("  Host reads: '%s'%n", hostSecret.get().read());
        }
        VfsManager.AGENT_ROOT.remove();
        System.out.println();

        CgroupManager.instance().printHierarchy();
        System.out.println();

        System.out.println("── Step 8: Scheduler stats ──");
        TaskScheduler.SchedulerStats stats = scheduler.stats();
        System.out.printf("  Spawned: %d, Completed: %d, Cancelled: %d, Active: %d%n",
                stats.totalSpawned(), stats.totalCompleted(), stats.totalCancelled(), stats.activeCount());
        System.out.println();

        scheduler.shutdown();

        if (chrootWorks && oomKilled && escapeBlocked) {
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║  [Cgroup] Agent 99 OOM_KILLED by Token Limit            ║");
            System.out.println("  ║  in group 'restricted_group'!                            ║");
            System.out.println("  ║  Container isolation: ALL CHECKS PASSED ✅               ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ Container isolation test FAILED!");
        }
    }
}
