package com.ouisani.aios.core.recovery;

/**
 * 恢复提示注入净化器 — 对不可信错误文本做转义/截断，防御"借恢复通道注入指令"攻击。
 * <p>
 * <b>威胁模型</b>：恢复策略（{@link ReflectionInjectionRecovery} 等）会把失败的错误信息
 * （{@code exception.getMessage()} / {@code lastErrorTrace}）原样注入下一轮 LLM 上下文。
 * 这些错误文本来自失败的工具/子 Agent 输出，<b>不可信</b>。一个恶意 app 可故意抛出
 * message 里藏着 {@code <tool_call>} 载荷的异常，让恢复策略把它当成"反思提示"注入，
 * 从而在"修复"过程中绕过原本会拦截它的权限检查。
 * <p>
 * <b>本类职责</b>（纵深防御的一层，核心防御仍是 {@link RecoveryPermissionGuard}）：
 * <ol>
 *   <li>转义工具调用控制标记 — {@code <tool_call>}、{@code <function=...>}、
 *       {@code <parameter=...>} 及其闭合标签，使其不再被 LLM 解析为工具调用。</li>
 *   <li>截断长度上限 — 防止超长载荷撑爆上下文或藏匿尾部 payload。</li>
 *   <li>剥离 fenced code block 闭合标记 — 防止载荷用 {@code ```} 提前闭合
 *       {@code ```text} 围栏后注入自由文本。</li>
 * </ol>
 * <p>
 * <b>非目标</b>：本类不替代权限重校验。即便 LLM 被诱导生成工具调用，
 * {@link RecoveryPermissionGuard} 仍会在重试前拦截越权请求。两层防御必须同时存在。
 */
public final class RecoveryPromptSanitizer {

    /** 净化后错误文本的最大长度（字符）。超长截断并标注。 */
    static final int MAX_ERROR_LENGTH = 2000;

    /** 需要被中和的工具调用控制标记（按长度降序，避免前缀误伤）。 */
    private static final String[] TOOL_CALL_MARKERS = {
            "</tool_call>",
            "<tool_call>",
            "</function=",
            "<function=",
            "</parameter>",
            "<parameter="
    };

    private RecoveryPromptSanitizer() {
    }

    /**
     * 净化不可信错误文本。
     * <p>
     * 语义保证：返回的字符串中不再包含任何 {@link #TOOL_CALL_MARKERS} 中的原始标记，
     * 且长度不超过 {@link #MAX_ERROR_LENGTH} + 截断标注。
     *
     * @param raw 不可信原始文本（异常 message / 错误 trace）；null 或空原样返回
     * @return 净化后的文本，可安全嵌入 prompt
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return raw;

        String s = raw;
        // 1. 中和 fenced code block 闭合标记 — 防止提前闭合 ```text 围栏
        s = s.replace("```", "\\u0060\\u0060\\u0060");
        // 2. 中和工具调用控制标记 — 逐个替换为可见但无害的占位
        for (String marker : TOOL_CALL_MARKERS) {
            if (s.contains(marker)) {
                s = s.replace(marker, "[BLOCKED:" + marker.replaceAll("[<>]", "") + "]");
            }
        }
        // 3. 截断长度上限
        if (s.length() > MAX_ERROR_LENGTH) {
            s = s.substring(0, MAX_ERROR_LENGTH) + "\n[...TRUNCATED by RecoveryPromptSanitizer, "
                    + (raw.length() - MAX_ERROR_LENGTH) + " chars omitted]";
        }
        return s;
    }
}
