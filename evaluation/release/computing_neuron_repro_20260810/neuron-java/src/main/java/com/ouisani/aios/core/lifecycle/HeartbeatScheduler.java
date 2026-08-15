package com.ouisani.aios.core.lifecycle;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.tick.SystemTickGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 心跳调度器 — Agent 的生命节律控制器。
 * 借鉴 Paperclip 的 Heartbeat System + Linux 的定时器中断调度。
 *
 * 核心理念：Agent 不是持续运行的，而是按心跳唤醒——
 * 每次唤醒检查工作、执行任务、然后退出。
 * 这大幅降低了空闲 Agent 的资源消耗。
 *
 * OS 类比：
 *   HeartbeatScheduler = 内核的 schedule() + timer interrupt
 *   WakeupRequest      = POSIX timer_create() + timer_settime()
 *   WakeupReason       = 信号类型（SIGALRM/SIGUSR1/SIGUSR2...）
 *
 * 唤醒原因（对标 Paperclip）：
 *   ON_DEMAND             — 手动触发（类比 kill -SIGCONT）
 *   ISSUE_ASSIGNED        — 任务被分配（类比 SIGUSR1）
 *   ISSUE_COMMENTED       — 任务被评论（类比 SIGUSR2）
 *   BLOCKERS_RESOLVED     — 阻塞任务已解决（类比 SIGIO）
 *   CHILDREN_COMPLETED    — 子任务完成（类比 SIGCHLD）
 *   APPROVAL_RESOLVED     — 审批已通过（类比 SIGPIPE）
 *   ROUTINE_EXECUTION     — 例行任务执行（类比 SIGALRM）
 *   SCHEDULED_RETRY       — 计划重试（类比定时器到期）
 */
