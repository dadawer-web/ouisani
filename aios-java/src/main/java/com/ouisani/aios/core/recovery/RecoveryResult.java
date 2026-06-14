package com.ouisani.aios.core.recovery;

/**
 * 恢复结果。
 *
 * @param success  是否恢复成功
 * @param message  结果描述
 * @param modifiedPrompt 恢复策略修改后的 Prompt（如果需要重新调用 LLM）
 */
public record RecoveryResult(
        boolean success,
        String message,
        String modifiedPrompt
) {
    public static RecoveryResult ok(String message) {
        return new RecoveryResult(true, message, null);
    }

    public static RecoveryResult ok(String message, String modifiedPrompt) {
        return new RecoveryResult(true, message, modifiedPrompt);
    }

    public static RecoveryResult failed(String message) {
        return new RecoveryResult(false, message, null);
    }
}
