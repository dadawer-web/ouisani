package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultCommandRunner} 契约测试 — 跑真实进程验证 ProcessBuilder 封装正确。
 * <p>
 * 用 {@code echo}/{@code exit}/{@code sleep} 等跨平台 shell 内建命令
 * （Linux/macOS 通用；CI 默认 Linux）。
 */
class CommandRunnerContractTest {

    private final DefaultCommandRunner runner = DefaultCommandRunner.INSTANCE;

    @Test
    @DisplayName("echo hello → exit 0, stdout 含 hello")
    void echoReturnsZeroExitAndStdout() {
        CommandRunner.CommandResult r = runner.run(
                List.of("sh", "-c", "echo hello"),
                null, null, 5);

        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("hello"), "stdout should contain 'hello'");
        assertFalse(r.timedOut());
    }

    @Test
    @DisplayName("exit 3 → 非零退出码")
    void nonZeroExitCodeReturned() {
        CommandRunner.CommandResult r = runner.run(
                List.of("sh", "-c", "exit 3"),
                null, null, 5);

        assertEquals(3, r.exitCode());
        assertFalse(r.timedOut());
    }

    @Test
    @DisplayName("sleep 5 超时（timeoutSeconds=1）→ timedOut=true, exitCode=-1")
    void sleepTimesOut() {
        CommandRunner.CommandResult r = runner.run(
                List.of("sh", "-c", "sleep 5"),
                null, null, 1);

        assertTrue(r.timedOut(), "should be marked timed out");
        assertEquals(-1, r.exitCode());
    }

    @Test
    @DisplayName("env 注入：子进程能读到 MY_VAR=hello")
    void envVarsPropagatedToChild() {
        CommandRunner.CommandResult r = runner.run(
                List.of("sh", "-c", "echo $MY_VAR"),
                java.util.Map.of("MY_VAR", "injected-value"),
                null, 5);

        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("injected-value"),
                "env var should propagate: " + r.stdout());
    }

    @Test
    @DisplayName("空命令 → exitCode=-1, stderr 非空")
    void emptyCommandReturnsError() {
        CommandRunner.CommandResult r = runner.run(
                List.of(), null, null, 5);

        assertEquals(-1, r.exitCode());
        assertFalse(r.stderr().isEmpty());
    }

    @Test
    @DisplayName("stdout 超长 → 不抛异常，正常返回（截断由上层负责）")
    void largeStdoutHandledGracefully() {
        CommandRunner.CommandResult r = runner.run(
                List.of("sh", "-c", "for i in $(seq 1 1000); do echo line$i; done"),
                null, null, 10);

        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("line1000"));
    }
}
