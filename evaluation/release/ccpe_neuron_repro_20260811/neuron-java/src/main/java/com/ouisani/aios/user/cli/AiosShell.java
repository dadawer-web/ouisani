package com.ouisani.aios.user.cli;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.mcp.McpServer;
import com.ouisani.aios.core.network.SyscallServer;
import com.ouisani.aios.drivers.llm.EmbeddingRoutingProvider;
import com.ouisani.aios.drivers.llm.LocalOnnxEmbedder;
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
        log.info("[AiosShell] 正在启动交互式 Shell Agent...");

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
        System.out.println("  │  AIOS Shell 就绪                                        │");
        System.out.println("  │  自然语言输入意图，或使用 '/' 执行原生 Syscall            │");
        System.out.println("  │  输入 'exit' 停止系统                                    │");
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
                    System.out.println(ANSI_RED + "正在执行系统停机... 再见。" + ANSI_RESET);
                    SemanticEtw.getInstance().logEvent("AiosShell", "SYSTEM_HALT", "用户退出 Shell");
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
                    System.out.println(ANSI_RED + "[错误] " + e.getMessage() + ANSI_RESET);
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
        System.out.println(ANSI_CYAN + ">> 通过 LLM 语义路由..." + ANSI_RESET);

        try {
            IntentRouter.RouteResult result = router.route(input);

            switch (result.intentType()) {
                case SYSTEM_COMMAND -> {
                    System.out.println(ANSI_CYAN + "  [SYSTEM_COMMAND] " + ANSI_RESET + result.response());
                }
                case WORKFLOW_DEPLOY -> {
                    System.out.println(ANSI_CYAN + "  [WORKFLOW_DEPLOY] " + ANSI_RESET + result.response());
                    System.out.println(ANSI_GREEN + ">> Mother Agent 已在后台调度。请查看 TaskScheduler 日志了解进度。" + ANSI_RESET);
                }
                case SEMANTIC_SEARCH -> {
                    System.out.println(ANSI_CYAN + "  [SEMANTIC_SEARCH] " + ANSI_RESET + result.response());
                }
                case CHAT -> {
                    System.out.println(ANSI_GREEN + result.response() + ANSI_RESET);
                }
            }
        } catch (Exception e) {
            System.out.println(ANSI_RED + "[错误] 语义路由失败：" + e.getMessage() + ANSI_RESET);
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

        System.out.println(ANSI_CYAN + ">> Syscall：" + action + ANSI_RESET);
        SyscallResponse response = dispatcher.execute("aios_shell", request);

        if (response.success()) {
            System.out.println(ANSI_GREEN + response.data() + ANSI_RESET);
        } else {
            System.out.println(ANSI_RED + "错误：" + response.errorMessage() + ANSI_RESET);
        }
    }

    @Override
    protected void onMessage(String msg) {
        // 外部消息直接输出到终端
        System.out.println();
        System.out.println(ANSI_CYAN + "[消息] " + msg + ANSI_RESET);
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
        Map<String, String> env = loadDotEnv(Path.of(com.ouisani.aios.core.config.AiosPaths.envFile()));

        // 1. TaskScheduler
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        com.ouisani.aios.user.bin.AiosAppManager.configure(scheduler);
        System.out.println("  ✓ TaskScheduler 已启动");
        System.out.println("  ✓ [InitDaemon] AiosAppManager 已显式配置 TaskScheduler。");

        // 2. LLM Router
        LlmRouter llmRouter = new LlmRouter();
        com.ouisani.aios.core.llm.LlmRouterHolder.set(llmRouter); // 全局持有，供 OperatorAgent 等组件访问
        String apiKey = env.getOrDefault("OPENAI_API_KEY", System.getenv().getOrDefault("OPENAI_API_KEY", ""));
        String baseUrl = env.getOrDefault("OPENAI_BASE_URL", System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com"));
        String model = env.getOrDefault("OPENAI_MODEL", System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"));

        if (!apiKey.isEmpty()) {
            // 【安全修复】API Key 注册到 SecretVault，Agent 只拿句柄
            com.ouisani.aios.core.security.SecretVault.instance().registerSecret("llm", "openai", apiKey);
            OpenAiAdapter adapter = new OpenAiAdapter(apiKey, baseUrl, model);
            // Embedding 路由：优先本地 ONNX（零外部 API），不可用则回退 adapter
            LocalOnnxEmbedder localEmbedder = new LocalOnnxEmbedder();
            LlmProvider embedProvider = adapter;
            if (localEmbedder.isAvailable()) {
                embedProvider = localEmbedder;
                System.out.println("  ✓ 本地 ONNX Embedding：all-MiniLM-L6-v2（384 维，离线）");
            } else {
                System.out.println("  ⚠ 本地 ONNX 模型未就绪，embedding 回退 OpenAI（EMBEDDING_API_KEY）");
            }
            EmbeddingRoutingProvider composite = new EmbeddingRoutingProvider(adapter, embedProvider);
            llmRouter.registerProvider("fast_model", composite);
            llmRouter.registerProvider("smart_model", composite);
            VfsManager.instance().configureLlmProvider(composite);
            System.out.printf("  ✓ LLM：%s @ %s（密钥 → 保管库句柄：%s）%n", model, baseUrl,
                    com.ouisani.aios.core.security.SecretVault.instance().getHandle("llm", "openai"));
        } else {
            System.out.println("  ⚠ 未设置 OPENAI_API_KEY — LLM 不可用");
        }

        // 2.1 Multimodal Provider — mimo-v2.5 等多模态模型（Computer Use 视觉理解）
        String mmApiKey = env.getOrDefault("MULTIMODAL_API_KEY", System.getenv().getOrDefault("MULTIMODAL_API_KEY", ""));
        String mmBaseUrl = env.getOrDefault("MULTIMODAL_BASE_URL", System.getenv().getOrDefault("MULTIMODAL_BASE_URL", ""));
        String mmModel = env.getOrDefault("MULTIMODAL_MODEL", System.getenv().getOrDefault("MULTIMODAL_MODEL", ""));

        if (!mmApiKey.isEmpty() && !mmBaseUrl.isEmpty() && !mmModel.isEmpty()) {
            // 【安全修复】多模态 Key 注册到 SecretVault
            com.ouisani.aios.core.security.SecretVault.instance().registerSecret("llm", "multimodal", mmApiKey);
            OpenAiAdapter multimodalAdapter = new OpenAiAdapter(mmApiKey, mmBaseUrl, mmModel);
            llmRouter.registerProvider("multimodal", multimodalAdapter);
            System.out.printf("  ✓ 多模态：%s @ %s（密钥 → 保管库句柄：%s）%n", mmModel, mmBaseUrl,
                    com.ouisani.aios.core.security.SecretVault.instance().getHandle("llm", "multimodal"));
        } else {
            System.out.println("  ⚠ 未配置 MULTIMODAL_* — Computer Use 视觉功能已禁用（截图无法理解）");
        }

        // 2.5 WebSearchTool — 注入 Serper API Key
        String serperKey = env.getOrDefault("SERPER_API_KEY", System.getenv().getOrDefault("SERPER_API_KEY", ""));
        if (!serperKey.isBlank()) {
            // 【安全修复】Serper Key 注册到 SecretVault
            com.ouisani.aios.core.security.SecretVault.instance().registerSecret("search", "serper", serperKey);
            com.ouisani.aios.core.plugin.WebSearchTool.configureSerperApiKey(serperKey);
            System.out.println("  ✓ WebSearch：Serper API 已配置（密钥 → 保管库句柄："
                    + com.ouisani.aios.core.security.SecretVault.instance().getHandle("search", "serper") + ")");
        } else {
            System.out.println("  ⚠ 未设置 SERPER_API_KEY — 网页搜索将尝试 Jina（在中国可能超时）");
        }

        // 3. VfsManager
        VfsManager.instance().configureTaskScheduler(scheduler);
        VfsManager.instance().init();

        // 注册 /factory/ 全局默认物理工作目录 — 仅作为兜底映射
        // 实际工作流执行时会按 workflowId 注册专属映射，优先级高于此全局映射
        String factoryPhysicalDir = com.ouisani.aios.core.config.AiosPaths.aiosHome() + "/workspaces/_default/factory";
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(factoryPhysicalDir));
        } catch (java.io.IOException e) {
            System.err.println("  ⚠ 无法创建默认工厂目录: " + e.getMessage());
        }
        VfsManager.instance().registerPhysicalWorkspace("/factory", factoryPhysicalDir);

        System.out.println("  ✓ VfsManager 已初始化（/factory 默认映射 → " + factoryPhysicalDir + "）");

        // 4. CgroupManager
        CgroupManager.instance().init();
        System.out.println("  ✓ CgroupManager 已初始化");

        // 5. SyscallDispatcher + IntentRouter
        if (!apiKey.isEmpty()) {
            SyscallDispatcher.getInstance().configure(llmRouter, VfsManager.instance(), ObjectManager.instance());
            IntentRouter.getInstance().configure(llmRouter, SyscallDispatcher.getInstance());
            System.out.println("  ✓ SyscallDispatcher + IntentRouter 已配置");
        }

        // 6. SyscallServer (HTTP/WebSocket 网关 — 前端连接端口 8080)
        int httpPort = Integer.parseInt(env.getOrDefault("AIOS_HTTP_PORT", "8080"));
        McpServer mcpServer = new McpServer();
        SyscallServer syscallServer = new SyscallServer(scheduler, mcpServer);
        syscallServer.start(httpPort);
        System.out.printf("  ✓ SyscallServer 已在端口 %d 启动（HTTP + WebSocket + SSE）%n", httpPort);

        // 7. SystemMonitorDaemon (遥测心跳 — 每秒采集系统指标)
        com.ouisani.aios.core.telemetry.SystemMonitorDaemon.getInstance().configure(scheduler);
        com.ouisani.aios.core.telemetry.SystemMonitorDaemon.getInstance().start();
        System.out.println("  ✓ SystemMonitorDaemon 已启动（1秒遥测间隔）");

        // 8. HeartbeatScheduler (心跳调度器 — Agent 按需唤醒)
        com.ouisani.aios.core.lifecycle.HeartbeatScheduler.instance().start();
        System.out.println("  ✓ HeartbeatScheduler 已启动（由 SystemTick 驱动）");

        // 9. ScienceMcpBootstrap (科研 MCP 网关 — paper-search: arXiv/PubMed/Crossref/Semantic Scholar 等 20+ 学术数据源)
        com.ouisani.aios.user.init.ScienceMcpBootstrap.registerDefaults();

        System.out.println();
        System.out.println("欢迎使用 AIOS。自然语言输入意图，或使用 '/' 执行原生 Syscall。");
        System.out.println("输入 'exit' 停止系统。\n");

        // ── REPL ──
        Scanner scanner = new Scanner(System.in);
        IntentRouter router = IntentRouter.getInstance();
        SyscallDispatcher dispatcher = SyscallDispatcher.getInstance();

        while (true) {
            System.out.print(ANSI_YELLOW + "aios> " + ANSI_RESET);
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit")) {
                System.out.println("正在执行系统停机... 再见。");
                SemanticEtw.getInstance().logEvent("AiosShell", "SYSTEM_HALT", "用户退出 Shell");
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
                    System.out.println(ANSI_CYAN + ">> Syscall：" + action + ANSI_RESET);
                    SyscallResponse response = dispatcher.execute("root_cli", request);
                    if (response.success()) {
                        System.out.println(ANSI_GREEN + "响应：" + response.data() + ANSI_RESET);
                    } else {
                        System.out.println(ANSI_RED + "错误：" + response.errorMessage() + ANSI_RESET);
                    }
                } else {
                    System.out.println(ANSI_CYAN + ">> 通过 LLM 语义路由..." + ANSI_RESET);
                    IntentRouter.RouteResult result = router.route(input);
                    switch (result.intentType()) {
                        case SYSTEM_COMMAND -> System.out.println(ANSI_CYAN + "  [SYSTEM_COMMAND] " + ANSI_RESET + result.response());
                        case WORKFLOW_DEPLOY -> {
                            System.out.println(ANSI_CYAN + "  [WORKFLOW_DEPLOY] " + ANSI_RESET + result.response());
                            System.out.println(ANSI_GREEN + ">> Mother Agent 已调度。" + ANSI_RESET);
                        }
                        case SEMANTIC_SEARCH -> System.out.println(ANSI_CYAN + "  [SEMANTIC_SEARCH] " + ANSI_RESET + result.response());
                        case CHAT -> System.out.println(ANSI_GREEN + result.response() + ANSI_RESET);
                    }
                }
            } catch (Exception e) {
                System.out.println(ANSI_RED + "[内核异常] " + e.getMessage() + ANSI_RESET);
            }
        }
        scanner.close();
    }

    private static Map<String, String> loadDotEnv(Path dotEnvPath) {
        Map<String, String> env = new HashMap<>();

        // 优先加载项目根目录上层的 .env（/home/xmy/tryaios/.env）
        Path parentEnv = Path.of(System.getProperty("user.dir")).getParent().resolve(".env");
        if (Files.exists(parentEnv)) {
            loadEnvFile(parentEnv, env);
            System.out.println("  ✓ 已加载 .env：" + parentEnv);
        }

        // 再加载 aiosHome/.env（会覆盖上层同 key）
        if (Files.exists(dotEnvPath)) {
            loadEnvFile(dotEnvPath, env);
        }

        return env;
    }

    private static void loadEnvFile(Path path, Map<String, String> env) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
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
            System.out.println("  ⚠ 读取 .env 失败：" + e.getMessage());
        }
    }
}
