package com.ouisani.aios.core.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 7 层：反思注入重试 — 捕获错误注入下一次 LLM 上下文。
 * <p>
 * 这是 AIOS 原有的自愈核心机制，现作为恢复策略链的一环。
 * 对标 omo 的 Auto-Retry + 反思注入思想。
 */
public class ReflectionInjectionRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(ReflectionInjectionRecovery.class);
    private static final int MAX_REFLECTION_ATTEMPTS = 3;

    @Override
    public String name() { return "ReflectionInjectionRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return context.attempt() <= MAX_REFLECTION_ATTEMPTS;
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[ReflectionInjectionRecovery] 正在为 Agent 注入反思 {} (attempt {})",
                context.agentId(), context.attempt());

        String lastError = context.lastErrorTrace();
        if (lastError == null || lastError.isEmpty()) {
            lastError = context.exception().getMessage() != null ? context.exception().getMessage() : "Unknown error";
        }

        String modifier = "\n\n[SYSTEM CRITICAL - PREVIOUS ATTEMPT FAILED]:\n"
                + "The previous execution failed with the following error/logs:\n"
                + "```text\n" + lastError + "\n```\n"
                + "Please thoroughly analyze this error, figure out what went wrong, "
                + "and provide a CORRECTED solution or code. "
                + "Do NOT repeat the same mistake!\n"
                + "Attempt " + context.attempt() + " of " + MAX_REFLECTION_ATTEMPTS + ".\n";

        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Reflection injection: error context added to prompt", modifier);
    }
}
