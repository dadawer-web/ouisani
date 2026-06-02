package com.ouisani.aios.user.cli;

import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.mcp.McpServer;
import com.ouisani.aios.core.network.SyscallServer;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import com.ouisani.aios.user.DaemonManager;
import com.ouisani.aios.user.container.ContainerRuntime;

public class TestFullSystemBoot {

    public static void main(String[] args) {
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║          🚀 AIOS Full System Boot Sequence                 ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("  [1/7] Starting TaskScheduler...");
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        System.out.println("  ✓ TaskScheduler: virtual thread executor active");
        System.out.println();

        System.out.println("  [2/7] Initializing VfsManager...");
        VfsManager.instance().configureTaskScheduler(scheduler);
        VfsManager.instance().init();
        System.out.println("  ✓ VfsManager: /, /bin, /dev, /mem, /proc, /tmp, /containers, /var");
        System.out.println("  ✓ /proc/agents + /proc/cgroups mounted");
        System.out.println();

        System.out.println("  [3/7] Initializing CgroupManager...");
        CgroupManager.instance().init();
        System.out.println("  ✓ CgroupManager: root(1M) → agents(500K), system(200K), tools(300K)");
        System.out.println();

        System.out.println("  [4/7] Starting WASM Sandbox...");
        GraalWasmSandbox sandbox = new GraalWasmSandbox();
        sandbox.initContext();
        System.out.println("  ✓ GraalWasmSandbox: context initialized");
        System.out.println();

        System.out.println("  [5/7] Starting ContainerRuntime + DaemonManager...");
        ContainerRuntime runtime = new ContainerRuntime(scheduler);
        DaemonManager systemd = new DaemonManager(runtime);
        systemd.startReconciler();
        System.out.println("  ✓ ContainerRuntime: ready");
        System.out.println("  ✓ DaemonManager: reconciler active (3s interval)");
        System.out.println();

        System.out.println("  [6/7] Starting MCP Server + Syscall Gateway...");
        McpServer mcpServer = new McpServer(sandbox);
        SyscallServer gateway = new SyscallServer(scheduler, mcpServer);
        gateway.start(8080);
        System.out.println();

        System.out.println("  [7/7] Handing off to AIOS Shell...");
        System.out.println();

        AiosShell.init(scheduler, runtime, systemd);
        AiosShell.main(args);
    }
}
