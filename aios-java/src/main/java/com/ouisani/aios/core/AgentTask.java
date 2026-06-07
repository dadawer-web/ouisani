package com.ouisani.aios.core;

import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.llm.ComputeAffinity;
import com.ouisani.aios.core.llm.ComputeCore;
import com.ouisani.aios.core.security.SecurityToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 任务控制块（PCB）— AIOS 中每个 Agent 进程的核心数据结构。
 * <p>
 * 类比传统 OS 的进程控制块（task_struct），记录 Agent 的 PID、状态、优先级、
 * 资源配额（gas/budget）、NUMA 亲和性、安全令牌等信息。
 * 每个 AgentTask 实例对应一个运行中的 Agent 虚拟线程。
 */
public final class AgentTask {

    private static final Logger log = LoggerFactory.getLogger(AgentTask.class);

    /** 任务状态 — 类比 Linux 进程状态（READY/RUNNING/BLOCKED/KILLED 等） */
    public enum TaskStatus {
        READY,
        RUNNING,
        BLOCKED,
        KILLED,
        OOM_KILLED,
        CRASHED,
        DEADLINE_EXCEEDED
    }

    /** 任务类型 — 区分 LLM 推理、工具调用、内存读写、VFS 调用等不同操作 */
    public enum TaskType {
        LLM_CHAT,
        LLM_INFERENCE,
        TOOL_CALL,
        WRITE_MEMORY,
        READ_MEMORY,
        CANCEL_TASK,
        VFS_CALL,
        PROCESS_CTRL,
        /** 推测执行任务 — 由 SpeculativePredictor 生成的预测性后台任务 */
        SPECULATIVE
    }

    /** 进程 ID — 类比 Linux PID，全局唯一 */
    private final int pid;
    /** 任务当前状态（volatile 保证多线程可见性） */
    private volatile TaskStatus status;
    /** 所属 cgroup — 类比 Linux cgroup，用于资源隔离 */
    private final String cgroup;
    /** 标准输入路径 — 类比 /proc/{pid}/fd/0 */
    private final String stdinPath;
    /** 标准输出路径 — 类比 /proc/{pid}/fd/1 */
    private final String stdoutPath;
    /** 上下文 token 记录 — Agent 的对话历史 */
    private final List<TokenRecord> context;
    /** 上下文压缩历史 — 用于上下文窗口管理 */
    private final List<String> contextHistory;

    /** 动态优先级数值 */
    private int priority;
    /** 进程优先级等级 — 类比 Linux nice 值 / Windows 优先级类 */
    private ProcessPriority processPriority;
    /** NUMA 亲和性 — 控制 LLM 请求是否可路由到远程节点 */
    private NumaAffinity affinity;
    /** 算力亲和性 — 根据优先级决定使用哪个 LLM 核心 */
    private ComputeAffinity computeAffinity;
    /** Token 预算 — 类比 cgroup memory.limit，控制单次任务最大 token 消耗 */
    private int budget;
    /** 任务类型 */
    private TaskType type;
    /** 任务载荷 — LLM prompt 或工具参数 */
    private String payload;
    /** 工具名称 — TOOL_CALL 类型时使用 */
    private String toolName;
    /** 工具代码 — TOOL_CALL 类型时使用 */
    private String toolCode;
    /** Gas 上限 — 类比以太坊 gas limit，防止单次调用无限消耗算力 */
    private int gasLimit;
    /** Gas 已用量 */
    private int gasUsed;
    /** 截止时间（毫秒时间戳）— 超时后任务被标记为 DEADLINE_EXCEEDED */
    private volatile long deadlineMs;
    /** 安全令牌 — 类比 Linux capability，控制 Agent 的权限范围 */
    private SecurityToken primaryToken;
    /** 取消标志 — 原子操作，支持异步取消 */
    private final AtomicBoolean cancelled;
    /** 待处理信号队列 — 类比 POSIX 信号队列（SIGTERM/SIGINT/SIGUSR1） */
    private final ConcurrentLinkedQueue<SignalType> pendingSignals;

