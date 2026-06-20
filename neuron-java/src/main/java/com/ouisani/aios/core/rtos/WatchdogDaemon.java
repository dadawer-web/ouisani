package com.ouisani.aios.core.rtos;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 硬件级看门狗守护进程 — AIOS 的最后一道防线。
 * <p>
 * 类比嵌入式系统的硬件看门狗定时器（Watchdog Timer, WDT）：
 * 内核主循环必须定期"喂狗"（调用 {@code ping()}），如果连续
 * 超过 {@code watchdogTimeoutMs}（默认 60 秒）没有喂狗，
 * 说明系统主循环被大模型 API 彻底卡死或发生了底层死锁，
 * 看门狗直接触发强制中断：
 * <ol>
 *   <li>生成系统 Trace（所有活跃 Agent 的状态快照）</li>
 *   <li>杀掉所有阻塞的 Agent 网络请求</li>
 *   <li>重置 (Reset) 调度器</li>
 * </ol>
 *
 * <h3>双重监控</h3>
 * <ul>
 *   <li><b>系统级喂狗</b>：TaskScheduler 和 LLM API 请求层定期 ping，
 *       防止系统整体卡死</li>
 *   <li><b>任务级 Deadline</b>：每个 AgentTask 可设置 deadlineMs，
 *       超时后硬杀该任务</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>嵌入式 / RTOS</th><th>AIOS WatchdogDaemon</th><th>说明</th></tr>
 *   <tr><td>WDT 喂狗</td><td>ping()</td><td>定期重置看门狗计数器</td></tr>
 *   <tr><td>WDT 超时</td><td>systemReset()</td><td>系统强制重置</td></tr>
 *   <tr><td>Task Deadline</td><td>checkDeadlines()</td><td>任务级超时检查</td></tr>
 *   <tr><td>MAX_PRIORITY</td><td>Thread.MAX_PRIORITY</td><td>最高优先级线程</td></tr>
 * </table>
 */
public final class WatchdogDaemon {

    private static final Logger log = LoggerFactory.getLogger(WatchdogDaemon.class);

    // ── 默认配置 ──

    private static final long CHECK_INTERVAL_MS = 10;
    private static final long DEFAULT_WATCHDOG_TIMEOUT_MS = 120_000L; // 120 秒

    // ── Singleton ──

    private static final class Holder {
        static final WatchdogDaemon INSTANCE = new WatchdogDaemon();
    }

    public static WatchdogDaemon instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private TaskScheduler taskScheduler;

    // ── 喂狗机制 ──

    /** 上次喂狗时间戳 */
    private volatile long lastPingTimeMs = System.currentTimeMillis();

    /** 看门狗超时阈值（毫秒） */
    private volatile long watchdogTimeoutMs = DEFAULT_WATCHDOG_TIMEOUT_MS;

    /** 喂狗来源追踪：source → lastPingTime */
    private final ConcurrentHashMap<String, Long> pingSources = new ConcurrentHashMap<>();

    /** 是否已触发系统重置（防止重复触发） */
    private volatile boolean systemResetTriggered = false;

    // ── 统计 ──

    private final AtomicLong totalKills = new AtomicLong(0);
    private final AtomicLong totalSystemResets = new AtomicLong(0);
    private final AtomicLong totalPings = new AtomicLong(0);

    private WatchdogDaemon() {}