public final class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    // 瞬态重试延迟（对标 Paperclip 的 BOUNDED_TRANSIENT_RETRY_DELAYS）
    private static final long[] RETRY_DELAYS_MS = {2 * 60_000, 10 * 60_000, 30 * 60_000, 2 * 3600_000};
    private static final int MAX_RETRY_ATTEMPTS = 4;
    private static final double RETRY_JITTER = 0.25; // 25% 抖动

    private static final HeartbeatScheduler INSTANCE = new HeartbeatScheduler();

    // 唤醒请求队列：agentId → 按时间排序的唤醒请求
    private final ConcurrentHashMap<String, PriorityBlockingQueue<WakeupRequest>> wakeupQueues = new ConcurrentHashMap<>();

    // Agent 心跳间隔配置：agentId → 间隔毫秒
    private final ConcurrentHashMap<String, Long> heartbeatIntervals = new ConcurrentHashMap<>();

    // Agent 上次心跳时间：agentId → 上次完成时间戳
    private final ConcurrentHashMap<String, Long> lastHeartbeatTime = new ConcurrentHashMap<>();

    // 重试计数：agentId → 重试次数
    private final ConcurrentHashMap<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

    // 心跳运行记录：runId → HeartbeatRun
    private final ConcurrentHashMap<String, HeartbeatRun> activeRuns = new ConcurrentHashMap<>();

    // 统计
    private final AtomicLong totalWakeups = new AtomicLong(0);
    private final AtomicLong totalExecutions = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicLong totalRetries = new AtomicLong(0);

    // 心跳执行线程池
    private ExecutorService heartbeatExecutor;

    private volatile boolean running = false;

    private HeartbeatScheduler() {}

    public static HeartbeatScheduler instance() { return INSTANCE; }

    // ═══════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════

    /**
     * 启动心跳调度器。
     * 订阅 SystemTick 的 SIG_TICK 信号，在每个 tick 中扫描唤醒队列。
     */
    public void start() {
        if (running) return;
        running = true;

        heartbeatExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 订阅系统 tick，驱动心跳调度
        EventBus.instance().subscribe("sig_tick", payload -> {
            if (running) onTick();
        });

        log.info("[Heartbeat] 调度器已启动，由 SystemTick 驱动");
        System.out.println("  💓 [Heartbeat] 调度器已启动 — Agent 将按需唤醒");
    }

    public void stop() {
        running = false;
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        log.info("[Heartbeat] 调度器已停止。");
    }

    // ═══════════════════════════════════════════════════════════
    // 唤醒请求管理
    // ═══════════════════════════════════════════════════════════

    /**
     * 提交唤醒请求 — 将 Agent 加入待唤醒队列。
     * 类比 Linux：向进程发送信号，将其从 INTERRUPTIBLE 唤醒到 RUNNABLE。
     */
    public void requestWakeup(String agentId, WakeupReason reason, String detail) {
        AgentLifecycleManager lcm = AgentLifecycleManager.instance();
        AgentState state = lcm.getState(agentId);

        // 只有 IDLE 状态的 Agent 才能被唤醒
        if (state != AgentState.IDLE) {
            log.debug("[Heartbeat] 唤醒已跳过: Agent {} 状态为 {} (非 IDLE)", agentId, state);
            return;
        }

        // 检查指挥链健康
        if (!lcm.isOrgChainHealthy(agentId)) {
            log.warn("[Heartbeat] 唤醒已跳过: Agent {} 指挥链断裂", agentId);
            return;
        }

        WakeupRequest request = new WakeupRequest(agentId, reason, detail, System.currentTimeMillis());
        wakeupQueues.computeIfAbsent(agentId, k -> new PriorityBlockingQueue<>()).add(request);

        log.info("[Heartbeat] 唤醒请求已提交: agent={}, reason={}, detail={}",
                agentId, reason.label(), detail);
    }

    /**
     * 注册 Agent 的心跳间隔。
     * @param intervalMs 心跳间隔毫秒，0 表示仅按需唤醒
     */
    public void registerAgent(String agentId, long intervalMs) {
        heartbeatIntervals.put(agentId, intervalMs);
        lastHeartbeatTime.put(agentId, System.currentTimeMillis());
        log.info("[Heartbeat] Agent 已注册: id={}, interval={}ms", agentId, intervalMs);
    }

    /** 注销 Agent */
    public void unregisterAgent(String agentId) {
        heartbeatIntervals.remove(agentId);
        lastHeartbeatTime.remove(agentId);
        wakeupQueues.remove(agentId);
        retryCount.remove(agentId);
    }

    // ═══════════════════════════════════════════════════════════
    // Tick 驱动的心跳调度
    // ═══════════════════════════════════════════════════════════

    /**
     * 每个 SystemTick 触发一次调度扫描。
     * 类比 Linux：timer_interrupt() → update_process_times() → schedule()
     *
     * 职责：
     *   1. 扫描到期的心跳间隔唤醒
     *   2. 处理显式唤醒请求
     *   3. 推进到期重试
     *   4. 清理孤儿运行
     */
    private void onTick() {
        if (!running) return;

        long now = System.currentTimeMillis();

        // Phase 1: 检查定时心跳到期的 Agent
        for (Map.Entry<String, Long> entry : heartbeatIntervals.entrySet()) {
            String agentId = entry.getKey();
            long interval = entry.getValue();
            if (interval <= 0) continue; // 仅按需唤醒

            Long lastTime = lastHeartbeatTime.get(agentId);
            if (lastTime != null && (now - lastTime) >= interval) {
                AgentState state = AgentLifecycleManager.instance().getState(agentId);
                if (state == AgentState.IDLE) {
                    requestWakeup(agentId, WakeupReason.SCHEDULED, "周期性心跳间隔已到期");
                }
            }
        }

        // Phase 2: 执行待唤醒队列中的请求
        List<WakeupRequest> toExecute = new ArrayList<>();
        for (Map.Entry<String, PriorityBlockingQueue<WakeupRequest>> entry : wakeupQueues.entrySet()) {
            WakeupRequest request = entry.getValue().poll();
            if (request != null) {
                toExecute.add(request);
            }
        }

        // Phase 3: 异步执行唤醒
        for (WakeupRequest request : toExecute) {
            heartbeatExecutor.submit(() -> executeHeartbeat(request));
        }

        // Phase 4: 清理超时的孤儿运行（5 分钟阈值）
        reapOrphanedRuns(now, 5 * 60_000);
    }

    // ═══════════════════════════════════════════════════════════
    // 心跳执行
    // ═══════════════════════════════════════════════════════════

    /**
     * 执行一次心跳 — 唤醒 Agent 执行任务。
     * 类比 Linux：context_switch() 到目标进程。
     */
    private void executeHeartbeat(WakeupRequest request) {
        String agentId = request.agentId();
        AgentLifecycleManager lcm = AgentLifecycleManager.instance();

        // 状态转换：IDLE → RUNNING
        boolean activated = lcm.activate(agentId, "Heartbeat: " + request.reason().label());
        if (!activated) {
            log.warn("[Heartbeat] Agent {} 激活失败: state={}", agentId, lcm.getState(agentId));
            return;
        }

        String runId = "hb_" + agentId + "_" + System.currentTimeMillis();
        HeartbeatRun run = new HeartbeatRun(runId, agentId, request.reason(), Instant.now());
        activeRuns.put(runId, run);

        totalWakeups.incrementAndGet();
        totalExecutions.incrementAndGet();

        log.info("[Heartbeat] 正在执行心跳: agent={}, reason={}, runId={}",
                agentId, request.reason().label(), runId);

        try {
            // 广播心跳开始事件
            EventBus.instance().broadcast("agent.heartbeat",
                    "{\"event\":\"started\",\"agentId\":\"" + agentId + "\",\"runId\":\"" + runId
                            + "\",\"reason\":\"" + request.reason().label() + "\"}");

            // 查找 Agent 实例并投递任务邮件
            com.ouisani.aios.core.team.TeamRegistry teamRegistry = com.ouisani.aios.core.team.TeamRegistry.getInstance();
            com.ouisani.aios.user.sdk.AbstractAgent agent = teamRegistry.findAgent(agentId);

            if (agent != null) {
                // 通过邮箱投递心跳任务
                com.ouisani.aios.core.team.MailMessage heartbeatMail = new com.ouisani.aios.core.team.MailMessage(
                        "HeartbeatScheduler", agentId,
                        com.ouisani.aios.core.team.MailMessage.MessageType.TASK_ASSIGN,
                        request
                );
                teamRegistry.dispatch(heartbeatMail);
                run.status = HeartbeatRun.Status.SUCCEEDED;
            } else {
                // Agent 不在线，标记为失败
                log.warn("[Heartbeat] Agent {} 在 TeamRegistry 中未找到，标记运行为失败", agentId);
                run.status = HeartbeatRun.Status.FAILED;
                run.error = "Agent 不在线";
                onHeartbeatFailure(run);
            }

        } catch (Exception e) {
            run.status = HeartbeatRun.Status.FAILED;
            run.error = e.getMessage();
            onHeartbeatFailure(run);
        } finally {
            run.finishedAt = Instant.now();
            activeRuns.remove(runId);

            // 状态转换：RUNNING → IDLE（如果还在 RUNNING 状态）
            AgentState currentState = lcm.getState(agentId);
            if (currentState == AgentState.RUNNING) {
                if (run.status == HeartbeatRun.Status.SUCCEEDED) {
                    lcm.deactivate(agentId);
                }
                // FAILED 状态由 onHeartbeatFailure 处理
            }

            lastHeartbeatTime.put(agentId, System.currentTimeMillis());

            // 广播心跳完成事件
            EventBus.instance().broadcast("agent.heartbeat",
                    "{\"event\":\"finished\",\"agentId\":\"" + agentId + "\",\"runId\":\"" + runId
                            + "\",\"status\":\"" + run.status.name() + "\"}");
        }
    }

    /**
     * 心跳失败处理 — 瞬态重试策略。
     * 对标 Paperclip 的 BOUNDED_TRANSIENT_HEARTBEAT_RETRY。
     */
    private void onHeartbeatFailure(HeartbeatRun run) {
        String agentId = run.agentId;
        int attempts = retryCount.computeIfAbsent(agentId, k -> new AtomicInteger(0)).incrementAndGet();

        if (attempts <= MAX_RETRY_ATTEMPTS) {
            // 计算重试延迟（带抖动）
            int delayIndex = Math.min(attempts - 1, RETRY_DELAYS_MS.length - 1);
            long baseDelay = RETRY_DELAYS_MS[delayIndex];
            long jitter = (long) (baseDelay * RETRY_JITTER * (Math.random() * 2 - 1));
            long delay = Math.max(1000, baseDelay + jitter);

            // 安排计划重试
            scheduleRetry(agentId, delay);
            totalRetries.incrementAndGet();

            log.info("[Heartbeat] 瞬态重试 #{}/{}: Agent {} 将在 {}ms 后重试",
                    attempts, MAX_RETRY_ATTEMPTS, agentId, delay);
        } else {
            // 超过最大重试次数，标记为 ERROR
            AgentLifecycleManager.instance().markError(agentId,
                    "Heartbeat failed after " + MAX_RETRY_ATTEMPTS + " retries: " + run.error);
            retryCount.remove(agentId);
            totalFailures.incrementAndGet();

            log.error("[Heartbeat] Agent {} 超过最大重试次数，已标记为 ERROR", agentId);
        }
    }

    /** 安排延迟重试 */
    private void scheduleRetry(String agentId, long delayMs) {
        // 使用 CompletableFuture 延迟投递唤醒请求
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    // 先恢复到 IDLE 状态
                    AgentLifecycleManager lcm = AgentLifecycleManager.instance();
                    AgentState state = lcm.getState(agentId);
                    if (state == AgentState.ERROR || state == AgentState.RUNNING) {
                        lcm.clearError(agentId);
                    }
                    requestWakeup(agentId, WakeupReason.SCHEDULED_RETRY, "失败后重试");
                });
    }

    /** 清理孤儿运行（类比 Paperclip 的 reapOrphanedRuns） */
    private void reapOrphanedRuns(long now, long thresholdMs) {
        Iterator<Map.Entry<String, HeartbeatRun>> it = activeRuns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, HeartbeatRun> entry = it.next();
            HeartbeatRun run = entry.getValue();
            long elapsed = now - run.startedAt.toEpochMilli();
            if (elapsed > thresholdMs) {
                log.warn("[Heartbeat] 孤儿运行已回收: runId={}, agent={}, elapsed={}ms",
                        entry.getKey(), run.agentId, elapsed);
                run.status = HeartbeatRun.Status.TIMED_OUT;
                AgentLifecycleManager.instance().markError(run.agentId, "Heartbeat 超时");
                it.remove();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 查询与统计
    // ═══════════════════════════════════════════════════════════

    public int getActiveRunCount() { return activeRuns.size(); }
    public long getTotalWakeups() { return totalWakeups.get(); }
    public long getTotalExecutions() { return totalExecutions.get(); }
    public long getTotalFailures() { return totalFailures.get(); }
    public long getTotalRetries() { return totalRetries.get(); }

    public Map<String, Object> getStatsReport() {
        return Map.of(
                "totalWakeups", totalWakeups.get(),
                "totalExecutions", totalExecutions.get(),
                "totalFailures", totalFailures.get(),
                "totalRetries", totalRetries.get(),
                "activeRuns", activeRuns.size(),
                "registeredAgents", heartbeatIntervals.size()
        );
    }

    // ═══════════════════════════════════════════════════════════
    // 内部数据结构
    // ═══════════════════════════════════════════════════════════

    /** 唤醒原因 */
    public enum WakeupReason {
        ON_DEMAND("on_demand", 0),
        ISSUE_ASSIGNED("issue_assigned", 10),
        ISSUE_COMMENTED("issue_commented", 20),
        BLOCKERS_RESOLVED("blockers_resolved", 30),
        CHILDREN_COMPLETED("children_completed", 40),
        APPROVAL_RESOLVED("approval_resolved", 50),
        ROUTINE_EXECUTION("routine_execution", 60),
        SCHEDULED("scheduled", 70),
        SCHEDULED_RETRY("scheduled_retry", 80);

        private final String label;
        private final int priority; // 数值越小优先级越高

        WakeupReason(String label, int priority) {
            this.label = label;
            this.priority = priority;
        }

        public String label() { return label; }
        public int priority() { return priority; }
    }

    /** 唤醒请求 */
    public record WakeupRequest(String agentId, WakeupReason reason, String detail, long createdAt)
            implements Comparable<WakeupRequest> {
        @Override
        public int compareTo(WakeupRequest other) {
            // 优先级高的（数值小的）排前面
            return Integer.compare(this.reason.priority(), other.reason.priority());
        }
    }

    /** 心跳运行记录 */
    public static class HeartbeatRun {
        public enum Status { RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED }

        private final String runId;
        private final String agentId;
        private final WakeupReason reason;
        private final Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Status status = Status.RUNNING;
        private volatile String error;

        public HeartbeatRun(String runId, String agentId, WakeupReason reason, Instant startedAt) {
            this.runId = runId;
            this.agentId = agentId;
            this.reason = reason;
            this.startedAt = startedAt;
        }

        public String runId() { return runId; }
        public String agentId() { return agentId; }
        public WakeupReason reason() { return reason; }
        public Instant startedAt() { return startedAt; }
        public Instant finishedAt() { return finishedAt; }
        public Status status() { return status; }
        public String error() { return error; }
    }
}