    /**
     * 构造 Agent 任务控制块。
     * <p>
     * PID < 100 的任务默认为 REALTIME 优先级（系统级 Agent），
     * 其余为 NORMAL 优先级（用户 Agent）。
     *
     * @param pid        进程 ID
     * @param status     初始状态
     * @param cgroup     所属 cgroup 名称
     * @param stdinPath  标准输入 VFS 路径
     * @param stdoutPath 标准输出 VFS 路径
     * @param context    初始上下文 token 列表
     */
    public AgentTask(int pid,
                     TaskStatus status,
                     String cgroup,
                     String stdinPath,
                     String stdoutPath,
                     List<TokenRecord> context) {
        this.pid = pid;
        this.status = status;
        this.cgroup = cgroup;
        this.stdinPath = stdinPath;
        this.stdoutPath = stdoutPath;
        this.context = context;
        this.contextHistory = new ArrayList<>();
        this.priority = 0;
        this.processPriority = (pid < 100) ? ProcessPriority.REALTIME : ProcessPriority.NORMAL;
        this.affinity = NumaAffinity.PREFER_LOCAL;
        this.computeAffinity = ComputeAffinity.fromPriority(this.processPriority);
        this.budget = 200;
        this.type = TaskType.LLM_CHAT;
        this.gasLimit = 10_000;
        this.gasUsed = 0;
        this.deadlineMs = 0;
        this.primaryToken = SecurityToken.forAgent(this);
        this.cancelled = new AtomicBoolean(false);
        this.pendingSignals = new ConcurrentLinkedQueue<>();
        log.debug("AgentTask created: pid={}, cgroup={}, status={}", pid, cgroup, status);
    }

    public int pid() {
        return pid;
    }

    public TaskStatus status() {
        return status;
    }

    /** 设置任务状态，并记录状态转换日志。类比 Linux 内核 set_task_state()。 */
    public void setStatus(TaskStatus status) {
        TaskStatus prev = this.status;
        this.status = status;
        log.info("Task#{} status transition: {} -> {}", pid, prev, status);
    }

    public String cgroup() {
        return cgroup;
    }

    public String stdinPath() {
        return stdinPath;
    }

    public String stdoutPath() {
        return stdoutPath;
    }

    public List<TokenRecord> context() {
        return List.copyOf(context);
    }

    public void appendContext(TokenRecord record) {
        context.add(record);
    }

    public List<String> contextHistory() {
        return contextHistory;
    }

    public void appendHistory(String entry) {
        contextHistory.add(entry);
    }

    /**
     * 替换上下文历史中 [from, to) 范围的条目为压缩摘要。
     * <p>
     * 类比 OS 内存页的压缩/换出：将旧的历史条目合并为一条压缩记录，
     * 释放上下文窗口空间。从后向前删除以避免索引偏移。
     *
     * @param from      起始索引（含）
     * @param to        结束索引（不含）
     * @param compressed 压缩后的摘要文本
     */
    public void replaceHistoryRange(int from, int to, String compressed) {
        for (int i = to - 1; i >= from; i--) {
            contextHistory.remove(i);
        }
        contextHistory.add(from, compressed);
    }

    public int priority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public ProcessPriority processPriority() {
        return processPriority;
    }

    public void setProcessPriority(ProcessPriority processPriority) {
        ProcessPriority prev = this.processPriority;
        this.processPriority = processPriority;
        log.info("Task#{} processPriority transition: {} -> {}", pid, prev, processPriority);
    }

    public NumaAffinity affinity() {
        return affinity;
    }

    public void setAffinity(NumaAffinity affinity) {
        NumaAffinity prev = this.affinity;
        this.affinity = affinity;
        log.info("Task#{} affinity transition: {} -> {}", pid, prev, affinity);
    }

    public ComputeAffinity computeAffinity() {
        return computeAffinity;
    }

