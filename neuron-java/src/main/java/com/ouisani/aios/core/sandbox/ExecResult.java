package com.ouisani.aios.core.sandbox;

/**
 * Shell 执行结果 — {@link BackendBase#exec_shell} 的返回值。
 * <p>
 * 不可变价值类型，封装退出码、合并后的 stdout/stderr 输出、超时标志与错误信息。
 * 永远非 null：失败时 {@link #exitCode} 为非零，{@link #errorMessage} 携带原因。
 *
 * @param exitCode      进程退出码；0 表示成功
 * @param output        stdout 与 stderr 合并后的输出（已按 maxOutputLength 截断）
 * @param timedOut      是否因超时被强制终止
 * @param errorMessage  错误信息（如启动失败、读取失败）；无错误为 null
 */
public record ExecResult(
        int exitCode,
        String output,
        boolean timedOut,
        String errorMessage
) {
    /** 快捷判断：exitCode == 0 且未超时且无错误。 */
    public boolean success() {
        return exitCode == 0 && !timedOut && errorMessage == null;
    }

    /** 成功结果工厂。 */
    public static ExecResult ok(String output) {
        return new ExecResult(0, output == null ? "" : output, false, null);
    }

    /** 失败结果工厂（带退出码与输出）。 */
    public static ExecResult failure(int exitCode, String output) {
        return new ExecResult(exitCode, output == null ? "" : output, false, null);
    }

    /** 超时结果工厂。 */
    public static ExecResult timeout(String partialOutput) {
        return new ExecResult(-1, partialOutput == null ? "" : partialOutput, true, null);
    }

    /** 错误结果工厂（启动/读取失败等）。 */
    public static ExecResult error(String message) {
        return new ExecResult(-1, "", false, message);
    }
}
