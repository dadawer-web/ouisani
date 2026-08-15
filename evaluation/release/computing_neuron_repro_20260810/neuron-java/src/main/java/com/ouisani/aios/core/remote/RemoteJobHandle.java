package com.ouisani.aios.core.remote;

/**
 * 异步作业句柄 — {@link RemoteExecutor#submit} 返回的不可变引用。
 * <p>
 * 调用方凭 {@link #jobId()} 后续调用 {@link RemoteExecutor#poll} / {@link RemoteExecutor#retrieve}
 * 查询状态或取回结果。{@link #executorType()} 标识产生此 handle 的执行器（"slurm" / "ssh" / ...），
 * 便于跨执行器调度时区分。{@link #submittedAt()} 为 submit 调用时刻（毫秒），用于超时计算。
 * <p>
 * <b>同步回退场景</b>：未覆写 {@code submit} 的执行器（Ssh/Modal/ModalRest）返回
 * {@code jobId="sync-<nanoTime>"} 的伪句柄 — 这类 handle 无法 poll/retrieve（poll 返回 UNKNOWN），
 * 调用方应直接使用 {@link RemoteExecutor#execute} 的返回值。
 *
 * @param jobId        后端作业 ID（Slurm 的 jobId、同步回退的 "sync-<nanoTime>"）
 * @param executorType 执行器类型标识（与 {@link RemoteExecutor#type()} 一致）
 * @param submittedAt  提交时刻（毫秒时间戳）
 */
public record RemoteJobHandle(String jobId, String executorType, long submittedAt) {
    public RemoteJobHandle {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId required");
        }
        if (executorType == null || executorType.isBlank()) {
            executorType = "unknown";
        }
    }
}
