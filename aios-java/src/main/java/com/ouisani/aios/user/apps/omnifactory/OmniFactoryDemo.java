package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.llm.OpenAiAdapter;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.security.PrivilegeSyscallFilter;
import com.ouisani.aios.core.security.RateLimitSyscallFilter;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.telemetry.SystemMonitorDaemon;
import com.ouisani.aios.user.bin.AiosAppManager;
import com.ouisani.aios.vfs.MutableFileNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OmniFactory 全能兵工厂启动入口 — 将母体智能体注入 AIOS 内核。
 * <p>
 * 启动流程：
 * <ol>
 *   <li>加载 .env 配置</li>
 *   <li>初始化内核子系统（TaskScheduler / LLM / VFS / Cgroup / Syscall）</li>
 *   <li>创建共享虚拟目录 /shared 和 /factory</li>
 *   <li>配置 AiosAppManager</li>
 *   <li>注入 OmniMotherAgent — 母体开始自举</li>
 *   <li>主线程挂起，观看监控大屏</li>
 * </ol>
 */
public class OmniFactoryDemo {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║          OMNIFACTORY — 全能兵工厂 启动序列                ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Step 0: 加载 .env ──
        Map<String, String> env = loadDotEnv(Path.of("/home/xmy/tryaios/.env"));

        // ── Step 1: TaskScheduler + SystemMonitorDaemon ──
        System.out.println("  [1/6] Starting TaskScheduler + SystemMonitorDaemon...");
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        SystemMonitorDaemon.getInstance().start();
        System.out.println("  ✓ TaskScheduler + SystemMonitorDaemon active");
        System.out.println();

        // ── Step 2: LLM Router ──
        System.out.println("  [2/6] Configuring LLM Router...");
        LlmRouter llmRouter = new LlmRouter();
        String apiKey = env.getOrDefault("OPENAI_API_KEY", System.getenv().getOrDefault("OPENAI_API_KEY", ""));
        String baseUrl = env.getOrDefault("OPENAI_BASE_URL", System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com"));
        String model = env.getOrDefault("OPENAI_MODEL", System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"));

        if (!apiKey.isEmpty()) {
            OpenAiAdapter adapter = new OpenAiAdapter(apiKey, baseUrl, model);
            llmRouter.registerProvider("fast_model", adapter);
            llmRouter.registerProvider("smart_model", adapter);
            VfsManager.instance().configureLlmProvider(adapter);
            System.out.printf("  ✓ LLM: %s @ %s%n", model, baseUrl);
        } else {
            System.out.println("  ⚠ No OPENAI_API_KEY — LLM unavailable");
        }
        System.out.println();

        // ── Step 3: VfsManager + 共享目录 ──
        System.out.println("  [3/6] Initializing VfsManager + shared directories...");
        VfsManager.instance().configureTaskScheduler(scheduler);
        VfsManager.instance().init();

        // 预先创建共享的虚拟目录，防止 AppManager 挂载失败
        MutableFileNode sharedInit = new MutableFileNode("/shared/.init");
        sharedInit.write("INIT");
        VfsManager.instance().mount("/shared", ".init", sharedInit);
        MutableFileNode factoryInit = new MutableFileNode("/factory/.init");
        factoryInit.write("INIT");
        VfsManager.instance().mount("/factory", ".init", factoryInit);
        System.out.println("  ✓ VfsManager: /shared and /factory directories created");
        System.out.println();

        // ── Step 4: CgroupManager + SyscallDispatcher ──
        System.out.println("  [4/6] Initializing CgroupManager + SyscallDispatcher...");
        CgroupManager.instance().init();

        GraalWasmSandbox sandbox = new GraalWasmSandbox();
        try {
            sandbox.initContext();
            System.out.println("  ✓ GraalWasmSandbox: context initialized");
        } catch (Exception e) {
            System.out.println("  ⚠ GraalWasmSandbox: " + e.getMessage() + " (non-critical)");
        }

        if (!apiKey.isEmpty()) {
            SyscallDispatcher.getInstance().configure(llmRouter, VfsManager.instance(), ObjectManager.instance());
            System.out.println("  ✓ SyscallDispatcher: LLM Router wired");
        }

        SyscallDispatcher.getInstance().addFilter(new RateLimitSyscallFilter());
        SyscallDispatcher.getInstance().addFilter(new PrivilegeSyscallFilter());
        System.out.println("  ✓ Seccomp Firewall active");
        System.out.println();

        // ── Step 5: 配置 AiosAppManager ──
        System.out.println("  [5/6] Configuring AiosAppManager...");
        AiosAppManager.configure(scheduler);
        System.out.println("  ✓ AiosAppManager: ready to hot-load child agents");
        System.out.println();

        // ════════════════════════════════════════════════════════════════
        //  Step 6: 注入全能母体 — Genesis!
        // ════════════════════════════════════════════════════════════════
        System.out.println("  [6/6] Injecting OmniFactory Mother Agent...");
        System.out.println();

        // 构造 WorkflowManifest
        List<WorkflowNode> demoNodes = List.of(
                new WorkflowNode("spider_1", "数据采集", "spider", Map.of(), "", "raw_data"),
                new WorkflowNode("analyzer_1", "情感分析", "analyzer", Map.of(), "raw_data", "analysis_result"),
                new WorkflowNode("presenter_1", "结果展示", "presenter", Map.of(), "analysis_result", "")
        );
        WorkflowManifest demoManifest = new WorkflowManifest("crypto_price_tracker", demoNodes);

        OmniMotherAgent mother = new OmniMotherAgent(demoManifest);

        // 构造 AgentTask（PID 由调度器分配）
        AgentTask motherTask = new AgentTask(
                scheduler.nextPid(),
                AgentTask.TaskStatus.READY,
                "system",       // cgroup: system 组（REALTIME 特权）
                "/dev/null",
                "/dev/null",
                List.of()
        );
        motherTask.setProcessPriority(ProcessPriority.REALTIME);

        scheduler.spawn(motherTask, mother, "/");

        System.out.println("[OS Kernel] OmniFactory Mother Agent injected. Brace for impact...");
        System.out.println();

        // ── 主线程挂起，观看监控大屏 ──
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            System.out.println("[OS Kernel] OmniFactory interrupted. Shutting down...");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 简单的 .env 文件加载器 — 读取 KEY=VALUE 行，忽略注释和空行。
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
            System.out.println("  ✓ Loaded " + env.size() + " variables from .env");
        } catch (IOException e) {
            System.out.println("  ⚠ Failed to read .env: " + e.getMessage());
        }
        return env;
    }
}
