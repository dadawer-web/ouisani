package com.ouisani.aios.core.tick;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Tick 睡眠注册表 — AIOS 的 sys_nanosleep 实现。
 * <p>
 * 在 Linux 中，进程调用 nanosleep() 时，内核将进程挂入定时器
 * 队列（timer wheel / hrtimer），当指定的时间到期后，内核向
 * 进程发送 SIGALRM 信号将其唤醒。
 * <p>
 * TickSleepRegistry 实现了完全相同的机制，但以 AIOS 的 Tick
 * 为时间单位：
 * <ul>
 *   <li>Agent 调用 {@link SystemTickGenerator#sysNanosleep(int, long)}
 *       注册一个"睡到第 N 个 Tick"的请求</li>
 *   <li>每个 Tick 到来时，{@link #onTick(long)} 检查是否有 Agent
 *       应该被唤醒</li>
 *   <li>唤醒时向 Agent 发送 {@link SignalType#SIG_ALRM} 信号</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>Linux</th><th>AIOS</th><th>说明</th></tr>
 *   <tr><td>nanosleep()</td><td>sysNanosleep()</td><td>进程挂起</td></tr>
 *   <tr><td>hrtimer</td><td>TickSleepRegistry</td><td>定时器队列</td></tr>
 *   <tr><td>SIGALRM</td><td>SIG_ALRM</td><td>唤醒信号</td></tr>
 *   <tr><td>jiffies</td><td>currentTick</td><td>时间单位</td></tr>
 * </table>
 *
 * <h3>数据结构</h3>
 * 使用 {@link ConcurrentSkipListMap} 以目标 Tick 为 key 排序，
 * 每个 Tick 对应一组等待唤醒的 PID。这样 onTick() 只需 O(log N)
 * 查找当前 Tick 是否有睡眠者，而非遍历全部注册项。
 *
 * @see SystemTickGenerator
 * @see SignalType#SIG_ALRM
 */
public final class TickSleepRegistry {

    private static final Logger log = LoggerFactory.getLogger(TickSleepRegistry.class);

    /** 按目标 Tick 排序的定时器队列 — 类似 Linux 的 timer wheel */
    private final ConcurrentSkipListMap<Long, Set<Integer>> timerWheel = new ConcurrentSkipListMap<>();

    /** PID → 目标 Tick 的反向索引，用于取消操作 */
    private final ConcurrentHashMap<Integer, Long> pidToWakeTick = new ConcurrentHashMap<>();

    /** 对 SystemTickGenerator 的引用（用于获取 TaskScheduler） */
    private final SystemTickGenerator tickGenerator;

    /** 统计：总共唤醒的 Agent 数 */
    private long totalWoken = 0;

    TickSleepRegistry(SystemTickGenerator tickGenerator) {
        this.tickGenerator = tickGenerator;
    }

    // ════════════════════════════════════════════════════════════════
    //  注册 / 取消睡眠
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册一个 Agent 的睡眠请求。
     *
     * @param pid        睡眠的 Agent PID
     * @param wakeAtTick 应该被唤醒的 Tick 号
     */
    public void registerSleep(int pid, long wakeAtTick) {
        // 如果该 PID 已经注册了睡眠，先取消旧的
        Long oldTick = pidToWakeTick.put(pid, wakeAtTick);
        if (oldTick != null) {
            removeFromTimerWheel(pid, oldTick);
        }

        // 加入 timer wheel
        timerWheel.computeIfAbsent(wakeAtTick, k -> ConcurrentHashMap.newKeySet()).add(pid);

        log.debug("[TickSleep] Registered: pid={}, wakeAtTick={}", pid, wakeAtTick);
    }

    /**
     * 取消一个 Agent 的睡眠注册。
     *
     * @param pid 要取消的 Agent PID
     */
    public void cancelSleep(int pid) {
        Long wakeTick = pidToWakeTick.remove(pid);
        if (wakeTick != null) {
            removeFromTimerWheel(pid, wakeTick);
            log.debug("[TickSleep] Cancelled: pid={}, was wakeAtTick={}", pid, wakeTick);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Tick 驱动 — 每个 Tick 调用一次
    // ════════════════════════════════════════════════════════════════

    /**
     * 每个 Tick 到来时由 {@link SystemTickGenerator} 调用。
     * <p>
     * 检查当前 Tick 是否有应该被唤醒的 Agent，如果有，
     * 向它们发送 {@link SignalType#SIG_ALRM} 信号。
     *
     * @param currentTick 当前 Tick 号
     * @return 本轮唤醒的 Agent 数量
     */
    public int onTick(long currentTick) {
        int wokenCount = 0;

        // 取出所有 <= currentTick 的定时器（到期或过期）
        NavigableMap<Long, Set<Integer>> expired = timerWheel.headMap(currentTick, true);

        List<Long> ticksToRemove = new ArrayList<>();

        for (Map.Entry<Long, Set<Integer>> entry : expired.entrySet()) {
            long wakeTick = entry.getKey();
            Set<Integer> pids = entry.getValue();

            for (int pid : pids) {
                // 发送 SIG_ALRM 唤醒信号
                boolean delivered = deliverAlarm(pid, wakeTick, currentTick);
                if (delivered) {
                    wokenCount++;
                    totalWoken++;
                }

                // 清理反向索引
                pidToWakeTick.remove(pid, wakeTick);
            }

            ticksToRemove.add(wakeTick);
        }

        // 清理已处理的定时器槽位
        for (Long tick : ticksToRemove) {
            timerWheel.remove(tick);
        }

        if (wokenCount > 0) {
            log.info("[TickSleep] Tick #{}: woke {} agents (total woken: {})",
                    currentTick, wokenCount, totalWoken);
        }

        return wokenCount;
    }

    /**
     * 向指定 Agent 发送 SIG_ALRM 唤醒信号。
     */
    private boolean deliverAlarm(int pid, long wakeTick, long currentTick) {
        TaskScheduler scheduler = getTaskScheduler();
        if (scheduler == null) {
            log.warn("[TickSleep] Cannot deliver SIG_ALRM: TaskScheduler not available, pid={}", pid);
            return false;
        }

        AgentTask task = scheduler.getTask(pid);
        if (task == null) {
            log.debug("[TickSleep] PID {} 不再活跃，跳过 SIG_ALRM", pid);
            pidToWakeTick.remove(pid);
            return false;
        }

        // 发送 SIG_ALRM 信号
        task.sendSignal(SignalType.SIG_ALRM);

        long delay = currentTick - wakeTick;
        if (delay > 0) {
            log.warn("[TickSleep] SIG_ALRM delivered with {} tick delay: pid={}, wakeTick={}, currentTick={}",
                    delay, pid, wakeTick, currentTick);
        } else {
            log.debug("[TickSleep] SIG_ALRM delivered on time: pid={}, tick={}", pid, currentTick);
        }

        SemanticEtw.getInstance().logEvent("SYSTICK", "SIG_ALRM",
                "pid=" + pid + " wakeTick=" + wakeTick + " currentTick=" + currentTick
                + " delay=" + delay);

        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  查询
    // ════════════════════════════════════════════════════════════════

    /** 当前等待唤醒的 Agent 数量 */
    public int pendingCount() {
        return pidToWakeTick.size();
    }

    /** 查询指定 PID 的唤醒 Tick，如果未注册返回 -1 */
    public long getWakeTick(int pid) {
        return pidToWakeTick.getOrDefault(pid, -1L);
    }

    /** 获取所有等待唤醒的 PID 及其目标 Tick */
    public Map<Integer, Long> getPendingSleepers() {
        return Collections.unmodifiableMap(pidToWakeTick);
    }

    /** 总共唤醒的 Agent 数 */
    public long totalWoken() {
        return totalWoken;
    }

    // ── 内部辅助 ──

    private void removeFromTimerWheel(int pid, long wakeTick) {
        Set<Integer> pids = timerWheel.get(wakeTick);
        if (pids != null) {
            pids.remove(pid);
            if (pids.isEmpty()) {
                timerWheel.remove(wakeTick);
            }
        }
    }

    private TaskScheduler getTaskScheduler() {
        try {
            return com.ouisani.aios.core.VfsManager.instance().getTaskScheduler();
        } catch (Exception e) {
            return null;
        }
    }
}
