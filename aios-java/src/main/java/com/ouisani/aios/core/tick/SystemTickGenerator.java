package com.ouisani.aios.core.tick;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统节拍发生器 — AIOS 的硬件晶振 (SysTick Timer)。
 * <p>
 * 在真实的 SoC 中，SysTick 是一颗硬件定时器，它以固定的频率
 * 产生中断，驱动整个操作系统的"时间流逝"：调度器的时间片、
 * 定时器的到期、RCU 的宽限期、watchdog 的心跳……一切时间
 * 相关的机制都源于这颗跳动的晶振。
 * <p>
 * AIOS 的 SystemTickGenerator 扮演完全相同的角色：
 * <ul>
 *   <li>每隔固定的真实时间（默认 60 秒），产生一个 {@link SignalType#SIG_TICK} 中断</li>
 *   <li>向 EventBus 广播 {@code "sig_tick"} 事件</li>
 *   <li>向所有活跃 AgentTask 发送 SIG_TICK 信号</li>
 *   <li>驱动 {@link TickSleepRegistry} 检查是否有睡眠中的 Agent 应该被唤醒</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>硬件/OS</th><th>AIOS</th><th>说明</th></tr>
 *   <tr><td>ARM SysTick</td><td>SystemTickGenerator</td><td>硬件节拍定时器</td></tr>
 *   <tr><td>jiffies</td><td>currentTick</td><td>系统启动以来的节拍数</td></tr>
 *   <tr><td>HZ (100/250/1000)</td><td>tickIntervalMs</td><td>节拍频率</td></tr>
 *   <tr><td>timer_interrupt()</td><td>onTick()</td><td>节拍中断处理</td></tr>
 *   <tr><td>SIGALRM</td><td>SIG_ALRM</td><td>定时器到期信号</td></tr>
 * </table>
 *
 * @see TickSleepRegistry
 * @see SignalType#SIG_TICK
 * @see SignalType#SIG_ALRM
 */
public final class SystemTickGenerator {

    private static final Logger log = LoggerFactory.getLogger(SystemTickGenerator.class);

    // ── 默认配置 ──

    /** 默认节拍间隔：60 秒（1 分钟） */
    private static final long DEFAULT_TICK_INTERVAL_MS = 60_000L;

    // ── Singleton ──

    private static final class Holder {
        static final SystemTickGenerator INSTANCE = new SystemTickGenerator();
    }

    public static SystemTickGenerator instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    /** 系统启动以来的节拍数（jiffies） */
    private final AtomicLong currentTick = new AtomicLong(0);

    /** 系统启动时间戳 */
    private volatile long bootTimeMs;

    /** 节拍间隔（毫秒），可配置 */
    private volatile long tickIntervalMs = DEFAULT_TICK_INTERVAL_MS;

    /** 是否正在运行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 节拍调度器 */
    private ScheduledExecutorService tickScheduler;

    /** Tick Sleep 注册表 — 管理 sys_nanosleep 的 Agent 唤醒 */
    private final TickSleepRegistry sleepRegistry;

    /** 上一次节拍的时间戳（用于计算漂移） */
    private volatile long lastTickTimeMs;

    // ── 统计 ──

    private final AtomicLong totalTicks = new AtomicLong(0);
    private final AtomicLong totalSignalsSent = new AtomicLong(0);
    private final AtomicLong totalAgentsWoken = new AtomicLong(0);
    private final AtomicLong maxTickDriftMs = new AtomicLong(0);

    private SystemTickGenerator() {
        this.sleepRegistry = new TickSleepRegistry(this);
    }

    // ════════════════════════════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动系统节拍发生器 — 上电晶振。
     * <p>
     * 类比：SoC 上电后，晶振起振，SysTick 开始计数。
     */
    public void start() {
        start(DEFAULT_TICK_INTERVAL_MS);
    }

    /**
     * 以自定义节拍间隔启动。
     *
     * @param intervalMs 节拍间隔（毫秒），必须 > 0
     */
    public void start(long intervalMs) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[SysTick] Already running, ignoring start()");
            return;
        }

        this.tickIntervalMs = Math.max(1000L, intervalMs); // 最低 1 秒
        this.bootTimeMs = System.currentTimeMillis();
        this.lastTickTimeMs = bootTimeMs;

        tickScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aios-systick");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY); // 最高优先级 — 这是硬件中断
            return t;
        });

        // 固定速率调度 — 类似硬件定时器的周期性中断
        tickScheduler.scheduleAtFixedRate(
                this::onTick,
                tickIntervalMs,  // 初始延迟
                tickIntervalMs,  // 周期
                TimeUnit.MILLISECONDS
        );

        log.info("[SysTick] ╔══════════════════════════════════════════════════╗");
        log.info("[SysTick] ║  System Tick Generator STARTED                   ║");
        log.info("[SysTick] ║  Interval: {}ms ({}s)                          ║",
                tickIntervalMs, tickIntervalMs / 1000);
        log.info("[SysTick] ║  The system now has a heartbeat.               ║");
        log.info("[SysTick] ╚══════════════════════════════════════════════════╝");

        System.out.printf("  \u001B[36m[SysTick] Heartbeat started: interval=%dms — the system can now feel time.%n\u001B[0m",
                tickIntervalMs);

        SemanticEtw.getInstance().logEvent("SYSTICK", "START",
                "intervalMs=" + tickIntervalMs);
    }

    /**
     * 停止系统节拍发生器 — 晶振停振。
     * <p>
     * 类比：SoC 进入深度休眠，SysTick 停止计数。
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (tickScheduler != null) {
            tickScheduler.shutdown();
            try {
                if (!tickScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    tickScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                tickScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("[SysTick] Stopped. Total ticks: {}, uptime: {}ms",
                totalTicks.get(), System.currentTimeMillis() - bootTimeMs);

        SemanticEtw.getInstance().logEvent("SYSTICK", "STOP",
                "totalTicks=" + totalTicks.get());
    }

    // ════════════════════════════════════════════════════════════════
    //  节拍中断处理 — timer_interrupt()
    // ════════════════════════════════════════════════════════════════

    /**
     * 节拍中断处理函数 — AIOS 的 timer_interrupt()。
     * <p>
     * 每当硬件定时器触发，此方法被调用。它执行以下操作：
     * <ol>
     *   <li>递增 jiffies（currentTick）</li>
     *   <li>计算并记录节拍漂移</li>
     *   <li>向 EventBus 广播 "sig_tick" 事件</li>
     *   <li>向所有活跃 AgentTask 发送 SIG_TICK 信号</li>
     *   <li>驱动 TickSleepRegistry 检查唤醒</li>
     * </ol>
     */
    private void onTick() {
        if (!running.get()) return;

        long tick = currentTick.incrementAndGet();
        long now = System.currentTimeMillis();
        totalTicks.incrementAndGet();

        // ── 计算节拍漂移 ──
        long expectedTickTime = bootTimeMs + tick * tickIntervalMs;
        long drift = Math.abs(now - expectedTickTime);
        if (drift > maxTickDriftMs.get()) {
            maxTickDriftMs.set(drift);
        }
        lastTickTimeMs = now;

        // ── Phase 1: 广播 SIG_TICK 到 EventBus ──
        String tickPayload = String.format(
                "{\"tick\":%d,\"uptimeMs\":%d,\"driftMs\":%d,\"intervalMs\":%d}",
                tick, now - bootTimeMs, drift, tickIntervalMs);

        EventBus.instance().broadcast("sig_tick", tickPayload);

        // ── Phase 2: 向所有活跃 AgentTask 发送 SIG_TICK ──
        int signalCount = broadcastToActiveAgents(tick);

        // ── Phase 3: 驱动 TickSleepRegistry — 检查唤醒 ──
        int wokenCount = sleepRegistry.onTick(tick);

        totalSignalsSent.addAndGet(signalCount);
        totalAgentsWoken.addAndGet(wokenCount);

        // ── 日志（每 10 个 tick 输出一次详细日志，避免刷屏） ──
        if (tick % 10 == 0 || tick <= 3) {
            log.info("[SysTick] Tick #{}: uptime={}ms, drift={}ms, signals={}, woken={}",
                    tick, now - bootTimeMs, drift, signalCount, wokenCount);
        }

        // ── ETW 遥测 ──
        SemanticEtw.getInstance().logEvent("SYSTICK", "TICK",
                "tick=" + tick + " drift=" + drift + "ms signals=" + signalCount + " woken=" + wokenCount);
    }

    /**
     * 向所有活跃的 AgentTask 广播 SIG_TICK 信号。
     * <p>
     * 类比 Linux 的 timer_interrupt() 向所有 CPU 发送 IPI。
     */
    private int broadcastToActiveAgents(long tick) {
        TaskScheduler scheduler = getTaskScheduler();
        if (scheduler == null) return 0;

        int count = 0;
        for (AgentTask task : scheduler.activeTasks().values()) {
            task.sendSignal(SignalType.SIG_TICK);
            count++;
        }
        return count;
    }

    // ════════════════════════════════════════════════════════════════
    //  sys_nanosleep — Agent 主动时间意识
    // ════════════════════════════════════════════════════════════════

    /**
     * sys_nanosleep — 让当前 Agent 挂起指定的 Tick 数。
     * <p>
     * 类比 POSIX 的 nanosleep() 系统调用，但以 AIOS 的 Tick
     * 为单位而非纳秒。Agent 调用此方法后，会被注册到
     * {@link TickSleepRegistry}，当指定数量的 Tick 过去后，
     * 收到 {@link SignalType#SIG_ALRM} 信号被唤醒。
     *
     * <h3>使用示例</h3>
     * <pre>
     * // Agent 自我设置："挂起 60 个 Tick，然后醒来提醒用户喝水"
     * SystemTickGenerator.instance().sysNanosleep(myPid, 60);
     * // ... 60 个 Tick 后，Agent 收到 SIG_ALRM 信号
     * </pre>
     *
     * @param pid      请求睡眠的 Agent PID
     * @param tickCount 要睡眠的 Tick 数量
     * @return 唤醒时的目标 Tick 号
     */
    public long sysNanosleep(int pid, long tickCount) {
        long wakeAtTick = currentTick.get() + tickCount;
        sleepRegistry.registerSleep(pid, wakeAtTick);

        log.info("[SysTick] sys_nanosleep: pid={}, tickCount={}, wakeAtTick={}",
                pid, tickCount, wakeAtTick);

        SemanticEtw.getInstance().logEvent("SYSTICK", "NANOSLEEP",
                "pid=" + pid + " tickCount=" + tickCount + " wakeAt=" + wakeAtTick);

        return wakeAtTick;
    }

    /**
     * 取消一个 Agent 的睡眠注册。
     */
    public void cancelNanosleep(int pid) {
        sleepRegistry.cancelSleep(pid);
        log.info("[SysTick] nanosleep cancelled: pid={}", pid);
    }

    // ════════════════════════════════════════════════════════════════
    //  公共 API
    // ════════════════════════════════════════════════════════════════

    /** 当前 Tick 号（jiffies） */
    public long currentTick() {
        return currentTick.get();
    }

    /** 系统运行时间（毫秒） */
    public long uptimeMs() {
        return bootTimeMs > 0 ? System.currentTimeMillis() - bootTimeMs : 0;
    }

    /** 节拍间隔（毫秒） */
    public long tickIntervalMs() {
        return tickIntervalMs;
    }

    /** 是否正在运行 */
    public boolean isRunning() {
        return running.get();
    }

    /** 获取 TickSleepRegistry 引用 */
    public TickSleepRegistry sleepRegistry() {
        return sleepRegistry;
    }

    /**
     * 打印节拍统计报告。
     */
    public String getStatsReport() {
        long uptime = uptimeMs();
        long ticks = totalTicks.get();
        double actualHz = ticks > 0 && uptime > 0
                ? ticks * 1000.0 / uptime
                : 0;

        return """
                ┌─ SysTick Stats ──────────────────────────────────────
                │  Current Tick (jiffies) : %d
                │  Uptime                 : %dms (%.1fmin)
                │  Tick Interval          : %dms
                │  Actual Frequency       : %.3f Hz
                │  Max Drift              : %dms
                │  Total Signals Sent     : %d
                │  Total Agents Woken     : %d
                │  Pending Sleepers       : %d
                └─────────────────────────────────────────────────"""
                .formatted(
                        currentTick.get(),
                        uptime, uptime / 60_000.0,
                        tickIntervalMs,
                        actualHz,
                        maxTickDriftMs.get(),
                        totalSignalsSent.get(),
                        totalAgentsWoken.get(),
                        sleepRegistry.pendingCount());
    }

    // ── 内部辅助 ──

    private TaskScheduler getTaskScheduler() {
        try {
            return com.ouisani.aios.core.VfsManager.instance().getTaskScheduler();
        } catch (Exception e) {
            return null;
        }
    }
}
