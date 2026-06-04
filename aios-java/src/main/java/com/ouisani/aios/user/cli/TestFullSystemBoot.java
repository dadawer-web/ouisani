package com.ouisani.aios.user.cli;

import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.llm.OpenAiAdapter;
import com.ouisani.aios.core.mcp.McpServer;
import com.ouisani.aios.core.network.SyscallServer;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.user.DaemonManager;
import com.ouisani.aios.user.container.ContainerRuntime;
import com.ouisani.aios.user.init.InitDaemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class TestFullSystemBoot {

    public static void main(String[] args) {
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║          🚀 AIOS Full System Boot Sequence                 ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Load .env file first
        Map<String, String> env = loadDotEnv(Path.of("/home/xmy/tryaios/.env"));

        System.out.println("  [1/8] Starting TaskScheduler...");
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        System.out.println("  ✓ TaskScheduler: virtual thread executor active");
        System.out.println();

        // Configure LLM BEFORE VfsManager.init() so semantic/vec/graph nodes mount
        System.out.println("  [2/8] Configuring LLM Router...");
        LlmRouter llmRouter = new LlmRouter();
        String apiKey = env.getOrDefault("OPENAI_API_KEY", System.getenv().getOrDefault("OPENAI_API_KEY", ""));
        String baseUrl = env.getOrDefault("OPENAI_BASE_URL", System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com"));
        String model = env.getOrDefault("OPENAI_MODEL", System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"));

        if (!apiKey.isEmpty()) {
            OpenAiAdapter primaryAdapter = new OpenAiAdapter(apiKey, baseUrl, model);
            llmRouter.registerProvider("fast_model", primaryAdapter);
            System.out.printf("  ✓ Primary LLM: %s @ %s%n", model, baseUrl);

            String fbKey = env.getOrDefault("FALLBACK_API_KEY", System.getenv().getOrDefault("FALLBACK_API_KEY", ""));
            String fbUrl = env.getOrDefault("FALLBACK_BASE_URL", System.getenv().getOrDefault("FALLBACK_BASE_URL", ""));
            String fbModel = env.getOrDefault("FALLBACK_MODEL", System.getenv().getOrDefault("FALLBACK_MODEL", ""));
            if (!fbKey.isEmpty() && !fbUrl.isEmpty() && !fbModel.isEmpty()) {
                OpenAiAdapter fallbackAdapter = new OpenAiAdapter(fbKey, fbUrl, fbModel);
                if (fallbackAdapter.isAvailable()) {
                    llmRouter.registerProvider("smart_model", fallbackAdapter);
                    System.out.printf("  ✓ Fallback LLM: %s @ %s%n", fbModel, fbUrl);
                } else {
                    llmRouter.registerProvider("smart_model", primaryAdapter);
                    System.out.printf("  ⚠ Fallback LLM key invalid, using primary for smart_model too%n");
                }
            } else {
                llmRouter.registerProvider("smart_model", primaryAdapter);
                System.out.println("  ✓ Fallback LLM: using primary (no FALLBACK_ config)");
            }

            // Pre-configure VfsManager with LLM so semantic/vec/graph nodes mount during init
            VfsManager.instance().configureLlmProvider(primaryAdapter);
            System.out.println("  ✓ VfsManager: LLM pre-configured for semantic/vector/graph nodes");
        } else {
            System.out.println("  ⚠ No OPENAI_API_KEY found — LLM syscalls will fail");
            System.out.println("  ⚠ Set OPENAI_API_KEY in /home/xmy/tryaios/.env to enable LLM");
        }
        System.out.println();

        System.out.println("  [3/8] Initializing VfsManager...");
        VfsManager.instance().configureTaskScheduler(scheduler);
        VfsManager.instance().init();
        System.out.println("  ✓ VfsManager: /, /bin, /dev, /mem, /proc, /tmp, /containers, /var");
        System.out.println("  ✓ /proc/agents + /proc/cgroups mounted");
        System.out.println();

        System.out.println("  [4/8] Initializing CgroupManager...");
        CgroupManager.instance().init();
        System.out.println("  ✓ CgroupManager: root(1M) → agents(500K), system(200K), tools(300K)");
        System.out.println();

        System.out.println("  [5/8] Starting WASM Sandbox + SyscallDispatcher...");
        GraalWasmSandbox sandbox = new GraalWasmSandbox();
        sandbox.initContext();
        System.out.println("  ✓ GraalWasmSandbox: context initialized");

        if (!apiKey.isEmpty()) {
            SyscallDispatcher.getInstance().configure(llmRouter, VfsManager.instance(), ObjectManager.instance());
            System.out.println("  ✓ SyscallDispatcher: LLM Router wired");

            // Wire IntentRouter so natural language → LLM → Syscall pipeline works
            IntentRouter.getInstance().configure(llmRouter, SyscallDispatcher.getInstance());
            System.out.println("  ✓ IntentRouter: LLM + Dispatcher wired");
        }
        System.out.println();

        System.out.println("  [6/8] Starting ContainerRuntime + DaemonManager...");
        ContainerRuntime runtime = new ContainerRuntime(scheduler);
        DaemonManager systemd = new DaemonManager(runtime);
        systemd.startReconciler();
        System.out.println("  ✓ ContainerRuntime: ready");
        System.out.println("  ✓ DaemonManager: reconciler active (3s interval)");
        System.out.println();

        System.out.println("  [7/8] Starting MCP Server + Syscall Gateway...");
        McpServer mcpServer = new McpServer(sandbox);
        SyscallServer gateway = new SyscallServer(scheduler, mcpServer);
        gateway.start(8080);
        System.out.println();

        // ── Spawn PID 1: InitDaemon ──
        System.out.println("  [8/8] Spawning PID 1 — InitDaemon (Systemd)...");
        InitDaemon.spawnAsPid1(scheduler, sandbox);
        System.out.println();

        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║          🟢 AIOS Kernel Boot Complete                      ║");
        System.out.println("  ║          Handing off to AIOS Shell...                      ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        AiosShell.main(args);
    }

    /**
     * Simple .env file loader — reads KEY=VALUE lines, ignores comments and blanks.
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
