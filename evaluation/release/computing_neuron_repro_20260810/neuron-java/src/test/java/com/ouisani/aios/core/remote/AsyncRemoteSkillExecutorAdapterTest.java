package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ouisani.aios.core.remote.CommandRunner.CommandResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AsyncRemoteSkillExecutorAdapter} 单元测试 — 验证 submit→poll→retrieve 轮询收敛回同步 String 语义。
 * <p>
 * 复用 {@link SlurmExecutorTest} 的状态机 mock 模式（slurm 是唯一覆写真异步的 executor）。
 * 不实际提交 Slurm 作业 — mock runner 按 command[0]/shell 内容分发 canned 结果。
 */
class AsyncRemoteSkillExecutorAdapterTest {

    /**
     * 状态机 mock — 捕获 sbatch 脚本内容用于断言命令拼接：
     * <ul>
     *   <li>{@code sbatch --parsable <file>} → 读 file 内容存 {@link #capturedScript}，返回 {@link #sbatchResult}</li>
     *   <li>{@code sh -c "..."} → 按 shell 内容分发 sacct/cat/rm</li>
     * </ul>
     */
    private static final class StatefulRunner implements CommandRunner {
        final List<List<String>> allCommands = new ArrayList<>();
        String capturedScript = "";
        CommandResult sbatchResult = CommandResult.ok("12345\n");
        String defaultSacctResponse = "COMPLETED";
        String catOutResponse = "train-done";

        @Override
        public CommandResult run(List<String> command, Map<String, String> env,
                                  File workingDir, long timeoutSeconds) {
            allCommands.add(new ArrayList<>(command));
            if (command.isEmpty()) return CommandResult.fail(-1, "empty");

            return switch (command.get(0)) {
                case "sbatch" -> {
                    // sbatch --parsable <tempScript> — 读脚本内容用于断言命令拼接
                    if (command.size() >= 3) {
                        try {
                            capturedScript = Files.readString(Path.of(command.get(2)));
                        } catch (Exception ignored) {
                            capturedScript = "";
                        }
                    }
                    yield sbatchResult;
                }
                case "scancel" -> CommandResult.ok("");
                case "sh" -> {
                    String shell = command.size() >= 3 ? command.get(2) : "";
                    yield dispatchShell(shell);
                }
                default -> CommandResult.ok("default");
            };
        }

        private CommandResult dispatchShell(String shell) {
            if (shell.contains("sacct")) {
                return CommandResult.ok(defaultSacctResponse + "\n");
            }
            if (shell.contains("cat slurm-") && shell.contains(".out")) {
                return CommandResult.ok(catOutResponse);
            }
            if (shell.contains("cat slurm-") && shell.contains(".err")) {
                return CommandResult.ok("");
            }
            if (shell.contains("rm -f slurm-")) {
                return CommandResult.ok("");
            }
            return CommandResult.ok("");
        }
    }

    private StatefulRunner runner;
    private AsyncRemoteSkillExecutorAdapter adapter;

    @BeforeEach
    void setUp() {
        runner = new StatefulRunner();
        adapter = new AsyncRemoteSkillExecutorAdapter(
                "slurm", withTimeout(RemoteExecutorConfig.slurm("gpu", 4, 1), 5), runner);
        adapter.setPollIntervalMs(20); // 加速轮询
    }

    @Test
    @DisplayName("submit→poll(COMPLETED)→retrieve → 返回 stdout（含 cat slurm-*.out 内容）")
    void submitPollRetrieve_completed_returnsStdout() {
        String out = adapter.execute("agent-1", "train-skill", "--epochs 10", "/work");

        assertTrue(out.contains("train-done"),
                "should return stdout from retrieve: " + out);
    }

    @Test
    @DisplayName("submit→poll(FAILED)→retrieve → 返回空串（让 SkillChain 判 FAILED）")
    void submitPollRetrieve_failed_returnsEmpty() {
        runner.defaultSacctResponse = "FAILED";

        String out = adapter.execute("agent-1", "train-skill", "", "/work");

        assertEquals("", out, "FAILED job should return empty string");
    }

    @Test
    @DisplayName("poll 永不终态 + 短 timeout → 返回空串（best-effort，不 cancel）")
    void pollNeverTerminal_timesOut_returnsEmpty() {
        runner.defaultSacctResponse = "RUNNING"; // 永不终态
        adapter = new AsyncRemoteSkillExecutorAdapter(
                "slurm", withTimeout(RemoteExecutorConfig.slurm("gpu", 4, 1), 2), runner);
        adapter.setPollIntervalMs(20);

        String out = adapter.execute("agent-1", "train-skill", "", "/work");

        assertEquals("", out, "timeout should return empty string");
    }

    @Test
    @DisplayName("submit 抛 RemoteJobException（sbatch 失败）→ 返回空串，不向外抛")
    void submitThrows_returnsEmpty() {
        runner.sbatchResult = CommandResult.fail(1, "queue full");

        String out = adapter.execute("agent-1", "train-skill", "", "/work");

        assertEquals("", out, "submit failure should be swallowed → empty string");
    }

    @Test
    @DisplayName("命令拼接：args 非空 → 'skillName args'；args 空 → 'skillName'（捕获 sbatch 脚本末行断言）")
    void commandConcatenation() {
        adapter.execute("agent-1", "train-skill", "--epochs 10", "/work");

        // buildBatchScript 末行即用户命令
        String[] lines = runner.capturedScript.split("\n");
        String lastNonEmpty = "";
        for (String line : lines) {
            if (!line.isBlank()) lastNonEmpty = line;
        }
        assertEquals("train-skill --epochs 10", lastNonEmpty,
                "command should be skillName + ' ' + args: " + runner.capturedScript);

        // args 空 → 仅 skillName
        runner.capturedScript = "";
        adapter.execute("agent-1", "bare-skill", "", "/work");
        lines = runner.capturedScript.split("\n");
        lastNonEmpty = "";
        for (String line : lines) {
            if (!line.isBlank()) lastNonEmpty = line;
        }
        assertEquals("bare-skill", lastNonEmpty,
                "blank args → command is skillName only: " + runner.capturedScript);
    }

    @Test
    @DisplayName("unknown type → 构造器抛 IllegalArgumentException（fail-fast）")
    void unknownType_throwsIllegalArgumentException() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.slurm("gpu", 1, 0);

        assertThrows(IllegalArgumentException.class,
                () -> new AsyncRemoteSkillExecutorAdapter("k8s", cfg, runner),
                "unknown type should fail-fast in constructor");
    }

    // ── helpers ──

    /** 复制 cfg 但替换 timeoutSeconds（record 无 with- 方法，手动构造 19 字段）。 */
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
