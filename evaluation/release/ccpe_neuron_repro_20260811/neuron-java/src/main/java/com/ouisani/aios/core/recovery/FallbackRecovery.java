package com.ouisani.aios.core.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 11 层：兜底恢复策略 — 所有其他策略失败后的最后防线。
 * <p>
 * 对标 omo 的 session recovery + background agent circuit breaker。
 * 当所有分类策略都无法恢复时，尝试通用的反思+重试机制。
 */
public class FallbackRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(FallbackRecovery.class);

    @Override
    public String name() { return "FallbackRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return context.attempt() <= 3; // 兜底最多重试 3 次
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[FallbackRecovery] 正在为 Agent 应用通用回退 {}", context.agentId());
        // 不可信错误文本 — 净化后再注入，防止载荷借恢复通道绕过权限（同 ReflectionInjectionRecovery）
        String errorMsg = RecoveryPromptSanitizer.sanitize(
                context.exception().getMessage() != null ? context.exception().getMessage() : "Unknown error");

        String modifier = "\n\n[SYSTEM - RECOVERY FALLBACK]:\n"
                + "An unexpected error occurred:\n"
                + "```text\n" + errorMsg + "\n```\n"
                + "All specialized recovery strategies have been exhausted.\n"
                + "Please:\n"
                + "1. Carefully review the error above\n"
                + "2. Try a completely different approach\n"
                + "3. If you cannot resolve the issue, provide a summary of what you've accomplished\n"
                + "   and what remains to be done.\n";

        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Fallback recovery: generic retry prompt injected", modifier);
    }
}
