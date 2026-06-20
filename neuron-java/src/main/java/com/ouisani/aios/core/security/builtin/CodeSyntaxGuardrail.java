package com.ouisani.aios.core.security.builtin;

import com.ouisani.aios.core.security.Guardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代码语法检查护栏（OutputGuardrail）。
 * <p>
 * 对 Agent 输出中的代码块进行基本语法检查：
 * <ul>
 *   <li>代码块（```...```）是否正确闭合</li>
 *   <li>括号（()、{}、[]）是否匹配（考虑字符串字面量）</li>
 * </ul>
 * 这不是完整的语法分析器，仅做轻量级的结构性检查，
 * 用于拦截明显残缺的代码输出。
 */
public class CodeSyntaxGuardrail implements Guardrail.OutputGuardrail {

    private static final Logger log = LoggerFactory.getLogger(CodeSyntaxGuardrail.class);

    private static final String CODE_FENCE = "```";

    @Override
    public Guardrail.GuardrailResult check(String agentId, String output) {
        if (output == null || output.isBlank()) {
            return Guardrail.GuardrailResult.allowed();
        }

        int fenceCount = countOccurrences(output, CODE_FENCE);
        // 代码块必须成对出现（偶数个 ```）
        if (fenceCount % 2 != 0) {
            String info = "代码块未正确闭合（检测到 " + fenceCount + " 个 ``` 标记）";
            log.warn("[CodeSyntaxGuardrail] agent={}, {}", agentId, info);
            return Guardrail.GuardrailResult.tripped(info, Guardrail.GuardrailAction.REJECT_CONTENT);
        }

        // 逐个检查代码块的括号匹配
        int searchStart = 0;
        while (true) {
            int fenceStart = output.indexOf(CODE_FENCE, searchStart);
            if (fenceStart < 0) break;

            int langEnd = output.indexOf('\n', fenceStart);
            int codeStart = langEnd >= 0 ? langEnd + 1 : fenceStart + CODE_FENCE.length();

            int fenceEnd = output.indexOf(CODE_FENCE, codeStart);
            if (fenceEnd < 0) break; // 已被上面的奇偶检查覆盖

            String code = output.substring(codeStart, fenceEnd);
            if (!checkBracketBalance(code)) {
                String info = "代码块括号不匹配";
                log.warn("[CodeSyntaxGuardrail] agent={}, {}", agentId, info);
                return Guardrail.GuardrailResult.tripped(info, Guardrail.GuardrailAction.REJECT_CONTENT);
            }

            searchStart = fenceEnd + CODE_FENCE.length();
        }

        return Guardrail.GuardrailResult.allowed();
    }

    /** 统计子串出现次数 */
    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * 检查代码中的括号是否匹配（考虑字符串字面量和转义）。
     * 支持 ()、{}、[] 三种括号。
     */
    private boolean checkBracketBalance(String code) {
        int parens = 0, braces = 0, brackets = 0;
        boolean inString = false;
        char stringDelim = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escape = false;

        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = (i + 1 < code.length()) ? code.charAt(i + 1) : '\0';

            if (escape) {
                escape = false;
                continue;
            }
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') { inBlockComment = false; i++; }
                continue;
            }
            if (inString) {
                if (c == '\\') { escape = true; }
                else if (c == stringDelim) { inString = false; }
                continue;
            }
            // 注释检测
            if (c == '/' && next == '/') { inLineComment = true; i++; continue; }
            if (c == '/' && next == '*') { inBlockComment = true; i++; continue; }
            // 字符串检测
            if (c == '"' || c == '\'' || c == '`') { inString = true; stringDelim = c; continue; }

            switch (c) {
                case '(' -> parens++;
                case ')' -> { if (--parens < 0) return false; }
                case '{' -> braces++;
                case '}' -> { if (--braces < 0) return false; }
                case '[' -> brackets++;
                case ']' -> { if (--brackets < 0) return false; }
            }
        }
        return parens == 0 && braces == 0 && brackets == 0;
    }
}
