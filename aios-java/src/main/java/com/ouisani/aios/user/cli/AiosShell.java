package com.ouisani.aios.user.cli;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.mcp.McpServer;
import com.ouisani.aios.core.network.SyscallServer;
import com.ouisani.aios.drivers.llm.OpenAiAdapter;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * AIOS 交互式智能 Shell — 绑定 E_CORE 的自然语言终端。
 * <p>
 * 类比 Linux 的 agetty + bash：InitDaemon (PID 1) 引导完成后，
 * 创建 AiosShell 作为第一个用户交互进程。它挂载在 /dev/stdin
 * 和 /dev/stdout，接收用户在终端输入的自然语言命令，并将其
 * 转化为系统级的任务或子进程调用。
 *
 * <h3>双模式命令解析</h3>
 * <ul>
 *   <li><b>自然语言模式</b>：用户输入自然语言，通过 E_CORE LLM
 *       路由到 {@link IntentRouter}，将意图转化为系统调用</li>
 *   <li><b>极客模式</b>：以 {@code /} 开头的命令直接作为
 *       Syscall 执行，跳过 LLM 路由</li>
 * </ul>
 *
 * <h3>E_CORE 绑定</h3>
 * AiosShell 绑定到 E_CORE（能效核），因为命令解析和意图路由
 * 是轻量级任务，不需要旗舰模型的推理能力。这节省了算力资源，
 * 将 P_CORE 留给真正需要复杂推理的 Agent。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>Linux</th><th>AIOS AiosShell</th><th>说明</th></tr>
 *   <tr><td>agetty</td><td>InitDaemon.spawnAiosShell()</td><td>终端初始化</td></tr>
 *   <tr><td>bash</td><td>AiosShell REPL</td><td>交互式命令行</td></tr>
 *   <tr><td>/dev/tty0</td><td>/dev/stdin + /dev/stdout</td><td>标准 I/O</td></tr>
 *   <tr><td>fork + exec</td><td>IntentRouter → SyscallDispatcher</td><td>命令执行</td></tr>
 * </table>
 *
 * @see InitDaemon
 * @see IntentRouter
 */
