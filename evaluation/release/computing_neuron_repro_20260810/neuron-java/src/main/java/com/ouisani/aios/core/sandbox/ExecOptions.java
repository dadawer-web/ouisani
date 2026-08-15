package com.ouisani.aios.core.sandbox;

import java.util.Map;

/**
 * Shell 执行选项 — {@link BackendBase#exec_shell} 的入参。
 * <p>
 * 不可变价值类型。所有字段有合理默认值，调用方可按需覆盖。
 * <p>
 * OS 类比：相当于 Linux 的 {@code posix_spawnattr_t} + {@code posix_spawn_file_actions_t}。
 *
 * @param timeoutSeconds  超时秒数；{@code <= 0} 表示无超时（默认 120）
 * @param workingDir      工作目录；{@code null} 表示后端默认目录
 * @param env             额外环境变量；{@code null} 或空表示不追加
 * @param maxOutputLength 输出截断长度；{@code <= 0} 表示不截断（默认 30000）
 * @param redirectErrorStream 是否合并 stderr 到 stdout（默认 true）
 */
public record ExecOptions(
        int timeoutSeconds,
        String workingDir,
        Map<String, String> env,
        int maxOutputLength,
        boolean redirectErrorStream
) {
    /** 默认超时 120 秒，默认输出截断 30000 字符。 */
    public static final ExecOptions DEFAULT = new ExecOptions(120, null, null, 30000, true);

    /** 全参构造器 — 为字段提供合理默认值。 */
    public ExecOptions {
        if (timeoutSeconds <= 0) timeoutSeconds = 120;
        if (maxOutputLength <= 0) maxOutputLength = 30000;
        if (env == null) env = Map.of();
    }

    /** 仅指定工作目录的便捷构造器。 */
    public ExecOptions(String workingDir) {
        this(120, workingDir, null, 30000, true);
    }

    /** Builder 风格：覆盖工作目录。 */
    public ExecOptions withWorkingDir(String dir) {
        return new ExecOptions(timeoutSeconds, dir, env, maxOutputLength, redirectErrorStream);
    }

    /** Builder 风格：覆盖超时。 */
    public ExecOptions withTimeout(int seconds) {
        return new ExecOptions(seconds, workingDir, env, maxOutputLength, redirectErrorStream);
    }

    /** Builder 风格：追加环境变量。 */
    public ExecOptions withEnv(Map<String, String> extraEnv) {
        if (extraEnv == null || extraEnv.isEmpty()) return this;
        var merged = new java.util.HashMap<>(this.env);
        merged.putAll(extraEnv);
        return new ExecOptions(timeoutSeconds, workingDir, Map.copyOf(merged), maxOutputLength, redirectErrorStream);
    }
}
