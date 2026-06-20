package com.ouisani.aios.core.task;

/**
 * 任务接口 — 对标 Claude Code 的 Task。
 * <p>
 * 最小多态接口，每个任务类型只需实现 kill 方法。
 * <p>
 * OS 类比：相当于 Linux 的 task_struct 操作接口 — kill() 发送信号。
 */
public interface AiosTask {

    /**
     * 任务名称。
     */
    String name();

    /**
     * 任务类型。
     */
    TaskType type();

    /**
     * 任务 ID。
     */
    String taskId();

    /**
     * 当前状态。
     */
    TaskStatus status();

    /**
     * 任务描述。
     */
    String description();

    /**
     * 终止任务 — 发送 kill 信号。
     */
    void kill();

    /**
     * 获取任务结果（仅终态任务有效）。
     */
    default String result() { return ""; }
}
