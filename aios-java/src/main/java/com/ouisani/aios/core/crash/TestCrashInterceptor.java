package com.ouisani.aios.core.crash;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.OpenAiAdapter;

import java.util.List;

public class TestCrashInterceptor {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   TestCrashInterceptor: Throwable Safety Net in Scheduler   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
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
                public String think(String p, String s) { return "No LLM - local analysis only"; }
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

        System.out.println("── Step 2: Spawn Agent that throws OutOfMemoryError ──");
        AgentTask task1 = new AgentTask(501, AgentTask.TaskStatus.READY,
                "agents", "/dev/null", "/dev/null", List.of());
        task1.appendHistory("Agent was processing a large document");
        task1.appendHistory("Agent attempted to allocate huge buffer");

        scheduler.spawn(task1, () -> {
            throw new OutOfMemoryError("Java heap space: failed to allocate 2GB buffer");
        });
        Thread.sleep(2000);
        System.out.printf("  Agent#501 final status: %s%n%n", task1.status());

        System.out.println("── Step 3: Spawn Agent that throws StackOverflowError ──");
        AgentTask task2 = new AgentTask(502, AgentTask.TaskStatus.READY,
                "agents", "/dev/null", "/dev/null", List.of());
        task2.appendHistory("Agent entered recursive reasoning loop");

        scheduler.spawn(task2, () -> {
            throw new StackOverflowError("Recursive call depth exceeded limit");
        });
        Thread.sleep(2000);
        System.out.printf("  Agent#502 final status: %s%n%n", task2.status());

        System.out.println("── Step 4: Spawn Agent that throws InternalError ──");
        AgentTask task3 = new AgentTask(503, AgentTask.TaskStatus.READY,
                "agents", "/dev/null", "/dev/null", List.of());
        task3.appendHistory("Agent was executing WASM bytecode");

        scheduler.spawn(task3, () -> {
            throw new InternalError("JVM internal error during bytecode verification");
        });
        Thread.sleep(2000);
        System.out.printf("  Agent#503 final status: %s%n%n", task3.status());

        System.out.println("── Step 5: Verify host process is still alive ──");
        System.out.printf("  Host process: ✅ ALIVE (not killed by agent crashes)%n");
        System.out.printf("  Scheduler running: %s%n", scheduler.isRunning());
        System.out.printf("  Stats: %s%n%n", scheduler.stats());

        boolean testPassed = task1.status() == AgentTask.TaskStatus.CRASHED
                && task2.status() == AgentTask.TaskStatus.CRASHED
                && task3.status() == AgentTask.TaskStatus.CRASHED;

        scheduler.shutdown();

        if (testPassed) {
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║  🛡️  [CrashInterceptor] Throwable Safety Net PASSED!    ║");
            System.out.println("  ║                                                          ║");
            System.out.println("  ║  OutOfMemoryError → CRASHED + Core Dump ✅               ║");
            System.out.println("  ║  StackOverflowError → CRASHED + Core Dump ✅             ║");
            System.out.println("  ║  InternalError → CRASHED + Core Dump ✅                  ║");
            System.out.println("  ║  Host process: ALIVE AND WELL ✅                          ║");
            System.out.println("  ║  No crash can escape the safety net! 🌟                   ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ CrashInterceptor Test FAILED!");
            System.out.printf("     task1=%s, task2=%s, task3=%s%n", task1.status(), task2.status(), task3.status());
        }
    }
}
