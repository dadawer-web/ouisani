package com.ouisani.aios.core;

import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.security.SecurityToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentTask {

    private static final Logger log = LoggerFactory.getLogger(AgentTask.class);

    public enum TaskStatus {
        READY,
        RUNNING,
        BLOCKED,
        KILLED,
        OOM_KILLED,
        CRASHED,
        DEADLINE_EXCEEDED
    }

    public enum TaskType {
        LLM_CHAT,
        LLM_INFERENCE,
        TOOL_CALL,
        WRITE_MEMORY,
        READ_MEMORY,
        CANCEL_TASK,
        VFS_CALL,
        PROCESS_CTRL
    }

    private final int pid;
    private volatile TaskStatus status;
    private final String cgroup;
    private final String stdinPath;
    private final String stdoutPath;
    private final List<TokenRecord> context;
    private final List<String> contextHistory;

    private int priority;
    private ProcessPriority processPriority;
    private NumaAffinity affinity;
    private int budget;
    private TaskType type;
    private String payload;
    private String toolName;
    private String toolCode;
    private int gasLimit;
    private int gasUsed;
    private volatile long deadlineMs;
    private SecurityToken primaryToken;
    private final AtomicBoolean cancelled;
    private final ConcurrentLinkedQueue<SignalType> pendingSignals;

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

    public void cancel() {
        boolean was = cancelled.getAndSet(true);
        if (!was) {
            log.info("Task#{} cancelled", pid);
        }
    }

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

    public record TokenRecord(String role, String content, long timestamp) {

        public static TokenRecord of(String role, String content) {
            return new TokenRecord(role, content, System.currentTimeMillis());
        }
    }
}
