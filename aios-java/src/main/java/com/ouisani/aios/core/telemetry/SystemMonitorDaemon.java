package com.ouisani.aios.core.telemetry;

import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * System Monitor Daemon — periodic telemetry heartbeat that collects
 * system-wide metrics and broadcasts them via the global EventBus.
 * <p>
 * Runs a scheduled task every 1 second to gather:
 * <ul>
 *   <li>Active Agent count from {@link TaskScheduler}</li>
 *   <li>Total Token consumption from {@link CgroupManager}</li>
 *   <li>ZRAM compression ratio estimation</li>
 * </ul>
 * The resulting JSON is broadcast as a {@code system_metrics} event,
 * allowing any SSE-connected dashboard to render real-time telemetry.
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

            // 5. Broadcast via EventBus
            EventBus.instance().broadcast("system_metrics", json);

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
        return "{\"active_agents\":" + activeAgents
                + ",\"total_tokens\":" + totalTokensConsumed
                + ",\"total_quota\":" + totalTokensQuota
                + ",\"zram_compression\":\"" + zramCompression + "\""
                + ",\"timestamp\":" + System.currentTimeMillis()
                + "}";
    }
}
