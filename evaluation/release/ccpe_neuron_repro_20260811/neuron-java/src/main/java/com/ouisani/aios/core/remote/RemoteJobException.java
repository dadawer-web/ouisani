package com.ouisani.aios.core.remote;

/**
 * 远程作业提交/查询失败 — {@link RemoteExecutor#submit} 在后端拒绝作业（如 sbatch 失败）时抛出。
 * <p>
 * unchecked（继承 {@link RuntimeException}）— 让 {@link RemoteExecutor#submit} 的签名保持简洁，
 * 调用方（如 {@link AsyncRemoteSkillExecutorAdapter}）用 {@code catch (Exception)} 统一捕获并转为空串返回。
 * <p>
 * 与 {@link RemoteExecutor#execute} 的"不抛异常、经 {@link RemoteResult} 返回错误"语义不同：
 * submit 失败时无法构造有意义的 {@link RemoteJobHandle}（没有 jobId），故用异常表达。
 */
public class RemoteJobException extends RuntimeException {
    public RemoteJobException(String message) {
        super(message);
    }

    public RemoteJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
