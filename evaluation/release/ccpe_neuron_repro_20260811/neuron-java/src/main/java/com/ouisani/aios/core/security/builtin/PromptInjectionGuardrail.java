package com.ouisani.aios.core.security.builtin;

import com.ouisani.aios.core.security.Guardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Prompt 注入检测护栏（InputGuardrail）。
 * <p>
 * 通过正则匹配检测常见的 Prompt 注入攻击模式，例如：
 * <ul>
 *   <li>"ignore previous instructions"</li>
 *   <li>"system prompt"</li>
 *   <li>"you are now"</li>
 *   <li>"forget everything"</li>
 * </ul>
 * 触发时返回 {@link Guardrail.GuardrailAction#REJECT_CONTENT}，
 * 与 LLM 调用并行执行，触发即取消模型调用以节省 Token。
 */
public class PromptInjectionGuardrail implements Guardrail.InputGuardrail {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionGuardrail.class);

    /** Prompt 注入攻击的常见模式（大小写不敏感） */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(previous|prior|above|all)\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(previous|prior|all)\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s+prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+a", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(everything|all|previous|prior)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("reveal\\s+(your|the)\\s+(system\\s+)?prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("new\\s+instructions?\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act\\s+as\\s+if\\s+you\\s+are", Pattern.CASE_INSENSITIVE),
            Pattern.compile("override\\s+(your|the|all)\\s+(system\\s+)?(rules?|instructions?|prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("jailbreak", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public CompletableFuture<Guardrail.GuardrailResult> check(String agentId, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            if (prompt == null || prompt.isBlank()) {
                return Guardrail.GuardrailResult.allowed();
            }
            for (Pattern p : INJECTION_PATTERNS) {
                if (p.matcher(prompt).find()) {
                    String info = "检测到可能的 Prompt 注入攻击: 匹配模式 '" + p.pattern() + "'";
                    log.warn("[PromptInjectionGuardrail] agent={}, {}", agentId, info);
                    return Guardrail.GuardrailResult.tripped(info, Guardrail.GuardrailAction.REJECT_CONTENT);
                }
            }
            return Guardrail.GuardrailResult.allowed();
        });
    }
}
