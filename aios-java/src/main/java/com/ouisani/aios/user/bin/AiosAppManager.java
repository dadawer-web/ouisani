package com.ouisani.aios.user.bin;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.sandbox.DockerSandboxProvider;
import com.ouisani.aios.core.sandbox.SandboxProvider;
import com.ouisani.aios.user.container.AgentfileParser;
import com.ouisani.aios.user.container.AppManifest;
import com.ouisani.aios.user.sdk.AbstractAgent;
import com.ouisani.aios.vfs.ShmNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * AIOS 应用安装与调度引擎。
 * <p>
 * OS 类比：相当于 systemd 的 {@code systemctl start} + Docker 的 {@code docker run} —
 * 解析应用清单、挂载 VFS 目录、通过 Cgroup 分配 token 预算，
 * 然后生成 {@code spawnCount} 个虚拟线程，每个运行一个 {@link GenericAppAgent}，
 * 可在 Docker 沙箱中执行代码。
 * <p>
 * 使用方式：
 * <pre>
 * AiosAppManager.configure(scheduler);
 * AiosAppManager.installAndRun(manifestText);
 * </pre>
 */
public class AiosAppManager {

    private static final Logger log = LoggerFactory.getLogger(AiosAppManager.class);

    private static TaskScheduler scheduler;

    public static void configure(TaskScheduler taskScheduler) {
        scheduler = taskScheduler;
    }

    /**
     * 安装并运行通用 OS 应用（从清单文本解析）。
     *
     * @param appManifestContent 原始清单文本（APP_NAME, SPAWN, BUDGET, MOUNT, ENTRYPOINT）
     */
    public static void installAndRun(String appManifestContent) {
        if (scheduler == null) {
            throw new IllegalStateException("[App Manager] TaskScheduler not configured. Call configure() first.");
        }

        // 1. Parse manifest
        AppManifest manifest = AgentfileParser.parseManifest(appManifestContent);
        String appName = manifest.appName();
        int spawnCount = manifest.spawnCount();
        int tokenBudget = manifest.tokenBudget();
        String entrypoint = manifest.entrypoint();

        System.out.println("[App Manager] Installing generic application: " + appName);

        // 2. Mount VFS directories for this application
        for (Map.Entry<String, String> mount : manifest.mounts().entrySet()) {
            String hostPath = mount.getKey();
            String containerPath = mount.getValue();
            // Use ShmNode as a shared-memory-backed directory for inter-process communication
            String segmentId = appName + "_" + containerPath.replace("/", "_");
            ShmNode shmNode = new ShmNode(hostPath, segmentId);
            VfsManager.instance().mount(hostPath, containerPath, shmNode);
            System.out.printf("  ├─ [App Manager] Mounted: %s → %s (SHM)%n", hostPath, containerPath);
        }

        // 3. Spawn virtual threads
        System.out.printf("[App Manager] Allocated %d virtual threads with Cgroup budget %d%n", spawnCount, tokenBudget);

        for (int i = 0; i < spawnCount; i++) {
            String workerId = appName + "_worker_" + (i + 1);
            GenericAppAgent agent = new GenericAppAgent(workerId, tokenBudget, entrypoint, appName);
            agent.spawn(scheduler);
        }

        System.out.println("[App Manager] Application successfully launched into User Space.");
        log.info("[App Manager] Application '{}' launched: spawnCount={}, budget={}, entrypoint='{}'",
                appName, spawnCount, tokenBudget, entrypoint);
    }

    /**
     * 通用应用 Agent — 根据清单动态创建的工作线程。
     * <p>
     * 如果入口点包含 "docker" 或 "python"，则委托给 {@link DockerSandboxProvider}
     * 进行真实沙箱代码执行；否则使用 LLM "思考"入口点命令。
     */
    static class GenericAppAgent extends AbstractAgent {

        private final String entrypoint;
        private final String appName;

        GenericAppAgent(String agentId, int tokenBudget, String entrypoint, String appName) {
            super(agentId, ProcessPriority.NORMAL, tokenBudget);
            this.entrypoint = entrypoint;
            this.appName = appName;
        }

        @Override
        protected void onStart() {
            System.out.printf("  ▶ [%s] Booting... entrypoint='%s'%n", agentId, entrypoint);

            if (entrypoint == null || entrypoint.isBlank()) {
                System.out.printf("  ■ [%s] No entrypoint defined, idle exit.%n", agentId);
                exit();
                return;
            }

            String lowerEntrypoint = entrypoint.toLowerCase();

            if (lowerEntrypoint.contains("docker") || lowerEntrypoint.contains("python")) {
                // Delegate to Docker sandbox for real code execution
                try {
                    SandboxProvider sandbox = new DockerSandboxProvider();
                    // Extract the actual script/command from entrypoint
                    String script = extractScript(lowerEntrypoint);
                    String result = sandbox.executeCode(script, entrypoint);
                    System.out.printf("  ├─ [%s] Sandbox output: %s%n", agentId,
                            result.length() > 200 ? result.substring(0, 200) + "..." : result);
                } catch (Exception e) {
                    System.out.printf("  🚨 [%s] Sandbox execution failed: %s%n", agentId, e.getMessage());
                    log.warn("[{}] Sandbox execution failed: {}", agentId, e.getMessage());
                }
            } else {
                // Use LLM to process the entrypoint as a natural language command
                String response = sdk.think(agentId,
                        "Execute the following command in the AIOS environment: " + entrypoint);
                System.out.printf("  ├─ [%s] LLM response: %s%n", agentId,
                        response.length() > 200 ? response.substring(0, 200) + "..." : response);
            }

            System.out.printf("  ■ [%s] Task completed. Exiting.%n", agentId);
            exit();
        }

        @Override
        protected void onMessage(String msg) {
            // Generic app agents don't handle incoming messages
            log.debug("[{}] Message ignored: {}", agentId, msg.substring(0, Math.min(msg.length(), 60)));
        }

        /**
         * 从入口点提取 Python 脚本占位符。
         * 生产环境中会从 VFS 读取实际脚本文件。
         */
        private String extractScript(String lowerEntrypoint) {
            // If entrypoint is like "python3 /app/main.py", generate a placeholder
            if (lowerEntrypoint.contains("python")) {
                return "print('Hello from " + appName + " worker')\n"
                        + "import sys\n"
                        + "print(f'Python version: {sys.version}')\n"
                        + "print('Task completed successfully.')\n";
            }
            // Default: echo the entrypoint
            return "#!/bin/bash\necho '" + entrypoint + "'\n";
        }
    }
}