    // ════════════════════════════════════════════════════════════════
    //  启动 / 停止
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动看门狗守护进程。
     *
     * @param taskScheduler 被监控的调度器
     */
    public void start(TaskScheduler taskScheduler) {
        if (running) {
            log.warn("[WatchdogDaemon] Already running, ignoring start()");
            return;
        }

        this.taskScheduler = taskScheduler;
        this.running = true;
        this.lastPingTimeMs = System.currentTimeMillis();
        this.systemResetTriggered = false;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aios-watchdog");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY); // 最高优先级
            return t;
        });

        // 双重检查：任务级 Deadline + 系统级喂狗
        scheduler.scheduleAtFixedRate(
                this::watchdogTick, 0, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("[WatchdogDaemon] 已启动: 间隔 {}ms, 超时 {}ms",
                CHECK_INTERVAL_MS, watchdogTimeoutMs);
        System.out.printf("  ✓ [Watchdog] 硬实时 Watchdog 已激活 (timeout=%ds, check=%dms)%n",
                watchdogTimeoutMs / 1000, CHECK_INTERVAL_MS);
    }

    /**
     * 启动看门狗（无参数版本 — 向后兼容）。
     */
    public void start() {
        start(null);
    }

    public void stop() {
        if (!running) return;
        running = false;

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("[WatchdogDaemon] Stopped. Total kills: {}, system resets: {}",
                totalKills.get(), totalSystemResets.get());
    }

    // ════════════════════════════════════════════════════════════════
    //  喂狗机制 (Feed the Dog)
    // ════════════════════════════════════════════════════════════════

    /**
     * 喂狗 — 重置看门狗计数器。
     * <p>
     * 类比嵌入式系统的 WDT 喂狗操作：主循环必须在超时前
     * 调用此方法，否则看门狗将触发系统重置。
     * <p>
     * 调用场景：
     * <ul>
     *   <li>TaskScheduler 每次调度循环完成后</li>
     *   <li>LLM API 请求成功返回后</li>
     *   <li>SyscallDispatcher 成功执行系统调用后</li>
     * </ul>
     *
     * @param source 喂狗来源标识（如 "scheduler", "llm_api", "syscall"）
     */
    public void ping(String source) {
        long now = System.currentTimeMillis();
        this.lastPingTimeMs = now;
        this.totalPings.incrementAndGet();

        if (source != null) {
            pingSources.put(source, now);
        }

        // 如果之前触发了系统重置，但系统恢复了，清除重置标志
        if (systemResetTriggered) {
            systemResetTriggered = false;
            log.info("[WatchdogDaemon] System recovered — watchdog ping received from: {}", source);
        }
    }

    /**
     * 喂狗 — 无来源标识版本。
     */
    public void ping() {
        ping(null);
    }

    /**
     * 检查指定来源是否在超时内喂过狗。
     */
    public boolean isSourceAlive(String source) {
        Long lastPing = pingSources.get(source);
        if (lastPing == null) return false;
        return (System.currentTimeMillis() - lastPing) < watchdogTimeoutMs;
    }

    // ════════════════════════════════════════════════════════════════
    //  看门狗 Tick — 双重检查
    // ════════════════════════════════════════════════════════════════

    /**
     * 看门狗 Tick — 每 CHECK_INTERVAL_MS 执行一次。
     * <p>
     * 执行双重检查：
     * <ol>
     *   <li>系统级：检查是否超时未喂狗</li>
     *   <li>任务级：检查每个 AgentTask 的 Deadline</li>
     * </ol>
     */
    private void watchdogTick() {
        try {
            // ── 系统级检查：喂狗超时 ──
            checkSystemWatchdog();

            // ── 任务级检查：Deadline 超时 ──
            if (taskScheduler != null) {
                checkDeadlines();
            }
        } catch (Exception e) {
            log.error("[WatchdogDaemon] Error during watchdog tick: {}", e.getMessage());
        }
    }

    /**
     * 系统级看门狗检查 — 如果超时未喂狗，触发强制重置。
     * <p>
     * 这是 AIOS 的最后一道防线。当大模型 API 彻底卡死或
     * 发生底层死锁时，没有任何代码能执行 ping()，看门狗
     * 超时后直接触发系统重置。
     * <p>
     * 判定逻辑：只要任意一个 ping source 仍在存活窗口内，
     * 就认为系统未死。避免因单一来源的竞态条件误触发重置。
     */
    private void checkSystemWatchdog() {
        long now = System.currentTimeMillis();

        // 检查是否有任意 ping source 仍然存活
        boolean anySourceAlive = false;
        for (Long lastPing : pingSources.values()) {
            if ((now - lastPing) < watchdogTimeoutMs) {
                anySourceAlive = true;
                break;
            }
        }

        // 如果有存活的 source，更新全局 lastPingTimeMs 并跳过
        if (anySourceAlive) {
            lastPingTimeMs = now;
            return;
        }

        // 所有 source 都超时 或 无任何 source（仅靠全局 lastPingTimeMs）
        long elapsed = now - lastPingTimeMs;
        if (elapsed > watchdogTimeoutMs && !systemResetTriggered) {
            systemResetTriggered = true;
            totalSystemResets.incrementAndGet();

            log.error("  ╔══════════════════════════════════════════════════════════════╗");
            log.error("  ║  [WATCHDOG] ⚠ SYSTEM WATCHDOG TIMEOUT!                    ║");
            log.error("  ║  No ping for {}ms (threshold={}ms)               ║", elapsed, watchdogTimeoutMs);
            log.error("  ║  Triggering FORCED SYSTEM RESET...                         ║");
            log.error("  ╚══════════════════════════════════════════════════════════════╝");

            SemanticEtw.getInstance().logEvent("WATCHDOG", "SYSTEM_TIMEOUT",
                    "elapsed=" + elapsed + "ms threshold=" + watchdogTimeoutMs + "ms");

            // 触发系统重置
            systemReset();
        }
    }

    /**
     * 系统重置 — 强制中断所有阻塞的 Agent，重置调度器。
     * <p>
     * 类比嵌入式系统的硬件 Reset 信号：
     * <ol>
     *   <li>生成系统 Trace（所有活跃 Agent 的状态快照）</li>
     *   <li>杀掉所有阻塞的 Agent 网络请求</li>
     *   <li>重置调度器</li>
     *   <li>广播紧急停机事件</li>
     * </ol>
     */
    private void systemReset() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [WATCHDOG] SYSTEM RESET IN PROGRESS...                    ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        // ── Step 1: 生成系统 Trace ──
        String trace = generateSystemTrace();
        log.error("[WatchdogDaemon] System Trace:\n{}", trace);

        // ── Step 2: 杀掉所有阻塞的 Agent ──
        if (taskScheduler != null) {
            Map<Integer, AgentTask> tasks = taskScheduler.activeTasks();
            int killedCount = 0;

            for (Map.Entry<Integer, AgentTask> entry : tasks.entrySet()) {
                AgentTask task = entry.getValue();
                if (task.status() == AgentTask.TaskStatus.RUNNING
                        || task.status() == AgentTask.TaskStatus.BLOCKED) {
                    task.setStatus(AgentTask.TaskStatus.KILLED);
                    task.cancel();
                    taskScheduler.cancelAgent(task.pid());
                    killedCount++;
                }
            }

            log.warn("[WatchdogDaemon] Force-killed {} blocked agents", killedCount);
        }

        // ── Step 3: 广播紧急停机 ──
        try {
            EventBus.instance().broadcast("emergency_halt",
                    "[WATCHDOG] SYSTEM RESET — No ping for " + watchdogTimeoutMs + "ms. "
                    + "All blocked agents killed. Scheduler reset required.");
        } catch (Exception e) {
            log.debug("[WatchdogDaemon] EventBus broadcast failed: {}", e.getMessage());
        }

        // ── Step 4: 重置喂狗计时器 ──
        lastPingTimeMs = System.currentTimeMillis();

        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [WATCHDOG] SYSTEM RESET COMPLETE                          ║");
        System.out.println("  ║  System is now in recovery mode.                           ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
    }

    /**
     * 生成系统 Trace — 所有活跃 Agent 的状态快照。
     */
    private String generateSystemTrace() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ AIOS System Trace (Watchdog Triggered) ═══\n");
        sb.append("Timestamp: ").append(System.currentTimeMillis()).append("\n");
        sb.append("Watchdog Timeout: ").append(watchdogTimeoutMs).append("ms\n");
        sb.append("Last Ping: ").append(lastPingTimeMs).append("\n\n");

        sb.append("── Ping Sources ──\n");
        for (Map.Entry<String, Long> entry : pingSources.entrySet()) {
            long elapsed = System.currentTimeMillis() - entry.getValue();
            String status = elapsed < watchdogTimeoutMs ? "ALIVE" : "DEAD";
            sb.append(String.format("  %-20s lastPing=%dms ago [%s]%n",
                    entry.getKey(), elapsed, status));
        }

        if (taskScheduler != null) {
            sb.append("\n── Active Tasks ──\n");
            Map<Integer, AgentTask> tasks = taskScheduler.activeTasks();
            for (Map.Entry<Integer, AgentTask> entry : tasks.entrySet()) {
                AgentTask task = entry.getValue();
                sb.append(String.format("  PID=%d status=%s priority=%s%n",
                        task.pid(), task.status(), task.processPriority()));
            }
        }

        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  任务级 Deadline 检查
    // ════════════════════════════════════════════════════════════════

    private void checkDeadlines() {
        long now = System.currentTimeMillis();
        Map<Integer, AgentTask> tasks = taskScheduler.activeTasks();

        for (Map.Entry<Integer, AgentTask> entry : tasks.entrySet()) {
            AgentTask task = entry.getValue();

            if (task.status() != AgentTask.TaskStatus.RUNNING) continue;

            long deadline = task.deadlineMs();
            if (deadline <= 0) continue;

            if (now > deadline) {
                hardKill(task, now, deadline);
            }
        }
    }

    private void hardKill(AgentTask task, long now, long deadline) {
        int pid = task.pid();
        long overshootMs = now - deadline;

        log.warn("[WatchdogDaemon] DEADLINE EXCEEDED! Agent#{} overshoot={}ms",
                pid, overshootMs);

        // 1. 标记为 DEADLINE_EXCEEDED
        task.setStatus(AgentTask.TaskStatus.DEADLINE_EXCEEDED);
        task.cancel();

        // 2. 中断虚拟线程
        taskScheduler.cancelAgent(pid);

        // 3. 广播紧急停机
        try {
            EventBus.instance().broadcast("emergency_halt",
                    String.format("[RTOS] EMERGENCY HALT! Agent#%d exceeded deadline by %dms",
                            pid, overshootMs));
        } catch (Exception e) {
            log.debug("[WatchdogDaemon] EventBus broadcast failed: {}", e.getMessage());
        }

        totalKills.incrementAndGet();
        log.info("[WatchdogDaemon] Hard kill complete for Agent#{}. Total kills: {}",
                pid, totalKills.get());
    }

    // ════════════════════════════════════════════════════════════════
    //  配置与统计
    // ════════════════════════════════════════════════════════════════

    /**
     * 设置看门狗超时阈值。
     *
     * @param timeoutMs 超时毫秒数
     */
    public void setWatchdogTimeout(long timeoutMs) {
        this.watchdogTimeoutMs = timeoutMs;
        log.info("[WatchdogDaemon] Timeout updated: {}ms", timeoutMs);
    }

    public long getWatchdogTimeout() {
        return watchdogTimeoutMs;
    }

    public boolean isRunning() {
        return running;
    }

    public long totalKills() {
        return totalKills.get();
    }

    public long totalSystemResets() {
        return totalSystemResets.get();
    }

    public long totalPings() {
        return totalPings.get();
    }

    /**
     * 距上次喂狗的毫秒数。
     */
    public long msSinceLastPing() {
        return System.currentTimeMillis() - lastPingTimeMs;
    }

    /**
     * 系统是否处于健康状态（最近喂狗未超时）。
     */
    public boolean isSystemHealthy() {
        return msSinceLastPing() < watchdogTimeoutMs;
    }

    public String getStatsReport() {
        return """
                ┌─ WatchdogDaemon Stats ──────────────────────────────
                │  Running             : %s
                │  System Healthy      : %s
                │  Last Ping           : %dms ago
                │  Watchdog Timeout    : %dms
                │  Total Pings         : %d
                │  Total Kills         : %d
                │  Total System Resets : %d
                │  Ping Sources        : %s
                └─────────────────────────────────────────────────"""
                .formatted(running, isSystemHealthy(), msSinceLastPing(),
                        watchdogTimeoutMs, totalPings.get(), totalKills.get(),
                        totalSystemResets.get(), pingSources.keySet());
    }
}
