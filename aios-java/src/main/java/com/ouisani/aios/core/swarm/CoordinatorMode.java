package com.ouisani.aios.core.swarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协调器模式 — 对标 Claude Code 的 coordinator/ 模块。
 * <p>
 * Coordinator 不直接执行任务，而是通过 Agent 工具 spawn Worker，
 * 监控进度，合并结果。
 * <p>
 * OS 类比：相当于 Linux 的 cgroup manager — 不执行工作，只管理子进程组。
 */
public class CoordinatorMode {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorMode.class);

    private static final String COORDINATOR_ENV = "AIOS_COORDINATOR_MODE";

    /** Worker 身份 */
    public record WorkerIdentity(
            String agentId,
            String agentName,
            String teamName,
            String color
    ) {}

    /** Worker 状态 */
    public static class WorkerState {
        private final WorkerIdentity identity;
        private volatile boolean idle = true;
        private volatile boolean shutdownRequested = false;
        private final List<String> pendingMessages = new ArrayList<>();
        private String currentTask = null;

        public WorkerState(WorkerIdentity identity) {
            this.identity = identity;
        }

        public WorkerIdentity identity() { return identity; }
        public boolean isIdle() { return idle; }
        public void setIdle(boolean idle) { this.idle = idle; }
        public boolean isShutdownRequested() { return shutdownRequested; }
        public void requestShutdown() { this.shutdownRequested = true; }
        public List<String> drainMessages() {
            List<String> msgs = new ArrayList<>(pendingMessages);
            pendingMessages.clear();
            return msgs;
        }
        public void enqueueMessage(String msg) { pendingMessages.add(msg); }
        public String currentTask() { return currentTask; }
        public void setCurrentTask(String task) { this.currentTask = task; }
    }

    private final Map<String, WorkerState> workers = new ConcurrentHashMap<>();
    private volatile boolean active = false;

    /**
     * 检查是否处于协调器模式。
     */
    public static boolean isCoordinatorMode() {
        return "true".equalsIgnoreCase(System.getenv(COORDINATOR_ENV))
                || "1".equals(System.getenv(COORDINATOR_ENV));
    }

    /**
     * 激活协调器模式。
     */
    public void activate() {
        this.active = true;
        log.info("[CoordinatorMode] 已激活。准备生成 Worker。");
        System.out.println("[CoordinatorMode] 已激活。准备生成 Worker。");
    }

    /**
     * 注册 Worker。
     */
    public WorkerState registerWorker(WorkerIdentity identity) {
        WorkerState state = new WorkerState(identity);
        workers.put(identity.agentId(), state);
        log.info("[CoordinatorMode] Worker registered: {} @ {}", identity.agentName(), identity.teamName());
        return state;
    }

    /**
     * 注销 Worker。
     */
    public void unregisterWorker(String agentId) {
        WorkerState removed = workers.remove(agentId);
        if (removed != null) {
            removed.requestShutdown();
            log.info("[CoordinatorMode] Worker unregistered: {}", agentId);
        }
    }

    /**
     * 获取协调器系统提示词 — 对标 getCoordinatorSystemPrompt()。
     */
    public String getSystemPrompt() {
        return """
                # Coordinator Mode
                
                You are in Coordinator mode. Your role is to decompose tasks and delegate to workers.
                
                ## Rules
                1. Do NOT execute tasks directly — delegate to workers via the agent tool
                2. Use SendMessage to continue conversations with workers
                3. Monitor worker progress and merge results
                4. If a worker fails, reassign the task or handle it yourself
                
                ## Task Workflow
                1. Analyze the user's request
                2. Break it into independent subtasks
                3. Assign each subtask to a worker with a clear prompt
                4. Monitor progress and collect results
                5. Synthesize the final answer
                
                ## Prompt Writing Guide
                - Be specific about what the worker should do
                - Include relevant file paths and context
                - Specify the expected output format
                - Set clear boundaries (what NOT to do)
                """;
    }

    /**
     * 获取所有 Worker 状态。
     */
    public Collection<WorkerState> getWorkers() {
        return Collections.unmodifiableCollection(workers.values());
    }

    /**
     * 获取空闲的 Worker。
     */
    public List<WorkerState> getIdleWorkers() {
        return workers.values().stream().filter(WorkerState::isIdle).toList();
    }

    public boolean isActive() { return active; }

    /**
     * 添加 Worker — 便捷方法，用于母体分配节点到 Worker。
     *
     * @param workerId Worker ID
     * @param role     Worker 角色/任务描述
     */
    public void addWorker(String workerId, String role) {
        if (!active) activate();
        String[] colors = {"#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFEAA7", "#DDA0DD"};
        String color = colors[workers.size() % colors.length];
        WorkerIdentity identity = new WorkerIdentity(workerId, workerId, "omnifactory", color);
        WorkerState state = registerWorker(identity);
        state.setCurrentTask(role);
        state.setIdle(false);
        log.info("[CoordinatorMode] Worker {} assigned: {}", workerId, role);
    }

    /**
     * 关闭所有 Worker。
     */
    public void shutdown() {
        workers.values().forEach(WorkerState::requestShutdown);
        workers.clear();
        active = false;
        log.info("[CoordinatorMode] Shutdown complete");
    }
}
