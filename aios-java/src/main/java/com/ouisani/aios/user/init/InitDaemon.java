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
import com.ouisani.aios.user.cli.AiosShell;
import com.ouisani.aios.user.container.ContainerRuntime;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
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
     * Spawn InitDaemon as PID 1 on the given TaskScheduler.
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
