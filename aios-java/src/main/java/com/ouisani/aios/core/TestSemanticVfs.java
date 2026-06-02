package com.ouisani.aios.core;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.OpenAiAdapter;
import com.ouisani.aios.vfs.SemanticNode;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestSemanticVfs {

    public static void main(String[] args) throws InterruptedException {
        String apiKey = env("OPENAI_API_KEY", "");
        String baseUrl = env("OPENAI_BASE_URL", "https://api.openai.com");
        String model = env("OPENAI_MODEL", "gpt-4o-mini");

        System.out.println("========== TestSemanticVfs: End-to-End ==========");
        System.out.printf("  API Key:  %s%n", apiKey.isBlank() ? "(not set)" : maskKey(apiKey));
        System.out.printf("  Base URL: %s%n", baseUrl);
        System.out.printf("  Model:    %s%n", model);
        System.out.println();

        LlmProvider llm = new OpenAiAdapter(apiKey, baseUrl, model);

        VfsManager vfs = VfsManager.instance();
        vfs.configureLlmProvider(llm);
        vfs.init();

        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();

        CountDownLatch agentDone = new CountDownLatch(1);

        AgentTask task = new AgentTask(
                1,
                AgentTask.TaskStatus.READY,
                "cgroup/semantic-test",
                "/dev/null",
                "/dev/stdout/1",
                new ArrayList<>()
        );

        scheduler.spawn(task, () -> {
            try {
                System.out.println("[Agent#1] Resolving /dev/semantic ...");

                Optional<VfsNode> nodeOpt = vfs.resolve("/dev/semantic");
                if (nodeOpt.isEmpty()) {
                    System.out.println("[Agent#1] ERROR: /dev/semantic not found in VFS!");
                    agentDone.countDown();
                    return;
                }

                VfsNode node = nodeOpt.get();
                System.out.printf("[Agent#1] Resolved node: path=%s, type=%s%n",
                        node.path(), node.nodeType());

                if (!(node instanceof SemanticNode semanticNode)) {
                    System.out.println("[Agent#1] ERROR: /dev/semantic is not a SemanticNode!");
                    agentDone.countDown();
                    return;
                }

                String prompt = "请用一句话解释什么是操作系统的虚拟文件系统 (VFS)？";
                System.out.printf("[Agent#1] Writing to /dev/semantic: \"%s\"%n", prompt);

                boolean written = semanticNode.write(prompt);
                System.out.printf("[Agent#1] write() returned: %s (LLM call completed)%n", written);

                System.out.println("[Agent#1] Calling read() on /dev/semantic (will block until response ready)...");
                String answer = semanticNode.read();

                System.out.println("[Agent#1] Received from VFS: " + answer);
                System.out.printf("[Agent#1] SemanticNode stats: %s%n", semanticNode);
            } catch (Exception e) {
                System.out.printf("[Agent#1] ERROR: %s%n", e.getMessage());
                e.printStackTrace();
            } finally {
                agentDone.countDown();
            }
        });

        boolean finished = agentDone.await(60, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("========== TEST RESULTS ==========");
        System.out.printf("  Agent completed: %s%n", finished);
        System.out.printf("  Task status:     %s%n", task.status());
        System.out.printf("  Scheduler stats: %s%n", scheduler.stats());
        System.out.println("==================================");

        scheduler.shutdown();
        System.out.println("[TestSemanticVfs] Done.");
    }

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }

    private static String maskKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
