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
import com.ouisani.aios.core.security.PrivilegeSyscallFilter;
import com.ouisani.aios.core.security.RateLimitSyscallFilter;
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

/**
 * AIOS 全系统引导测试 — 模拟系统加电，验证 InitDaemon 三阶段引导序列。
 * <p>
 * 引导流程：
 * <ol>
 *   <li>加载 .env 配置</li>
 *   <li>启动 TaskScheduler</li>
 *   <li>配置 LLM Router</li>
 *   <li>初始化 VfsManager</li>
 *   <li>初始化 CgroupManager</li>
 *   <li>启动 WASM Sandbox + SyscallDispatcher</li>
 *   <li>启动 ContainerRuntime + DaemonManager</li>
 *   <li>启动 MCP Server + Syscall Gateway</li>
 *   <li>生成 PID 1 InitDaemon — 执行三阶段引导</li>
 *   <li>验证引导结果</li>
 * </ol>
 */
public class TestFullSystemBoot {

    public static void main(String[] args) {
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║          AIOS Full System Boot Sequence                    ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Step 0: 加载 .env ──
        Map<String, String> env = loadDotEnv(Path.of("/home/xmy/tryaios/.env"));

        // ── Step 1: TaskScheduler ──
        System.out.println("  [1/8] Starting TaskScheduler...");
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        System.out.println("  ✓ TaskScheduler: virtual thread executor active");
        System.out.println();

        // ── Step 2: LLM Router ──
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

            VfsManager.instance().configureLlmProvider(primaryAdapter);
            System.out.println("  ✓ VfsManager: LLM pre-configured for semantic/vector/graph nodes");
        } else {
            System.out.println("  ⚠ No OPENAI_API_KEY found — LLM syscalls will fail");
        }
        System.out.println();

        // ── Step 3: VfsManager ──
        System.out.println("  [3/8] Initializing VfsManager...");
        VfsManager.instance().configureTaskScheduler(scheduler);
        VfsManager.instance().init();
        System.out.println("  ✓ VfsManager: /, /bin, /dev, /mem, /proc, /tmp, /containers, /var");
        System.out.println();

        // ── Step 4: CgroupManager ──
        System.out.println("  [4/8] Initializing CgroupManager...");
        CgroupManager.instance().init();
        System.out.println("  ✓ CgroupManager: root(1M) → agents(500K), system(200K), tools(300K)");
        System.out.println();

        // ── Step 5: WASM Sandbox + SyscallDispatcher ──
        System.out.println("  [5/8] Starting WASM Sandbox + SyscallDispatcher...");
        GraalWasmSandbox sandbox = new GraalWasmSandbox();
        sandbox.initContext();
        System.out.println("  ✓ GraalWasmSandbox: context initialized");

        if (!apiKey.isEmpty()) {
            SyscallDispatcher.getInstance().configure(llmRouter, VfsManager.instance(), ObjectManager.instance());
            System.out.println("  ✓ SyscallDispatcher: LLM Router wired");

            IntentRouter.getInstance().configure(llmRouter, SyscallDispatcher.getInstance());
            System.out.println("  ✓ IntentRouter: LLM + Dispatcher wired");
        }

        SyscallDispatcher.getInstance().addFilter(new RateLimitSyscallFilter());
        SyscallDispatcher.getInstance().addFilter(new PrivilegeSyscallFilter());
        System.out.println("  ✓ Seccomp Firewall: RateLimit(50/s) + Privilege checks active");
        System.out.println();

        // ── Step 6: ContainerRuntime + DaemonManager ──
        System.out.println("  [6/8] Starting ContainerRuntime + DaemonManager...");
        ContainerRuntime runtime = new ContainerRuntime(scheduler);
        DaemonManager systemd = new DaemonManager(runtime);
        systemd.startReconciler();
        System.out.println("  ✓ ContainerRuntime: ready");
        System.out.println("  ✓ DaemonManager: reconciler active (3s interval)");
        System.out.println();

        // ── Step 7: MCP Server + Syscall Gateway ──
        System.out.println("  [7/8] Starting MCP Server + Syscall Gateway...");
        McpServer mcpServer = new McpServer(sandbox);
        SyscallServer gateway = new SyscallServer(scheduler, mcpServer);
        gateway.start(8080);
        System.out.println();

        // ════════════════════════════════════════════════════════════════
        //  Step 8: 生成 PID 1 — InitDaemon 三阶段引导
        // ════════════════════════════════════════════════════════════════
        System.out.println("  [8/8] Spawning PID 1 — InitDaemon (Systemd)...");
        System.out.println();

        InitDaemon init = InitDaemon.spawnAsPid1(scheduler, sandbox, llmRouter);

        // ── 等待引导完成 ──
        // InitDaemon.onStart() 在虚拟线程中异步执行，需要等待
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // ── 验证引导结果 ──
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");

        boolean systemReady = init.isSystemReady();
        Map<String, Boolean> bootResults = init.bootResults();
        long bootTime = init.bootTimeMs();

        if (systemReady) {
            System.out.println("  ║          BOOT VERIFICATION: PASSED ✓                       ║");
        } else {
            System.out.println("  ║          BOOT VERIFICATION: FAILED ✗                       ║");
        }

        System.out.printf("  ║  Boot Phase: %-45s ║%n", init.currentPhase());
        System.out.printf("  ║  Boot Time:  %-45s ║%n", bootTime + "ms");
        System.out.println("  ║                                                             ║");
        System.out.println("  ║  Subsystem Status:                                          ║");

        for (Map.Entry<String, Boolean> entry : bootResults.entrySet()) {
            String status = entry.getValue() ? "OK ✓" : "FAIL ✗";
            System.out.printf("  ║    %-28s %-20s ║%n", entry.getKey(), status);
        }

        System.out.println("  ║                                                             ║");
        System.out.println("  ║  Shell Prompt: aios>                                        ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── 验证断言 ──
        assert systemReady : "InitDaemon should reach COMPLETE phase";
        assert bootResults.containsKey("SystemTickGenerator") : "SystemTickGenerator should be checked";
        assert bootResults.containsKey("VFS") : "VFS should be checked";
        assert bootResults.containsKey("BpfManager") : "BpfManager should be checked";
        assert bootResults.containsKey("WatchdogDaemon") : "WatchdogDaemon should be checked";
        assert bootResults.containsKey("CognitiveDreamDaemon") : "CognitiveDreamDaemon should be checked";

        System.out.println("  ✓ All boot assertions passed.");
        System.out.println();

        // ── 交接给 AiosShell ──
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║          AIOS Kernel Boot Complete                          ║");
        System.out.println("  ║          Handing off to AIOS Shell...                       ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // AiosShell 已由 InitDaemon 在 Phase 3 后自动拉起
        // 这里进入主线程的交互式 Shell（向后兼容）
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
