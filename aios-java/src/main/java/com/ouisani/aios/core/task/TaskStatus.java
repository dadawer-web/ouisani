package com.ouisani.aios.core.task;

/**
 * 任务状态 — 对标 Claude Code 的 TaskStatus。
 * <p>
 * 状态机：PENDING → RUNNING → COMPLETED | FAILED | KILLED
 * <p>
 * OS 类比：相当于 Linux 进程状态 — TASK_RUNNING/TASK_STOPPED/EXIT_ZOMBIE 等。
 */
public enum TaskStatus {

    /** 等待执行 */
    PENDING,
    /** 正在执行 */
    RUNNING,
    /** 执行成功 */
    COMPLETED,
    /** 执行失败 */
    FAILED,
    /** 被手动终止 */
    KILLED;

    /** 是否为终态 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }
}
