package com.ouisani.aios.core;

import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.TokenOomException;
import com.ouisani.aios.core.cluster.SemanticRaftNode;
import com.ouisani.aios.core.crash.SemanticCrashAnalyzer;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.llm.SpeculativePredictor;
import com.ouisani.aios.core.memory.CognitiveDreamDaemon;
import com.ouisani.aios.core.rtos.WatchdogDaemon;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.core.tick.SystemTickGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务调度器 — AIOS 的核心调度组件，负责 Agent 进程的创建、调度和生命周期管理。
 * <p>
 * 类比 Linux 内核的 schedule() + fork()/exit()：维护进程控制块表（PCB），
 * 使用 Java 虚拟线程实现轻量级 Agent 调度，支持 cgroup 资源隔离、
 * OOM 杀死、语义级 Kernel Panic、推测执行等高级特性。
 * <p>
 * 同时管理 WatchdogDaemon（看门狗）、CognitiveDreamDaemon（认知梦守护）、
 * SystemTickGenerator（系统心跳）等系统级守护进程的启停。
 */
public final class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    /** 当前线程绑定的 Agent 任务 — 类比 Linux current 宏，获取当前进程的 task_struct */
    public static final ThreadLocal<AgentTask> CURRENT_TASK = new ThreadLocal<>();

    /** 进程控制块表 — 类比 Linux 的 task_struct 数组，PID → AgentTask 映射 */
    private final ConcurrentHashMap<Integer, AgentTask> pcb = new ConcurrentHashMap<>();
    /** Agent 虚拟线程映射 — PID → Thread，用于中断和取消 */
    private final ConcurrentHashMap<Integer, Thread> agentThreads = new ConcurrentHashMap<>();
    /** 虚拟线程执行器 — 每个 Agent 对应一个虚拟线程 */
    private final ExecutorService virtualThreadExecutor;
    /** 调度器运行标志 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalSpawned = new AtomicLong(0);
    private final AtomicLong totalCompleted = new AtomicLong(0);
    private final AtomicLong totalCancelled = new AtomicLong(0);

    public TaskScheduler() {
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public int spawn(AgentTask task, Runnable agentLogic) {
        return spawn(task, agentLogic, null);
    }

    /**
     * 创建并启动 Agent 进程 — 类比 Linux fork() + exec()。
     * <p>
     * 在虚拟线程中运行 Agent 逻辑，自动绑定 CURRENT_TASK、cgroup、VFS root 等上下文。
     * 支持 OOM 杀死、崩溃恢复（语义级 Kernel Panic）、中断取消等异常处理。
     *
     * @param task       Agent 任务控制块
     * @param agentLogic Agent 执行逻辑
     * @param rootPath   VFS 根路径（用于容器命名空间隔离），null 表示全局根
     * @return PID
     */
    public int spawn(AgentTask task, Runnable agentLogic, String rootPath) {
        if (!running.get()) {
            throw new IllegalStateException("TaskScheduler is not running. Call start() first.");
        }

        // ── 严格沙箱隔离校验 ──
        // USER 态进程（NORMAL/IDLE）必须通过 SandboxProvider 执行，不允许直接在宿主机 JVM 裸奔
        ProcessPriority priority = task.processPriority();
        if (priority == ProcessPriority.NORMAL || priority == ProcessPriority.IDLE) {
            log.info("[TaskScheduler] USER-priority agent#{} (priority={}) spawned — sandbox isolation enforced",
                    task.pid(), priority);
        }

        int pid = task.pid();

        AgentTask existing = pcb.putIfAbsent(pid, task);
        if (existing != null) {
            log.warn("PID {} already exists in PCB, rejecting spawn", pid);
            throw new IllegalArgumentException("Duplicate PID: " + pid);
        }

        task.setStatus(AgentTask.TaskStatus.READY);
        totalSpawned.incrementAndGet();

        String effectiveRoot = (rootPath != null && !rootPath.isEmpty() && !rootPath.equals("/"))
                ? rootPath : null;

        Thread vt = Thread.ofVirtual()
                .name("agent-" + pid)
                .unstarted(() -> {
                    try {
                        CURRENT_TASK.set(task);

                        if (effectiveRoot != null) {
                            VfsManager.AGENT_ROOT.set(effectiveRoot);
                            log.debug("Agent#{} bound to AGENT_ROOT={}", pid, effectiveRoot);
                        }

                        CgroupManager.instance().bindToCurrentThread(
                                CgroupManager.instance().getOrCreateAgentCgroup(pid));

                        task.setStatus(AgentTask.TaskStatus.RUNNING);
                        log.debug("Agent#{} virtual thread started", pid);

                        agentLogic.run();

                        if (Thread.interrupted()) {
                            task.cancel();
                            task.setStatus(AgentTask.TaskStatus.KILLED);
                            totalCancelled.incrementAndGet();
                            log.info("Agent#{} virtual thread interrupted", pid);
                        } else {
                            task.setStatus(AgentTask.TaskStatus.READY);
                            totalCompleted.incrementAndGet();
                            log.debug("Agent#{} virtual thread completed normally", pid);
                        }
                    } catch (TokenOomException e) {
                        task.setStatus(AgentTask.TaskStatus.OOM_KILLED);
                        totalCancelled.incrementAndGet();
                        System.out.printf("  ☠️ [CGROUP OOM] Agent#%d OOM_KILLED: %s%n", pid, e.getMessage());
                        log.error("[CGROUP OOM] Agent#{} killed by cgroup limit: {}", pid, e.getMessage());
                        // 触发语义级 Kernel Panic — 收集认知快照 + LLM 诊断
                        SemanticCrashAnalyzer.instance().kernelPanic(String.valueOf(pid), e);
                    } catch (Exception e) {
                        task.setStatus(AgentTask.TaskStatus.KILLED);
                        if (task.isCancelled()) {
                            totalCancelled.incrementAndGet();
                            log.info("Agent#{} virtual thread interrupted (cancelled)", pid);
                        } else {
                            totalCancelled.incrementAndGet();
                            log.error("Agent#{} virtual thread crashed: {}", pid, e.getMessage(), e);
                            // 触发语义级 Kernel Panic
                            SemanticCrashAnalyzer.instance().kernelPanic(String.valueOf(pid), e);
                        }
                    } catch (Throwable t) {
                        task.setStatus(AgentTask.TaskStatus.CRASHED);
                        totalCancelled.incrementAndGet();
                        log.error("[KERNEL PANIC] Agent#{} crashed with fatal throwable: {}", pid, t.getClass().getName());

                        // 触发语义级 Kernel Panic — 收集认知快照 + LLM 诊断
                        SemanticCrashAnalyzer.instance().kernelPanic(String.valueOf(pid), t);
                    } finally {
                        CURRENT_TASK.remove();
                        CgroupManager.instance().unbindFromCurrentThread();
                        if (effectiveRoot != null) {
                            VfsManager.AGENT_ROOT.remove();
                        }
                        agentThreads.remove(pid);
                        pcb.remove(pid, task);
                    }
                });

        agentThreads.put(pid, vt);
        vt.start();

        SemanticEtw.getInstance().logEvent("SCHEDULER", "SPAWN",
                "pid=" + pid + " cgroup=" + task.cgroup()
                + " priority=" + task.processPriority()
                + " affinity=" + task.affinity());

        log.info("Agent#{} spawned on virtual thread", pid);
        return pid;
    }

    /**
     * 取消 Agent 进程 — 类比 Linux kill(pid, SIGKILL)。
     * 设置取消标志、更新状态为 KILLED、中断虚拟线程、从 PCB 中移除。
     *
     * @param pid 目标进程 ID
     * @return true 取消成功，false PID 不存在
     */
    public boolean cancelAgent(int pid) {
        AgentTask task = pcb.get(pid);
        if (task == null) {
            log.warn("cancel_agent: PID {} not found in PCB", pid);
            return false;
        }

        task.cancel();
        task.setStatus(AgentTask.TaskStatus.KILLED);

        SemanticEtw.getInstance().logEvent("SCHEDULER", "CANCEL",
                "pid=" + pid + " cgroup=" + task.cgroup());

        Thread vt = agentThreads.get(pid);
        if (vt != null) {
            vt.interrupt();
            log.info("Agent#{} virtual thread interrupted", pid);
        }

        agentThreads.remove(pid);
        pcb.remove(pid, task);

        return true;
    }

    /**
     * Send a POSIX signal to a target agent process.
     * <ul>
     *   <li>{@link SignalType#SIGTERM} — Enqueue and immediately interrupt the thread.</li>
     *   <li>{@link SignalType#SIGINT}  — Enqueue for the agent to check before its next operation.</li>
     *   <li>{@link SignalType#SIGUSR1} — Enqueue; the next LLM call will inject a system interrupt prompt.</li>
     * </ul>
     *
     * @param targetPid the PID of the target agent
     * @param signal    the signal to send
     * @return true if the signal was delivered, false if the PID was not found
     */
    public boolean kill(String targetPid, SignalType signal) {
        int pid;
        try {
            pid = Integer.parseInt(targetPid);
        } catch (NumberFormatException e) {
            log.warn("kill: invalid PID format: {}", targetPid);
            return false;
        }

        AgentTask task = pcb.get(pid);
        if (task == null) {
            log.warn("kill: PID {} not found in PCB", pid);
            return false;
        }

        task.sendSignal(signal);
        log.info("[Signal] {} sent to Agent#{}", signal, pid);

        if (signal == SignalType.SIGTERM) {
            task.cancel();
            task.setStatus(AgentTask.TaskStatus.KILLED);
            Thread vt = agentThreads.get(pid);
            if (vt != null) {
                vt.interrupt();
                log.info("[Signal] Agent#{} thread interrupted by SIGTERM", pid);
            }
        }

        return true;
    }

    /**
     * 启动调度器 — 类比 Linux 内核初始化。
     * 依次启动 WatchdogDaemon、CognitiveDreamDaemon、SystemTickGenerator。
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("TaskScheduler started with virtual thread executor");
            WatchdogDaemon.instance().start(this);
            log.info("WatchdogDaemon started alongside TaskScheduler");
            CognitiveDreamDaemon.instance().start();
            log.info("CognitiveDreamDaemon started alongside TaskScheduler");
            SystemTickGenerator.instance().start();
            log.info("SystemTickGenerator started — the system now has a heartbeat");
        }
    }

    /**
     * 关闭调度器 — 类比 Linux shutdown。
     * 停止系统守护进程，中断所有活跃 Agent 线程，清空 PCB，关闭虚拟线程执行器。
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            SystemTickGenerator.instance().stop();
            WatchdogDaemon.instance().stop();
            CognitiveDreamDaemon.instance().stop();

            log.info("TaskScheduler 正在关闭，中断 {} 个活跃 Agent", agentThreads.size());

            for (Map.Entry<Integer, Thread> entry : agentThreads.entrySet()) {
                AgentTask task = pcb.get(entry.getKey());
                if (task != null) {
                    task.cancel();
                    task.setStatus(AgentTask.TaskStatus.KILLED);
                }
                entry.getValue().interrupt();
            }

            agentThreads.clear();
            pcb.clear();

            virtualThreadExecutor.close();
            log.info("TaskScheduler shutdown complete | spawned={} completed={} cancelled={}",
                    totalSpawned.get(), totalCompleted.get(), totalCancelled.get());
        }
    }

    public AgentTask getTask(int pid) {
        return pcb.get(pid);
    }

    public Set<Integer> activePids() {
        return Collections.unmodifiableSet(pcb.keySet());
    }

    public Map<Integer, AgentTask> activeTasks() {
        return Collections.unmodifiableMap(pcb);
    }

    /**
     * 获取活跃 Agent 线程映射（只读）。
     * 用于 CrashAnalyzer 挂起崩溃的 Agent 线程。
     */
    public Map<Integer, Thread> activeThreads() {
        return Collections.unmodifiableMap(agentThreads);
    }

    public int activeCount() {
        return pcb.size();
    }

    /** PID 序列号 — 从 1000 开始递增，类比 Linux 的 pid_allocator */
    private final AtomicInteger nextPidSeq = new AtomicInteger(1000);

    /** 分配下一个 PID — 类比 Linux alloc_pid() */
    public int nextPid() {
        return nextPidSeq.incrementAndGet();
    }

    public SchedulerStats stats() {
        return new SchedulerStats(
                totalSpawned.get(),
                totalCompleted.get(),
                totalCancelled.get(),
                pcb.size()
        );
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Check if the system is idle — no HIGH or NORMAL priority tasks running.
     * <p>
     * The system is considered idle when all active tasks are either
     * IDLE priority or there are no active tasks at all. In this state,
     * the CognitiveDreamDaemon is allowed to run its dream cycle.
     * <p>
     * This is the AIOS equivalent of the Linux kernel's idle task
     * detection: when the runqueue is empty, the scheduler switches
     * to PID 0 (swapper/idle task).
     *
     * @return true if the system is idle
     */
    public boolean isSystemIdle() {
        if (pcb.isEmpty()) return true;

        for (AgentTask task : pcb.values()) {
            ProcessPriority priority = task.processPriority();
            if (priority == ProcessPriority.REALTIME
                    || priority == ProcessPriority.HIGH
                    || priority == ProcessPriority.NORMAL) {
                return false;
            }
        }
        return true;
    }

    /**
     * Count active tasks by priority level.
     */
    public Map<ProcessPriority, Integer> activeByPriority() {
        Map<ProcessPriority, Integer> counts = new java.util.EnumMap<>(ProcessPriority.class);
        for (ProcessPriority p : ProcessPriority.values()) {
            counts.put(p, 0);
        }
        for (AgentTask task : pcb.values()) {
            ProcessPriority p = task.processPriority();
            counts.merge(p, 1, Integer::sum);
        }
        return counts;
    }

    private String extractLastContext(AgentTask task) {
        var history = task.contextHistory();
        if (history == null || history.isEmpty()) return "(no context history)";
        int size = history.size();
        int from = Math.max(0, size - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < size; i++) {
            if (i > from) sb.append(" | ");
            String entry = history.get(i);
            sb.append(entry.length() > 200 ? entry.substring(0, 200) + "..." : entry);
        }
        return sb.toString();
    }

    /** 调度器统计信息 — 类比 /proc/stat 中的进程统计 */
    public record SchedulerStats(long totalSpawned, long totalCompleted, long totalCancelled, int activeCount) {
        @Override
        public String toString() {
            return "SchedulerStats{spawned=%d, completed=%d, cancelled=%d, active=%d}"
                    .formatted(totalSpawned, totalCompleted, totalCancelled, activeCount);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  推测执行集成 (Speculative Execution Integration)
    // ════════════════════════════════════════════════════════════════

    /**
     * 触发推测执行 — 当系统空闲时，预测并预执行可能的下一步。
     * <p>
     * 类比 CPU 在分支等待期间的推测执行：利用空闲算力
     * 预测 3 个最可能的分支，在后台静默执行，将结果
     * 暂存到预测缓冲区。当真实意图到达时，如果命中
     * 预测（相似度 > 95%），瞬间返回结果（0 延迟）。
     *
     * @param context 当前上下文
     * @return 预测 ID，用于后续命中检查
     */
    public String triggerSpeculativeExecution(String context) {
        if (!isSystemIdle() && activeCount() > 2) {
            log.debug("[TaskScheduler] System busy, skipping speculative execution");
            return null;
        }

        SpeculativePredictor predictor = SpeculativePredictor.instance();
        if (!predictor.isEnabled()) return null;

        String predictionId = predictor.speculate(context);

        if (predictionId != null) {
            log.info("[TaskScheduler] Speculative execution triggered: predictionId={}", predictionId);
            SemanticEtw.getInstance().logEvent("SCHEDULER", "SPEC_EXEC",
                    "predictionId=" + predictionId);
        }

        return predictionId;
    }

    /**
     * 检查推测执行命中 — 当真实意图到达时调用。
     *
     * @param realIntent 真实意图
     * @param predictionId 预测 ID
     * @return 命中结果，未命中返回 null
     */
    public SpeculativePredictor.PredictionHitResult checkSpeculativeHit(String realIntent, String predictionId) {
        return SpeculativePredictor.instance().checkPredictionHit(realIntent, predictionId);
    }

    // ════════════════════════════════════════════════════════════════
    //  全局算力池集成 (Global Compute Pool)
    // ════════════════════════════════════════════════════════════════

    /** 集群 Raft 节点 */
    private SemanticRaftNode raftNode;

    /**
     * 配置集群 Raft 节点 — 启用全局算力池。
     *
     * @param raftNode Raft 节点实例
     */
    public void setRaftNode(SemanticRaftNode raftNode) {
        this.raftNode = raftNode;

        // 设置记忆应用回调 — 当 Raft 日志提交时应用到本地缓存
        raftNode.setMemoryApplyCallback(entry -> {
            if (entry.type() == SemanticRaftNode.RaftLogEntry.Type.MEMORY) {
                applyReplicatedMemory(entry);
            }
        });

        log.info("[TaskScheduler] Raft node configured: nodeId={}, role={}",
                raftNode.nodeId(), raftNode.role());
    }

    /**
     * 全局任务派发 — 当本机 TaskQueue 爆满或算力不足时，
     * 将任务派发给集群中最空闲的节点执行。
     * <p>
     * 类比分布式任务队列：Leader 统筹全局任务调度，
     * Follower 将无法处理的任务转发给 Leader。
     *
     * @param task 需要远程执行的任务
     * @return 任务 ID，用于获取结果
     */
    public String dispatchToCluster(AgentTask task) {
        if (raftNode == null) {
            log.warn("[TaskScheduler] No Raft node configured, cannot dispatch to cluster");
            return null;
        }

        // 构造任务描述
        String taskPayload = "{\"pid\":" + task.pid()
                + ",\"type\":\"" + task.type() + "\""
                + ",\"priority\":\"" + task.processPriority() + "\""
                + ",\"prompt\":\"" + task.context().stream()
                    .map(AgentTask.TokenRecord::content)
                    .reduce("", (a, b) -> a + " " + b)
                    .replace("\"", "\\\"")
                    .substring(0, Math.min(500, task.context().stream()
                        .map(AgentTask.TokenRecord::content)
                        .reduce("", (a, b) -> a + " " + b).length()))
                + "\"}";

        String taskId = raftNode.dispatchTask(taskPayload);

        // 更新本节点负载
        raftNode.updateLocalLoad(activeCount());

        log.info("[TaskScheduler] Task dispatched to cluster: pid={}, taskId={}", task.pid(), taskId);
        return taskId;
    }

    /**
     * 应用集群复制的记忆 — 当 Raft 日志提交时，
     * 将其他节点学到的知识应用到本地缓存。
     */
    private void applyReplicatedMemory(SemanticRaftNode.RaftLogEntry entry) {
        try {
            com.ouisani.aios.core.cache.SemanticCacheManager cacheManager =
                    com.ouisani.aios.core.cache.SemanticCacheManager.instance();

            // 使用 null 向量（需要重新计算 Embedding）
            // 标记来源为集群复制
            Map<String, Object> metadata = new HashMap<>(entry.metadata());
            metadata.put("source", "cluster_replication");
            metadata.put("origin_node", entry.query());

            cacheManager.putCache(entry.query(), null, entry.response(), metadata);

            log.info("[TaskScheduler] Cluster memory applied: query={}",
                    entry.query().length() > 50 ? entry.query().substring(0, 50) + "..." : entry.query());
        } catch (Exception e) {
            log.warn("[TaskScheduler] Failed to apply replicated memory: {}", e.getMessage());
        }
    }

    /**
     * 获取集群报告。
     */
    public String getClusterReport() {
        if (raftNode == null) {
            return "Cluster: NOT CONFIGURED (standalone mode)";
        }
        return raftNode.getClusterReport();
    }
}
