package com.ouisani.aios.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentTask {

    private static final Logger log = LoggerFactory.getLogger(AgentTask.class);

    public enum TaskStatus {
        READY,
        RUNNING,
        BLOCKED,
        KILLED,
        OOM_KILLED
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

    private int priority;
    private TaskType type;
    private String payload;
    private String toolName;
    private String toolCode;
    private int gasLimit;
    private int gasUsed;
    private final AtomicBoolean cancelled;

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
        this.priority = 0;
        this.type = TaskType.LLM_CHAT;
        this.gasLimit = 10_000;
        this.gasUsed = 0;
        this.cancelled = new AtomicBoolean(false);
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

    public int priority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
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

    @Override
    public String toString() {
        return "AgentTask{pid=%d, status=%s, cgroup='%s', type=%s, gas=%d/%d}"
                .formatted(pid, status, cgroup, type, gasUsed, gasLimit);
    }

    public record TokenRecord(String role, String content, long timestamp) {

        public static TokenRecord of(String role, String content) {
            return new TokenRecord(role, content, System.currentTimeMillis());
        }
    }
}
