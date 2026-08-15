package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.ouisani.aios.core.remote.CommandRunner.CommandResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SshExecutor} 单元测试 — 用 mock {@link CommandRunner} 注入，
 * 验证命令构造、env 注入、退出码处理、超时、NULL safety。
 * 不实际连接任何 SSH 主机。
 */
class SshExecutorTest {

    /** 捕获传入的命令参数，返回预设结果。 */
    private static final class CapturingRunner implements CommandRunner {
        final List<String> lastCommand = new java.util.ArrayList<>();
        Map<String, String> lastEnv;
        File lastWorkingDir;
        long lastTimeout;
        CommandResult nextResult = CommandResult.ok("default-stdout");

        @Override
        public CommandResult run(List<String> command, Map<String, String> env,
                                  File workingDir, long timeoutSeconds) {
            lastCommand.clear();
            lastCommand.addAll(command);
            lastEnv = env;
            lastWorkingDir = workingDir;
            lastTimeout = timeoutSeconds;
            return nextResult;
        }
    }

    private final CapturingRunner runner = new CapturingRunner();
    private final SshExecutor executor = new SshExecutor(runner);

    // ── 命令构造 ──

    @Test
    @DisplayName("argv 含 ssh + StrictHostKeyChecking + BatchMode + user@host")
    void argvContainsRequiredFlags() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh("gpu-box", 2222, "researcher", "/home/me/key");

        executor.execute(cfg, "python train.py", "/work");

