package com.ouisani.aios.user.init;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.rtos.WatchdogDaemon;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import com.ouisani.aios.core.telemetry.SystemMonitorDaemon;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AIOS Init Daemon — PID 1, the first user-space Agent.
 * <p>
 * Inspired by Linux systemd / init, this is the root of the user-space
 * process tree. It runs at REALTIME priority with unlimited token budget
 * and is responsible for bootstrapping all background services.
 * <p>
 * Boot sequence:
 * <ol>
 *   <li>Start WatchdogDaemon (hard real-time deadline enforcement)</li>
 *   <li>Start SystemMonitorDaemon (telemetry heartbeat)</li>
 *   <li>Scan and load WASM plugins from /opt/aios/plugins</li>
 * </ol>
 */
public class InitDaemon extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(InitDaemon.class);

    private static final String PLUGIN_DIR = "/opt/aios/plugins";

    private final TaskScheduler scheduler;
    private final GraalWasmSandbox sandbox;

    public InitDaemon(TaskScheduler scheduler, GraalWasmSandbox sandbox) {
        super("sys_init_1", ProcessPriority.REALTIME, 9999999);
        this.scheduler = scheduler;
        this.sandbox = sandbox;
    }

    @Override
    protected void onStart() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [PID 1] InitDaemon starting...                             ║");
        System.out.println("  ║  Agent ID: sys_init_1 | Priority: REALTIME | Budget: ∞      ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        log.info("[PID 1] InitDaemon starting...");

        // ── Bootstrap Phase 1: WatchdogDaemon ──
        boolean watchdogOk = false;
        try {
            WatchdogDaemon.instance(); // Already started by TaskScheduler
            watchdogOk = true;
        } catch (Exception e) {
            log.warn("[PID 1] WatchdogDaemon check failed: {}", e.getMessage());
        }

        // ── Bootstrap Phase 2: SystemMonitorDaemon ──
        boolean telemetryOk = false;
        try {
            SystemMonitorDaemon.getInstance().configure(scheduler);
            SystemMonitorDaemon.getInstance().start();
            telemetryOk = true;
        } catch (Exception e) {
            log.warn("[PID 1] SystemMonitorDaemon start failed: {}", e.getMessage());
        }

        // ── Bootstrap Phase 3: PluginManager ──
        int pluginCount = 0;
        try {
            PluginManager.getInstance().configure(sandbox);
            PluginManager.getInstance().scanAndLoadPlugins(PLUGIN_DIR);
            pluginCount = PluginManager.getInstance().registeredPlugins().size();
        } catch (Exception e) {
            log.warn("[PID 1] PluginManager scan failed: {}", e.getMessage());
        }

        // ── Report ──
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("  ║  [PID 1] Bootstrapping background services:                ║%n");
        System.out.printf("  ║    Watchdog  [%s]                                           ║%n",
                watchdogOk ? "OK" : "FAIL");
        System.out.printf("  ║    Telemetry [%s]                                           ║%n",
                telemetryOk ? "OK" : "FAIL");
        System.out.printf("  ║    Plugins   [%d loaded]                                    ║%n", pluginCount);
        System.out.println("  ║                                                             ║");
        System.out.println("  ║  [PID 1] System reaches RUNLEVEL 5.                        ║");
        System.out.println("  ║          User space is now fully operational!              ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        log.info("[PID 1] Bootstrapping background services: Watchdog [{}], Telemetry [{}], Plugins [{}]",
                watchdogOk ? "OK" : "FAIL", telemetryOk ? "OK" : "FAIL", pluginCount);
        log.info("[PID 1] System reaches RUNLEVEL 5. User space is now fully operational!");
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
        InitDaemon init = new InitDaemon(scheduler, sandbox);
        init.spawn(scheduler);
        return init;
    }
}
