package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SlurmExecutor} 单元测试 — 静态方法 + 状态机 mock runner 行为测试。
 * <p>
 * 不实际提交任何 Slurm 作业。Mock runner 按 command[0] 分发 canned 结果，
 * 模拟 sbatch → sacct 轮询 → cat 输出 → cleanup 的完整生命周期。
 */
class SlurmExecutorTest {

    /**
     * 状态机 mock — 按 command[0] 分发：
     * <ul>
     *   <li>{@code sbatch} → ok("12345")</li>
     *   <li>{@code sh -c "..."} → 按 shell 命令内容返回（sacct/cat/rm）</li>
     *   <li>{@code scancel} → ok("") + 记录到 cancelledJobs</li>
     *   <li>{@code ssh} → 按 argv 末项（远程 shell）内容分发</li>
     * </ul>
     * 可通过 {@link #sacctResponses} 队列让 sacct 返回不同状态（模拟 PENDING→RUNNING→COMPLETED）。
     * 默认 sacct 返回 "COMPLETED"。
     */
    private static final class StatefulRunner implements CommandRunner {
        final List<List<String>> allCommands = new ArrayList<>();
        final List<String> cancelledJobs = new ArrayList<>();
        final List<String> sshCaptured = new ArrayList<>();

        /** sacct 返回队列；空时用 defaultSacctResponse。 */
        List<String> sacctResponses = new ArrayList<>();
        String defaultSacctResponse = "COMPLETED";
        String catOutResponse = "train-done";
        String catErrResponse = "";
        String sbatchResponse = "12345";

        @Override
        public CommandResult run(List<String> command, Map<String, String> env,
                                  File workingDir, long timeoutSeconds) {
            allCommands.add(new ArrayList<>(command));
            if (command.isEmpty()) return CommandResult.fail(-1, "empty");

            String head = command.get(0);
            switch (head) {
                case "sbatch":
                    return CommandResult.ok(sbatchResponse + "\n");
                case "scancel": {
                    // scancel <jobId>
                    if (command.size() > 1) cancelledJobs.add(command.get(1));
                    return CommandResult.ok("");
                }
                case "ssh": {
                    // argv 末项是远程 shell 命令
                    String remoteShell = command.get(command.size() - 1);
                    sshCaptured.add(remoteShell);
                    return dispatchShell(remoteShell);
                }
                case "sh": {
                    // sh -c "<shell>"
                    if (command.size() >= 3) {
                        return dispatchShell(command.get(2));
                    }
                    return CommandResult.ok("");
                }
                default:
                    return CommandResult.ok("default");
            }
        }

        private CommandResult dispatchShell(String shell) {
            if (shell.contains("sbatch --parsable")) {
                // 远程模式：ssh + heredoc 提交 sbatch
                return CommandResult.ok(sbatchResponse + "\n");
            }
            if (shell.contains("sacct")) {
                String state = sacctResponses.isEmpty()
                        ? defaultSacctResponse
                        : sacctResponses.remove(0);
                return CommandResult.ok(state + "\n");
            }
            if (shell.contains("cat slurm-") && shell.contains(".out")) {
                return CommandResult.ok(catOutResponse);
            }
            if (shell.contains("cat slurm-") && shell.contains(".err")) {
                return CommandResult.ok(catErrResponse);
            }
            if (shell.contains("rm -f slurm-")) {
                return CommandResult.ok("");
            }
            if (shell.contains("scancel")) {
                // 远程模式 scancel 经 ssh
                int idx = shell.indexOf("scancel");
                String rest = shell.substring(idx + "scancel".length()).trim();
                String jobId = rest.split("\\s+")[0];
                cancelledJobs.add(jobId);
                return CommandResult.ok("");
            }
            return CommandResult.ok("");
        }
    }

    private StatefulRunner runner;
    private SlurmExecutor executor;

    @BeforeEach
    void setUp() {
        runner = new StatefulRunner();
        executor = new SlurmExecutor(runner);
        executor.setPollIntervalMs(20); // 加速轮询
    }

    // ── 静态方法测试 ──

    @Test
    @DisplayName("buildBatchScript — partition/time/cpus/gpus/output/error directives + env exports + 命令末尾")
    void buildBatchScript_includesAllDirectives_whenConfigured() {
        RemoteExecutorConfig cfg = new RemoteExecutorConfig(
                "slurm", null, 22, null, null, null,
                null, "gpu-partition", 120, 8, 4, "/work",
                null, null, null, null, null,
                600, Map.of("FOO", "bar", "BAZ", "qux"));

        String script = SlurmExecutor.buildBatchScript(cfg, "python train.py --epochs 10");

        assertTrue(script.startsWith("#!/bin/bash\n"), "shebang: " + script);
        assertTrue(script.contains("#SBATCH --partition=gpu-partition"), "partition: " + script);
        assertTrue(script.contains("#SBATCH --time=120"), "time: " + script);
        assertTrue(script.contains("#SBATCH --cpus-per-task=8"), "cpus: " + script);
        assertTrue(script.contains("#SBATCH --gres=gpu:4"), "gpus: " + script);
        assertTrue(script.contains("#SBATCH --output=slurm-%j.out"), "output: " + script);
        assertTrue(script.contains("#SBATCH --error=slurm-%j.err"), "error: " + script);
        assertTrue(script.contains("export FOO=bar"), "FOO env: " + script);
        assertTrue(script.contains("export BAZ=qux"), "BAZ env: " + script);
        assertTrue(script.trim().endsWith("python train.py --epochs 10"),
                "command at end: " + script);
    }