    public void setComputeAffinity(ComputeAffinity computeAffinity) {
        ComputeAffinity prev = this.computeAffinity;
        this.computeAffinity = computeAffinity;
        log.info("Task#{} computeAffinity transition: {} -> {}", pid, prev, computeAffinity);
    }

    public int budget() {
        return budget;
    }

    public void setBudget(int budget) {
        this.budget = budget;
    }

    public long deadlineMs() {
        return deadlineMs;
    }

    public void setDeadlineMs(long deadlineMs) {
        this.deadlineMs = deadlineMs;
        if (deadlineMs > 0) {
            log.info("Task#{} deadline set: {} (in {}ms)", pid, deadlineMs, deadlineMs - System.currentTimeMillis());
        }
    }

    public SecurityToken primaryToken() {
        return primaryToken;
    }

    public void setPrimaryToken(SecurityToken primaryToken) {
        SecurityToken prev = this.primaryToken;
        this.primaryToken = primaryToken;
        log.info("Task#{} primaryToken transition: {} -> {}", pid,
                prev != null ? prev.ownerId() : "null",
                primaryToken != null ? primaryToken.ownerId() : "null");
    }

    public TaskType type() {
        return type;
    }

    public void setType(TaskType type) {
        this.type = type;
    }

    public String payload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String toolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String toolCode() {
        return toolCode;
    }

    public void setToolCode(String toolCode) {
        this.toolCode = toolCode;
    }

    public int gasLimit() {
        return gasLimit;
    }

    public void setGasLimit(int gasLimit) {
        this.gasLimit = gasLimit;
    }

    public int gasUsed() {
        return gasUsed;
    }

    public void setGasUsed(int gasUsed) {
        this.gasUsed = gasUsed;
    }

    /**
     * 消耗 Gas — 类比以太坊 EVM 的 gas 计费机制。
     * 每次消耗前检查是否超出 gasLimit，超出则拒绝并返回 false。
     *
     * @param tokens 本次消耗的 gas 数量
     * @return true 消耗成功，false 超出上限
     */
    public boolean consumeGas(int tokens) {
        if (gasUsed + tokens > gasLimit) {
            log.warn("Task#{} gas exceeded: used={}, requested={}, limit={}",
                    pid, gasUsed, tokens, gasLimit);
            return false;
        }
        gasUsed += tokens;
        return true;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 取消任务 — 原子操作，类比 POSIX kill(pid, SIGTERM)。 */
    public void cancel() {
        boolean was = cancelled.getAndSet(true);
        if (!was) {
            log.info("Task#{} cancelled", pid);
        }
    }

    /**
     * 重置取消标志 — 用于崩溃恢复后重新调度。
     * 仅由 SemanticCrashAnalyzer 的恢复流程调用。
     */
    public void resetForRecovery() {
        cancelled.set(false);
        log.info("Task#{} reset for recovery", pid);
    }

    /** 发送信号 — 类比 POSIX kill(pid, signal)，将信号入队待 Agent 处理。 */
    public void sendSignal(SignalType signal) {
        pendingSignals.offer(signal);
        log.info("Task#{} received signal: {}", pid, signal);
    }

    public SignalType pollSignal() {
        return pendingSignals.poll();
    }

    public boolean hasPendingSignals() {
        return !pendingSignals.isEmpty();
    }

    public ConcurrentLinkedQueue<SignalType> pendingSignals() {
        return pendingSignals;
    }

    @Override
    public String toString() {
        return "AgentTask{pid=%d, status=%s, cgroup='%s', type=%s, priority=%s, gas=%d/%d}"
                .formatted(pid, status, cgroup, type, processPriority, gasUsed, gasLimit);
    }

    /** Token 记录 — 类比 OS 的日志条目，记录对话中的角色、内容和时间戳。 */
    public record TokenRecord(String role, String content, long timestamp) {

        public static TokenRecord of(String role, String content) {
            return new TokenRecord(role, content, System.currentTimeMillis());
        }
    }
}
