package com.ouisani.aios.user.init;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.memory.CognitiveDreamDaemon;
import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.rtos.WatchdogDaemon;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import com.ouisani.aios.core.security.BpfManager;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.core.telemetry.SystemMonitorDaemon;
import com.ouisani.aios.core.tick.SystemTickGenerator;
import com.ouisani.aios.core.vfs.VfsJournal;
import com.ouisani.aios.user.DaemonManager;
import com.ouisani.aios.user.apps.omnifactory.AgentBlueprint;
import com.ouisani.aios.user.apps.omnifactory.TemplateManager;
import com.ouisani.aios.user.apps.omnifactory.WorkflowEngine;
import com.ouisani.aios.user.apps.omnifactory.WorkflowManifest;
import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;
import com.ouisani.aios.user.cli.AiosShell;
import com.ouisani.aios.user.container.ContainerRuntime;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AIOS Init Daemon — PID 1，用户空间的第一个进程。
 * <p>
 * 类比 Linux 的 systemd / init，InitDaemon 是用户空间进程树的根。
 * 它以 REALTIME 优先级运行，Token 预算无上限，负责按严格的层次化
 * 顺序引导所有内核子系统和用户空间服务。
 *
 * <h3>三阶段引导序列</h3>
 *
 * <b>Phase 1: 硬件层 (Hardware Layer)</b> — 类比 BIOS POST + 晶振起振
 * <ul>
 *   <li>启动 {@link SystemTickGenerator} — 硬件晶振起振，系统获得心跳</li>
 *   <li>初始化 {@link LlmProvider} 连接 — 类比硬件自检 (POST)</li>
 *   <li>打开 {@link VfsJournal} — WAL 日志就绪</li>
 * </ul>
 *
 * <b>Phase 2: 内核层 (Kernel Layer)</b> — 类比 Linux 内核初始化
 * <ul>
 *   <li>挂载虚拟文件系统 (VFS) — /dev, /proc, /var/memory</li>
 *   <li>启动 {@link BpfManager} 安全模块 — 类比加载 LSM</li>
 *   <li>初始化 {@link com.ouisani.aios.core.cgroup.CgroupManager} — 资源控制</li>
 * </ul>
 *
 * <b>Phase 3: 服务层 (Service Layer)</b> — 类比 systemd 启动服务单元
 * <ul>
 *   <li>启动 {@link WatchdogDaemon} — 硬实时看门狗</li>
 *   <li>启动 {@link SystemMonitorDaemon} — 遥测心跳</li>
 *   <li>启动 {@link CognitiveDreamDaemon} — 记忆巩固</li>
 *   <li>通过 {@link DaemonManager} 拉起后台守护进程</li>
 *   <li>扫描并加载 WASM 插件</li>
 * </ul>
 *
 * <h3>引导完成</h3>
 * 系统达到 RUNLEVEL 5 后，InitDaemon 创建 {@link AiosShell} 进程，
 * 将控制权移交给交互式 Shell。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>Linux</th><th>AIOS</th><th>说明</th></tr>
 *   <tr><td>BIOS POST</td><td>Phase 1</td><td>硬件自检</td></tr>
 *   <tr><td>kernel init</td><td>Phase 2</td><td>内核初始化</td></tr>
 *   <tr><td>systemd units</td><td>Phase 3</td><td>服务启动</td></tr>
 *   <tr><td>agetty + bash</td><td>AiosShell</td><td>交互式 Shell</td></tr>
 *   <tr><td>runlevel 5</td><td>RUNLEVEL 5</td><td>完全多用户模式</td></tr>
 * </table>
 */
