package com.ouisani.aios.core.compact;

import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话压缩系统 — 对标 Claude Code 的 compact/ 模块。
 * <p>
 * 三级压缩策略：
 * 1. MicroCompact — 轻量级：移除旧工具结果，保留最近 N 个
 * 2. AutoCompact — 中量级：LLM 生成摘要替换历史消息
 * 3. FullCompact — 重量级：完整压缩管线 + 附件重建
 * <p>
 * OS 类比：相当于 Linux 的内存回收管线：
 * MicroCompact = kscrub（轻量清理）
 * AutoCompact = kswapd（后台回收）
 * FullCompact = direct reclaim（直接回收）
 */
public class CompactService {

    private static final Logger log = LoggerFactory.getLogger(CompactService.class);

    // ── 常量（对标 Claude Code） ──
    private static final int AUTOCOMPACT_BUFFER_TOKENS = 13000;
    private static final int WARNING_THRESHOLD = 20000;
    private static final int POST_COMPACT_MAX_FILES = 5;
    private static final int POST_COMPACT_TOKEN_BUDGET = 50000;
    private static final int POST_COMPACT_MAX_TOKENS_PER_FILE = 5000;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 可压缩的工具结果 */
    private static final List<String> COMPACTABLE_TOOLS = List.of(
            "file_read", "bash", "grep", "glob", "web_fetch", "web_search", "file_edit", "file_write"
    );

    /** 压缩结果 */
    public record CompactionResult(
            List<String> summaryMessages,
            int preCompactTokens,
            int postCompactTokens,
            String userDisplayMessage
    ) {}

    /** 自动压缩跟踪状态 */
    public static class AutoCompactState {
        public boolean compacted = false;
        public int turnCounter = 0;
        public String turnId = "";
        public int consecutiveFailures = 0;
    }

    /**
     * 微压缩 — 移除旧的工具调用结果，保留最近 N 个。
     * 对标 microCompact.ts。
     */
    public static List<String> microCompact(List<String> messages, int keepRecent) {
        List<String> result = new ArrayList<>();
        int toolResultCount = 0;

        // 从后向前计数，保留最近 keepRecent 个工具结果
        for (int i = messages.size() - 1; i >= 0; i--) {
            String msg = messages.get(i);
            if (isCompactableToolResult(msg)) {
                toolResultCount++;
                if (toolResultCount > keepRecent) {
                    result.add(0, "[tool result compacted]");
                    continue;
                }
            }
            result.add(0, msg);
        }

        log.debug("[CompactService] MicroCompact: {} → {} messages", messages.size(), result.size());
        return result;
    }

    /**
     * 自动压缩 — 当 token 数超过阈值时触发 LLM 生成摘要。
     * 对标 autoCompact.ts。
     */
    public static CompactionResult autoCompact(
            List<String> messages,
            int currentTokens,
            int contextWindowSize,
            AiosSdk sdk,
            String agentId,
            AutoCompactState state
    ) {
        int effectiveWindow = contextWindowSize - 20000;
        int threshold = effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS;

        if (currentTokens < threshold) {
            return null; // 不需要压缩
        }

        if (state.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            log.warn("[CompactService] Circuit breaker activated: {} consecutive failures", state.consecutiveFailures);
            return null;
        }

        log.info("[CompactService] Auto-compact triggered: {} tokens > {} threshold", currentTokens, threshold);
        System.out.printf("[CompactService] Auto-compact triggered: %d tokens > %d threshold%n", currentTokens, threshold);

        try {
            // 构建压缩 Prompt
            String compactPrompt = buildCompactPrompt(messages);

            // 调用 LLM 生成摘要
            String summary = sdk.think(agentId, compactPrompt);

            state.compacted = true;
            state.consecutiveFailures = 0;

            int estimatedPostTokens = estimateTokens(summary);

            return new CompactionResult(
                    List.of(summary),
                    currentTokens,
                    estimatedPostTokens,
                    "Context auto-compacted: " + currentTokens + " → ~" + estimatedPostTokens + " tokens"
            );
        } catch (Exception e) {
            state.consecutiveFailures++;
            log.error("[CompactService] Auto-compact failed (attempt {}): {}", state.consecutiveFailures, e.getMessage());
            return null;
        }
    }

    /**
     * 完整压缩 — 带附件重建的压缩管线。
     * 对标 compact.ts 的 compactConversation()。
     */
    public static CompactionResult fullCompact(
            List<String> messages,
            int currentTokens,
            AiosSdk sdk,
            String agentId
    ) {
        log.info("[CompactService] Full compact triggered for {} messages", messages.size());

        try {
            String compactPrompt = buildCompactPrompt(messages);
            String summary = sdk.think(agentId, compactPrompt);

            int estimatedPostTokens = estimateTokens(summary);

            return new CompactionResult(
                    List.of(summary),
                    currentTokens,
                    estimatedPostTokens,
                    "Context fully compacted: " + currentTokens + " → ~" + estimatedPostTokens + " tokens"
            );
        } catch (Exception e) {
            log.error("[CompactService] Full compact failed: {}", e.getMessage());
            return null;
        }
    }

    // ── 内部方法 ──

    private static boolean isCompactableToolResult(String message) {
        String lower = message.toLowerCase();
        return COMPACTABLE_TOOLS.stream().anyMatch(tool -> lower.contains("<" + tool + ">") || lower.contains("tool_result name=\"" + tool + "\""));
    }

    private static String buildCompactPrompt(List<String> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please summarize the following conversation history concisely. ");
        sb.append("Preserve all important decisions, code changes, file paths, and technical details. ");
        sb.append("Remove redundant tool outputs and intermediate reasoning steps.\n\n");
        sb.append("--- CONVERSATION HISTORY ---\n\n");

        for (String msg : messages) {
            sb.append(msg).append("\n\n");
        }

        sb.append("--- END OF HISTORY ---\n\n");
        sb.append("Provide a concise summary that preserves all critical information:");
        return sb.toString();
    }

    private static int estimateTokens(String text) {
        // 粗略估算：4/3 字符比
        return (int) (text.length() * 4.0 / 3.0);
    }
}
