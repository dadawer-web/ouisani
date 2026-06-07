package com.ouisani.aios.user.apps.devhouse;

import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.llm.OpenAiAdapter;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.syscall.SyscallDispatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Auto Dev House — 自动开发屋的启动入口（Big Bang）。
 * <p>
 * OS 类比：相当于 Linux 的 init 进程 — 引导 AIOS 内核（VfsManager + TaskScheduler），
 * 挂载 /devhouse 工作区，然后以虚拟线程生成三个 Agent：
 * <ul>
 *   <li>{@link ReviewerAgent} — REALTIME 优先级，首席架构师 / QA</li>
 *   <li>{@link CoderAgent} — NORMAL 优先级，程序员</li>
 *   <li>{@link PmAgent} — HIGH 优先级，产品经理</li>
 * </ul>
 * <p>
 * 流水线流程：PM → Coder → Reviewer，通过 VFS 状态文件协调。
 */
public class AutoDevHouseDemo {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║           🏠 Auto Dev House — Bootstrapping...             ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Load .env for LLM configuration
        Map<String, String> env = loadDotEnv(Path.of("/home/xmy/tryaios/.env"));

        // Step 1: Start TaskScheduler
        System.out.println("  [1/5] Starting TaskScheduler...");
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        System.out.println("  ✓ TaskScheduler: virtual thread executor active");
        System.out.println();

        // Step 2: Configure LLM
        System.out.println("  [2/5] Configuring LLM Router...");
        LlmRouter llmRouter = new LlmRouter();
        String apiKey = env.getOrDefault("OPENAI_API_KEY", System.getenv().getOrDefault("OPENAI_API_KEY", ""));
        String baseUrl = env.getOrDefault("OPENAI_BASE_URL", System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com"));
        String model = env.getOrDefault("OPENAI_MODEL", System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"));

        if (!apiKey.isEmpty()) {
            OpenAiAdapter primaryAdapter = new OpenAiAdapter(apiKey, baseUrl, model);
            llmRouter.registerProvider("fast_model", primaryAdapter);
            llmRouter.registerProvider("smart_model", primaryAdapter);
            VfsManager.instance().configureLlmProvider(primaryAdapter);
            System.out.printf("  ✓ LLM configured: %s @ %s%n", model, baseUrl);
        } else {
            System.out.println("  ⚠ No OPENAI_API_KEY — agents will use fallback content");
        }
        System.out.println();

        // Step 3: Initialize VfsManager
        System.out.println("  [3/5] Initializing VfsManager...");
        VfsManager.instance().configureTaskScheduler(scheduler);
        VfsManager.instance().init();
        System.out.println("  ✓ VfsManager: root filesystem mounted");
        System.out.println();

        // Step 4: Initialize CgroupManager + SyscallDispatcher
        System.out.println("  [4/5] Initializing CgroupManager + SyscallDispatcher...");
        CgroupManager.instance().init();

        if (!apiKey.isEmpty()) {
            SyscallDispatcher.getInstance().configure(llmRouter, VfsManager.instance(), ObjectManager.instance());
            System.out.println("  ✓ SyscallDispatcher: LLM Router wired");
        }
        System.out.println();

        // Step 5: Mount /devhouse workspace and spawn agents
        System.out.println("  [5/5] Mounting /devhouse workspace and spawning agents...");
        VfsManager.instance().mount("/", "devhouse", new VfsNode.DirectoryNode("/devhouse"));
        System.out.println("  ✓ /devhouse workspace mounted");

        // Spawn all three agents
        ReviewerAgent reviewer = new ReviewerAgent();
        CoderAgent coder = new CoderAgent();
        PmAgent pm = new PmAgent();

        reviewer.spawn(scheduler);
        coder.spawn(scheduler);
        pm.spawn(scheduler);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [OS Kernel] Auto Dev House process group launched!        ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * 简单 .env 文件加载器 — 读取 KEY=VALUE 行，忽略注释和空行。
     */
    private static Map<String, String> loadDotEnv(Path dotEnvPath) {
        Map<String, String> env = new HashMap<>();
        if (!Files.exists(dotEnvPath)) {
            System.out.println("  ⚠ .env not found at: " + dotEnvPath);
            return env;
        }

        try (BufferedReader reader = Files.newBufferedReader(dotEnvPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (!key.isEmpty()) {
                        env.put(key, value);
                    }
                }
            }
            System.out.println("  ✓ Loaded " + env.size() + " variables from " + dotEnvPath.getFileName());
        } catch (IOException e) {
            System.out.println("  ⚠ Failed to read .env: " + e.getMessage());
        }
        return env;
    }
}
