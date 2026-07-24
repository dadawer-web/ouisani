package com.ouisani.aios.core.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * SCP 文件传输器 — 通过 {@code scp -r} CLI 在本地与远程主机之间递归传输文件/目录。
 * <p>
 * <b>命令构造</b>：
 * <pre>{@code
 * scp -r -i <privateKeyPath> -P <port>
 *     -o StrictHostKeyChecking=yes -o BatchMode=yes
 *     [-o UserKnownHostsFile=<knownHostsPath>]
 *     <src> <dst>
 * }</pre>
 * 其中 {@code <src>/<dst>} 形如 {@code <user>@<host>:<remotePath>}（远程端）或纯本地路径。
 * <ul>
 *   <li>upload：{@code <localPath> <user>@<host>:<remotePath>}</li>
 *   <li>download：{@code <user>@<host>:<remotePath> <localPath>}</li>
 * </ul>
 * <p>
 * <b>设计选择</b>：
 * <ul>
 *   <li>shell out 到 {@code scp} CLI 而非引入 JSch/sshj — 与 {@link SshExecutor} 一致，零新增依赖</li>
 *   <li>统一加 {@code -r}（递归）— 单文件也兼容，避免调用方区分文件/目录</li>
 *   <li>{@code -P}（大写）指定端口 — scp 与 ssh 的端口 flag 大小写不同（ssh 用 {@code -p}）</li>
 *   <li>{@code BatchMode=yes} 禁用交互式密码提示，{@code StrictHostKeyChecking=yes} 强校验 host key</li>
 *   <li>非零退出码不抛异常，返回 {@link RemoteResult#failure}</li>
 * </ul>
 * <p>
 * <b>SFTP 未实现</b>：SFTP 协议级实现需重依赖或复杂批处理交互，{@code scp -r} 覆盖递归/单文件/权限保留
 * 等绝大多数场景。SFTP 留作 R4.2（若需 resume/断点续传）。
 *
 * @see FileTransfer
 * @see SshExecutor（复用其连接参数与 shellQuote 模式）
 */
public final class ScpFileTransfer implements FileTransfer {

    private static final Logger log = LoggerFactory.getLogger(ScpFileTransfer.class);

    private final CommandRunner runner;

    /** 生产构造器：用 {@link DefaultCommandRunner#INSTANCE}。 */
    public ScpFileTransfer() {
        this(DefaultCommandRunner.INSTANCE);
    }

    /** 测试构造器：注入 mock runner。 */
    public ScpFileTransfer(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public RemoteResult upload(RemoteExecutorConfig config, String localPath, String remotePath) {
        if (config == null) return RemoteResult.configError("config is null");
        if (localPath == null || localPath.isBlank()) return RemoteResult.configError("localPath is empty");
        if (remotePath == null || remotePath.isBlank()) return RemoteResult.configError("remotePath is empty");

        String remoteTarget = formatRemoteTarget(config, remotePath);
        List<String> argv = buildScpArgv(config, localPath, remoteTarget);
        return runTransfer(argv, config, "upload " + localPath + " -> " + remoteTarget);
    }

    @Override
    public RemoteResult download(RemoteExecutorConfig config, String remotePath, String localPath) {
        if (config == null) return RemoteResult.configError("config is null");
        if (remotePath == null || remotePath.isBlank()) return RemoteResult.configError("remotePath is empty");
        if (localPath == null || localPath.isBlank()) return RemoteResult.configError("localPath is empty");

        String remoteSource = formatRemoteTarget(config, remotePath);
        List<String> argv = buildScpArgv(config, remoteSource, localPath);
        return runTransfer(argv, config, "download " + remoteSource + " -> " + localPath);
    }

    @Override
    public String type() {
        return "scp";
    }

    // ════════════════════════════════════════════════════════════════
    //  命令构造（package-private 便于测试断言）
    // ════════════════════════════════════════════════════════════════

    /** 构造 {@code scp -r ... <src> <dst>} argv（不含 env，env 由 ssh agent 提供）。 */
    static List<String> buildScpArgv(RemoteExecutorConfig config, String src, String dst) {
        List<String> argv = new ArrayList<>();
        argv.add("scp");
        argv.add("-r"); // 递归（单文件也兼容）
        if (config.privateKeyPath() != null && !config.privateKeyPath().isBlank()) {
            argv.add("-i");
            argv.add(config.privateKeyPath());
        }
        int port = config.sshPort();
        if (port != 22) {
            argv.add("-P"); // scp 用大写 -P（ssh 用小写 -p）
            argv.add(String.valueOf(port));
        }
        argv.add("-o");
        argv.add("StrictHostKeyChecking=yes");
        argv.add("-o");
        argv.add("BatchMode=yes");
        if (config.knownHostsPath() != null && !config.knownHostsPath().isBlank()) {
            argv.add("-o");
            argv.add("UserKnownHostsFile=" + config.knownHostsPath());
        }
        argv.add(src);
        argv.add(dst);
        return argv;
    }

    /** 拼接远程端标识 {@code <user>@<host>:<remotePath>}（user 为空时省略 user@ 前缀）。 */
    private static String formatRemoteTarget(RemoteExecutorConfig config, String remotePath) {
        if (config.user() != null && !config.user().isBlank()) {
            return config.user() + "@" + config.host() + ":" + remotePath;
        }
        return config.host() + ":" + remotePath;
    }

    /** 执行 scp 命令并映射结果（与 SshExecutor 的结果映射一致）。 */
    private RemoteResult runTransfer(List<String> argv, RemoteExecutorConfig config, String desc) {
        long start = System.currentTimeMillis();
        log.info("[ScpFileTransfer] {}", desc);

        CommandRunner.CommandResult r = runner.run(argv, null, null, config.timeoutSeconds());
        long elapsed = System.currentTimeMillis() - start;

        if (r.timedOut()) {
            log.warn("[ScpFileTransfer] 超时 ({}ms): {}", elapsed, desc);
            return RemoteResult.timeout(elapsed);
        }
        if (r.exitCode() == 0) {
            log.info("[ScpFileTransfer] 成功 ({}ms): {}", elapsed, desc);
            return RemoteResult.success(r.stdout(), elapsed);
        }
        log.warn("[ScpFileTransfer] 失败 exit={} ({}ms): {}, stderr={}",
                r.exitCode(), elapsed, desc, r.stderr());
        return RemoteResult.failure(r.exitCode(), r.stdout(), r.stderr(), elapsed);
    }
}
