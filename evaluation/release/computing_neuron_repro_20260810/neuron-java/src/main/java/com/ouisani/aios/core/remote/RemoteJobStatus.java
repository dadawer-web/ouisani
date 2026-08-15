package com.ouisani.aios.core.remote;

/**
 * 远程作业状态 — {@link RemoteExecutor#poll} 返回的状态枚举。
 * <p>
 * 对标 Slurm 的 State（COMPLETED/FAILED/TIMEOUT/CANCELLED）并抽象出通用终态语义。
 * 非终态（PENDING/RUNNING）表示作业仍在执行，需继续 poll；UNKNOWN 表示状态查询失败
 * （同步回退执行器无作业注册表，或 sacct 临时失败）。
 */
public enum RemoteJobStatus {
    /** 已提交待调度（Slurm PENDING） */
    PENDING,
    /** 执行中（Slurm RUNNING/CONFIGURING/COMPLETING） */
    RUNNING,
    /** 成功完成（Slurm COMPLETED） */
    COMPLETED,
    /** 失败（Slurm FAILED/OUT_OF_MEMORY/NODE_FAIL/BOOT_FAIL） */
    FAILED,
    /** 超时（Slurm TIMEOUT） */
    TIMEOUT,
    /** 被取消（Slurm CANCELLED） */
    CANCELLED,
    /** 状态未知（查询失败或同步回退执行器不支持 poll） */
    UNKNOWN;

    /** 是否为终态（不再变化，可 retrieve 结果）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == TIMEOUT || this == CANCELLED;
    }

    /** 是否为成功终态。 */
    public boolean isSuccessful() {
        return this == COMPLETED;
    }
}
