package com.ouisani.aios.core.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slurm 执行器 — 通过 {@code sbatch}+{@code sacct} CLI 在 Slurm 集群上提交并轮询批处理作业。
 * <p>
 * <b>执行流程</b>：
 * <ol>
 *   <li>生成 batch script（含 #SBATCH directives + 用户命令）</li>
 *   <li>{@code sbatch --parsable script.sh} → 解析 jobId</li>
 *   <li>每 {@link #pollIntervalMs} 轮询 {@code sacct -j <jobId> --format=State --noheader -P}</li>
 *   <li>终态（COMPLETED/FAILED/TIMEOUT/CANCELLED/...）→ 读取 {@code slurm-<jobId>.out}</li>
 *   <li>清理临时脚本与输出文件（best-effort）</li>
 * </ol>
 * <p>
 * <b>本地 vs 远程模式</b>：
 * <ul>
 *   <li>{@code slurmLoginHost} 为 null/空 → 本地 sbatch（集群头节点就是本机）</li>
 *   <li>{@code slurmLoginHost} 非空 → 所有 sbatch/sacct/cat 命令经内部 {@link SshExecutor}
 *       转发到登录节点。SSH 凭据复用 config 的 {@code user}/{@code privateKeyPath}/{@code knownHostsPath}</li>
 * </ul>
 * <p>
 * <b>同步阻塞</b>：{@link #execute} 阻塞到作业终态或总超时 {@link RemoteExecutorConfig#timeoutSeconds()}。
 *
 * @see RemoteExecutor
 * @see SshExecutor
 */
public final class SlurmExecutor implements RemoteExecutor {

    private static final Logger log = LoggerFactory.getLogger(SlurmExecutor.class);

    /** 默认轮询间隔（5 秒）。 */
    private static final long DEFAULT_POLL_INTERVAL_MS = 5000L;

    private final CommandRunner runner;
    private final SshExecutor sshExecutor;
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    /**
     * jobId → workingDir 的瞬态映射 — submit 时记录作业的工作目录，retrieve 时取回
     * （{@link RemoteExecutor#retrieve} 接口签名无 workingDir 参数，但 readOutput/cleanup
     * 的 {@code cat slurm-<jobId>.out} 必须在 sbatch 运行目录执行才能找到输出文件）。
     * retrieve/超时路径移除条目，避免内存泄漏。
     */
    private final Map<String, String> jobWorkingDirs = new ConcurrentHashMap<>();

    /** 生产构造器。 */
    public SlurmExecutor() {
        this(DefaultCommandRunner.INSTANCE);
    }

    /** 测试构造器：注入 mock runner。 */
    public SlurmExecutor(CommandRunner runner) {
        this.runner = runner;
        this.sshExecutor = new SshExecutor(runner);
    }

    /** 设置轮询间隔（测试用，可设为 10ms 加速测试）。 */
    void setPollIntervalMs(long ms) {
        this.pollIntervalMs = ms;
    }

    @Override
    public RemoteResult execute(RemoteExecutorConfig config, String command, String workingDir) {
        if (config == null) return RemoteResult.configError("config is null");
        if (command == null || command.isBlank()) return RemoteResult.configError("command is empty");

        boolean remote = config.slurmViaLoginNode();
        log.info("[SlurmExecutor] 提交作业: remote={}, partition={}, cpus={}, gpus={}, cmd={}",
                remote, config.partition(), config.cpus(), config.gpus(), command);

        // ── 1. submit（sbatch） ──
        RemoteJobHandle handle;
        try {
            handle = submit(config, command, workingDir);
        } catch (RemoteJobException e) {
            // sbatch 失败 — submit 已 catch runSbatch 的失败并抛 RemoteJobException，转 configError 保持原语义
            return RemoteResult.configError(e.getMessage());
        }
        log.info("[SlurmExecutor] 作业已提交: jobId={}", handle.jobId());

        // ── 2. 轮询 poll 直到终态或总超时 ──
        long deadline = handle.submittedAt() + config.timeoutSeconds() * 1000L;
        RemoteJobSnapshot snap;
        while (true) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                jobWorkingDirs.remove(handle.jobId());
                return RemoteResult.failure(-1, "", "polling interrupted",
                        System.currentTimeMillis() - handle.submittedAt());
            }
            snap = poll(config, handle);
            if (snap.status().isTerminal()) {
                break;
            }
            log.debug("[SlurmExecutor] 作业 {} 状态: {}", handle.jobId(), snap.status());
            if (System.currentTimeMillis() >= deadline) {
                long elapsed = System.currentTimeMillis() - handle.submittedAt();
                log.warn("[SlurmExecutor] 作业 {} 总超时 ({}ms)", handle.jobId(), elapsed);
                scancel(config, handle.jobId(), workingDir);
                jobWorkingDirs.remove(handle.jobId());
                return RemoteResult.timeout(elapsed);
            }
        }

        // ── 3. retrieve（读输出 + 清理） ──
        return retrieve(config, handle);
    }

    // ════════════════════════════════════════════════════════════════
    //  异步 API（R4.1）— 真异步覆写
    // ════════════════════════════════════════════════════════════════

    @Override
    public RemoteJobHandle submit(RemoteExecutorConfig config, String command, String workingDir) {
        if (config == null) throw new RemoteJobException("config is null");
        if (command == null || command.isBlank()) throw new RemoteJobException("command is empty");

        String script = buildBatchScript(config, command);
        SlurmCommandOutcome sbatchOutcome = runSbatch(config, script, workingDir);
        if (!sbatchOutcome.success) {
            throw new RemoteJobException("sbatch failed: " + sbatchOutcome.result.errorMessage());
        }
        String jobId = sbatchOutcome.jobId;
        // 记录工作目录，供 retrieve 的 readOutput/cleanup 使用（接口 retrieve 无 workingDir 参数）
        jobWorkingDirs.put(jobId, workingDir);
        return new RemoteJobHandle(jobId, "slurm", System.currentTimeMillis());
    }

    @Override
    public RemoteJobSnapshot poll(RemoteExecutorConfig config, RemoteJobHandle handle) {
        // sacct 按 jobId 全局查询，workingDir 不影响（传 null 即可）
        SlurmCommandOutcome stateOutcome = runSacct(config, handle.jobId(), null);
        if (!stateOutcome.success) {
            return new RemoteJobSnapshot(handle, RemoteJobStatus.UNKNOWN, "", "", 0L);
        }
        String state = stateOutcome.jobId; // 复用字段名存 state
        RemoteJobStatus status = mapSlurmState(state);
        return new RemoteJobSnapshot(handle, status, "", "", 0L);
    }

    @Override
    public RemoteResult retrieve(RemoteExecutorConfig config, RemoteJobHandle handle) {
        String workingDir = jobWorkingDirs.getOrDefault(handle.jobId(), null);
        RemoteJobSnapshot snap = poll(config, handle);
        SlurmCommandOutcome outputOutcome = readOutput(config, handle.jobId(), workingDir);
        cleanup(config, handle.jobId(), workingDir);
        jobWorkingDirs.remove(handle.jobId());

        long elapsed = System.currentTimeMillis() - handle.submittedAt();
        String stdout = outputOutcome.result.stdout();
        String stderr = outputOutcome.result.stderr();

        if (snap.status() == RemoteJobStatus.COMPLETED) {
            log.info("[SlurmExecutor] 作业 {} COMPLETED ({}ms)", handle.jobId(), elapsed);
            return RemoteResult.success(stdout, elapsed);
        }
        if (snap.status() == RemoteJobStatus.TIMEOUT) {
            log.warn("[SlurmExecutor] 作业 {} TIMEOUT ({}ms)", handle.jobId(), elapsed);
            return RemoteResult.timeout(elapsed);
        }
        // FAILED / CANCELLED / UNKNOWN
        String stateLabel = snap.status() == RemoteJobStatus.UNKNOWN ? "UNKNOWN" : snap.status().name();
        String errMsg = "slurm job " + handle.jobId() + " " + stateLabel
                + (stderr != null && !stderr.isBlank() ? ": " + stderr.trim() : "");
        log.warn("[SlurmExecutor] 作业 {} {}: {}", handle.jobId(), stateLabel, stderr);
        return new RemoteResult(1, stdout, stderr, elapsed, false, errMsg);
    }

    /**
     * 把 Slurm State 字符串映射为 {@link RemoteJobStatus}。
     * <p>
     * COMPLETED* → COMPLETED；TIMEOUT* → TIMEOUT；CANCELLED* → CANCELLED；
     * 其他终态（FAILED/OUT_OF_MEMORY/NODE_FAIL/BOOT_FAIL）→ FAILED；非终态 → RUNNING。
     */
    static RemoteJobStatus mapSlurmState(String state) {
        if (state == null || state.isBlank()) return RemoteJobStatus.RUNNING;
        String upper = state.toUpperCase();
        if (upper.startsWith("COMPLETED")) return RemoteJobStatus.COMPLETED;
        if (upper.startsWith("TIMEOUT")) return RemoteJobStatus.TIMEOUT;
        if (upper.startsWith("CANCELLED")) return RemoteJobStatus.CANCELLED;
        if (isTerminalState(state)) return RemoteJobStatus.FAILED; // 其余终态归 FAILED
        return RemoteJobStatus.RUNNING;
    }

    @Override
    public String type() {
        return "slurm";
    }

    // ════════════════════════════════════════════════════════════════
    //  batch script 生成（package-private 便于测试断言）
    // ════════════════════════════════════════════════════════════════

    /** 生成 Slurm batch script 内容。 */
    static String buildBatchScript(RemoteExecutorConfig config, String command) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        if (config.partition() != null && !config.partition().isBlank()) {
            sb.append("#SBATCH --partition=").append(config.partition()).append("\n");
        }
        if (config.timeLimitMinutes() > 0) {
            sb.append("#SBATCH --time=").append(config.timeLimitMinutes()).append("\n");
        }
        if (config.cpus() > 0) {
            sb.append("#SBATCH --cpus-per-task=").append(config.cpus()).append("\n");
        }
        if (config.gpus() > 0) {
            sb.append("#SBATCH --gres=gpu:").append(config.gpus()).append("\n");
        }
        sb.append("#SBATCH --output=slurm-%j.out\n");
        sb.append("#SBATCH --error=slurm-%j.err\n");
        sb.append("\n");
        // env 注入到 batch script（作业运行时生效）
        if (config.env() != null && !config.env().isEmpty()) {
            for (Map.Entry<String, String> e : config.env().entrySet()) {
                sb.append("export ").append(e.getKey()).append("=")
                        .append(SshExecutor.shellQuote(e.getValue() == null ? "" : e.getValue()))
                        .append("\n");
            }
        }
        sb.append(command).append("\n");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  sbatch / sacct / cat / scancel / cleanup
    // ════════════════════════════════════════════════════════════════

    /** 内部多用途结果：sbatch 成功时 jobId 存作业 ID，sacct 成功时 jobId 存 state。 */
    private record SlurmCommandOutcome(boolean success, String jobId, RemoteResult result) {
        static SlurmCommandOutcome ok(String jobId) {
            return new SlurmCommandOutcome(true, jobId, null);
        }
        static SlurmCommandOutcome fail(RemoteResult r) {
            return new SlurmCommandOutcome(false, null, r);
        }
        static SlurmCommandOutcome okWithResult(RemoteResult r) {
            return new SlurmCommandOutcome(true, null, r);
        }
    }

    /** 提交作业：本地用 sbatch，远程用 ssh + heredoc。 */
    private SlurmCommandOutcome runSbatch(RemoteExecutorConfig config, String script, String workingDir) {
        if (config.slurmViaLoginNode()) {
            // 远程：通过 ssh + heredoc 把 script 喂给 sbatch --parsable
            String remoteCmd = "sbatch --parsable << 'AIOS_SBATCH_EOF'\n"
                    + script
                    + "AIOS_SBATCH_EOF";
            RemoteExecutorConfig sshCfg = deriveSshConfig(config);
            RemoteResult r = sshExecutor.execute(sshCfg, remoteCmd, workingDir);
            if (!r.success()) {
                return SlurmCommandOutcome.fail(RemoteResult.failure(r.exitCode(), r.stdout(), r.stderr(), r.durationMs()));
            }
            String jobId = parseJobId(r.stdout());
            if (jobId == null) {
                return SlurmCommandOutcome.fail(RemoteResult.configError(
                        "cannot parse jobId from sbatch output: " + r.stdout()));
            }
            return SlurmCommandOutcome.ok(jobId);
        }

        // 本地：写 temp script 文件，sbatch --parsable <file>
        Path tempScript = null;
        try {
            tempScript = Files.createTempFile("aios-slurm-", ".sh");
            Files.writeString(tempScript, script);
            tempScript.toFile().setExecutable(true);
            File wd = workingDir != null ? new File(workingDir) : null;
            CommandRunner.CommandResult r = runner.run(
                    List.of("sbatch", "--parsable", tempScript.toString()),
                    null, wd, config.timeoutSeconds());
            if (r.timedOut()) {
                return SlurmCommandOutcome.fail(RemoteResult.timeout(r.exitCode() >= 0 ? r.exitCode() : 0));
            }
            if (r.exitCode() != 0) {
                return SlurmCommandOutcome.fail(RemoteResult.failure(
                        r.exitCode(), r.stdout(), r.stderr(), 0));
            }
            String jobId = parseJobId(r.stdout());
            if (jobId == null) {
                return SlurmCommandOutcome.fail(RemoteResult.configError(
                        "cannot parse jobId from sbatch output: " + r.stdout()));
            }
            return SlurmCommandOutcome.ok(jobId);
        } catch (Exception e) {
            return SlurmCommandOutcome.fail(RemoteResult.configError(
                    "sbatch failed: " + e.getMessage()));
        } finally {
            if (tempScript != null) {
                try { Files.deleteIfExists(tempScript); } catch (Exception ignored) {}
            }
        }
    }

    /** 查询作业状态：sacct -j <jobId> --format=State --noheader -P。 */
    private SlurmCommandOutcome runSacct(RemoteExecutorConfig config, String jobId, String workingDir) {
        String sacctCmd = "sacct -j " + jobId + " --format=State --noheader -P | head -1";
        if (config.slurmViaLoginNode()) {
            RemoteExecutorConfig sshCfg = deriveSshConfig(config);
            RemoteResult r = sshExecutor.execute(sshCfg, sacctCmd, workingDir);
            if (!r.success()) {
                return SlurmCommandOutcome.fail(r);
            }
            return SlurmCommandOutcome.ok(r.stdout().trim());
        }
        File wd = workingDir != null ? new File(workingDir) : null;
        CommandRunner.CommandResult r = runner.run(
                List.of("sh", "-c", sacctCmd),
                null, wd, Math.min(config.timeoutSeconds(), 60));
        if (r.timedOut() || r.exitCode() != 0) {
            return SlurmCommandOutcome.fail(RemoteResult.failure(
                    r.exitCode(), r.stdout(), r.stderr(), 0));
        }
        return SlurmCommandOutcome.ok(r.stdout().trim());
    }

    /** 读取作业输出文件 slurm-<jobId>.out。 */
    private SlurmCommandOutcome readOutput(RemoteExecutorConfig config, String jobId, String workingDir) {
        String catCmd = "cat slurm-" + jobId + ".out 2>/dev/null";
        String errCmd = "cat slurm-" + jobId + ".err 2>/dev/null";

        if (config.slurmViaLoginNode()) {
            RemoteExecutorConfig sshCfg = deriveSshConfig(config);
            RemoteResult stdoutR = sshExecutor.execute(sshCfg, catCmd, workingDir);
            RemoteResult stderrR = sshExecutor.execute(sshCfg, errCmd, workingDir);
            RemoteResult combined = new RemoteResult(
                    stdoutR.exitCode(),
                    stdoutR.success() ? stdoutR.stdout() : "",
                    stderrR.success() ? stderrR.stderr() : "",
                    stdoutR.durationMs(),
                    true,
                    "");
            return SlurmCommandOutcome.okWithResult(combined);
        }

        File wd = workingDir != null ? new File(workingDir) : null;
        CommandRunner.CommandResult stdoutR = runner.run(
                List.of("sh", "-c", catCmd), null, wd, 30);
        CommandRunner.CommandResult stderrR = runner.run(
                List.of("sh", "-c", errCmd), null, wd, 30);
        RemoteResult combined = new RemoteResult(
                stdoutR.exitCode(),
                stdoutR.stdout(),
                stderrR.stdout(), // CommandRunner 把 stderr 合到 stdout，这里 errCmd 的输出在 stdout
                0,
                true,
                "");
        return SlurmCommandOutcome.okWithResult(combined);
    }

    /** 取消作业（best-effort，超时或异常时不影响主流程）。 */
    private void scancel(RemoteExecutorConfig config, String jobId, String workingDir) {
        try {
            if (config.slurmViaLoginNode()) {
                RemoteExecutorConfig sshCfg = deriveSshConfig(config);
                sshExecutor.execute(sshCfg, "scancel " + jobId, workingDir);
            } else {
                File wd = workingDir != null ? new File(workingDir) : null;
                runner.run(List.of("scancel", jobId), null, wd, 30);
            }
        } catch (Exception e) {
            log.warn("[SlurmExecutor] scancel 失败 (best-effort 忽略): jobId={}, err={}",
                    jobId, e.getMessage());
        }
    }

    /** 清理输出文件（best-effort）。 */
    private void cleanup(RemoteExecutorConfig config, String jobId, String workingDir) {
        try {
            String rmCmd = "rm -f slurm-" + jobId + ".out slurm-" + jobId + ".err";
            if (config.slurmViaLoginNode()) {
                RemoteExecutorConfig sshCfg = deriveSshConfig(config);
                sshExecutor.execute(sshCfg, rmCmd, workingDir);
            } else {
                File wd = workingDir != null ? new File(workingDir) : null;
                runner.run(List.of("sh", "-c", rmCmd), null, wd, 30);
            }
        } catch (Exception e) {
            log.debug("[SlurmExecutor] cleanup 失败 (best-effort 忽略): jobId={}, err={}",
                    jobId, e.getMessage());
        }
    }

    /** 从 Slurm config 派生 SSH config（用于远程模式连登录节点）。 */
    private static RemoteExecutorConfig deriveSshConfig(RemoteExecutorConfig config) {
        return new RemoteExecutorConfig(
                "ssh",
                config.slurmLoginHost(),
                config.port(),
                config.user(),
                config.privateKeyPath(),
                config.knownHostsPath(),
                null, null, 0, 0, 0, config.remoteWorkDir(),
                null, null, null, null, null,
                config.timeoutSeconds(),
                config.env());
    }

    // ════════════════════════════════════════════════════════════════
    //  jobId / state 解析（package-private 便于测试）
    // ════════════════════════════════════════════════════════════════

    /** 解析 sbatch 输出为 jobId。支持 {@code --parsable}（纯数字）和默认格式（"Submitted batch job 12345"）。 */
    static String parseJobId(String sbatchOutput) {
        if (sbatchOutput == null || sbatchOutput.isBlank()) return null;
        String trimmed = sbatchOutput.trim();
        // --parsable: 纯数字
        if (trimmed.matches("\\d+")) return trimmed;
        // 默认格式: "Submitted batch job 12345"
        String[] parts = trimmed.split("\\s+");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].matches("\\d+")) return parts[i];
        }
        return null;
    }

    /** 是否为 Slurm 终态（不再变化的状态）。 */
    static boolean isTerminalState(String state) {
        if (state == null || state.isBlank()) return false;
        String upper = state.toUpperCase();
        return upper.startsWith("COMPLETED")
                || upper.startsWith("FAILED")
                || upper.startsWith("TIMEOUT")
                || upper.startsWith("CANCELLED")
                || upper.startsWith("OUT_OF_MEMORY")
                || upper.startsWith("NODE_FAIL")
                || upper.startsWith("BOOT_FAIL");
    }

    /** 是否为成功终态。 */
    static boolean isCompletedState(String state) {
        return state != null && state.toUpperCase().startsWith("COMPLETED");
    }
}
