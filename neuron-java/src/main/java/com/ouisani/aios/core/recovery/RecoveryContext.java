package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.recovery.RecoveryOrchestrator.ErrorCategory;

/**
 * 恢复上下文 — 携带恢复所需的所有信息。
 *
 * @param agentId       发生错误的 Agent ID
 * @param exception     原始异常
 * @param category      错误分类（由编排器填充）
 * @param attempt       当前恢复尝试次数
 * @param lastErrorTrace 上一次失败的错误信息
 * @param promptModifier 恢复策略对 Prompt 的修改（注入反思/纠正提示）
 * @param metadata       额外元数据（工具名、文件路径等）
 */
public record RecoveryContext(
        String agentId,
        Exception exception,
        ErrorCategory category,
        int attempt,
        String lastErrorTrace,
        StringBuilder promptModifier,
        java.util.Map<String, Object> metadata
) {
    public RecoveryContext(String agentId, Exception exception, int attempt, String lastErrorTrace) {
        this(agentId, exception, ErrorCategory.UNKNOWN, attempt, lastErrorTrace,
                new StringBuilder(), new java.util.HashMap<>());
    }

    /** 设置错误分类（由编排器调用） */
    public RecoveryContext withCategory(ErrorCategory category) {
        return new RecoveryContext(agentId, exception, category, attempt, lastErrorTrace,
                promptModifier, metadata);
    }

    /** 追加 Prompt 修改 */
    public RecoveryContext appendPromptModifier(String modifier) {
        promptModifier.append(modifier);
        return this;
    }

    /** 添加元数据 */
    public RecoveryContext withMetadata(String key, Object value) {
        metadata.put(key, value);
        return this;
    }
}
