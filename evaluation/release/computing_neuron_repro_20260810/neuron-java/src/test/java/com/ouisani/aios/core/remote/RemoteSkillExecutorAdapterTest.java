package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.ouisani.aios.core.remote.CommandRunner.CommandResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoteSkillExecutorAdapter} 单元测试 — 验证 implements {@link com.ouisani.aios.core.skill.SkillChain.SkillExecutor}
 * 的路由逻辑：成功返回 stdout、失败返回空串、command 拼接、workingDir 透传、unknown type fail-fast。
 */
class RemoteSkillExecutorAdapterTest {

    /**
     * 复合 mock — 支持 ssh/slurm/modal 三种 type 的 CommandRunner 调用模式：
     * <ul>
     *   <li>ssh：单次 run，argv[0]=="ssh"，返回 {@link #sshResult}</li>
     *   <li>modal：单次 run，argv[0]=="modal"，返回 {@link #modalResult}，stdout 末行作返回值</li>
     *   <li>slurm：多次 run（sbatch/sacct/cat/cleanup），按 command[0] 分发</li>
     * </ul>
     * 捕获 argv 末项用于断言 command 拼接与 workingDir 透传。
     */
    private static final class CompositeRunner implements CommandRunner {
        List<String> lastCommand;
        File lastWorkingDir;
        long lastTimeout;
        CommandResult sshResult = CommandResult.ok("ssh-out");
        CommandResult modalResult = CommandResult.ok("line1\nmodal-out");
        String slurmSacctResponse = "COMPLETED";
        String slurmCatOutResponse = "slurm-out";

        @Override
        public CommandResult run(List<String> command, Map<String, String> env,
                                  File workingDir, long timeoutSeconds) {
            lastCommand = new java.util.ArrayList<>(command);
            lastWorkingDir = workingDir;
            lastTimeout = timeoutSeconds;
            if (command.isEmpty()) return CommandResult.fail(-1, "empty");

            return switch (command.get(0)) {
                case "ssh"   -> sshResult;
                case "modal" -> modalResult;
                case "sbatch" -> CommandResult.ok("12345\n");
                case "scancel" -> CommandResult.ok("");
                case "sh" -> {
                    String shell = command.size() >= 3 ? command.get(2) : "";
                    if (shell.contains("sacct")) yield CommandResult.ok(slurmSacctResponse + "\n");
                    if (shell.contains("cat slurm-") && shell.contains(".out")) yield CommandResult.ok(slurmCatOutResponse);
                    if (shell.contains("cat slurm-") && shell.contains(".err")) yield CommandResult.ok("");
                    if (shell.contains("rm -f slurm-")) yield CommandResult.ok("");
                    yield CommandResult.ok("");
                }
                default -> CommandResult.ok("default");
            };
        }
    }

    private CompositeRunner runner;

    @BeforeEach
    void setUp() {
        runner = new CompositeRunner();
    }

    // ── ssh type ──

    @Test
    @DisplayName("ssh type + 成功 → 返回 stdout")
    void sshType_success_returnsStdout() {
        runner.sshResult = CommandResult.ok("ssh-result-stdout");
        RemoteSkillExecutorAdapter adapter = new RemoteSkillExecutorAdapter(
                "ssh", RemoteExecutorConfig.ssh("host", "user", "/key"), runner);

        String out = adapter.execute("agent-1", "train-skill", "arg1 arg2", "/work");

        assertEquals("ssh-result-stdout", out);
    }

    @Test
    @DisplayName("ssh type + 失败 → 返回空串（让 SkillChain 判 FAILED）")
    void sshType_failure_returnsEmptyString() {
        runner.sshResult = new CommandRunner.CommandResult(127, "", "command not found", false);
        RemoteSkillExecutorAdapter adapter = new RemoteSkillExecutorAdapter(
                "ssh", RemoteExecutorConfig.ssh("host", "user", "/key"), runner);

        String out = adapter.execute("agent-1", "missing-skill", "", "/work");

        assertEquals("", out, "failure should return empty string");
    }

    // ── modal type ──

    @Test
    @DisplayName("modal type + 成功 → 返回 stdout 末行（ModalExecutor.parseStdout 逻辑）")
    void modalType_success_returnsStdout() {
        runner.modalResult = CommandResult.ok("log line\nmodal-final-result");
        RemoteSkillExecutorAdapter adapter = new RemoteSkillExecutorAdapter(
                "modal", RemoteExecutorConfig.modal("/app.py", "train"), runner);

        String out = adapter.execute("agent-1", "modal-skill", "", null);

        assertEquals("modal-final-result", out);
    }

    // ── slurm type ──
    // SlurmExecutor 的完整生命周期已在 SlurmExecutorTest 中验证；
    // adapter 只做 delegate，此处不重复跑 5s+ 轮询。

    // ── 命令拼接 + workingDir 透传 ──

    @Test
    @DisplayName("command 拼接 = skillName + ' ' + args；workingDir 透传到 ssh 远程 shell 的 cd 前缀")
    void commandConcatenation_andWorkingDirPassthrough() {
        RemoteSkillExecutorAdapter adapter = new RemoteSkillExecutorAdapter(
                "ssh", RemoteExecutorConfig.ssh("host", "user", "/key"), runner);

        adapter.execute("agent-1", "skill-name", "arg1 arg2", "/explicit/wd");

        // argv 末项是远程 shell，应含 cd /explicit/wd && ... && skill-name arg1 arg2
        String remoteShell = runner.lastCommand.get(runner.lastCommand.size() - 1);
        assertTrue(remoteShell.contains("cd /explicit/wd"),
                "should cd to workingDir: " + remoteShell);
        assertTrue(remoteShell.contains("skill-name arg1 arg2"),
                "command should be skillName + ' ' + args: " + remoteShell);
    }

    // ── unknown type ──

    @Test
    @DisplayName("unknown type → 构造器抛 IllegalArgumentException（fail-fast）")
    void unknownType_throwsIllegalArgumentException() {
        RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh("host", "user", "/key");

        assertThrows(IllegalArgumentException.class,
                () -> new RemoteSkillExecutorAdapter("k8s", cfg, runner),
                "unknown type should fail-fast in constructor");
    }
}
