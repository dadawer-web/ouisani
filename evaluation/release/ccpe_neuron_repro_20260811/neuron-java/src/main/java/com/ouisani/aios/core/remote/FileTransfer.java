package com.ouisani.aios.core.remote;

/**
 * 文件传输器 — 在本地与远程算力后端之间搬运文件/目录。
 * <p>
 * sealed interface 限定实现：当前仅 {@link ScpFileTransfer}（shell out 到 {@code scp -r} CLI）。
 * SFTP 协议级实现（resume/断点续传/权限精细控制）留作 R4.2，届时追加 {@code SftpFileTransfer}
 * 到 permits 子句即可，调用方零修改。
 * <p>
 * <b>连接配置复用</b>：复用 {@link RemoteExecutorConfig} 的 SSH 段（host/port/user/privateKeyPath/
 * knownHostsPath），与 {@link SshExecutor} 共享同一套连接参数 — 调用方构造一份 config 即可同时
 * 用于执行命令和传输文件。
 * <p>
 * <b>错误传播</b>：与 {@link RemoteExecutor} 一致 — 不抛异常（除非 config 非法），失败经
 * {@link RemoteResult#success()}{@code ==false} + {@link RemoteResult#errorMessage()} 返回。
 *
 * @see ScpFileTransfer
 * @see RemoteExecutorConfig
 */
public sealed interface FileTransfer permits ScpFileTransfer {

    /**
     * 上传本地文件/目录到远程路径。
     *
     * @param config     连接配置（用 SSH 段）
     * @param localPath  本地路径（文件或目录）
     * @param remotePath 远程路径
     * @return 传输结果（成功时 success=true）
     */
    RemoteResult upload(RemoteExecutorConfig config, String localPath, String remotePath);

    /**
     * 下载远程文件/目录到本地路径。
     *
     * @param config     连接配置（用 SSH 段）
     * @param remotePath 远程路径
     * @param localPath  本地路径
     * @return 传输结果（成功时 success=true）
     */
    RemoteResult download(RemoteExecutorConfig config, String remotePath, String localPath);

    /** 传输器类型标识。 */
    String type();
}
