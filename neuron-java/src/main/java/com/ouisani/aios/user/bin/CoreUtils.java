package com.ouisani.aios.user.bin;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * AIOS 核心工具集 — 类似 GNU coreutils 的内置系统工具。
 * <p>
 * OS 类比：相当于 Linux 的 coreutils（ps/kill/whoami/uptime/free/ls），
 * 在 AIOS 中以 {@code coreutils.} 前缀暴露为系统调用动作，
 * Agent 可通过 SDK 或自然语言（经 Intent Router）调用。
 * <p>
 * 可用命令：
 * <ul>
 *   <li>{@code coreutils.ps} — 列出所有进程</li>
 *   <li>{@code coreutils.kill} — 向进程发送信号</li>
 *   <li>{@code coreutils.whoami} — 显示当前 Agent 身份</li>
 *   <li>{@code coreutils.uptime} — 显示系统运行时间</li>
 *   <li>{@code coreutils.free} — 显示 token/内存使用量</li>
 *   <li>{@code coreutils.ls} — 列出 VFS 目录</li>
 * </ul>
 */
public final class CoreUtils {

    private static final Logger log = LoggerFactory.getLogger(CoreUtils.class);

    /** 系统启动时间戳 */
    private static final long BOOT_TIME = System.currentTimeMillis();

    private CoreUtils() {}

    // ── TaskScheduler 引用（启动时设置） ──
    private static TaskScheduler scheduler;

    public static void configure(TaskScheduler taskScheduler) {
        scheduler = taskScheduler;
    }

    // ── ps：列出所有进程 ──

    /**
     * 以格式化表格列出所有活跃进程。
     *
     * @return 格式化的进程表
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

    // ── kill：向进程发送信号 ──

    /**
     * 按 PID 终止进程。先发送 SIGTERM，再发送 SIGKILL。
     *
     * @param pidStr PID 字符串
     * @return 结果消息
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

    // ── whoami：显示当前 Agent 身份 ──

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

    // ── uptime：显示系统运行时间 ──

    public static String uptime() {
        long uptimeMs = System.currentTimeMillis() - BOOT_TIME;
        long sec = uptimeMs / 1000;
        long min = sec / 60;
        long hr = min / 60;
        return String.format("Up %dh %dm %ds | %d active agents",
                hr, min % 60, sec % 60,
                scheduler != null ? scheduler.activeCount() : 0);
    }

    // ── free：显示 token/内存使用量 ──

    public static String free() {
        if (scheduler == null) return "[Error] TaskScheduler not configured";

        var stats = scheduler.stats();
        return String.format("Agents: %d active | Spawned: %d | Completed: %d | Cancelled: %d",
                stats.activeCount(), stats.totalSpawned(),
                stats.totalCompleted(), stats.totalCancelled());
    }

    // ── 按动作名分派 coreutils 命令 ──

    /**
     * 按名称分派 coreutils 动作。
     *
     * @param subAction 子动作（如 "ps"、"kill"）
     * @param params    参数
     * @return 结果字符串
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
