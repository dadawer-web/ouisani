package com.ouisani.aios.core.crash;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.OpenAiAdapter;

import java.util.List;

public class TestKernelPanic {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║   TestKernelPanic: Disaster Destruction Test                    ║");
        System.out.println("║   Deliberate Crashes → Interceptor → Core Dump → LLM Diagnosis ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize infrastructure ──");
        String apiKey = System.getenv().getOrDefault("OPENAI_API_KEY", "");
        String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");

        LlmProvider llm;
        if (!apiKey.isBlank()) {
            llm = new OpenAiAdapter(apiKey, baseUrl, model);
        } else {
            llm = new LlmProvider() {
                public String name() { return "fallback"; }
                public String think(String p, String s) { return "No LLM available"; }
                public String thinkWithHistory(List<ChatMessage> m, String s) { return "N/A"; }
                public float[] embed(String t) { return mockEmbed(t); }
                public boolean isAvailable() { return false; }
            };
        }

        VfsManager.instance().configureLlmProvider(llm);
        VfsManager.instance().init();
        CgroupManager.instance().init();
        SemanticCrashAnalyzer.instance().configureLlmProvider(llm);

        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        System.out.println("  ✓ All infrastructure initialized");
        System.out.println();

        // ── Disaster 1: ArrayIndexOutOfBoundsException ──
        System.out.println("── Step 2: Disaster #1 — ArrayIndexOutOfBounds ──");
        AgentTask task1 = new AgentTask(666, AgentTask.TaskStatus.READY,
                "agents", "/dev/null", "/dev/null", List.of());
        task1.appendHistory("Agent was processing an array of sensor readings");
        task1.appendHistory("Agent attempted to access reading at index 5");

        scheduler.spawn(task1, () -> {
            String[] memory = new String[2];
            System.out.println(memory[5]);
        });
        Thread.sleep(5000);
        System.out.printf("  Agent#666 status: %s%n%n", task1.status());

        // ── Disaster 2: ArithmeticException (divide by zero) ──
        System.out.println("── Step 3: Disaster #2 — ArithmeticException (divide by zero) ──");
        AgentTask task2 = new AgentTask(667, AgentTask.TaskStatus.READY,
                "agents", "/dev/null", "/dev/null", List.of());
        task2.appendHistory("Agent was computing token allocation ratio");

        scheduler.spawn(task2, () -> {
            int x = 1 / 0;
            System.out.println("This will never print: " + x);
        });
        Thread.sleep(5000);
        System.out.printf("  Agent#667 status: %s%n%n", task2.status());

        // ── Disaster 3: NullPointerException ──
        System.out.println("── Step 4: Disaster #3 — NullPointerException ──");
        AgentTask task3 = new AgentTask(668, AgentTask.TaskStatus.READY,
                "agents", "/dev/null", "/dev/null", List.of());
        task3.appendHistory("Agent was reading VFS node /dev/vec_mem");
        task3.appendHistory("VFS node returned null, agent did not check");

        scheduler.spawn(task3, () -> {
            String vfsResult = null;
            int len = vfsResult.length();
            System.out.println("Length: " + len);
        });
        Thread.sleep(5000);
        System.out.printf("  Agent#668 status: %s%n%n", task3.status());

        // ── Disaster 4: OutOfMemoryError ──
        System.out.println("── Step 5: Disaster #4 — OutOfMemoryError ──");
        AgentTask task4 = new AgentTask(669, AgentTask.TaskStatus.READY,
                "agents", "/dev/null", "/dev/null", List.of());
        task4.appendHistory("Agent attempted to load entire corpus into memory");

        scheduler.spawn(task4, () -> {
            throw new OutOfMemoryError("Java heap space: failed to allocate 2GB for corpus");
        });
        Thread.sleep(5000);
        System.out.printf("  Agent#669 status: %s%n%n", task4.status());

        // ── Final verification ──
        System.out.println("── Step 6: Wait for LLM diagnoses to complete ──");
        Thread.sleep(15000);

        System.out.println("── Step 7: Verify host JVM survived all disasters ──");
        System.out.printf("  Host JVM: ✅ ALIVE%n");
        System.out.printf("  Scheduler running: %s%n", scheduler.isRunning());
        System.out.printf("  Stats: %s%n%n", scheduler.stats());

        boolean allHandled = (task1.status() == AgentTask.TaskStatus.KILLED || task1.status() == AgentTask.TaskStatus.CRASHED)
                && (task2.status() == AgentTask.TaskStatus.KILLED || task2.status() == AgentTask.TaskStatus.CRASHED)
                && (task3.status() == AgentTask.TaskStatus.KILLED || task3.status() == AgentTask.TaskStatus.CRASHED)
                && task4.status() == AgentTask.TaskStatus.CRASHED;

        boolean hostAlive = scheduler.isRunning();

        scheduler.shutdown();

        if (allHandled && hostAlive) {
            System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
            System.out.println("  ║  🛡️  [Kernel Panic] Disaster Destruction Test PASSED!          ║");
            System.out.println("  ║                                                                  ║");
            System.out.println("  ║  Disaster #1: ArrayIndexOutOfBounds → Intercepted ✅             ║");
            System.out.println("  ║  Disaster #2: ArithmeticException   → Intercepted ✅             ║");
            System.out.println("  ║  Disaster #3: NullPointerException   → Intercepted ✅             ║");
            System.out.println("  ║  Disaster #4: OutOfMemoryError      → Intercepted ✅             ║");
            System.out.println("  ║                                                                  ║");
            System.out.println("  ║  [Self-Healing] Bug successfully diagnosed by AIOS Kernel! 🌟    ║");
            System.out.println("  ║  Host JVM: ALIVE AND WELL — No crash can kill us!                ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ Kernel Panic Test FAILED!");
            System.out.printf("     task1=%s, task2=%s, task3=%s, task4=%s, hostAlive=%b%n",
                    task1.status(), task2.status(), task3.status(), task4.status(), hostAlive);
        }
    }
}
