package com.ouisani.aios.core.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Agent 生命周期管理器。
 * 借鉴 Paperclip 的 Agent Lifecycle + Linux 进程管理。
 *
 * 职责：
 *   1. 维护所有 Agent 的状态机（5 态转换 + 合法性校验）
 *   2. 配置版本控制（before/after 快照，支持安全回滚）
 *   3. 状态变更事件广播（通过 EventBus）
 *   4. Agent 可调度性判定（指挥链健康检查）
 *
 * OS 类比：
 *   AgentLifecycleManager = 内核的 task_struct 管理器 + /proc 文件系统
 *   AgentStateRecord      = task_struct（进程控制块）
 *   ConfigRevision        = cgroup 配置快照
 */
public final class AgentLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(AgentLifecycleManager.class);
    private static final AgentLifecycleManager INSTANCE = new AgentLifecycleManager();

    // Agent 注册表：agentId → AgentStateRecord
    private final ConcurrentHashMap<String, AgentStateRecord> registry = new ConcurrentHashMap<>();

    // 配置版本历史：agentId → 有序修订列表
    private final ConcurrentHashMap<String, List<ConfigRevision>> configHistory = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();

    private AgentLifecycleManager() {}

    public static AgentLifecycleManager instance() { return INSTANCE; }

    // ═══════════════════════════════════════════════════════════
    // Agent 注册与注销
    // ═══════════════════════════════════════════════════════════

    /**
     * 注册新 Agent。初始状态为 PENDING_APPROVAL。
     * 类比 Linux：fork() 创建新进程，初始状态 TASK_UNINTERRUPTIBLE。
     */
    public AgentStateRecord register(String agentId, String role, String reportsTo, Map<String, Object> config) {
        AgentStateRecord record = new AgentStateRecord(
                agentId, role, reportsTo, AgentState.PENDING_APPROVAL, config
        );
        AgentStateRecord existing = registry.putIfAbsent(agentId, record);
        if (existing != null) {
            log.warn("[Lifecycle] Agent 已注册: {}", agentId);
            return existing;
        }

        // 保存初始配置为第 0 版
        ConfigRevision rev0 = new ConfigRevision(
                agentId, 0, null, config, "system", "初始注册"
        );
        configHistory.put(agentId, new ArrayList<>(List.of(rev0)));

        log.info("[Lifecycle] Agent 已注册: id={}, role={}, reportsTo={}, state={}",
                agentId, role, reportsTo, AgentState.PENDING_APPROVAL);
        return record;
    }

    /**
     * 注销 Agent。强制转换到 TERMINATED 状态。
     * 类比 Linux：kill -9 + wait() 回收 task_struct。
     */
    public void unregister(String agentId) {
        AgentStateRecord record = registry.get(agentId);
        if (record != null) {
            record.state.set(AgentState.TERMINATED);
            record.terminatedAt = Instant.now();
            log.info("[Lifecycle] Agent 已终止: {}", agentId);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 状态转换
    // ═══════════════════════════════════════════════════════════

    /**
     * 执行状态转换。校验合法性，记录转换历史，广播事件。
     * 类比 Linux：内核修改 task->state 并唤醒/挂起进程。
     *
     * @return true 如果转换成功
     */
    public boolean transition(String agentId, AgentState target, String reason) {
        AgentStateRecord record = registry.get(agentId);
        if (record == null) {
            log.warn("[Lifecycle] 状态转换失败: Agent {} 不存在", agentId);
            return false;
        }

        AgentState current = record.state.get();
        if (!current.canTransitionTo(target)) {
            log.warn("[Lifecycle] 非法状态转换: {} → {} for agent {} (reason: {})",
                    current, target, agentId, reason);
            return false;
        }

        record.state.set(target);
        record.lastTransitionAt = Instant.now();
        record.lastTransitionReason = reason;

        // 记录状态转换历史
        record.transitionHistory.add(new StateTransition(current, target, reason, Instant.now()));

        log.info("[Lifecycle] 状态转换: {} → {} for agent {} (reason: {})",
                current.label(), target.label(), agentId, reason);

        // 广播状态变更事件
        broadcastStateChange(agentId, current, target, reason);

        return true;
    }

    /** 审批通过 — PENDING_APPROVAL → IDLE */
    public boolean approve(String agentId) {
        return transition(agentId, AgentState.IDLE, "Approved by board");
    }

    /** 唤醒 — IDLE → RUNNING */
    public boolean activate(String agentId, String reason) {
        return transition(agentId, AgentState.RUNNING, reason);
    }

    /** 任务完成 — RUNNING → IDLE */
    public boolean deactivate(String agentId) {
        return transition(agentId, AgentState.IDLE, "任务已完成");
    }

    /** 暂停 — IDLE/RUNNING → PAUSED */
    public boolean pause(String agentId, String reason) {
        AgentStateRecord record = registry.get(agentId);
        if (record == null) return false;
        AgentState current = record.state.get();
        if (current == AgentState.IDLE || current == AgentState.RUNNING) {
            return transition(agentId, AgentState.PAUSED, reason);
        }
        return false;
    }

    /** 恢复 — PAUSED → IDLE */
    public boolean resume(String agentId) {
        return transition(agentId, AgentState.IDLE, "已恢复");
    }

    /** 错误 — RUNNING → ERROR */
    public boolean markError(String agentId, String errorDetail) {
        return transition(agentId, AgentState.ERROR, errorDetail);
    }

    /** 清除错误 — ERROR → IDLE */
    public boolean clearError(String agentId) {
        return transition(agentId, AgentState.IDLE, "错误已清除");
    }

    // ═══════════════════════════════════════════════════════════
    // 配置版本控制
    // ═══════════════════════════════════════════════════════════

    /**
     * 更新 Agent 配置。自动记录 before/after 快照，支持回滚。
     * 类比 Linux：cgroup 配置变更 + audit log。
     */
    public boolean updateConfig(String agentId, Map<String, Object> newConfig, String changedBy, String reason) {
        AgentStateRecord record = registry.get(agentId);
        if (record == null) return false;

        configLock.writeLock().lock();
        try {
            Map<String, Object> before = new HashMap<>(record.config);
            record.config.putAll(newConfig);

            // 创建修订记录
            List<ConfigRevision> history = configHistory.computeIfAbsent(agentId, k -> new ArrayList<>());
            int revisionNumber = history.size();
            ConfigRevision revision = new ConfigRevision(
                    agentId, revisionNumber, before, new HashMap<>(record.config), changedBy, reason
            );
            history.add(revision);

            log.info("[Lifecycle] 配置已更新: agent={}, rev={}, changedBy={}, reason={}",
                    agentId, revisionNumber, changedBy, reason);
            return true;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    /**
     * 回滚 Agent 配置到指定修订版本。
     * 类比 Linux：cgroup 配置回滚 + systemctl revert。
     *
     * @param agentId       Agent ID
     * @param targetRevision 目标修订号
     * @return true 如果回滚成功
     */
    public boolean rollbackConfig(String agentId, int targetRevision) {
        configLock.writeLock().lock();
        try {
            List<ConfigRevision> history = configHistory.get(agentId);
            if (history == null || targetRevision < 0 || targetRevision >= history.size()) {
                log.warn("[Lifecycle] 回滚失败: 无效的修订版本 {} for agent {}", targetRevision, agentId);
                return false;
            }

            AgentStateRecord record = registry.get(agentId);
            if (record == null) return false;

            ConfigRevision target = history.get(targetRevision);
            Map<String, Object> before = new HashMap<>(record.config);
            record.config.clear();
            record.config.putAll(target.configAfter());

            // 记录回滚操作为新的修订
            int newRev = history.size();
            ConfigRevision rollbackRev = new ConfigRevision(
                    agentId, newRev, before, new HashMap<>(record.config),
                    "system", "回滚到修订版本 " + targetRevision
            );
            history.add(rollbackRev);

            log.info("[Lifecycle] 配置已回滚: agent={}, fromRev={}, toRev={}, newRev={}",
                    agentId, newRev - 1, targetRevision, newRev);
            return true;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    /** 获取配置修订历史 */
    public List<ConfigRevision> getConfigHistory(String agentId) {
        configLock.readLock().lock();
        try {
            List<ConfigRevision> history = configHistory.get(agentId);
            return history != null ? Collections.unmodifiableList(history) : List.of();
        } finally {
            configLock.readLock().unlock();
        }
    }

    /** 获取最新修订号 */
    public int getLatestRevision(String agentId) {
        List<ConfigRevision> history = configHistory.get(agentId);
        return history != null ? history.size() - 1 : -1;
    }

    // ═══════════════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════════════

    public AgentState getState(String agentId) {
        AgentStateRecord record = registry.get(agentId);
        return record != null ? record.state.get() : null;
    }

    public AgentStateRecord getRecord(String agentId) {
        return registry.get(agentId);
    }

    /** 获取所有处于指定状态的 Agent */
    public List<String> getAgentsByState(AgentState state) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, AgentStateRecord> entry : registry.entrySet()) {
            if (entry.getValue().state.get() == state) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** 获取所有可调度的 Agent（IDLE 状态 + 指挥链健康） */
    public List<String> getInvocableAgents() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, AgentStateRecord> entry : registry.entrySet()) {
            AgentStateRecord record = entry.getValue();
            if (record.state.get() == AgentState.IDLE && isOrgChainHealthy(entry.getKey())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * 检查 Agent 的指挥链健康状态。
     * 沿 reportsTo 向上追溯，确保所有上级都存在且未终止。
     * 类比 Linux：检查进程的父进程链是否完整。
     */
    public boolean isOrgChainHealthy(String agentId) {
        Set<String> visited = new HashSet<>();
        String current = agentId;

        while (current != null) {
            if (!visited.add(current)) {
                // 检测到循环
                log.warn("[Lifecycle] 检测到循环汇报关系: {}", current);
                return false;
            }

            AgentStateRecord record = registry.get(current);
            if (record == null) {
                // 链断裂：上级不存在
                return false;
            }
            if (record.state.get() == AgentState.TERMINATED) {
                // 链中有已终止的上级
                return false;
            }

            current = record.reportsTo;
        }
        return true;
    }

    /** 获取 Agent 的指挥链（从自身到 CEO 的路径） */
    public List<String> getChainOfCommand(String agentId) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = agentId;

        while (current != null && visited.add(current)) {
            chain.add(current);
            AgentStateRecord record = registry.get(current);
            if (record == null) break;
            current = record.reportsTo;
        }
        return chain;
    }

    public int size() { return registry.size(); }

    public Map<String, AgentState> getAllStates() {
        Map<String, AgentState> result = new HashMap<>();
        registry.forEach((id, record) -> result.put(id, record.state.get()));
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════

    private void broadcastStateChange(String agentId, AgentState from, AgentState to, String reason) {
        try {
            com.ouisani.aios.core.network.EventBus.instance().broadcast(
                    "agent.lifecycle",
                    "{\"agentId\":\"" + agentId + "\",\"from\":\"" + from.label()
                            + "\",\"to\":\"" + to.label() + "\",\"reason\":\"" + reason + "\"}"
            );
        } catch (Exception e) {
            log.debug("[Lifecycle] EventBus 广播失败: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 内部数据结构
    // ═══════════════════════════════════════════════════════════

    /**
     * Agent 状态记录 — 类比 Linux task_struct（进程控制块）。
     */
    public static class AgentStateRecord {
        private final String agentId;
        private final String role;
        private final String reportsTo;       // 组织树：向上汇报关系
        private final AtomicReference<AgentState> state;
        private final Map<String, Object> config;
        private final List<StateTransition> transitionHistory;
        private Instant createdAt;
        private Instant lastTransitionAt;
        private String lastTransitionReason;
        private Instant terminatedAt;

        public AgentStateRecord(String agentId, String role, String reportsTo,
                                AgentState initialState, Map<String, Object> config) {
            this.agentId = agentId;
            this.role = role;
            this.reportsTo = reportsTo;
            this.state = new AtomicReference<>(initialState);
            this.config = new ConcurrentHashMap<>(config != null ? config : Map.of());
            this.transitionHistory = Collections.synchronizedList(new ArrayList<>());
            this.createdAt = Instant.now();
            this.lastTransitionAt = this.createdAt;
        }

        public String agentId() { return agentId; }
        public String role() { return role; }
        public String reportsTo() { return reportsTo; }
        public AgentState state() { return state.get(); }
        public Map<String, Object> config() { return Collections.unmodifiableMap(config); }
        public List<StateTransition> transitionHistory() { return List.copyOf(transitionHistory); }
        public Instant createdAt() { return createdAt; }
        public Instant lastTransitionAt() { return lastTransitionAt; }
    }

    /** 状态转换记录 */
    public record StateTransition(AgentState from, AgentState to, String reason, Instant timestamp) {}

    /** 配置修订记录 */
    public record ConfigRevision(
            String agentId,
            int revisionNumber,
            Map<String, Object> configBefore,
            Map<String, Object> configAfter,
            String changedBy,
            String reason
    ) {}
}
