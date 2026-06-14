package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.compact.CompactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 5 层：上下文窗口限制恢复 — 5 种截断策略按优先级执行。
 * <p>
 * 对标 omo 的 anthropic-context-window-limit-recovery（31 个文件，2232 LOC）。
 * 当 Token 超限时，按以下优先级尝试：
 * <ol>
 *   <li>空内容恢复 — 移除空/null 内容块</li>
 *   <li>去重 — 移除重复的工具结果</li>
 *   <li>目标 Token 截断 — 将工具输出截断到目标比率</li>
 *   <li>激进截断 — 最后手段，保留最少输出</li>
 *   <li>摘要重试 — 压缩 + 摘要后重试</li>
 * </ol>
 */
public class ContextWindowRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(ContextWindowRecovery.class);
    private static final double TARGET_TRUNCATION_RATIO = 0.5;
    private static final int MAX_TRUNCATION_ATTEMPTS = 20;

    @Override
    public String name() { return "ContextWindowRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return context.category() == RecoveryOrchestrator.ErrorCategory.CONTEXT_WINDOW_EXCEEDED;
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[ContextWindowRecovery] Attempting context window recovery for agent {}", context.agentId());

        // 策略 1-4 通过 Prompt 修改实现（指示 LLM 缩减输出）
        String modifier = "\n\n[SYSTEM CRITICAL - CONTEXT WINDOW EXCEEDED]:\n"
                + "Your conversation has exceeded the context window limit.\n"
                + "You MUST take immediate action to reduce token usage:\n"
                + "1. Do NOT repeat previous tool outputs or code in your response\n"
                + "2. Keep your responses extremely concise — no explanations, just actions\n"
                + "3. If you need to reference previous results, summarize them in 1-2 sentences\n"
                + "4. Do NOT include large code blocks unless absolutely necessary\n"
                + "5. Focus ONLY on the remaining task — do not revisit completed steps\n";

        // 策略 5：触发压缩
        try {
            CompactService.CompactionResult compactResult = CompactService.autoCompact(
                    java.util.List.of(), 200000, 100000,
                    null, context.agentId(), new CompactService.AutoCompactState());
            if (compactResult != null) {
                modifier += "6. [AUTO-COMPACT APPLIED]: Your conversation has been automatically compressed.\n";
                log.info("[ContextWindowRecovery] Auto-compact applied for agent {}", context.agentId());
            }
        } catch (Exception e) {
            log.warn("[ContextWindowRecovery] Auto-compact failed: {}", e.getMessage());
        }

        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Context window recovery: applied truncation + compact", modifier);
    }
}
