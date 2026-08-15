package com.ouisani.aios.core.security.builtin;

import com.ouisani.aios.core.security.Guardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感数据检测护栏（ToolGuardrail）。
 * <p>
 * 检查工具调用的输入和输出是否包含敏感数据，例如：
 * <ul>
 *   <li>API Key（OpenAI sk-、GitHub ghp_、AWS AKIA 等格式）</li>
 *   <li>密码（password=、passwd=、pwd= 等模式）</li>
 *   <li>Token / Secret</li>
 *   <li>私钥（-----BEGIN PRIVATE KEY-----）</li>
 * </ul>
 * 触发时返回 {@link Guardrail.GuardrailAction#REJECT_CONTENT}，
 * 阻止敏感数据流入对话历史或返回给用户。
 */
public class SensitiveDataGuardrail implements Guardrail.ToolGuardrail {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataGuardrail.class);

    /** 敏感数据模式列表 */
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            // OpenAI API Key
            Pattern.compile("sk-[A-Za-z0-9]{20,}"),
            // GitHub Personal Access Token
            Pattern.compile("ghp_[A-Za-z0-9]{36}"),
            // AWS Access Key ID
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            // 通用 api_key= / apikey= 赋值
            Pattern.compile("(?i)api[_-]?key\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]{20,}"),
            // 密码赋值
            Pattern.compile("(?i)(password|passwd|pwd)\\s*[:=]\\s*[\"']?[^\"'\\s]{6,}"),
            // Token / Secret 赋值
            Pattern.compile("(?i)(token|secret)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-\\.]{20,}"),
            // PEM 私钥头
            Pattern.compile("-----BEGIN\\s+(RSA\\s+|EC\\s+|OPENSSH\\s+|DSA\\s+)?PRIVATE\\s+KEY-----")
    );

    @Override
    public Guardrail.GuardrailResult check(String agentId, String toolName, String input, String output) {
        String combined = (input == null ? "" : input) + "\n" + (output == null ? "" : output);
        if (combined.isBlank()) {
            return Guardrail.GuardrailResult.allowed();
        }
        for (Pattern p : SENSITIVE_PATTERNS) {
            if (p.matcher(combined).find()) {
                String info = "工具 '" + toolName + "' 的输入/输出中检测到敏感数据: 匹配模式 '"
                        + maskPattern(p.pattern()) + "'";
                log.warn("[SensitiveDataGuardrail] agent={}, {}", agentId, info);
                return Guardrail.GuardrailResult.tripped(info, Guardrail.GuardrailAction.REJECT_CONTENT);
            }
        }
        return Guardrail.GuardrailResult.allowed();
    }

    /** 对正则模式做简单脱敏，避免日志中泄露完整模式细节 */
    private String maskPattern(String pattern) {
        if (pattern.length() > 40) {
            return pattern.substring(0, 40) + "...";
        }
        return pattern;
    }
}
