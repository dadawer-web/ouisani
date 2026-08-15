package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.ouisani.aios.core.remote.CommandRunner.CommandResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModalExecutor} 单元测试 — 验证 argv 构造、token env 注入、stdout 末行解析、退出码处理。
 * 不实际调用 {@code modal} CLI。
 */
class ModalExecutorTest {

    /** 捕获 argv/env/timeout，返回预设结果。 */
    private static final class CapturingRunner implements CommandRunner {
        List<String> lastCommand;
        Map<String, String> lastEnv;
        File lastWorkingDir;
        long lastTimeout;
        CommandResult nextResult = CommandResult.ok("default-stdout");

        @Override
        public CommandResult run(List<String> command, Map<String, String> env,
                                  File workingDir, long timeoutSeconds) {
            lastCommand = new java.util.ArrayList<>(command);
            lastEnv = env;
            lastWorkingDir = workingDir;
            lastTimeout = timeoutSeconds;
            return nextResult;
        }
    }

    private final CapturingRunner runner = new CapturingRunner();
    private final ModalExecutor executor = new ModalExecutor(runner);

    private RemoteExecutorConfig fullModalConfig() {
        return new RemoteExecutorConfig(
                "modal", null, 22, null, null, null,
                null, null, 0, 0, 0, null,
                "/work/my_app.py", "train",
                "ak-token-id-xyz", "sk-token-secret-abc", "my-workspace",
                60, Map.of("MODAL_ARG_EPOCHS", "10", "MODAL_ARG_LR", "0.001"));
    }

    // ── argv 构造 ──

    @Test
    @DisplayName("argv 含 modal / run / app::fn / --args-json / JSON 串")
    void argvContainsModalRunWithAppFunctionAndArgsJson() {
        executor.execute(fullModalConfig(), "python train.py", "/work");

        assertEquals("modal", runner.lastCommand.get(0));
        assertEquals("run", runner.lastCommand.get(1));
        assertEquals("/work/my_app.py::train", runner.lastCommand.get(2));
        assertEquals("--args-json", runner.lastCommand.get(3));
        String json = runner.lastCommand.get(4);
        // JSON 应含 command/workingDir/MODAL_ARG_ 剥离后的业务参数
        assertTrue(json.contains("\"command\":\"python train.py\""), "json command: " + json);
        assertTrue(json.contains("\"workingDir\":\"/work\""), "json workingDir: " + json);
        assertTrue(json.contains("\"EPOCHS\":\"10\""), "json EPOCHS: " + json);
        assertTrue(json.contains("\"LR\":\"0.001\""), "json LR: " + json);
    }

    @Test
    @DisplayName("token 经 env 注入，绝不写命令行（避免 ps aux 泄露）")
    void tokensInjectedViaEnvNotArgv() {
        executor.execute(fullModalConfig(), "python train.py", "/work");

        // env 含 3 个 token 字段
        assertEquals("ak-token-id-xyz", runner.lastEnv.get("MODAL_TOKEN_ID"));
        assertEquals("sk-token-secret-abc", runner.lastEnv.get("MODAL_TOKEN_SECRET"));
        assertEquals("my-workspace", runner.lastEnv.get("MODAL_WORKSPACE"));
        // argv 不含任何 token 值
        for (String arg : runner.lastCommand) {
            assertFalse(arg.contains("ak-token-id-xyz"),
                    "argv should NOT contain token id: " + runner.lastCommand);
            assertFalse(arg.contains("sk-token-secret-abc"),
                    "argv should NOT contain token secret: " + runner.lastCommand);
        }
        // MODAL_ARG_ 业务参数不应出现在 env（已通过 argsJson 传）
        assertFalse(runner.lastEnv.containsKey("MODAL_ARG_EPOCHS"));
        assertFalse(runner.lastEnv.containsKey("MODAL_ARG_LR"));
    }

    // ── stdout 解析 ──

    @Test
    @DisplayName("exit 0 + 多行 stdout → success，stdout 取末行非空")
    void exitZero_returnsSuccessWithLastLine() {
        runner.nextResult = CommandResult.ok("log line 1\nlog line 2\nfinal-result");

        RemoteResult r = executor.execute(fullModalConfig(), "python train.py", null);

        assertTrue(r.success());
        assertEquals(0, r.exitCode());
        assertEquals("final-result", r.stdout());
    }

    @Test
    @DisplayName("exit 0 + 空 stdout → success，stdout 为空串")
    void emptyStdout_returnsSuccessWithEmpty() {
        runner.nextResult = CommandResult.ok("");

        RemoteResult r = executor.execute(fullModalConfig(), "python train.py", null);

        assertTrue(r.success());
        assertEquals("", r.stdout());
    }

    // ── 退出码 / 超时 ──

    @Test
    @DisplayName("exit 非0 → failure，errorMessage 含退出码和 stderr")
    void nonZeroExit_returnsFailure() {
        runner.nextResult = new CommandRunner.CommandResult(1, "partial output", "modal error", false);

        RemoteResult r = executor.execute(fullModalConfig(), "python train.py", null);

        assertFalse(r.success());
        assertEquals(1, r.exitCode());
        assertTrue(r.errorMessage().contains("1"));
        assertTrue(r.errorMessage().contains("modal error"));
    }

    @Test
    @DisplayName("超时 → timeout，exitCode=-1")
    void timeout_returnsTimeoutResult() {
        runner.nextResult = CommandRunner.CommandResult.timeout();

        RemoteResult r = executor.execute(fullModalConfig(), "python train.py", null);

        assertFalse(r.success());
        assertEquals(-1, r.exitCode());
        assertTrue(r.errorMessage().contains("timed out"));
    }

    // ── type / null safety ──

    @Test
    @DisplayName("type() 返回 'modal' + null config / 空命令 → configError")
    void typeReturnsModal_andNullSafety() {
        assertEquals("modal", executor.type());

        RemoteExecutorConfig cfg = fullModalConfig();
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

    @Test
    @DisplayName("config 缺 modalAppPath 或 modalFunctionName → configError（不抛异常）")
    void missingAppPathOrFunctionName_returnsConfigError() {
        // appPath 为 null（RemoteExecutorConfig.modal 工厂要求非空，故手动构造）
        RemoteExecutorConfig noApp = new RemoteExecutorConfig(
                "modal", null, 22, null, null, null,
                null, null, 0, 0, 0, null,
                null, "train", null, null, null,
                60, null);
        RemoteExecutorConfig noFn = new RemoteExecutorConfig(
                "modal", null, 22, null, null, null,
                null, null, 0, 0, 0, null,
                "/work/app.py", null, null, null, null,
                60, null);

        RemoteResult r1 = executor.execute(noApp, "cmd", null);
        RemoteResult r2 = executor.execute(noFn, "cmd", null);

        assertFalse(r1.success());
        assertFalse(r2.success());
        assertTrue(r1.errorMessage().contains("modalAppPath required"));
        assertTrue(r2.errorMessage().contains("modalFunctionName required"));
    }
}