    @Test
    @DisplayName("buildBatchScript — null partition / 0 cpus / 0 gpus / 0 timeLimit → 对应 directive 不出现")
    void buildBatchScript_omitsDirectives_whenBlankOrZero() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.slurm(null, 0, 0);

        String script = SlurmExecutor.buildBatchScript(cfg, "echo hi");

        assertFalse(script.contains("#SBATCH --partition="), "no partition: " + script);
        assertFalse(script.contains("#SBATCH --time="), "no time: " + script);
        assertFalse(script.contains("#SBATCH --cpus-per-task="), "no cpus: " + script);
        assertFalse(script.contains("#SBATCH --gres=gpu:"), "no gpus: " + script);
        // output/error directives 始终有
        assertTrue(script.contains("#SBATCH --output=slurm-%j.out"));
        assertTrue(script.contains("#SBATCH --error=slurm-%j.err"));
        assertTrue(script.trim().endsWith("echo hi"));
    }

    @Test
    @DisplayName("parseJobId — --parsable 纯数字、'Submitted batch job N'、null/空/garbage")
    void parseJobId_handlesParsableAndDefaultFormats() {
        assertEquals("12345", SlurmExecutor.parseJobId("12345"));
        assertEquals("12345", SlurmExecutor.parseJobId("  12345\n"));
        assertEquals("12345", SlurmExecutor.parseJobId("Submitted batch job 12345"));
        assertEquals("67890", SlurmExecutor.parseJobId("Submitted batch job 67890 submitted"));
        assertNull(SlurmExecutor.parseJobId(null));
        assertNull(SlurmExecutor.parseJobId(""));
        assertNull(SlurmExecutor.parseJobId("   "));
        assertNull(SlurmExecutor.parseJobId("garbage no numbers"));
    }

    @Test
    @DisplayName("isTerminalState — 终态 true，非终态 false")
    void isTerminalState_classifiesCorrectly() {
        // 终态
        assertTrue(SlurmExecutor.isTerminalState("COMPLETED"));
        assertTrue(SlurmExecutor.isTerminalState("FAILED"));
        assertTrue(SlurmExecutor.isTerminalState("TIMEOUT"));
        assertTrue(SlurmExecutor.isTerminalState("CANCELLED"));
        assertTrue(SlurmExecutor.isTerminalState("OUT_OF_MEMORY"));
        assertTrue(SlurmExecutor.isTerminalState("NODE_FAIL"));
        assertTrue(SlurmExecutor.isTerminalState("BOOT_FAIL"));
        // 大小写不敏感
        assertTrue(SlurmExecutor.isTerminalState("completed"));
        assertTrue(SlurmExecutor.isTerminalState("Cancelled"));
        // 带后缀（如 "COMPLETED+"）
        assertTrue(SlurmExecutor.isTerminalState("COMPLETED+"));

        // 非终态
        assertFalse(SlurmExecutor.isTerminalState("PENDING"));
        assertFalse(SlurmExecutor.isTerminalState("RUNNING"));
        assertFalse(SlurmExecutor.isTerminalState("CONFIGURING"));
        assertFalse(SlurmExecutor.isTerminalState("COMPLETING"));
        assertFalse(SlurmExecutor.isTerminalState(""));
        assertFalse(SlurmExecutor.isTerminalState(null));
    }

    @Test
    @DisplayName("isCompletedState — COMPLETED 前缀 true，其他 false")
    void isCompletedState_onlyMatchesCompletedPrefix() {
        assertTrue(SlurmExecutor.isCompletedState("COMPLETED"));
        assertTrue(SlurmExecutor.isCompletedState("completed"));
        assertTrue(SlurmExecutor.isCompletedState("COMPLETED+"));
        assertFalse(SlurmExecutor.isCompletedState("FAILED"));
        assertFalse(SlurmExecutor.isCompletedState("TIMEOUT"));
        assertFalse(SlurmExecutor.isCompletedState(null));
        assertFalse(SlurmExecutor.isCompletedState(""));
    }

    // ── 行为测试（状态机 mock） ──

    @Test
    @DisplayName("本地 sbatch + COMPLETED → success，stdout 来自 cat slurm-{jobId}.out")
    void localSbatch_completed_returnsSuccess() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.slurm("gpu", 4, 1);
        // 重新构造带 timeoutSeconds=5 的 cfg（默认 600 太长，轮询超时上限也受其限制）
        cfg = withTimeout(cfg, 5);

        RemoteResult r = executor.execute(cfg, "python train.py", "/work");

        assertTrue(r.success(), "should be success: " + r.errorMessage());
        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("train-done"),
                "stdout should come from cat .out: " + r.stdout());
        // 应该看到 sbatch + sacct + cat .out + cat .err + cleanup 命令序列
        assertFalse(runner.allCommands.isEmpty());
    }

    @Test
    @DisplayName("本地 sbatch + FAILED → failure，exitCode=1，errorMessage 含 FAILED")
    void localSbatch_failed_returnsFailure() {
        runner.defaultSacctResponse = "FAILED";
        runner.catErrResponse = "oom killed";
        RemoteExecutorConfig cfg = withTimeout(RemoteExecutorConfig.slurm("gpu", 4, 1), 5);

        RemoteResult r = executor.execute(cfg, "python train.py", "/work");

        assertFalse(r.success());
        assertEquals(1, r.exitCode());
        assertTrue(r.errorMessage().contains("FAILED"),
                "errorMessage should contain FAILED: " + r.errorMessage());
    }

    @Test
    @DisplayName("本地 sbatch + 永不终态 → timeout + scancel 调用")
    void localSbatch_neverTerminal_timesOutAndScancels() {
        runner.defaultSacctResponse = "RUNNING"; // 永不终态
        RemoteExecutorConfig cfg = withTimeout(RemoteExecutorConfig.slurm("gpu", 4, 1), 2);

        RemoteResult r = executor.execute(cfg, "python train.py", "/work");

        assertFalse(r.success());
        assertEquals(-1, r.exitCode());
        assertTrue(r.errorMessage().contains("timed out"),
                "errorMessage should mention timed out: " + r.errorMessage());
        // 验证 scancel 被调用
        assertFalse(runner.cancelledJobs.isEmpty(),
                "scancel should be invoked: " + runner.cancelledJobs);
        assertEquals("12345", runner.cancelledJobs.get(0));
    }

    @Test
    @DisplayName("远程模式（slurmLoginHost 非空）→ 经 SshExecutor 透传，ssh 捕获含 sbatch --parsable")
    void remoteMode_delegatesToSshExecutor() {
        RemoteExecutorConfig cfg = withTimeout(
                RemoteExecutorConfig.slurm("login1.cluster", "gpu", 4, 1, "/remote/work"),
                5);

        RemoteResult r = executor.execute(cfg, "python train.py", "/remote/work");

        assertTrue(r.success(), "should be success: " + r.errorMessage());
        assertTrue(r.stdout().contains("train-done"),
                "stdout from remote cat: " + r.stdout());
        // 至少捕获到 sbatch/sacct/cat 三类 ssh 命令
        assertFalse(runner.sshCaptured.isEmpty(), "should have ssh calls");
        boolean hasSbatch = runner.sshCaptured.stream().anyMatch(s -> s.contains("sbatch --parsable"));
        assertTrue(hasSbatch, "ssh should carry sbatch --parsable: " + runner.sshCaptured);
    }

    @Test
    @DisplayName("type() 返回 'slurm'")
    void type_returnsSlurm() {
        assertEquals("slurm", executor.type());
    }

    @Test
    @DisplayName("config=null / command=null/空 → configError，不抛异常")
    void nullConfigAndEmptyCommand_returnConfigError() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.slurm("gpu", 1, 0);

        RemoteResult r1 = executor.execute(null, "cmd", null);
        RemoteResult r2 = executor.execute(cfg, null, null);
        RemoteResult r3 = executor.execute(cfg, "   ", null);

        assertFalse(r1.success());
        assertFalse(r2.success());
        assertFalse(r3.success());
        assertTrue(r1.errorMessage().contains("config is null"));
        assertTrue(r2.errorMessage().contains("command is empty"));
        assertTrue(r3.errorMessage().contains("command is empty"));
    }

    // ── helpers ──

    /** 复制 cfg 但替换 timeoutSeconds（record 无 with- 方法，手动构造）。 */
    private static RemoteExecutorConfig withTimeout(RemoteExecutorConfig cfg, long timeoutSeconds) {
        return new RemoteExecutorConfig(
                cfg.type(), cfg.host(), cfg.port(), cfg.user(), cfg.privateKeyPath(),
                cfg.knownHostsPath(),
                cfg.slurmLoginHost(), cfg.partition(), cfg.timeLimitMinutes(),
                cfg.cpus(), cfg.gpus(), cfg.remoteWorkDir(),
                cfg.modalAppPath(), cfg.modalFunctionName(),
                cfg.modalTokenId(), cfg.modalTokenSecret(), cfg.modalWorkspace(),
                timeoutSeconds, cfg.env());
    }
}
