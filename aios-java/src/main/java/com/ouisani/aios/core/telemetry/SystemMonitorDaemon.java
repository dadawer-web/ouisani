package com.ouisani.aios.core.telemetry;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统监控守护进程 — 周期性遥测心跳，采集全系统指标并通过 EventBus 广播。
 * <p>
 * 每 1 秒执行一次定时任务，采集：
 * <ul>
 *   <li>活跃 Agent 数量（来自 {@link TaskScheduler}）</li>
 *   <li>总 Token 消耗量（来自 {@link CgroupManager}）</li>
 *   <li>ZRAM 压缩比估算</li>
 * </ul>
 * 结果以 JSON 格式广播为 {@code system_metrics} 事件，
 * 允许任何 SSE 连接的大屏渲染实时遥测。
 * <p>
 * OS 类比: Linux 的 vmstat/iostat 守护进程 + perf 采样。
 */
public final class SystemMonitorDaemon {

    private static final Logger log = LoggerFactory.getLogger(SystemMonitorDaemon.class);

    private static final class Holder {
        static final SystemMonitorDaemon INSTANCE = new SystemMonitorDaemon();
    }

    public static SystemMonitorDaemon getInstance() {
        return Holder.INSTANCE;
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private TaskScheduler taskScheduler;

    private SystemMonitorDaemon() {}

    /**
     * Configure the daemon with the system's TaskScheduler.
     */
    public void configure(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    /**
     * Start the periodic telemetry collection loop.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[System Monitor] Already running");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aios-monitor-daemon");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::collectAndBroadcast, 1, 1, TimeUnit.SECONDS);

        log.info("[System Monitor] Daemon started — collecting metrics every 1s");
        System.out.println("  ✓ [System Monitor] Telemetry daemon started (1s interval)");
    }

    /**
     * Stop the telemetry daemon.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("[System Monitor] Daemon stopped");
    }

    private void collectAndBroadcast() {
        try {
            // 1. Active Agent count
            int activeAgents = 0;
            if (taskScheduler != null) {
                activeAgents = taskScheduler.activeCount();
            }

            // 2. Total Token consumption from all CgroupNodes
            long totalTokensConsumed = 0;
            long totalTokensQuota = 0;
            for (String nodeName : CgroupManager.instance().nodeNames()) {
                CgroupNode node = CgroupManager.instance().getNode(nodeName);
                if (node != null) {
                    totalTokensConsumed += node.tokenConsumed();
                    totalTokensQuota += node.tokenQuota();
                }
            }

            // 3. ZRAM compression estimation
            // Estimate based on root cgroup usage ratio as a proxy for memory pressure
            CgroupNode rootCgroup = CgroupManager.instance().getNode("aios-root");
            String zramCompression = "N/A";
            if (rootCgroup != null && rootCgroup.tokenQuota() > 0) {
                long consumed = rootCgroup.tokenConsumed();
                long quota = rootCgroup.tokenQuota();
                // Simulate compression ratio: higher usage → higher compression
                // In a real system, this would come from TokenZram statistics
                double usageRatio = (double) consumed / quota;
                int compressionPct = (int) Math.min(95, Math.max(0, usageRatio * 100));
                zramCompression = compressionPct + "%";
            }

            // 4. Build JSON payload
            String json = buildMetricsJson(activeAgents, totalTokensConsumed, totalTokensQuota, zramCompression);

            // 5. Broadcast via EventBus — 主题名 sys.telemetry.metrics，前端直接消费
            EventBus.instance().broadcast("sys.telemetry.metrics", json);

            // 6. Log to ETW
            SemanticEtw.getInstance().logEvent("MONITOR", "HEARTBEAT",
                    "active_agents=" + activeAgents
                    + " total_tokens=" + totalTokensConsumed
                    + " zram=" + zramCompression);

            log.debug("[System Monitor] Metrics broadcast: agents={}, tokens={}/{}, zram={}",
                    activeAgents, totalTokensConsumed, totalTokensQuota, zramCompression);
        } catch (Exception e) {
            log.error("[System Monitor] Collection error: {}", e.getMessage());
        }
    }

    private String buildMetricsJson(int activeAgents, long totalTokensConsumed,
                                     long totalTokensQuota, String zramCompression) {
        // 构建进程列表
        StringBuilder processesJson = new StringBuilder("[");
        if (taskScheduler != null) {
            boolean first = true;
            for (Map.Entry<Integer, AgentTask> entry : taskScheduler.activeTasks().entrySet()) {
                if (!first) processesJson.append(",");
                AgentTask t = entry.getValue();
                String statusStr = switch (t.status()) {
                    case RUNNING -> "Running";
                    case READY -> "Sleeping";
                    case BLOCKED -> "Blocked";
                    case CRASHED -> "Crashed";
                    case KILLED, OOM_KILLED, DEADLINE_EXCEEDED -> "Killed";
                };
                String sandboxType = t.cgroup() != null && t.cgroup().contains("wasm") ? "Wasm" : "Docker";
                // CPU 估算：基于 cgroup 消耗占比
                int cpuPct = 0;
                CgroupNode agentCgroup = CgroupManager.instance().getOrCreateAgentCgroup(t.pid());
                if (agentCgroup != null && agentCgroup.tokenQuota() > 0) {
                    cpuPct = (int) (agentCgroup.tokenConsumed() * 100 / agentCgroup.tokenQuota());
                }
                long ram = agentCgroup != null ? agentCgroup.tokenConsumed() : 0;
                processesJson.append("{\"pid\":").append(t.pid())
                        .append(",\"agentName\":\"").append(t.cgroup() != null ? t.cgroup() : "agent-" + t.pid()).append("\"")
                        .append(",\"sandboxType\":\"").append(sandboxType).append("\"")
                        .append(",\"status\":\"").append(statusStr).append("\"")
                        .append(",\"cpu\":").append(cpuPct)
                        .append(",\"ram\":").append(ram)
                        .append("}");
                first = false;
            }
        }
        processesJson.append("]");

        // CPU 使用率估算
        int cpuUsage = 0;
        if (totalTokensQuota > 0) {
            cpuUsage = (int) (totalTokensConsumed * 100 / totalTokensQuota);
        }
        int ramUsage = cpuUsage; // 简化：RAM 使用率与 Token 使用率成正比

        return "{\"type\":\"SYS_METRICS\""
                + ",\"cpuUsage\":" + cpuUsage
                + ",\"ramUsage\":" + ramUsage
                + ",\"activeProcesses\":" + activeAgents
                + ",\"processes\":" + processesJson
                + ",\"active_agents\":" + activeAgents
                + ",\"total_tokens\":" + totalTokensConsumed
                + ",\"total_quota\":" + totalTokensQuota
                + ",\"zram_compression\":\"" + zramCompression + "\""
                + ",\"timestamp\":" + System.currentTimeMillis()
                + "}";
    }
}
