package com.ouisani.aios.user.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.user.DaemonManager;
import com.ouisani.aios.user.container.AgentImageConfig;
import com.ouisani.aios.user.container.AgentfileParser;
import com.ouisani.aios.user.container.ContainerRuntime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

public class AiosShell {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static TaskScheduler scheduler;
    private static ContainerRuntime runtime;
    private static DaemonManager systemd;

    public static void init(TaskScheduler scheduler, ContainerRuntime runtime, DaemonManager systemd) {
        AiosShell.scheduler = scheduler;
        AiosShell.runtime = runtime;
        AiosShell.systemd = systemd;
    }

    public static void main(String[] args) {
        printBanner();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("aios> ");
            if (!scanner.hasNextLine()) break;

            String line = scanner.nextLine().strip();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 2);
            String cmd = parts[0].toLowerCase();
            String arg = parts.length > 1 ? parts[1].strip() : "";

            try {
                switch (cmd) {
                    case "ps" -> cmdPs();
                    case "top" -> cmdTop();
                    case "cat" -> cmdCat(arg);
                    case "run" -> cmdRun(arg);
                    case "help" -> cmdHelp();
                    case "exit", "quit" -> {
                        cmdExit();
                        return;
                    }
                    default -> System.out.println("  Unknown command: " + cmd + " (type 'help' for commands)");
                }
            } catch (Exception e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }
    }

    private static void cmdPs() {
        Optional<VfsNode> node = VfsManager.instance().resolve("/proc/agents");
        if (node.isEmpty()) {
            System.out.println("  /proc/agents not available (TaskScheduler not configured?)");
            return;
        }

        try {
            String json = node.get().read();
            JsonNode root = objectMapper.readTree(json);

            System.out.println("  ┌──────┬──────────┬────────────┬──────────┬──────────┐");
            System.out.println("  │  PID │ Status   │ Cgroup     │ Type     │ Gas      │");
            System.out.println("  ├──────┼──────────┼────────────┼──────────┼──────────┤");

            JsonNode agents = root.get("agents");
            if (agents != null && agents.isArray()) {
                for (JsonNode agent : agents) {
                    System.out.printf("  │ %4d │ %-8s │ %-10s │ %-8s │ %4d/%-4d │%n",
                            agent.get("pid").asInt(),
                            agent.get("status").asText(),
                            truncate(agent.get("cgroup").asText(), 10),
                            truncate(agent.get("type").asText(), 8),
                            agent.get("gasUsed").asInt(),
                            agent.get("gasLimit").asInt());
                }
            }

            System.out.println("  └──────┴──────────┴────────────┴──────────┴──────────┘");

            JsonNode stats = root.get("stats");
            if (stats != null) {
                System.out.printf("  Stats: spawned=%d, completed=%d, cancelled=%d, active=%d%n",
                        stats.get("totalSpawned").asInt(),
                        stats.get("totalCompleted").asInt(),
                        stats.get("totalCancelled").asInt(),
                        stats.get("activeCount").asInt());
            }
        } catch (Exception e) {
            System.out.println("  Failed to parse /proc/agents: " + e.getMessage());
        }
    }

    private static void cmdTop() {
        Optional<VfsNode> node = VfsManager.instance().resolve("/proc/cgroups");
        if (node.isEmpty()) {
            System.out.println("  /proc/cgroups not available");
            return;
        }

        try {
            String json = node.get().read();
            JsonNode root = objectMapper.readTree(json);

            System.out.println("  ┌─ Cgroup Hierarchy ──────────────────────────────────────┐");
            System.out.println("  │  Name              Quota        Consumed    Remaining    │");
            System.out.println("  ├─────────────────────────────────────────────────────────┤");

            JsonNode cgroups = root.get("cgroups");
            if (cgroups != null && cgroups.isArray()) {
                for (JsonNode cg : cgroups) {
                    String parent = cg.has("parent") && !cg.get("parent").isNull()
                            ? cg.get("parent").asText() : null;
                    int depth = 0;
                    String name = cg.get("name").asText();
                    long quota = cg.get("quota").asLong();
                    long consumed = cg.get("consumed").asLong();
                    long remaining = cg.get("remaining").asLong();

                    String indent = parent != null ? "  │    " : "  │  ";
                    String prefix = parent != null ? "├─ " : "├─ ";
                    System.out.printf("%s%s%-17s %8d    %8d    %8d%n",
                            indent, prefix, name, quota, consumed, remaining);
                }
            }

            System.out.println("  └─────────────────────────────────────────────────────────┘");
        } catch (Exception e) {
            System.out.println("  Failed to parse /proc/cgroups: " + e.getMessage());
        }
    }

    private static void cmdCat(String path) {
        if (path.isEmpty()) {
            System.out.println("  Usage: cat <vfs_path>");
            return;
        }

        Optional<VfsNode> node = VfsManager.instance().resolve(path);
        if (node.isEmpty()) {
            System.out.println("  Node not found: " + path);
            return;
        }

        VfsNode vfsNode = node.get();
        System.out.printf("  [%s] %s%n", vfsNode.nodeType(), path);

        try {
            String content = vfsNode.read();
            if (content == null || content.isEmpty()) {
                System.out.println("  (empty)");
            } else {
                try {
                    Object parsed = objectMapper.readValue(content, Object.class);
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(parsed).indent(2));
                } catch (Exception ignored) {
                    System.out.println(content.indent(2));
                }
            }
        } catch (UnsupportedOperationException e) {
            System.out.println("  (write-only node)");
        }
    }

    private static void cmdRun(String agentfilePath) {
        if (agentfilePath.isEmpty()) {
            System.out.println("  Usage: run <agentfile_path>");
            return;
        }

        try {
            String content = Files.readString(Path.of(agentfilePath));
            AgentfileParser parser = new AgentfileParser();
            AgentImageConfig config = parser.parse(content);

            String containerId = "shell_" + System.currentTimeMillis() % 100000;
            runtime.runContainer(containerId, config);
            System.out.printf("  Container '%s' deployed from %s%n", containerId, agentfilePath);
        } catch (IOException e) {
            System.out.println("  File not found: " + agentfilePath);
        } catch (Exception e) {
            System.out.println("  Deploy failed: " + e.getMessage());
        }
    }

    private static void cmdHelp() {
        System.out.println("  ┌─ AIOS Shell Commands ──────────────────────────────┐");
        System.out.println("  │  ps              List running agents               │");
        System.out.println("  │  top             Show cgroup resource hierarchy     │");
        System.out.println("  │  cat <path>      Read VFS node content             │");
        System.out.println("  │  run <file>      Deploy container from Agentfile   │");
        System.out.println("  │  help            Show this help                    │");
        System.out.println("  │  exit            Shutdown and quit                 │");
        System.out.println("  └────────────────────────────────────────────────────┘");
    }

    private static void cmdExit() {
        System.out.println("  Shutting down AIOS...");

        if (systemd != null) {
            systemd.stopReconciler();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }

        System.out.println("  Goodbye!");
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║          🧠  AIOS — AI Operating System Shell              ║");
        System.out.println("  ║          Java 21 Virtual Threads | GraalVM WASM            ║");
        System.out.println("  ║          VFS Namespace | Cgroup Isolation | MCP Protocol   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println("  Type 'help' for available commands.");
        System.out.println();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
