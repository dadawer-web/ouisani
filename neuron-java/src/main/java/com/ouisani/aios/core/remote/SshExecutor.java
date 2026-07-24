package com.ouisani.aios.core.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SSH 执行器 — 通过 {@code ssh} CLI 在远程主机上执行命令。
 * <p>
 * <b>命令构造</b>：
 * <pre>{@code
 * ssh -i <privateKeyPath> -p <port>
 *     -o StrictHostKeyChecking=yes -o BatchMode=yes
 *     [-o UserKnownHostsFile=<knownHostsPath>]
 *     <user>@<host>
 *     "cd <workingDir> && export K=V; ... && <command>"
 * }</pre>
 * <p>
 * <b>设计选择</b>：
 * <ul>
 *   <li>shell out 到 {@code ssh} CLI 而非引入 JSch/sshj 依赖 — 与 ExternalAgentRunner 一致，
 *       零新增 Maven 依赖，且 OS 自带的 ssh 支持所有高级特性（ProxyJump、ControlMaster 等）</li>
 *   <li>{@code BatchMode=yes} 禁用交互式密码提示，避免挂死</li>
 *   <li>{@code StrictHostKeyChecking=yes} 强校验 host key（首次连陌生主机需先 ssh-keyscan 加入 known_hosts）</li>
 *   <li>env 通过 {@code export K=V;} 前缀注入到远程 shell（不靠 ssh -o SendEnv，因多数 sshd 默认禁用）</li>
 *   <li>非零退出码不抛异常，返回 {@link RemoteResult#failure}</li>
 * </ul>
 *
 * @see RemoteExecutor
 * @see SlurmExecutor（用本类透传命令到 Slurm 登录节点）
 */
public final class SshExecutor implements RemoteExecutor {

    private static final Logger log = LoggerFactory.getLogger(SshExecutor.class);

    private final CommandRunner runner;

    /** 生产构造器：用 {@link DefaultCommandRunner#INSTANCE}。 */
    public SshExecutor() {
        this(DefaultCommandRunner.INSTANCE);
    }

    /** 测试构造器：注入 mock runner。 */
    public SshExecutor(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public RemoteResult execute(RemoteExecutorConfig config, String command, String workingDir) {
        if (config == null) return RemoteResult.configError("config is null");
        if (command == null || command.isBlank()) return RemoteResult.configError("command is empty");

        List<String> argv = buildSshArgv(config);
        String remoteShell = buildRemoteShell(config, command, workingDir, config.env());
        argv.add(remoteShell);

        long start = System.currentTimeMillis();
        log.info("[SshExecutor] 执行: host={}, user={}, cmd={}",
                config.host(), config.user(), command);

        CommandRunner.CommandResult r = runner.run(argv, null, null, config.timeoutSeconds());
        long elapsed = System.currentTimeMillis() - start;

        if (r.timedOut()) {
            log.warn("[SshExecutor] 超时 ({}ms): host={}, cmd={}", elapsed, config.host(), command);
            return RemoteResult.timeout(elapsed);
        }

        if (r.exitCode() == 0) {
            log.info("[SshExecutor] 成功 ({}ms): host={}", elapsed, config.host());
            return RemoteResult.success(r.stdout(), elapsed);
        }

        log.warn("[SshExecutor] 失败 exit={} ({}ms): host={}, stderr={}",
                r.exitCode(), elapsed, config.host(), r.stderr());
        return RemoteResult.failure(r.exitCode(), r.stdout(), r.stderr(), elapsed);
    }

    @Override
    public String type() {
        return "ssh";
    }

    // ════════════════════════════════════════════════════════════════
    //  命令构造（package-private 便于测试断言）
    // ════════════════════════════════════════════════════════════════

    /** 构造 ssh 命令的 argv（不含最后的远程 shell 命令参数）。 */
    static List<String> buildSshArgv(RemoteExecutorConfig config) {
        List<String> argv = new ArrayList<>();
        argv.add("ssh");
        if (config.privateKeyPath() != null && !config.privateKeyPath().isBlank()) {
            argv.add("-i"); argv.add(config.privateKeyPath());
        }
        int port = config.sshPort();
        if (port != 22) {
            argv.add("-p"); argv.add(String.valueOf(port));
        }
        argv.add("-o"); argv.add("StrictHostKeyChecking=yes");
        argv.add("-o"); argv.add("BatchMode=yes");
        if (config.knownHostsPath() != null && !config.knownHostsPath().isBlank()) {
            argv.add("-o"); argv.add("UserKnownHostsFile=" + config.knownHostsPath());
        }
        // user 为 null 时不加 user@ 前缀（让 ssh 用当前用户或 ~/.ssh/config）
        if (config.user() != null && !config.user().isBlank()) {
            argv.add(config.user() + "@" + config.host());
        } else {
            argv.add(config.host());
        }
        return argv;
    }

    /**
     * 构造远程 shell 命令字符串 — 含 cd 前缀、env 注入、用户命令。
     * <p>
     * 例：{@code cd /work && export FOO=bar; export BAZ=qux; python train.py}
     */
    static String buildRemoteShell(RemoteExecutorConfig config, String command,
                                    String workingDir, Map<String, String> env) {
        StringBuilder sb = new StringBuilder();
        String wd = workingDir != null ? workingDir : config.remoteWorkDir();
        if (wd != null && !wd.isBlank()) {
            sb.append("cd ").append(wd).append(" && ");
        }
        if (env != null && !env.isEmpty()) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                sb.append("export ").append(e.getKey()).append("=")
                        .append(shellQuote(e.getValue() == null ? "" : e.getValue()))
                        .append("; ");
            }
        }
        sb.append(command);
        return sb.toString();
    }

    /** 简单的 shell 引号包裹 — 单引号 + 内部单引号转义。 */
    static String shellQuote(String s) {
        if (s == null || s.isEmpty()) return "''";
        if (s.matches("[A-Za-z0-9_./:=@,+~-]+")) return s; // 安全字符免引号
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
