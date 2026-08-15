package com.ouisani.aios.core.remote;

/**
 * 远程作业快照 — {@link RemoteExecutor#poll} 返回的某时刻状态快照。
 * <p>
 * 含作业句柄、当前状态、以及（终态时）输出。非终态快照的 {@code stdout}/{@code stderr} 为空串
 * （输出尚未产生）。{@link #toResult()} 把终态快照映射为 {@link RemoteResult}，
 * 供 {@link AsyncRemoteSkillExecutorAdapter} 这类同步外壳使用。
 *
 * @param handle    作业句柄
 * @param status    当前状态
 * @param stdout    标准输出（终态时有值；非终态为空串）
 * @param stderr    标准错误（终态时有值；非终态为空串）
 * @param elapsedMs 自 submit 以来的耗时（毫秒）
 */
public record RemoteJobSnapshot(RemoteJobHandle handle, RemoteJobStatus status,
                                String stdout, String stderr, long elapsedMs) {
    public RemoteJobSnapshot {
        if (handle == null) {
            throw new IllegalArgumentException("handle required");
        }
        if (status == null) {
            status = RemoteJobStatus.UNKNOWN;
        }
        if (stdout == null) stdout = "";
        if (stderr == null) stderr = "";
    }

    /**
     * 把终态快照映射为 {@link RemoteResult}。
     * <p>
     * 非终态状态返回 {@link RemoteResult#configError}（调用方误对非终态快照调 toResult 的保护）。
     *
     * @param resultStdout  最终输出 stdout（覆盖快照自身的 stdout，便于 retrieve 注入实际输出）
     * @param resultStderr  最终输出 stderr
     * @return 映射后的 RemoteResult
     */
    public RemoteResult toResult(String resultStdout, String resultStderr) {
        String out = resultStdout == null ? "" : resultStdout;
        String err = resultStderr == null ? "" : resultStderr;
        return switch (status) {
            case COMPLETED -> RemoteResult.success(out, elapsedMs);
            case TIMEOUT -> RemoteResult.timeout(elapsedMs);
            case FAILED, CANCELLED -> RemoteResult.failure(-1, out, err, elapsedMs);
            default -> RemoteResult.configError("non-terminal status: " + status);
        };
    }
}
