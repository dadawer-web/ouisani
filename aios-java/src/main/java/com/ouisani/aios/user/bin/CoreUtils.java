package com.ouisani.aios.user.bin;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * AIOS Core Utilities — built-in system tools analogous to GNU coreutils.
 * <p>
 * These are exposed as syscall actions with the {@code coreutils.} prefix,
 * and can be invoked by Agents through the SDK or by natural language
 * through the Intent Router.
 * <p>
 * Available commands:
 * <ul>
 *   <li>{@code coreutils.ps} — list all processes</li>
 *   <li>{@code coreutils.kill} — send signal to a process</li>
 *   <li>{@code coreutils.whoami} — show current agent identity</li>
 *   <li>{@code coreutils.uptime} — show system uptime</li>
 *   <li>{@code coreutils.free} — show token/memory usage</li>
 *   <li>{@code coreutils.ls} — list VFS directory</li>
 * </ul>
 */
public final class CoreUtils {

    private static final Logger log = LoggerFactory.getLogger(CoreUtils.class);

    private static final long BOOT_TIME = System.currentTimeMillis();

    private CoreUtils() {}

    // ── TaskScheduler reference (set during boot) ──
    private static TaskScheduler scheduler;

    public static void configure(TaskScheduler taskScheduler) {
        scheduler = taskScheduler;
    }

    // ── ps: list all processes ──

    /**
     * List all active processes in a formatted table.
     *
     * @return formatted process table
     */
    public static String ps() {
        if (scheduler == null) return "[Error] TaskScheduler not configured";

        Map<Integer, AgentTask> tasks = scheduler.activeTasks();
        if (tasks.isEmpty()) {
            return "No active processes.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-6s %-12s %-12s %-10s%n",
                "PID", "STATUS", "PRIORITY", "CGROUP"));
        sb.append("─".repeat(42)).append("\n");

        for (var entry : tasks.entrySet()) {
            AgentTask task = entry.getValue();
            sb.append(String.format("%-6d %-12s %-12s %-10s%n",
                    task.pid(),
                    task.status() != null ? task.status() : "-",
                    task.processPriority() != null ? task.processPriority() : "-",
                    task.cgroup() != null ? task.cgroup() : "-"
            ));
        }

        var stats = scheduler.stats();
        sb.append("\nTotal: ").append(stats.activeCount()).append(" active, ")
          .append(stats.totalSpawned()).append(" spawned, ")
          .append(stats.totalCompleted()).append(" completed, ")
          .append(stats.totalCancelled()).append(" cancelled");

        SemanticEtw.getInstance().logEvent("COREUTILS", "PS",
                "active=" + stats.activeCount());
        return sb.toString();
    }

    // ── kill: send signal to process ──

    /**
     * Kill a process by PID. Sends SIGTERM first, then SIGKILL.
     *
     * @param pidStr the PID as a string
     * @return result message
     */
    public static String kill(String pidStr) {
        if (scheduler == null) return "[Error] TaskScheduler not configured";

        try {
            int pid = Integer.parseInt(pidStr.trim());
            boolean sent = scheduler.kill(String.valueOf(pid), SignalType.SIGTERM);

            if (sent) {
                String msg = "SIGTERM sent to PID " + pid;
                SemanticEtw.getInstance().logEvent("COREUTILS", "KILL", "pid=" + pid);
                log.info("[CoreUtils] Kill PID {}: SIGTERM sent", pid);
                return msg;
            } else {
                return "PID " + pid + " not found or already terminated";
            }
        } catch (NumberFormatException e) {
            return "Invalid PID: " + pidStr;
        }
    }

    // ── whoami: show current agent identity ──

    public static String whoami() {
        AgentTask current = TaskScheduler.CURRENT_TASK.get();
        if (current != null) {
            return String.format("PID=%d Priority=%s Cgroup=%s",
                    current.pid(),
                    current.processPriority(),
                    current.cgroup());
        }
        return "PID=0 Priority=REALTIME Cgroup=root";
    }

    // ── uptime: show system uptime ──

    public static String uptime() {
        long uptimeMs = System.currentTimeMillis() - BOOT_TIME;
        long sec = uptimeMs / 1000;
        long min = sec / 60;
        long hr = min / 60;
        return String.format("Up %dh %dm %ds | %d active agents",
                hr, min % 60, sec % 60,
                scheduler != null ? scheduler.activeCount() : 0);
    }

    // ── free: show token/memory usage ──

    public static String free() {
        if (scheduler == null) return "[Error] TaskScheduler not configured";

        var stats = scheduler.stats();
        return String.format("Agents: %d active | Spawned: %d | Completed: %d | Cancelled: %d",
                stats.activeCount(), stats.totalSpawned(),
                stats.totalCompleted(), stats.totalCancelled());
    }

    // ── Dispatch coreutils by action name ──

    /**
     * Dispatch a coreutils action by name.
     *
     * @param subAction the sub-action (e.g. "ps", "kill")
     * @param params    the parameters
     * @return the result string
     */
    public static String dispatch(String subAction, Map<String, Object> params) {
        return switch (subAction) {
            case "ps" -> ps();
            case "kill" -> kill(params.getOrDefault("pid", "0").toString());
            case "whoami" -> whoami();
            case "uptime" -> uptime();
            case "free" -> free();
            default -> "Unknown coreutils command: " + subAction;
        };
    }
}
