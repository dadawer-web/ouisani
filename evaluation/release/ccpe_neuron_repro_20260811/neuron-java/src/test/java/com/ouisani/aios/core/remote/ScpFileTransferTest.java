package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScpFileTransfer} 单元测试 — 命令构造 + 结果映射。
 * <p>
 * 用 {@link CapturingRunner} 捕获 argv，验证 scp 命令构造（{@code -r}/{-i}/{-P}/user@host:remote 顺序）；
 * 用 canned {@link CommandRunner.CommandResult} 验证 success/failure/timeout 映射。
 */
class ScpFileTransferTest {

    /** 捕获 argv + 返回预设结果的 runner。 */
    private static class CapturingRunner implements CommandRunner {
        List<String> lastCommand;
        CommandRunner.CommandResult nextResult = CommandRunner.CommandResult.ok("");

        @Override
        public CommandResult run(List<String> command, Map<String, String> env,
                                 File workingDir, long timeoutSeconds) {
            this.lastCommand = command;
            return nextResult;
        }
    }

    private RemoteExecutorConfig sshConfig(String key, int port) {
        return new RemoteExecutorConfig("ssh", "gpu1", port, "ubuntu", key, null,
                null, null, 0, 0, 0, null,
                null, null, null, null, null,
                60L, null);
    }

    @Test
    @DisplayName("upload 构造 scp -r 递归 argv，末两项为 localPath 与 user@host:remote")
    void upload_buildsScpRecursiveArgv() {
        CapturingRunner runner = new CapturingRunner();
        ScpFileTransfer scp = new ScpFileTransfer(runner);

        scp.upload(sshConfig("/home/u/.ssh/id_rsa", 2222), "/local/dir", "/remote/work");

        List<String> argv = runner.lastCommand;
        assertEquals("scp", argv.get(0));
        assertTrue(argv.contains("-r"), "scp 必须含 -r 递归 flag");
        assertTrue(argv.contains("-i"));
        assertTrue(argv.contains("/home/u/.ssh/id_rsa"));
        assertTrue(argv.contains("-P"), "scp 端口用大写 -P");
        assertTrue(argv.contains("2222"));
        assertTrue(argv.contains("StrictHostKeyChecking=yes"));
        assertTrue(argv.contains("BatchMode=yes"));
        // 末两项：localPath, user@host:remote
        assertEquals("/local/dir", argv.get(argv.size() - 2));
        assertEquals("ubuntu@gpu1:/remote/work", argv.get(argv.size() - 1));
    }

    @Test
    @DisplayName("download 构造反转 argv — 末两项为 user@host:remote 与 localPath")
    void download_buildsReversedArgv() {
        CapturingRunner runner = new CapturingRunner();
        ScpFileTransfer scp = new ScpFileTransfer(runner);

        scp.download(sshConfig(null, 22), "/remote/out.tar", "/local/out.tar");

        List<String> argv = runner.lastCommand;
        // 默认 22 端口不输出 -P
        assertFalse(argv.contains("-P"));
        // 无 privateKey 不输出 -i
        assertFalse(argv.contains("-i"));
        assertEquals("ubuntu@gpu1:/remote/out.tar", argv.get(argv.size() - 2));
        assertEquals("/local/out.tar", argv.get(argv.size() - 1));
    }

    @Test
    @DisplayName("upload exit 0 返回 success")
    void upload_success_returnsSuccess() {
        CapturingRunner runner = new CapturingRunner();
        runner.nextResult = CommandRunner.CommandResult.ok("");
        ScpFileTransfer scp = new ScpFileTransfer(runner);

        RemoteResult r = scp.upload(sshConfig(null, 22), "/l", "/r");

        assertTrue(r.success());
    }

    @Test
    @DisplayName("upload 非零退出返回 failure，errorMessage 含 stderr")
    void upload_failure_returnsFailure() {
        CapturingRunner runner = new CapturingRunner();
        runner.nextResult = CommandRunner.CommandResult.fail(1, "permission denied");
        ScpFileTransfer scp = new ScpFileTransfer(runner);

        RemoteResult r = scp.upload(sshConfig(null, 22), "/l", "/r");

        assertFalse(r.success());
        assertEquals(1, r.exitCode());
        assertTrue(r.errorMessage().contains("permission denied"));
    }

    @Test
    @DisplayName("超时返回 timeout 结果")
    void timeout_returnsTimeoutResult() {
        CapturingRunner runner = new CapturingRunner();
        runner.nextResult = CommandRunner.CommandResult.timeout();
        ScpFileTransfer scp = new ScpFileTransfer(runner);

        RemoteResult r = scp.upload(sshConfig(null, 22), "/l", "/r");

        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("timed out"));
    }

    @Test
    @DisplayName("type 返回 scp；null/空路径返回 configError")
    void typeAndNullSafety() {
        ScpFileTransfer scp = new ScpFileTransfer(new CapturingRunner());
        assertEquals("scp", scp.type());

        // null config
        RemoteResult r1 = scp.upload(null, "/l", "/r");
        assertFalse(r1.success());
        assertTrue(r1.errorMessage().contains("config"));

        // 空 localPath
        RemoteResult r2 = scp.upload(sshConfig(null, 22), "  ", "/r");
        assertFalse(r2.success());
        assertTrue(r2.errorMessage().contains("localPath"));

        // 空 remotePath (download)
        RemoteResult r3 = scp.download(sshConfig(null, 22), "", "/l");
        assertFalse(r3.success());
        assertTrue(r3.errorMessage().contains("remotePath"));
    }
}
