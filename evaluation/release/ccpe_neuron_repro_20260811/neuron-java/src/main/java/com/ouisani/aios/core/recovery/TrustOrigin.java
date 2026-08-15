package com.ouisani.aios.core.recovery;

import java.util.Map;

/**
 * 内容信任来源标签 — 4 级信任分级，恢复通道注入攻击面（Direction D）的基础设施。
 * <p>
 * <b>洞1 背景</b>：{@link ReflectionInjectionRecovery} 把失败的错误文本塞进 {@code [SYSTEM CRITICAL]}
 * 高信任模板注入下一轮 prompt，但 {@code lastErrorTrace} 来源没分级 —— 若 agent 在处理外部网页/文件时
 * 失败，错误信息里带那段网页内容，攻击者可在网页里埋"看起来像报错日志、实际是指令"的文本，经失败→恢复
 * 路径被系统自己盖上 SYSTEM CRITICAL 信任戳原样送进下一轮 agent。这是经恢复通道的间接 prompt injection。
 * <p>
 * <b>4 级分级</b>（关键创新：区分工具输出的内部/外部来源）：
 * <ul>
 *   <li>{@link #SYSTEM_GENERATED} — 内核/恢复策略自身生成（如内核异常栈）。<b>可信</b>，可套 SYSTEM CRITICAL。</li>
 *   <li>{@link #USER_INPUT} — 用户直接输入。可信主体（用户是 principal），可套高信任框架。</li>
 *   <li>{@link #TOOL_OUTPUT_INTERNAL} — 内部工具输出（bash 执行内部命令、内置只读工具）。
 *       工具本身受控，<b>可信</b>，可套高信任框架。</li>
 *   <li>{@link #TOOL_OUTPUT_EXTERNAL} — 外部工具输出（web_fetch/file_read 处理外部网页/不可信文件）。
 *       工具回显了外部攻击者可控内容，<b>不可信</b>，<b>禁止</b>套 SYSTEM CRITICAL 高信任框架。</li>
 * </ul>
 * <p>
 * <b>与 {@link RecoveryPromptSanitizer} 的关系</b>：sanitizer 是"载荷中和"（转义 {@code <tool_call>}
 * 控制标记，防 Vector B）；信任分级是"框架降级"（不给外部内容盖 SYSTEM CRITICAL，防自然语言指令
 * Vector C）。两者正交，必须同时存在 —— sanitizer 挡不住自然语言指令，信任分级挡不住控制标记。
 * <p>
 * <b>来源信号传播</b>：本标签由 {@link RecoveryContext#metadata()} 的 {@code source} 键携带，
 * 上游（工具调用捕获异常处，如 {@link ToolErrorRecovery}）根据失败工具是否处理外部内容打标。
 * {@link #fromMetadata} 缺失时<b>保守返回 {@link #SYSTEM_GENERATED}</b> —— 向后兼容：未打标签的旧调用点
 * 维持原高信任行为（其错误历史上来自内核自身异常，可信）。显式标 {@code external} 才降级，对新调用点
 * 是 opt-in 安全增强，对旧调用点零回归。
 */
public enum TrustOrigin {

    /** 内核/恢复策略自身生成 — 可信，可套 SYSTEM CRITICAL。 */
    SYSTEM_GENERATED,

    /** 用户直接输入 — 可信主体，可套高信任框架。 */
    USER_INPUT,

    /** 内部工具输出（bash 内部命令、内置只读工具）— 工具受控，可信。 */
    TOOL_OUTPUT_INTERNAL,

    /** 外部工具输出（web_fetch/file_read 处理外部内容）— 回显攻击者可控内容，不可信。 */
    TOOL_OUTPUT_EXTERNAL;

    /** metadata 中携带来源标签的键名。 */
    public static final String META_KEY = "source";

    /**
     * 是否可信（可套用 SYSTEM CRITICAL 高信任框架）。
     * <p>
     * 仅 {@link #TOOL_OUTPUT_EXTERNAL} 不可信 —— 这是洞1 防御的核心判定：外部工具回显的内容
     * 一律不得盖系统级信任戳。其余三级（内核/用户/内部工具）均可信。
     */
    public boolean isTrusted() {
        return this != TOOL_OUTPUT_EXTERNAL;
    }

    /** 是否为外部不可信来源（仅 {@link #TOOL_OUTPUT_EXTERNAL}）。 */
    public boolean isExternalUntrusted() {
        return this == TOOL_OUTPUT_EXTERNAL;
    }

    /**
     * 从 {@link RecoveryContext#metadata()} 解析来源标签。
     * <p>
     * 缺失/无法识别时保守返回 {@link #SYSTEM_GENERATED}（向后兼容零回归）。
     *
     * @param metadata 恢复上下文元数据；null → SYSTEM_GENERATED
     * @return 解析出的信任来源
     */
    public static TrustOrigin fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) return SYSTEM_GENERATED;
        Object raw = metadata.get(META_KEY);
        if (raw == null) return SYSTEM_GENERATED;
        String s = raw.toString().trim();
        if (s.isEmpty()) return SYSTEM_GENERATED;
        // 精确名匹配
        for (TrustOrigin o : values()) {
            if (o.name().equalsIgnoreCase(s)) return o;
        }
        // 别名
        switch (s.toLowerCase()) {
            case "system", "kernel" -> { return SYSTEM_GENERATED; }
            case "user" -> { return USER_INPUT; }
            case "tool", "tool_internal", "internal" -> { return TOOL_OUTPUT_INTERNAL; }
            // "external" / "tool_output" / "untrusted" → 外部不可信（保守降级）
            case "external", "tool_output", "tool_output_external", "untrusted" -> { return TOOL_OUTPUT_EXTERNAL; }
            default -> { return SYSTEM_GENERATED; }
        }
    }
}
