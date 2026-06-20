package com.ouisani.aios.core.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 1 层：空内容恢复 — 处理 LLM 返回空/null 响应。
 * <p>
 * 对标 omo 的 anthropic-context-window-limit-recovery 中的空内容恢复子策略。
 * 当 LLM 返回空字符串或 null 时，注入提示要求重新生成。
 */
public class EmptyResponseRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(EmptyResponseRecovery.class);

    @Override
    public String name() { return "EmptyResponseRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return true; // 空响应总是适用
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[EmptyResponseRecovery] 正在为 Agent 注入重试 Prompt {}", context.agentId());
        String modifier = "\n\n[SYSTEM WARNING - EMPTY RESPONSE DETECTED]:\n"
                + "Your previous response was empty or null. This is not acceptable.\n"
                + "You MUST provide a substantive response. Please retry with actual content.\n";
        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Empty response recovery: injected retry prompt", modifier);
    }
}