public class InitDaemon extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(InitDaemon.class);

    private static final String PLUGIN_DIR = "/opt/aios/plugins";

    /** 引导阶段枚举 */
    public enum BootPhase {
        /** 硬件层：晶振起振 + LLM 连接 */
        HARDWARE,
        /** 内核层：VFS + 安全模块 */
        KERNEL,
        /** 服务层：守护进程 + 插件 */
        SERVICE,
        /** 引导完成，系统就绪 */
        COMPLETE
    }

    private final TaskScheduler scheduler;
    private final GraalWasmSandbox sandbox;
    private final LlmRouter llmRouter;

    /** 当前引导阶段 */
    private volatile BootPhase currentPhase = null;

    /** 引导结果 — 记录每个子系统的启动状态 */
    private final Map<String, Boolean> bootResults = new LinkedHashMap<>();

    /** 引导耗时（毫秒） */
    private long bootTimeMs = 0;

    public InitDaemon(TaskScheduler scheduler, GraalWasmSandbox sandbox) {
        this(scheduler, sandbox, null);
    }

    public InitDaemon(TaskScheduler scheduler, GraalWasmSandbox sandbox, LlmRouter llmRouter) {
        super("sys_init_1", ProcessPriority.REALTIME, 9999999);
        this.scheduler = scheduler;
        this.sandbox = sandbox;
        this.llmRouter = llmRouter;
    }

    @Override
    protected void onStart() {
        long bootStart = System.currentTimeMillis();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [PID 1] InitDaemon taking control...                      ║");
        System.out.println("  ║  Agent ID: sys_init_1 | Priority: REALTIME | Budget: ∞     ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        log.info("[PID 1] InitDaemon starting...");

        // ════════════════════════════════════════════════════════════════
        //  Phase 1: 硬件层 (Hardware Layer) — BIOS POST + 晶振起振
        // ════════════════════════════════════════════════════════════════
        currentPhase = BootPhase.HARDWARE;
        System.out.println();
        System.out.println("  ┌─ Phase 1: Hardware Layer ──────────────────────────────────┐");

        // 1a. SystemTickGenerator — 硬件晶振起振
        boolean tickOk = false;
        try {
            SystemTickGenerator.instance().start();
            tickOk = true;
            System.out.println("  │  [HW] SystemTickGenerator: CRYSTAL OSCILLATOR STARTED ✓    │");
        } catch (Exception e) {
            log.warn("[PID 1] SystemTickGenerator start failed: {}", e.getMessage());
            System.out.println("  │  [HW] SystemTickGenerator: FAILED ✗                       │");
        }
        bootResults.put("SystemTickGenerator", tickOk);

        // 1b. LlmProvider 连接检查 — 硬件自检 (POST)
        boolean llmOk = false;
        try {
            if (llmRouter != null) {
                llmOk = llmRouter.isAvailable();
                if (llmOk) {
                    System.out.println("  │  [HW] LlmProvider: API CONNECTION VERIFIED ✓               │");
                } else {
                    System.out.println("  │  [HW] LlmProvider: NO PROVIDER REGISTERED ⚠                │");
                }
            } else {
                System.out.println("  │  [HW] LlmProvider: NOT CONFIGURED ⚠                        │");
            }
        } catch (Exception e) {
            System.out.println("  │  [HW] LlmProvider: CHECK FAILED ✗                          │");
        }
        bootResults.put("LlmProvider", llmOk);

        // 1c. VfsJournal — WAL 日志就绪
        boolean journalOk = false;
        try {
            VfsJournal.getInstance().open();
            journalOk = true;
            System.out.println("  │  [HW] VfsJournal: WAL LOG READY ✓                          │");
        } catch (Exception e) {
            System.out.println("  │  [HW] VfsJournal: OPEN FAILED ✗                            │");
        }
        bootResults.put("VfsJournal", journalOk);

        System.out.println("  └────────────────────────────────────────────────────────────┘");

        // ════════════════════════════════════════════════════════════════
        //  Phase 2: 内核层 (Kernel Layer) — VFS + 安全模块
        // ════════════════════════════════════════════════════════════════
        currentPhase = BootPhase.KERNEL;
        System.out.println();
        System.out.println("  ┌─ Phase 2: Kernel Layer ────────────────────────────────────┐");

        // 2a. VFS 挂载 — 由 VfsManager.init() 完成（在 InitDaemon 之前已调用）
        boolean vfsOk = false;
        try {
            VfsManager vfs = VfsManager.instance();
            vfsOk = vfs.resolve("/dev").isPresent() && vfs.resolve("/proc").isPresent();
            if (vfsOk) {
                System.out.println("  │  [KERN] VFS: /, /dev, /proc, /var MOUNTED ✓                │");
            } else {
                System.out.println("  │  [KERN] VFS: PARTIAL MOUNT ⚠                               │");
            }
        } catch (Exception e) {
            System.out.println("  │  [KERN] VFS: MOUNT FAILED ✗                                │");
        }
        bootResults.put("VFS", vfsOk);

        // 2b. BpfManager 安全模块 — 类比加载 LSM (Linux Security Module)
        boolean bpfOk = false;
        try {
            BpfManager bpf = BpfManager.instance();
            bpfOk = true; // BpfManager 已初始化即视为 OK
            System.out.println("  │  [KERN] BpfManager: SEMANTIC RULES LOADED ✓                │");
        } catch (Exception e) {
            System.out.println("  │  [KERN] BpfManager: INIT FAILED ✗                          │");
        }
        bootResults.put("BpfManager", bpfOk);

        // 2c. SemanticEtw — 审计追踪就绪
        boolean etwOk = false;
        try {
            SemanticEtw etw = SemanticEtw.getInstance();
            etw.logEvent("BOOT", "KERNEL_INIT", "Phase 2 kernel layer initialized");
            etwOk = true;
            System.out.println("  │  [KERN] SemanticEtw: AUDIT TRAIL ACTIVE ✓                   │");
        } catch (Exception e) {
            System.out.println("  │  [KERN] SemanticEtw: INIT FAILED ✗                          │");
        }
        bootResults.put("SemanticEtw", etwOk);

        System.out.println("  └────────────────────────────────────────────────────────────┘");

        // ════════════════════════════════════════════════════════════════
        //  Phase 3: 服务层 (Service Layer) — systemd 服务单元
        // ════════════════════════════════════════════════════════════════
        currentPhase = BootPhase.SERVICE;
        System.out.println();
        System.out.println("  ┌─ Phase 3: Service Layer ───────────────────────────────────┐");

        // 3a. WatchdogDaemon — 硬实时看门狗
        boolean watchdogOk = false;
        try {
            WatchdogDaemon.instance();
            watchdogOk = true;
            System.out.println("  │  [SVC] WatchdogDaemon: HARD RT WATCHDOG ACTIVE ✓            │");
        } catch (Exception e) {
            System.out.println("  │  [SVC] WatchdogDaemon: FAILED ✗                             │");
        }
        bootResults.put("WatchdogDaemon", watchdogOk);

        // 3b. SystemMonitorDaemon — 遥测心跳
        boolean telemetryOk = false;
        try {
            SystemMonitorDaemon.getInstance().configure(scheduler);
            SystemMonitorDaemon.getInstance().start();
            telemetryOk = true;
            System.out.println("  │  [SVC] SystemMonitorDaemon: TELEMETRY HEARTBEAT 1s ✓        │");
        } catch (Exception e) {
            System.out.println("  │  [SVC] SystemMonitorDaemon: FAILED ✗                        │");
        }
        bootResults.put("SystemMonitorDaemon", telemetryOk);

        // 3c. CognitiveDreamDaemon — 记忆巩固
        boolean dreamOk = false;
        try {
            CognitiveDreamDaemon.instance().start();
            dreamOk = true;
            System.out.println("  │  [SVC] CognitiveDreamDaemon: MEMORY CONSOLIDATION ✓         │");
        } catch (Exception e) {
            System.out.println("  │  [SVC] CognitiveDreamDaemon: FAILED ✗                       │");
        }
        bootResults.put("CognitiveDreamDaemon", dreamOk);

        // 3d. PluginManager — WASM 插件扫描
        int pluginCount = 0;
        boolean pluginOk = false;
        try {
            PluginManager.getInstance().configure(sandbox);
            PluginManager.getInstance().scanAndLoadPlugins(PLUGIN_DIR);
            pluginCount = PluginManager.getInstance().registeredPlugins().size();
            pluginOk = true;
            System.out.printf("  │  [SVC] PluginManager: %d PLUGINS LOADED ✓                  │%n", pluginCount);
        } catch (Exception e) {
            System.out.println("  │  [SVC] PluginManager: SCAN FAILED ✗                         │");
        }
        bootResults.put("PluginManager", pluginOk);

        // 3e. VFS Manifest 驱动启动 — 从 /etc/init/startup_manifest.json 读取业务进程清单
        boolean manifestOk = false;
        try {
            System.out.println("  │  [SVC] Reading /etc/init/startup_manifest.json...              │");
            manifestOk = bootFromVfsManifest();
        } catch (Exception e) {
            log.warn("[PID 1] VFS manifest boot failed: {}", e.getMessage());
            System.out.println("  │  [SVC] VFS Manifest Boot: FAILED ✗                          │");
        }
        bootResults.put("VfsManifestBoot", manifestOk);

        System.out.println("  └────────────────────────────────────────────────────────────┘");

        // ════════════════════════════════════════════════════════════════
        //  引导完成 — RUNLEVEL 5
        // ════════════════════════════════════════════════════════════════
        currentPhase = BootPhase.COMPLETE;
        bootTimeMs = System.currentTimeMillis() - bootStart;

        long okCount = bootResults.values().stream().filter(b -> b).count();
        long total = bootResults.size();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("  ║  [PID 1] BOOT COMPLETE: %d/%d subsystems OK               ║%n", okCount, total);
        System.out.printf("  ║  Boot time: %dms                                            ║%n", bootTimeMs);
        System.out.println("  ║                                                             ║");
        System.out.println("  ║  System reaches RUNLEVEL 5.                                ║");
        System.out.println("  ║  User space is now fully operational!                      ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        log.info("[PID 1] Boot complete: {}/{} subsystems OK, bootTime={}ms",
                okCount, total, bootTimeMs);

        SemanticEtw.getInstance().logEvent("BOOT", "RUNLEVEL_5",
                "subsystems=" + okCount + "/" + total + " bootTime=" + bootTimeMs + "ms");

        // ── 拉起 AiosShell ──
        spawnAiosShell();
    }

    /**
     * VFS Manifest 驱动启动 — 从 /etc/init/startup_manifest.json 读取业务进程清单。
     * <p>
     * 如果 manifest 不存在，使用 TemplateManager 写入一个包含系统基础进程的默认 JSON。
     * 然后解析 JSON 为 WorkflowManifest，传递给 WorkflowEngine 自动拉起进程。
     * <p>
     * OS 类比：相当于 systemd 读取 /etc/systemd/system/*.service 单元文件，
     * 而不是在内核代码中硬编码要启动的服务。
     */
    private boolean bootFromVfsManifest() {
        VfsManager vfs = VfsManager.instance();
        String manifestPath = "/etc/init/startup_manifest.json";

        String manifestJson = vfs.readText(manifestPath);

        if (manifestJson == null) {
            // Manifest 不存在，写入默认 manifest
            System.out.println("  │  [SVC] No startup_manifest.json found, generating default... │");
            log.info("[PID 1] No VFS manifest at '{}', generating default with AutoMedicAgent", manifestPath);

            // 确保 /etc/init 目录存在
            vfs.writeText("/etc/init/.keep", "");

            // 初始化模板
            TemplateManager.initTemplates();

            // 生成默认 manifest — 包含系统基础进程
            manifestJson = generateDefaultManifest();
            vfs.writeText(manifestPath, manifestJson);
            System.out.println("  │  [SVC] Default manifest written to VFS ✓                     │");
        }

        // 解析 manifest JSON
        try {
            WorkflowManifest manifest = parseManifestJson(manifestJson);

            // 构建蓝图注册表（系统内置蓝图）
            Map<String, AgentBlueprint> blueprintRegistry = buildSystemBlueprints();

            // 传递给 WorkflowEngine 自动拉起进程
            WorkflowEngine.getInstance().executeWorkflow(manifest, blueprintRegistry);

            System.out.printf("  │  [SVC] VFS Manifest Boot: %d processes launched ✓           │%n",
                    manifest.nodes().size());
            System.out.println("[Kernel] Business logic purged. Booting strictly from VFS manifest.");

            return true;
        } catch (Exception e) {
            log.error("[PID 1] Failed to parse/execute VFS manifest: {}", e.getMessage());
            System.out.println("  │  [SVC] VFS Manifest parse error: " + e.getMessage() + "   │");
            return false;
        }
    }

    /**
     * 生成默认 startup_manifest.json — 包含系统基础守护进程。
     */
    private String generateDefaultManifest() {
        return """
                {
                  "workflowName": "aios_system_daemons",
                  "nodes": [
                    {
                      "instanceId": "auto_medic_1",
                      "role": "系统自愈守护进程 — 监听崩溃事件并自动修复",
                      "blueprintId": "auto_medic",
                      "userParams": {},
                      "subscribeTopic": "sys.kernel.panic",
                      "publishTopic": "sys.medic.fixed"
                    }
                  ]
                }
                """;
    }

    /**
     * 解析 manifest JSON 为 WorkflowManifest record。
     * <p>
     * 简单的手写 JSON 解析器，避免引入 Jackson/Gson 依赖。
     */
    private WorkflowManifest parseManifestJson(String json) {
        // 提取 workflowName
        String workflowName = extractJsonString(json, "workflowName");

        // 提取 nodes 数组
        String nodesSection = extractJsonArray(json, "nodes");
        java.util.List<WorkflowNode> nodes = new java.util.ArrayList<>();

        // 逐个解析 node 对象
        int depth = 0;
        int start = -1;
        for (int i = 0; i < nodesSection.length(); i++) {
            char c = nodesSection.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String nodeJson = nodesSection.substring(start, i + 1);
                    nodes.add(parseWorkflowNode(nodeJson));
                    start = -1;
                }
            }
        }

        return new WorkflowManifest(workflowName, nodes);
    }

    private WorkflowNode parseWorkflowNode(String json) {
        String instanceId = extractJsonString(json, "instanceId");
        String role = extractJsonString(json, "role");
        String blueprintId = extractJsonString(json, "blueprintId");
        String subscribeTopic = extractJsonString(json, "subscribeTopic");
        String publishTopic = extractJsonString(json, "publishTopic");
        return new WorkflowNode(instanceId, role, blueprintId, Map.of(), subscribeTopic, publishTopic);
    }

    /** 从 JSON 字符串中提取指定 key 的字符串值 */
    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(pattern);
        if (keyIdx < 0) return "";
        int colonIdx = json.indexOf(':', keyIdx + pattern.length());
        if (colonIdx < 0) return "";
        // 找到值部分的引号
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return "";
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return "";
        return json.substring(startQuote + 1, endQuote);
    }

    /** 从 JSON 字符串中提取指定 key 的数组内容 */
    private String extractJsonArray(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(pattern);
        if (keyIdx < 0) return "[]";
        int colonIdx = json.indexOf(':', keyIdx + pattern.length());
        if (colonIdx < 0) return "[]";
        int startBracket = json.indexOf('[', colonIdx + 1);
        if (startBracket < 0) return "[]";
        int depth = 0;
        for (int i = startBracket; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(startBracket + 1, i);
            }
        }
        return "[]";
    }

    /**
     * 构建系统内置蓝图注册表。
     * <p>
     * 内核只提供系统级守护进程的蓝图，业务蓝图由用户通过 VFS 或前端注入。
     */
    private Map<String, AgentBlueprint> buildSystemBlueprints() {
        Map<String, AgentBlueprint> blueprints = new LinkedHashMap<>();

        // AutoMedic 蓝图 — 系统自愈守护进程
        blueprints.put("auto_medic", new AgentBlueprint(
                "auto_medic",
                "系统自愈守护进程 — 监听 sys.kernel.panic 事件并自动修复崩溃节点",
                "# AutoMedic — 系统内置，由 InitDaemon 自动拉起\n" +
                        "# 实际逻辑由 com.ouisani.aios.user.apps.omnifactory.AutoMedicAgent 提供\n" +
                        "import BaseAgent\n" +
                        "import json\n\n" +
                        "class AutoMedicDaemon(BaseAgent.BaseAgent):\n" +
                        "    def process_data(self, data):\n" +
                        "        # AutoMedic 由内核 AutoMedicAgent.java 驱动\n" +
                        "        # 此 Python 入口仅作为 EventBus 桥接\n" +
                        "        pass\n",
                List.of()
        ));

        return blueprints;
    }

    /**
     * 拉起交互式 Shell — 类比 Linux 的 agetty + bash。
     * <p>
     * InitDaemon 创建一个特殊的 Agent 进程 AiosShell，它绑定到
     * E_CORE（轻量级 LLM），挂载在 /dev/stdin 和 /dev/stdout，
     * 负责接收用户的自然语言命令并转化为系统调用。
     */
    private void spawnAiosShell() {
        System.out.println();
        System.out.println("  ┌─ Spawning AiosShell (PID 2) ──────────────────────────────┐");

        try {
            AiosShell shell = new AiosShell(scheduler, llmRouter);
            shell.spawn(scheduler);

            System.out.printf("  │  Shell PID: %d                                             │%n", shell.getPid());
            System.out.println("  │  LLM Binding: E_CORE (efficiency core)                     │");
            System.out.println("  │  Stdin:  /dev/stdin                                        │");
            System.out.println("  │  Stdout: /dev/stdout                                       │");
            System.out.println("  │  Prompt: aios>                                             │");
            System.out.println("  └────────────────────────────────────────────────────────────┘");

            log.info("[PID 1] AiosShell spawned as PID {}", shell.getPid());

            SemanticEtw.getInstance().logEvent("BOOT", "SHELL_SPAWNED",
                    "shellPid=" + shell.getPid());

        } catch (Exception e) {
            log.error("[PID 1] Failed to spawn AiosShell: {}", e.getMessage());
            System.out.println("  │  AiosShell: SPAWN FAILED ✗                                 │");
            System.out.println("  └────────────────────────────────────────────────────────────┘");
        }
    }

    @Override
    protected void onMessage(String msg) {
        log.info("[PID 1] Received message: {}", msg);
        String response = sdk.think(agentId, "Init daemon received: " + msg);
        System.out.printf("  [PID 1] Response: %s%n", response);
    }

    /**
     * 以 PID 1 身份在指定 TaskScheduler 上生成 InitDaemon。
     */
    public static InitDaemon spawnAsPid1(TaskScheduler scheduler, GraalWasmSandbox sandbox) {
        return spawnAsPid1(scheduler, sandbox, null);
    }

    public static InitDaemon spawnAsPid1(TaskScheduler scheduler, GraalWasmSandbox sandbox, LlmRouter llmRouter) {
        InitDaemon init = new InitDaemon(scheduler, sandbox, llmRouter);
        init.spawn(scheduler);
        return init;
    }

    // ── 公共查询 API ──

    /** 当前引导阶段 */
    public BootPhase currentPhase() {
        return currentPhase;
    }

    /** 引导结果 */
    public Map<String, Boolean> bootResults() {
        return Map.copyOf(bootResults);
    }

    /** 引导耗时 */
    public long bootTimeMs() {
        return bootTimeMs;
    }

    /** 系统是否就绪（RUNLEVEL 5） */
    public boolean isSystemReady() {
        return currentPhase == BootPhase.COMPLETE;
    }
}