public class AiosShell extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(AiosShell.class);

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RED = "\u001B[31m";

    private static final String PROMPT = ANSI_YELLOW + "aios> " + ANSI_RESET;

    /** Shell 的系统提示词 — 定义它作为 AIOS 终端的行为 */
    private static final String SHELL_SYSTEM_PROMPT =
            "你是 AIOS 操作系统的交互式终端助手。用户会输入自然语言命令，"
            + "你需要理解用户意图并转化为系统操作。"
            + "支持的操作包括：查看进程列表、查看系统状态、管理文件、启动/停止 Agent 等。"
            + "请简洁地回复。";

    private final LlmRouter llmRouter;

    /** 是否已打印就绪提示符 */
    private volatile boolean readyPromptPrinted = false;

    /**
     * 创建 AiosShell — 由 InitDaemon 调用。
     *
     * @param scheduler TaskScheduler
     * @param llmRouter LLM 路由器（用于 E_CORE 路由）
     */
    public AiosShell(TaskScheduler scheduler, LlmRouter llmRouter) {
        super("aios_shell", ProcessPriority.NORMAL, 50000);
        this.llmRouter = llmRouter;
    }

    @Override
    protected void onStart() {
        log.info("[AiosShell] Starting as interactive shell agent...");

        // 设置算力亲和性 — 绑定 E_CORE
        // Shell 的命令解析是轻量级任务，不需要旗舰模型
        // 注意：computeAffinity 会在 spawn() 时通过 AgentTask 设置

        // 打印就绪提示符
        printReadyPrompt();
        readyPromptPrinted = true;

        // 启动 REPL 循环
        startRepl();
    }

    /**
     * 打印就绪提示符 — 类比 bash 的 PS1。
     */
    private void printReadyPrompt() {
        System.out.println();
        System.out.println(ANSI_CYAN + "  ┌─────────────────────────────────────────────────────────┐");
        System.out.println("  │  AIOS Shell Ready                                       │");
        System.out.println("  │  Type naturally or use '/' for raw syscalls             │");
        System.out.println("  │  Type 'exit' to halt the system                         │");
        System.out.println("  └─────────────────────────────────────────────────────────┘" + ANSI_RESET);
        System.out.println();
        System.out.print(PROMPT);
    }

    /**
     * REPL 循环 — 读取用户输入，路由到 LLM 或直接执行 Syscall。
     */
    private void startRepl() {
        Scanner scanner = new Scanner(System.in);
        IntentRouter router = IntentRouter.getInstance();
        SyscallDispatcher dispatcher = SyscallDispatcher.getInstance();

        while (isRunning()) {
            try {
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    System.out.print(PROMPT);
                    continue;
                }

                if (input.equalsIgnoreCase("exit")) {
                    System.out.println(ANSI_RED + "Initiating system halt... Goodbye." + ANSI_RESET);
                    SemanticEtw.getInstance().logEvent("AiosShell", "SYSTEM_HALT", "User exited shell");
                    exit();
                    break;
                }

                try {
                    // 极客模式：以 / 开头的命令直接执行 Syscall
                    if (input.startsWith("/")) {
                        handleRawSyscall(input, dispatcher);
                    }
                    // 自然语言模式：通过 E_CORE LLM 路由意图
                    else {
                        handleNaturalLanguage(input, router);
                    }
                } catch (Exception e) {
                    System.out.println(ANSI_RED + "[Error] " + e.getMessage() + ANSI_RESET);
                }

                System.out.print(PROMPT);

            } catch (Exception e) {
                // Scanner 可能被关闭
                break;
            }
        }

        scanner.close();
    }

    /**
     * 处理自然语言命令 — 通过 LLM 语义路由，零硬编码匹配。
     * <p>
     * 所有自然语言输入统一通过 IntentRouter.route() 进行 LLM 语义分类，
     * 再由 switch-case 分发到对应的系统组件。
     * 绝不使用 startsWith/equals/正则表达式等硬编码匹配。
     */
    private void handleNaturalLanguage(String input, IntentRouter router) {
        System.out.println(ANSI_CYAN + ">> Semantic routing via LLM..." + ANSI_RESET);

        try {
            IntentRouter.RouteResult result = router.route(input);

            switch (result.intentType()) {
                case SYSTEM_COMMAND -> {
                    System.out.println(ANSI_CYAN + "  [SYSTEM_COMMAND] " + ANSI_RESET + result.response());
                }
                case WORKFLOW_DEPLOY -> {
                    System.out.println(ANSI_CYAN + "  [WORKFLOW_DEPLOY] " + ANSI_RESET + result.response());
                    System.out.println(ANSI_GREEN + ">> Mother Agent dispatched in background. Check TaskScheduler logs for progress." + ANSI_RESET);
                }
                case SEMANTIC_SEARCH -> {
                    System.out.println(ANSI_CYAN + "  [SEMANTIC_SEARCH] " + ANSI_RESET + result.response());
                }
                case CHAT -> {
                    System.out.println(ANSI_GREEN + result.response() + ANSI_RESET);
                }
            }
        } catch (Exception e) {
            System.out.println(ANSI_RED + "[Error] Semantic routing failed: " + e.getMessage() + ANSI_RESET);
        }
    }

    /**
     * 处理原生 Syscall 命令 — 极客模式。
     */
    private void handleRawSyscall(String input, SyscallDispatcher dispatcher) {
        String[] parts = input.substring(1).split(" ", 2);
        String action = parts[0];

        SyscallRequest request;
        if (parts.length > 1) {
            String[] paramPairs = parts[1].split("=", 2);
            if (paramPairs.length == 2) {
                request = new SyscallRequest(action, Map.of(paramPairs[0], paramPairs[1]));
            } else {
                request = new SyscallRequest(action, Map.of("payload", parts[1]));
            }
        } else {
            request = new SyscallRequest(action, Map.of());
        }

        System.out.println(ANSI_CYAN + ">> Syscall: " + action + ANSI_RESET);
        SyscallResponse response = dispatcher.execute("aios_shell", request);

        if (response.success()) {
            System.out.println(ANSI_GREEN + response.data() + ANSI_RESET);
        } else {
            System.out.println(ANSI_RED + "Error: " + response.errorMessage() + ANSI_RESET);
        }
    }

    @Override
    protected void onMessage(String msg) {
        // 外部消息直接输出到终端
        System.out.println();
        System.out.println(ANSI_CYAN + "[Message] " + msg + ANSI_RESET);
        System.out.print(PROMPT);
    }

    /**
     * 是否已打印就绪提示符 — 用于测试验证。
     */
    public boolean isReadyPromptPrinted() {
        return readyPromptPrinted;
    }

    /**
     * 独立模式入口 — 自动初始化内核后进入 Shell REPL。
     * <p>
     * 如果没有 InitDaemon 的完整引导，此方法会执行最小化内核初始化：
     * TaskScheduler → LLM Router → VfsManager → CgroupManager → SyscallDispatcher → IntentRouter
     */
    public static void main(String[] args) {
        System.out.println("\u001B[36m");
        System.out.println("   ___  _________  _____ ");
        System.out.println("  / _ \\/  _/ __ \\/ ___/ ");
        System.out.println(" / __ _/ // /_/ /\\__ \\  ");
        System.out.println("/_/ |_/___/\\____/____/  ");
        System.out.println("                        ");
        System.out.println("Ouisani General-Purpose AIOS v1.0.0-FINAL\u001B[0m");
        System.out.println();

        // ── 最小化内核初始化 ──
        Map<String, String> env = loadDotEnv(Path.of("/home/xmy/tryaios/.env"));

        // 1. TaskScheduler
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        com.ouisani.aios.user.bin.AiosAppManager.configure(scheduler);
        System.out.println("  ✓ TaskScheduler started");
        System.out.println("  ✓ [InitDaemon] AiosAppManager explicitly configured with TaskScheduler.");

        // 2. LLM Router
        LlmRouter llmRouter = new LlmRouter();
        com.ouisani.aios.core.llm.LlmRouterHolder.set(llmRouter); // 全局持有，供 OperatorAgent 等组件访问
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

        // 2.1 Multimodal Provider — mimo-v2.5 等多模态模型（Computer Use 视觉理解）
        String mmApiKey = env.getOrDefault("MULTIMODAL_API_KEY", System.getenv().getOrDefault("MULTIMODAL_API_KEY", ""));
        String mmBaseUrl = env.getOrDefault("MULTIMODAL_BASE_URL", System.getenv().getOrDefault("MULTIMODAL_BASE_URL", ""));
        String mmModel = env.getOrDefault("MULTIMODAL_MODEL", System.getenv().getOrDefault("MULTIMODAL_MODEL", ""));

        if (!mmApiKey.isEmpty() && !mmBaseUrl.isEmpty() && !mmModel.isEmpty()) {
            OpenAiAdapter multimodalAdapter = new OpenAiAdapter(mmApiKey, mmBaseUrl, mmModel);
            llmRouter.registerProvider("multimodal", multimodalAdapter);
            System.out.printf("  ✓ Multimodal: %s @ %s (for Computer Use vision)%n", mmModel, mmBaseUrl);
        } else {
            System.out.println("  ⚠ No MULTIMODAL_* config — Computer Use vision disabled (screenshot without understanding)");
        }

        // 2.5 WebSearchTool — 注入 Serper API Key
        String serperKey = env.getOrDefault("SERPER_API_KEY", System.getenv().getOrDefault("SERPER_API_KEY", ""));
        if (!serperKey.isBlank()) {
            com.ouisani.aios.core.plugin.WebSearchTool.configureSerperApiKey(serperKey);
            System.out.println("  ✓ WebSearch: Serper API configured");
        } else {
            System.out.println("  ⚠ No SERPER_API_KEY — web search will try Jina (may timeout in China)");
        }

        // 3. VfsManager
        VfsManager.instance().configureTaskScheduler(scheduler);
        VfsManager.instance().init();
        System.out.println("  ✓ VfsManager initialized");

        // 4. CgroupManager
        CgroupManager.instance().init();
        System.out.println("  ✓ CgroupManager initialized");

        // 5. SyscallDispatcher + IntentRouter
        if (!apiKey.isEmpty()) {
            SyscallDispatcher.getInstance().configure(llmRouter, VfsManager.instance(), ObjectManager.instance());
            IntentRouter.getInstance().configure(llmRouter, SyscallDispatcher.getInstance());
            System.out.println("  ✓ SyscallDispatcher + IntentRouter configured");
        }

        // 6. SyscallServer (HTTP/WebSocket 网关 — 前端连接端口 8080)
        int httpPort = Integer.parseInt(env.getOrDefault("AIOS_HTTP_PORT", "8080"));
        McpServer mcpServer = new McpServer();
        SyscallServer syscallServer = new SyscallServer(scheduler, mcpServer);
        syscallServer.start(httpPort);
        System.out.printf("  ✓ SyscallServer started on port %d (HTTP + WebSocket + SSE)%n", httpPort);

        // 7. SystemMonitorDaemon (遥测心跳 — 每秒采集系统指标)
        com.ouisani.aios.core.telemetry.SystemMonitorDaemon.getInstance().configure(scheduler);
        com.ouisani.aios.core.telemetry.SystemMonitorDaemon.getInstance().start();
        System.out.println("  ✓ SystemMonitorDaemon started (1s telemetry interval)");

        System.out.println();
        System.out.println("Welcome to AIOS. Type your intent naturally, or use '/' for raw syscalls.");
        System.out.println("Type 'exit' to halt the system.\n");

        // ── REPL ──
        Scanner scanner = new Scanner(System.in);
        IntentRouter router = IntentRouter.getInstance();
        SyscallDispatcher dispatcher = SyscallDispatcher.getInstance();

        while (true) {
            System.out.print(ANSI_YELLOW + "aios> " + ANSI_RESET);
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit")) {
                System.out.println("Initiating system halt... Goodbye.");
                SemanticEtw.getInstance().logEvent("AiosShell", "SYSTEM_HALT", "User exited shell");
                break;
            }

            if (input.isEmpty()) continue;

            try {
                if (input.startsWith("/")) {
                    String[] parts = input.substring(1).split(" ", 2);
                    String action = parts[0];
                    SyscallRequest request;
                    if (parts.length > 1) {
                        String[] paramPairs = parts[1].split("=", 2);
                        if (paramPairs.length == 2) {
                            request = new SyscallRequest(action, Map.of(paramPairs[0], paramPairs[1]));
                        } else {
                            request = new SyscallRequest(action, Map.of("payload", parts[1]));
                        }
                    } else {
                        request = new SyscallRequest(action, Map.of());
                    }
                    System.out.println(ANSI_CYAN + ">> Syscall: " + action + ANSI_RESET);
                    SyscallResponse response = dispatcher.execute("root_cli", request);
                    if (response.success()) {
                        System.out.println(ANSI_GREEN + "Response: " + response.data() + ANSI_RESET);
                    } else {
                        System.out.println(ANSI_RED + "Error: " + response.errorMessage() + ANSI_RESET);
                    }
                } else {
                    System.out.println(ANSI_CYAN + ">> Semantic routing via LLM..." + ANSI_RESET);
                    IntentRouter.RouteResult result = router.route(input);
                    switch (result.intentType()) {
                        case SYSTEM_COMMAND -> System.out.println(ANSI_CYAN + "  [SYSTEM_COMMAND] " + ANSI_RESET + result.response());
                        case WORKFLOW_DEPLOY -> {
                            System.out.println(ANSI_CYAN + "  [WORKFLOW_DEPLOY] " + ANSI_RESET + result.response());
                            System.out.println(ANSI_GREEN + ">> Mother Agent dispatched." + ANSI_RESET);
                        }
                        case SEMANTIC_SEARCH -> System.out.println(ANSI_CYAN + "  [SEMANTIC_SEARCH] " + ANSI_RESET + result.response());
                        case CHAT -> System.out.println(ANSI_GREEN + result.response() + ANSI_RESET);
                    }
                }
            } catch (Exception e) {
                System.out.println(ANSI_RED + "[Kernel Panic] " + e.getMessage() + ANSI_RESET);
            }
        }
        scanner.close();
    }

    private static Map<String, String> loadDotEnv(Path dotEnvPath) {
        Map<String, String> env = new HashMap<>();
        if (!Files.exists(dotEnvPath)) return env;
        try (BufferedReader reader = Files.newBufferedReader(dotEnvPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (!key.isEmpty()) env.put(key, value);
                }
            }
        } catch (IOException e) {
            System.out.println("  ⚠ Failed to read .env: " + e.getMessage());
        }
        return env;
    }
}
