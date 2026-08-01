package com.ouisani.aios.core.recovery;

/**
 * 带来源标记的内容 — 把"裸字符串"升级为"字符串 + 信任来源 + 来源引用"的结构化载体。
 * <p>
 * <b>Phase 1 基础设施</b>：攻击和防御都依赖这个。{@link RecoveryContext} 的错误文本从裸 String
 * 升级为 {@code TaggedContent}，恢复策略（{@link ReflectionInjectionRecovery} /
 * {@link TopologyMutationStrategy}）在使用前必须检查 {@link #origin()}，外部不可信内容
 * （{@link TrustOrigin#TOOL_OUTPUT_EXTERNAL}）不得套用 {@code [SYSTEM CRITICAL]} 高信任框架。
 * <p>
 * <b>三字段</b>：
 * <ul>
 *   <li>{@link #text} — 内容文本（已过 {@link RecoveryPromptSanitizer} 净化）</li>
 *   <li>{@link #origin} — 信任来源（{@link TrustOrigin} 4 级）</li>
 *   <li>{@link #sourceRef} — 来源引用（如工具名/URL/文件路径，供审计追溯；可空）</li>
 * </ul>
 *
 * @param text      内容文本
 * @param origin    信任来源
 * @param sourceRef 来源引用（可空）— 记录这段内容来自哪个工具/URL/文件，供 provenance 审计
 */
public record TaggedContent(String text, TrustOrigin origin, String sourceRef) {

    /** 便捷工厂：系统生成的内容（可信）。 */
    public static TaggedContent system(String text) {
        return new TaggedContent(text, TrustOrigin.SYSTEM_GENERATED, null);
    }

    /** 便捷工厂：外部工具输出（不可信）。 */
    public static TaggedContent externalToolOutput(String text, String sourceRef) {
        return new TaggedContent(text, TrustOrigin.TOOL_OUTPUT_EXTERNAL, sourceRef);
    }

    /** 便捷工厂：内部工具输出（可信）。 */
    public static TaggedContent internalToolOutput(String text, String sourceRef) {
        return new TaggedContent(text, TrustOrigin.TOOL_OUTPUT_INTERNAL, sourceRef);
    }

    /** 是否可信（可套高信任框架）— 委托给 {@link TrustOrigin#isTrusted()}。 */
    public boolean isTrusted() {
        return origin != null && origin.isTrusted();
    }

    /** 文本是否为空。 */
    public boolean isEmpty() {
        return text == null || text.isEmpty();
    }
}
