package com.ouisani.aios.core.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务注册中心 — 对标 Claude Code 的任务状态管理。
 * <p>
 * 集中管理所有活跃任务，提供：
 * - 任务注册与注销
 * - 按类型/ID 查找
 * - 批量终止
 * - 状态监控
 * <p>
 * OS 类比：相当于 Linux 的 PID 命名空间 — 管理所有进程描述符。
 */
public class TaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(TaskRegistry.class);
    private static final TaskRegistry INSTANCE = new TaskRegistry();

    private final Map<String, AiosTask> tasks = new ConcurrentHashMap<>();

    private TaskRegistry() {}

    public static TaskRegistry instance() { return INSTANCE; }

    /**
     * 注册任务。
     */
    public void register(AiosTask task) {
        tasks.put(task.taskId(), task);
        log.info("[TaskRegistry] Registered task: {} ({}) — {}", task.taskId(), task.type(), task.description());
    }

    /**
     * 注销任务。
     */
    public void unregister(String taskId) {
        AiosTask removed = tasks.remove(taskId);
        if (removed != null) {
            log.info("[TaskRegistry] Unregistered task: {}", taskId);
        }
    }

    /**
     * 按ID查找任务。
     */
    public Optional<AiosTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * 获取所有活跃任务。
     */
    public Collection<AiosTask> all() {
        return Collections.unmodifiableCollection(tasks.values());
    }

    /**
     * 按类型查找任务。
     */
    public List<AiosTask> getByType(TaskType type) {
        return tasks.values().stream()
                .filter(t -> t.type() == type)
                .toList();
    }

    /**
     * 获取运行中的任务。
     */
    public List<AiosTask> getRunning() {
        return tasks.values().stream()
                .filter(t -> t.status() == TaskStatus.RUNNING)
                .toList();
    }

    /**
     * 终止指定任务。
     */
    public boolean kill(String taskId) {
        AiosTask task = tasks.get(taskId);
        if (task != null && !task.status().isTerminal()) {
            task.kill();
            log.info("[TaskRegistry] Killed task: {}", taskId);
            return true;
        }
        return false;
    }

    /**
     * 终止所有任务。
     */
    public void killAll() {
        tasks.values().stream()
                .filter(t -> !t.status().isTerminal())
                .forEach(AiosTask::kill);
        log.info("[TaskRegistry] All tasks killed");
    }

    /**
     * 清理已终止的任务。
     */
    public int cleanup() {
        int before = tasks.size();
        tasks.entrySet().removeIf(e -> e.getValue().status().isTerminal());
        int cleaned = before - tasks.size();
        if (cleaned > 0) {
            log.info("[TaskRegistry] Cleaned up {} terminated tasks", cleaned);
        }
        return cleaned;
    }

    /**
     * 获取任务统计摘要。
     */
    public String summary() {
        long pending = tasks.values().stream().filter(t -> t.status() == TaskStatus.PENDING).count();
        long running = tasks.values().stream().filter(t -> t.status() == TaskStatus.RUNNING).count();
        long completed = tasks.values().stream().filter(t -> t.status() == TaskStatus.COMPLETED).count();
        long failed = tasks.values().stream().filter(t -> t.status() == TaskStatus.FAILED).count();
        long killed = tasks.values().stream().filter(t -> t.status() == TaskStatus.KILLED).count();
        return String.format("Tasks: %d total | %d pending | %d running | %d completed | %d failed | %d killed",
                tasks.size(), pending, running, completed, failed, killed);
    }
}
