package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.recovery.RecoveryOrchestrator.ErrorCategory;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;

/**
 * 恢复上下文 — 携带恢复所需的所有信息。
 *
 * @param agentId        发生错误的 Agent ID
 * @param exception      原始异常
 * @param category       错误分类（由编排器填充）
 * @param attempt        当前恢复尝试次数
 * @param lastErrorTrace 上一次失败的错误信息
 * @param promptModifier 恢复策略对 Prompt 的修改（注入反思/纠正提示）
 * @param metadata       额外元数据（工具名、文件路径等）
 * @param originalTool        触发失败的工具调用对应的工具；null 表示非工具调用失败（无需权限重校验）
 * @param originalToolInput   触发失败的工具调用输入；与 {@code originalTool} 同源，供 {@link RecoveryPermissionGuard} 重校验
 * @param originalToolContext 触发失败的工具调用上下文（含 tenantId）；用于重校验时还原租户/agent 归属
 */
public record RecoveryContext(
        String agentId,
        Exception exception,
        ErrorCategory category,
        int attempt,
        String lastErrorTrace,
        StringBuilder promptModifier,
        java.util.Map<String, Object> metadata,
        Tool<?> originalTool,
        ToolInput originalToolInput,
        ToolContext originalToolContext
) {
    /**
     * 向后兼容的 4 参构造器 — 不携带原始工具调用信息。
     * <p>
     * 旧调用点（{@link RecoveryOrchestrator#performCrashRecovery}、
     * {@code OmniMotherAgent}）零改动继续工作；新字段默认 null，
     * 表示"本次失败非工具调用触发，跳过权限重校验"。
     */
    public RecoveryContext(String agentId, Exception exception, int attempt, String lastErrorTrace) {
        this(agentId, exception, ErrorCategory.UNKNOWN, attempt, lastErrorTrace,
                new StringBuilder(), new java.util.HashMap<>(),
                null, null, null);
    }

    /** 设置错误分类（由编排器调用） */
    public RecoveryContext withCategory(ErrorCategory category) {
        return new RecoveryContext(agentId, exception, category, attempt, lastErrorTrace,
                promptModifier, metadata,
                originalTool, originalToolInput, originalToolContext);
    }

    /**
     * 携带触发失败的工具调用信息 — 供 {@link RecoveryPermissionGuard} 在重试前重新校验权限。
     * <p>
     * <b>为何需要</b>：恢复策略默认"失败=无害意外，重试=安全"，但在多租户/有权限边界的内核里
     * 这个假设可能被利用——恶意 app 可故意制造看似正常的失败，借恢复重试通道绕过原始权限检查。
     * 携带原始工具调用后，编排器会在每次重试前用 {@link PermissionChecker#checkPermission}
     * 重新校验，确保重试不越过原始请求的权限边界。
     *
     * @param tool  触发失败的工具
     * @param input 触发失败的工具输入
     * @param ctx   触发失败的工具上下文
     * @param <I>   工具输入类型
     * @return 携带工具调用信息的新上下文
     */
    public <I extends ToolInput> RecoveryContext withOriginalToolCall(Tool<I> tool, I input, ToolContext ctx) {
        return new RecoveryContext(agentId, exception, category, attempt, lastErrorTrace,
                promptModifier, metadata,
                tool, input, ctx);
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

    // ─────────────────────────────────────────────────────────────
    //  Phase 1：内容信任来源标记（additive，保留 lastErrorTrace String 向后兼容）
    // ─────────────────────────────────────────────────────────────

    /**
     * 带来源标记的错误内容（Phase 1 基础设施）— {@code lastErrorTrace} + {@link TrustOrigin}。
     * <p>
     * 恢复策略（{@link ReflectionInjectionRecovery}/{@link TopologyMutationStrategy}）应使用本访问器
     * 而非裸 {@link #lastErrorTrace()}，以便检查来源：{@link TrustOrigin#TOOL_OUTPUT_EXTERNAL}
     * 的内容不得套用 {@code [SYSTEM CRITICAL]} 高信任框架（洞1 防御）。
     * <p>
     * 来源由 {@link TrustOrigin#fromMetadata(metadata)} 解析 —— 缺失保守返回
     * {@link TrustOrigin#SYSTEM_GENERATED}（向后兼容：旧调用点维持高信任行为）。
     *
     * @return 带来源标记的错误内容（text=lastErrorTrace 或 exception message）
     */
    public TaggedContent taggedError() {
        String text = lastErrorTrace;
        if (text == null || text.isEmpty()) {
            text = exception != null && exception.getMessage() != null ? exception.getMessage() : "";
        }
        String sourceRef = metadata.get("errorSourceRef") != null
                ? metadata.get("errorSourceRef").toString() : null;
        return new TaggedContent(text, TrustOrigin.fromMetadata(metadata), sourceRef);
    }

    /**
     * 标记错误来源 —— 上游（工具调用捕获处）调用，把"本次失败是否处理过外部内容"信号写入 metadata。
     * <p>
     * 例：{@code web_fetch}/{@code file_read} 处理外部网页/不可信文件失败 → 标
     * {@link TrustOrigin#TOOL_OUTPUT_EXTERNAL}；{@code bash} 内部命令失败 → 标
     * {@link TrustOrigin#TOOL_OUTPUT_INTERNAL}；内核异常 → {@link TrustOrigin#SYSTEM_GENERATED}。
     *
     * @param origin 错误内容来源
     * @return this（链式）
     */
    public RecoveryContext withErrorOrigin(TrustOrigin origin) {
        metadata.put(TrustOrigin.META_KEY, origin != null ? origin.name() : TrustOrigin.SYSTEM_GENERATED.name());
        return this;
    }

    /**
     * 携带来源引用（工具名/URL/文件路径）— 供 provenance 审计追溯。与 {@link #withErrorOrigin} 配合。
     */
    public RecoveryContext withErrorSourceRef(String sourceRef) {
        metadata.put("errorSourceRef", sourceRef);
        return this;
    }
}
