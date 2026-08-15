package com.ouisani.aios.core.remote;

/**
 * 远程执行结果 — 一次 {@link RemoteExecutor#execute} 的不可变快照。
 * <p>
 * 三种典型构造路径：
 * <ul>
 *   <li>{@link #success(String, long)} — 退出码 0，返回 stdout</li>
 *   <li>{@link #failure(int, String, String, long)} — 非零退出码，含 stderr 用于诊断</li>
 *   <li>{@link #timeout(long)} — 外层 ProcessBuilder 超时（与 Slurm 内部 TIMEOUT 状态区分）</li>
 * </ul>
 * <p>
 * <b>不变量</b>：{@code success==true} 当且仅当 exitCode==0 且非 timeout。
 * {@code errorMessage} 始终非 null（失败时含可读描述，成功时为空串）。
 *
 * @param exitCode     远程进程退出码（timeout 时为 -1）
 * @param stdout       标准输出（可能为空串，不为 null）
 * @param stderr       标准错误（可能为空串，不为 null）
 * @param durationMs   执行耗时（毫秒）
 * @param success      是否成功（exitCode==0 && !timeout）
 * @param errorMessage 失败时的可读错误描述；成功时为空串
 */
public record RemoteResult(
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        boolean success,
        String errorMessage
) {
    public RemoteResult {
        if (stdout == null) stdout = "";
        if (stderr == null) stderr = "";
        if (errorMessage == null) errorMessage = "";
    }

    /** 成功结果。 */
    public static RemoteResult success(String stdout, long durationMs) {
        return new RemoteResult(0, stdout == null ? "" : stdout, "", durationMs, true, "");
    }

    /** 失败结果（非零退出码）。 */
    public static RemoteResult failure(int exitCode, String stdout, String stderr, long durationMs) {
        String msg = "exit code " + exitCode
                + (stderr != null && !stderr.isBlank() ? ": " + stderr.trim() : "");
        return new RemoteResult(exitCode,
                stdout == null ? "" : stdout,
                stderr == null ? "" : stderr,
                durationMs, false, msg);
    }

    /** 超时结果（外层 ProcessBuilder waitFor 超时）。 */
    public static RemoteResult timeout(long durationMs) {
        return new RemoteResult(-1, "", "", durationMs, false,
                "command timed out after " + durationMs + "ms");
    }

    /** 配置错误等不可能构造 RemoteResult 的场景用 — 不执行任何远程命令。 */
    public static RemoteResult configError(String message) {
        return new RemoteResult(-1, "", "", 0L, false, "config error: " + message);
    }
}
