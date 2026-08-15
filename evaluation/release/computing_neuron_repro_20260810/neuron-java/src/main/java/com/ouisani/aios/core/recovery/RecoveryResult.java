package com.ouisani.aios.core.recovery;

/**
 * 恢复结果。
 *
 * @param success  是否恢复成功
 * @param message  结果描述
 * @param modifiedPrompt 恢复策略修改后的 Prompt（如果需要重新调用 LLM）
 * @param requiresReauthorization 本结果是否产生需重新授权的副作用（角色变更/恢复后继续执行等）。
 *        <b>Phase 4 defense #4（统一重新授权关卡）</b>：true 表示编排器在让结果真正生效前
 *        （如 resumeNode、prompt 重注入）必须先过 {@link RecoveryReauthorizationGate}。
 *        默认 false —— 现有策略零行为变化（向后兼容）；只有显式声明副作用的策略（如
 *        {@link TopologyMutationStrategy} 的角色替换）才置 true。
 */
public record RecoveryResult(
        boolean success,
        String message,
        String modifiedPrompt,
        boolean requiresReauthorization
) {
    public static RecoveryResult ok(String message) {
        return new RecoveryResult(true, message, null, false);
    }

    public static RecoveryResult ok(String message, String modifiedPrompt) {
        return new RecoveryResult(true, message, modifiedPrompt, false);
    }

    public static RecoveryResult failed(String message) {
        return new RecoveryResult(false, message, null, false);
    }

    /**
     * 声明恢复成功且结果需重新授权才能生效 —— 供产生副作用的策略（角色变更等）使用。
     * 编排器会在让结果生效前过 {@link RecoveryReauthorizationGate}。
     */
    public static RecoveryResult okRequiringReauthorization(String message, String modifiedPrompt) {
        return new RecoveryResult(true, message, modifiedPrompt, true);
    }
}
