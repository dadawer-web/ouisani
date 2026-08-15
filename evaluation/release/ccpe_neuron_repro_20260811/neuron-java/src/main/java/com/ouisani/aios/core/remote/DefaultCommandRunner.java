package com.ouisani.aios.core.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandRunner} 的生产实现 — 基于 {@link ProcessBuilder}。
 * <p>
 * 复用 {@code ExternalAgentRunner#executeExternalAgent} 的成熟模式：
 * <ul>
 *   <li>{@code redirectErrorStream(true)} — stdout/stderr 合并读取，避免管道阻塞</li>
 *   <li>逐行读 stdout 累积到 StringBuilder（有上限保护）</li>
 *   <li>{@code process.waitFor(timeout, SECONDS)} 超时后 {@code destroyForcibly}</li>
 *   <li>非零退出码不抛异常，由调用方判断 {@link CommandResult#exitCode()}</li>
 * </ul>
 * <p>
 * <b>单例</b>：无状态，{@link #INSTANCE} 全局共享。
 *
 * @see CommandRunner
 */
public final class DefaultCommandRunner implements CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultCommandRunner.class);

    /** 单例（无状态，全局共享）。 */
    public static final DefaultCommandRunner INSTANCE = new DefaultCommandRunner();

    /** stdout/stderr 合并后的最大长度，超出截断。 */
    private static final int MAX_OUTPUT_LENGTH = 1_000_000;

    private DefaultCommandRunner() {}

    @Override
    public CommandResult run(List<String> command, Map<String, String> env,
                              File workingDir, long timeoutSeconds) {
        if (command == null || command.isEmpty()) {
            return new CommandResult(-1, "", "empty command", false);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        if (env != null && !env.isEmpty()) {
            Map<String, String> pbEnv = pb.environment();
            pbEnv.putAll(env);
        }

        long start = System.currentTimeMillis();
        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            log.warn("[DefaultCommandRunner] 启动失败: cmd={}, err={}",
                    String.join(" ", command), e.getMessage());
            return new CommandResult(-1, "", "spawn failed: " + e.getMessage(), false);
        }

        // 在独立线程中异步排空 stdout —— 否则 readLine() 会阻塞到进程退出，
        // 让 waitFor(timeout) 形同虚设（对不产生输出直到结束的进程尤为明显，如 sleep 5）。
        // destroyForcibly() 会关闭进程的 stdout 管道，让 reader 线程自然退出。
        StringBuilder output = new StringBuilder();
        Thread drainThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_LENGTH) {
                        output.append(line).append('\n');
                    }
                }
            } catch (Exception e) {
                log.warn("[DefaultCommandRunner] 读 stdout 异常: cmd={}, err={}",
                        String.join(" ", command), e.getMessage());
            }
        }, "aios-cmd-drain-" + Integer.toHexString(command.hashCode()));
        drainThread.setDaemon(true);
        drainThread.start();

        boolean finished;
        try {
            if (timeoutSeconds > 0) {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                process.waitFor();
                finished = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            joinQuietly(drainThread);
            return new CommandResult(-1, output.toString(),
                    "interrupted: " + e.getMessage(), false);
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(drainThread);
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[DefaultCommandRunner] 超时 ({}ms): cmd={}",
                    elapsed, String.join(" ", command));
            return CommandResult.timeout();
        }

        // 进程正常退出 — 等 drain 线程读完残余输出（最多 2s，防止意外挂死）
        joinQuietly(drainThread);

        int exitCode = process.exitValue();
        String combined = output.toString();
        // 由于 redirectErrorStream(true)，combined 包含 stdout+stderr
        // 此处简化：全部作为 stdout 返回（stderr 留空），调用方按退出码判定
        return new CommandResult(exitCode, combined, "", false);
    }

    /** 等 drain 线程结束，最多 2s（best-effort，避免测试挂死）。 */
    private static void joinQuietly(Thread t) {
        try {
            t.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
