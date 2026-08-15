package com.ouisani.aios.core.remote;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 命令运行器 — 把 {@code ProcessBuilder} 调用抽象为可注入的函数式接口。
 * <p>
 * <b>存在意义</b>：让 {@link SshExecutor}/{@link SlurmExecutor}/{@link ModalExecutor}
 * 可在单测中注入 mock（返回 canned {@link CommandResult}），无需真实 ssh/sbatch/modal 二进制。
 * 生产环境用 {@link DefaultCommandRunner}（基于 {@link ProcessBuilder}）。
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code posix_spawn} 系统调用封装 —
 * 上层只关心「跑命令、拿结果」，不关心是 fork+exec 还是 mock。
 *
 * @see DefaultCommandRunner
 */
@FunctionalInterface
public interface CommandRunner {

    /**
     * 同步执行一条命令。
     *
     * @param command        命令及参数（第一项为可执行文件，其余为 argv）
     * @param env            额外环境变量（与当前进程环境合并；null 表示不追加）
     * @param workingDir     工作目录（null 表示继承当前进程）
     * @param timeoutSeconds 超时秒数（&lt;=0 表示不超时）
     * @return 命令结果（含退出码、stdout、stderr、是否超时）
     */
    CommandResult run(List<String> command, Map<String, String> env,
                      File workingDir, long timeoutSeconds);

    /**
     * 命令结果 — {@link CommandRunner#run} 的不可变返回值。
     *
     * @param exitCode 退出码（超时为 -1）
     * @param stdout   标准输出
     * @param stderr   标准错误
     * @param timedOut 是否因超时被 destroyForcibly
     */
    record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        public CommandResult {
            if (stdout == null) stdout = "";
            if (stderr == null) stderr = "";
        }

        /** 快捷构造成功结果（测试用）。 */
        public static CommandResult ok(String stdout) {
            return new CommandResult(0, stdout, "", false);
        }

        /** 快捷构造失败结果（测试用）。 */
        public static CommandResult fail(int exitCode, String stderr) {
            return new CommandResult(exitCode, "", stderr, false);
        }

        /** 快捷构造超时结果（测试用）。 */
        public static CommandResult timeout() {
            return new CommandResult(-1, "", "", true);
        }
    }
}