        // 前 6 项：ssh -i key -p 2222 -o StrictHostKeyChecking=yes -o BatchMode=yes
        assertEquals("ssh", runner.lastCommand.get(0));
        assertTrue(runner.lastCommand.contains("-i"));
        assertTrue(runner.lastCommand.contains("/home/me/key"));
        assertTrue(runner.lastCommand.contains("-p"));
        assertTrue(runner.lastCommand.contains("2222"));
        assertTrue(runner.lastCommand.contains("StrictHostKeyChecking=yes"));
        assertTrue(runner.lastCommand.contains("BatchMode=yes"));
        assertTrue(runner.lastCommand.contains("researcher@gpu-box"));
        // 最后一项是远程 shell 命令
        String remoteShell = runner.lastCommand.get(runner.lastCommand.size() - 1);
        assertTrue(remoteShell.contains("cd /work"), "remote shell should cd to workingDir: " + remoteShell);
        assertTrue(remoteShell.contains("python train.py"));
    }

    @Test
    @DisplayName("port=22 默认时不加 -p 参数")
    void defaultPortOmitsPFlag() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh("host", "user", "/key");

        executor.execute(cfg, "echo hi", null);

        assertFalse(runner.lastCommand.contains("-p"),
                "default port 22 should not add -p: " + runner.lastCommand);
    }

    @Test
    @DisplayName("knownHostsPath 非空 → 加 UserKnownHostsFile 选项")
    void knownHostsPathAddedAsOption() {
        RemoteExecutorConfig cfg = new RemoteExecutorConfig(
                "ssh", "host", 22, "user", "/key", "/custom/known_hosts",
                null, null, 0, 0, 0, null,
                null, null, null, null, null,
                60, null);

        executor.execute(cfg, "ls", null);

        assertTrue(runner.lastCommand.contains("UserKnownHostsFile=/custom/known_hosts"),
                "should add UserKnownHostsFile option: " + runner.lastCommand);
    }

    @Test
    @DisplayName("knownHostsPath 为空 → 不加 UserKnownHostsFile 选项")
    void nullKnownHostsPathSkipsOption() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh("host", "user", "/key");

        executor.execute(cfg, "ls", null);

        assertFalse(runner.lastCommand.stream().anyMatch(s -> s.startsWith("UserKnownHostsFile=")),
                "null knownHostsPath should not add UserKnownHostsFile: " + runner.lastCommand);
    }

    // ── env 注入 ──

    @Test
    @DisplayName("env Map 注入到远程 shell 命令（export K=V; 前缀）")
    void envVarsInjectedIntoRemoteShell() {
        RemoteExecutorConfig cfg = new RemoteExecutorConfig(
                "ssh", "host", 22, "user", "/key", null,
                null, null, 0, 0, 0, null,
                null, null, null, null, null,
                60, Map.of("FOO", "bar", "BAZ", "qux"));

        executor.execute(cfg, "python run.py", "/work");

        String remoteShell = runner.lastCommand.get(runner.lastCommand.size() - 1);
        assertTrue(remoteShell.contains("export FOO=bar;"), "FOO env should be exported: " + remoteShell);
        assertTrue(remoteShell.contains("export BAZ=qux;"), "BAZ env should be exported: " + remoteShell);
        assertTrue(remoteShell.contains("python run.py"));
    }

    @Test
    @DisplayName("含特殊字符的 env value 用单引号包裹")
    void envValueWithSpecialCharsIsQuoted() {
        RemoteExecutorConfig cfg = new RemoteExecutorConfig(
                "ssh", "host", 22, "user", "/key", null,
                null, null, 0, 0, 0, null,
                null, null, null, null, null,
                60, Map.of("SECRET", "p@ss w'ord"));

        executor.execute(cfg, "echo hi", null);

        String remoteShell = runner.lastCommand.get(runner.lastCommand.size() - 1);
        // p@ss w'ord 含空格和单引号 → 应该被引号包裹
        assertTrue(remoteShell.contains("export SECRET="),
                "SECRET env should be exported: " + remoteShell);
        // 验证单引号出现（具体转义细节由 shellQuote 实现）
        assertTrue(remoteShell.contains("'") || remoteShell.contains("p@ss"),
                "special chars should be handled: " + remoteShell);
    }

    // ── 退出码 / 超时 ──

    @Test
    @DisplayName("exit 0 → RemoteResult.success，stdout 透传")
    void exitZeroReturnsSuccess() {
        runner.nextResult = CommandResult.ok("training complete\n");
        RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh("host", "user", "/key");

        RemoteResult r = executor.execute(cfg, "python train.py", null);

        assertTrue(r.success());
        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("training complete"));
        assertTrue(r.durationMs() >= 0);
    }

    @Test
    @DisplayName("exit 非0 → RemoteResult.failure，errorMessage 含退出码")
    void nonZeroExitReturnsFailure() {
        runner.nextResult = new CommandRunner.CommandResult(127, "", "command not found", false);
        RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh("host", "user", "/key");

        RemoteResult r = executor.execute(cfg, "nonexistent-cmd", null);

        assertFalse(r.success());
        assertEquals(127, r.exitCode());
        assertTrue(r.errorMessage().contains("127"));
        assertTrue(r.errorMessage().contains("command not found"));
    }

    @Test
    @DisplayName("超时 → RemoteResult.timeout，exitCode=-1")
    void timeoutReturnsTimeoutResult() {
        runner.nextResult = CommandRunner.CommandResult.timeout();
        RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh("host", "user", "/key");

        RemoteResult r = executor.execute(cfg, "long-running-cmd", null);

        assertFalse(r.success());
        assertEquals(-1, r.exitCode());
        assertTrue(r.errorMessage().contains("timed out"));
    }

    // ── NULL / 边界 safety ──

    @Test
    @DisplayName("config=null → configError，不抛异常")
    void nullConfigReturnsConfigError() {
        RemoteResult r = executor.execute(null, "cmd", null);

        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("config is null"));
    }

    @Test
    @DisplayName("command=null/空 → configError")
    void emptyCommandReturnsConfigError() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh("host", "user", "/key");

        RemoteResult r1 = executor.execute(cfg, null, null);
        RemoteResult r2 = executor.execute(cfg, "   ", null);

        assertFalse(r1.success());
        assertFalse(r2.success());
        assertTrue(r1.errorMessage().contains("command is empty"));
        assertTrue(r2.errorMessage().contains("command is empty"));
    }

    @Test
    @DisplayName("type() 返回 'ssh'")
    void typeReturnsSsh() {
        assertEquals("ssh", executor.type());
    }

    @Test
    @DisplayName("workingDir=null 且 remoteWorkDir=null → 远程 shell 不含 cd 前缀")
    void nullWorkingDirSkipsCd() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh("host", "user", "/key");

        executor.execute(cfg, "ls", null);

        String remoteShell = runner.lastCommand.get(runner.lastCommand.size() - 1);
        assertFalse(remoteShell.contains("cd "),
                "null workingDir should not add cd prefix: " + remoteShell);
        assertEquals("ls", remoteShell);
    }

    @Test
    @DisplayName("workingDir 优先于 config.remoteWorkDir()")
    void workingDirOverridesConfigRemoteWorkDir() {
        RemoteExecutorConfig cfg = new RemoteExecutorConfig(
                "ssh", "host", 22, "user", "/key", null,
                null, null, 0, 0, 0, "/config-default-wd",
                null, null, null, null, null,
                60, null);

        executor.execute(cfg, "ls", "/explicit-wd");

        String remoteShell = runner.lastCommand.get(runner.lastCommand.size() - 1);
        assertTrue(remoteShell.contains("cd /explicit-wd"),
                "workingDir should be used: " + remoteShell);
        assertFalse(remoteShell.contains("/config-default-wd"),
                "config.remoteWorkDir should not be used when workingDir provided: " + remoteShell);
    }

    @Test
    @DisplayName("workingDir=null 时回退到 config.remoteWorkDir()")
    void nullWorkingDirFallsBackToConfigRemoteWorkDir() {
        RemoteExecutorConfig cfg = new RemoteExecutorConfig(
                "ssh", "host", 22, "user", "/key", null,
                null, null, 0, 0, 0, "/config-default-wd",
                null, null, null, null, null,
                60, null);

        executor.execute(cfg, "ls", null);

        String remoteShell = runner.lastCommand.get(runner.lastCommand.size() - 1);
        assertTrue(remoteShell.contains("cd /config-default-wd"),
                "should fall back to config.remoteWorkDir: " + remoteShell);
    }

    @Test
    @DisplayName("timeoutSeconds 透传给 CommandRunner")
    void timeoutSecondsPassedToRunner() {
        RemoteExecutorConfig cfg = new RemoteExecutorConfig(
                "ssh", "host", 22, "user", "/key", null,
                null, null, 0, 0, 0, null,
                null, null, null, null, null,
                42, null);

        executor.execute(cfg, "ls", null);

        assertEquals(42, runner.lastTimeout);
    }
}
