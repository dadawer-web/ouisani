package com.ouisani.aios.core.remote;

/**
 * 远程执行器 — 把命令路由到远程算力后端（SSH/Slurm/Modal）。
 * <p>
 * sealed interface 限定 4 个实现：{@link SshExecutor}、{@link SlurmExecutor}、
 * {@link ModalExecutor}、{@link ModalRestExecutor}。
 * 通过 {@link RemoteExecutorRegistry#get(String)} 按类型名查找，或直接构造。
 * <p>
 * <b>同步语义</b>：{@link #execute} 阻塞到命令完成或超时。Slurm 长任务内部轮询 {@code sacct}，
 * 总等待不超过 {@link RemoteExecutorConfig#timeoutSeconds()}。
 * <p>
 * <b>异步语义（R4.1）</b>：{@link #submit}/{@link #poll}/{@link #retrieve} 三方法提供
 * submit→jobId + 后续 poll/retrieve 的异步模式。默认实现为<b>同步回退</b>（submit 包装 execute，
 * poll 返回 UNKNOWN，retrieve 返回 configError）— 仅 {@link SlurmExecutor} 覆写为真异步。
 * 调用方通过 {@link AsyncRemoteSkillExecutorAdapter} 用异步 API 但对外保持同步 {@code SkillExecutor} 语义。
 * <p>
 * <b>错误传播</b>：执行器<b>不抛异常</b>（除非 config 非法），所有错误通过
 * {@link RemoteResult#success()}{@code ==false} + {@link RemoteResult#errorMessage()} 返回。
 * 调用方（如 {@link RemoteSkillExecutorAdapter}）据此决定返回值。
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code execve} 系统调用 —
 * 区别是「本地 fork+exec」换成「SSH 通道 / Slurm 调度器 / Modal serverless / Modal REST」。
 *
 * @see RemoteExecutorConfig
 * @see RemoteResult
 * @see RemoteSkillExecutorAdapter
 * @see AsyncRemoteSkillExecutorAdapter
 */
public sealed interface RemoteExecutor
        permits SshExecutor, SlurmExecutor, ModalExecutor, ModalRestExecutor {

    /**
     * 在远程后端执行命令。
     *
     * @param config     执行器配置（含连接参数、超时、env）
     * @param command    要执行的 shell 命令（如 {@code python train.py --epochs 10}）
     * @param workingDir 远程工作目录（null 表示用后端默认 — SSH 用 home，Slurm 用 remoteWorkDir，Modal 忽略）
     * @return 执行结果（永不返回 null；失败时 success=false）
     */
    RemoteResult execute(RemoteExecutorConfig config, String command, String workingDir);

    /**
     * 执行器类型标识 — 与 {@link RemoteExecutorConfig#type()} 一致。
     *
     * @return "ssh" | "slurm" | "modal" | "modal-rest"
     */
    String type();

    // ════════════════════════════════════════════════════════════════
    //  异步 API（R4.1）— default 同步回退，仅 SlurmExecutor 覆写真异步
    // ════════════════════════════════════════════════════════════════

    /**
     * 异步提交 — 立即返回作业句柄，不阻塞等待完成。
     * <p>
     * <b>默认实现（同步回退）</b>：同步调 {@link #execute} 后包装为伪句柄
     * （{@code jobId="sync-<nanoTime>"}）。此回退非真异步 — 调用方应直接用 execute 返回值，
     * 不应对回退句柄调 poll/retrieve。{@link SlurmExecutor} 覆写为真异步（sbatch 后立即返回 jobId）。
     *
     * @param config     执行器配置
     * @param command    要执行的 shell 命令
     * @param workingDir 远程工作目录（null 表示后端默认）
     * @return 作业句柄（含 jobId、executorType、submittedAt）
     */
    default RemoteJobHandle submit(RemoteExecutorConfig config, String command, String workingDir) {
        // 同步回退：阻塞执行，返回伪句柄。execute 的结果被丢弃（回退场景调用方应直接用 execute）
        execute(config, command, workingDir);
        return new RemoteJobHandle("sync-" + System.nanoTime(), type(), System.currentTimeMillis());
    }

    /**
     * 查询作业状态。
     * <p>
     * <b>默认实现（同步回退）</b>：无作业注册表可查 → 返回 {@link RemoteJobStatus#UNKNOWN}。
     * {@link SlurmExecutor} 覆写为调 {@code sacct} 查实际状态。
     *
     * @param config 执行器配置
     * @param handle {@link #submit} 返回的句柄
     * @return 状态快照（回退实现返回 UNKNOWN）
     */
    default RemoteJobSnapshot poll(RemoteExecutorConfig config, RemoteJobHandle handle) {
        return new RemoteJobSnapshot(handle, RemoteJobStatus.UNKNOWN, "", "", 0L);
    }

    /**
     * 取回最终结果（作业须已终态）。
     * <p>
     * <b>默认实现（同步回退）</b>：无保存的 command 可重跑 → 返回 {@link RemoteResult#configError}，
     * 提示调用方改用 {@link #execute}。{@link SlurmExecutor} 覆写为按 jobId 读
     * {@code slurm-<jobId>.out} + 清理临时文件。
     *
     * @param config 执行器配置
     * @param handle {@link #submit} 返回的句柄
     * @return 最终执行结果
     */
    default RemoteResult retrieve(RemoteExecutorConfig config, RemoteJobHandle handle) {
        return RemoteResult.configError("retrieve not supported by " + type() + " (sync fallback)");
    }
}
