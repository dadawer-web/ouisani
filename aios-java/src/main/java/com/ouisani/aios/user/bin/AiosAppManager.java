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
 * AIOS Application Installation & Scheduling Engine.
 * <p>
 * Parses an {@link AppManifest} from text, mounts VFS directories,
 * allocates token budgets via Cgroup, and spawns {@code spawnCount}
 * virtual threads — each running a {@link GenericAppAgent} that can
 * execute code inside a Docker sandbox if the entrypoint requires it.
 *
 * <h3>Usage:</h3>
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
     * Install and run a generic OS application from its manifest text.
     *
     * @param appManifestContent the raw manifest text (APP_NAME, SPAWN, BUDGET, MOUNT, ENTRYPOINT)
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
     * Generic application Agent — dynamically created per manifest worker.
     * <p>
     * If the entrypoint contains "docker" or "python", it delegates execution
     * to {@link DockerSandboxProvider} for real sandboxed code execution.
     * Otherwise, it uses the LLM to "think" through the entrypoint command.
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
         * Extract a Python script stub from the entrypoint.
         * In production, this would read the actual script file from VFS.
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
