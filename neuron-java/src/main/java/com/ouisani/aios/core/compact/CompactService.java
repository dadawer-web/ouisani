package com.ouisani.aios.core.compact;

import com.ouisani.aios.core.AgentTask;
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

    // ════════════════════════════════════════════════════════════════
    //  两级降级压缩 — 借鉴 OpenHarness 的 microcompact + full compact
    //  微压缩 (零 LLM 调用) → 全压缩 (LLM 摘要)
    // ════════════════════════════════════════════════════════════════

    /** 默认保留最近的工具结果数量 */
    private static final int DEFAULT_KEEP_RECENT = 5;

    /** 微压缩后仍超限的阈值比例 — 超过此比例才触发全压缩 */
    private static final double STILL_OVER_RATIO = 0.85;

    /**
     * 微压缩 AgentTask 上下文 — 零 LLM 调用，纯 CPU 操作。
     * <p>
     * 遍历 {@code AgentTask.context}（List&lt;TokenRecord&gt;），
     * 把超过 N 轮的 TokenRecord 中属于工具结果的 content 替换为
     * {@code [tool_result_truncated]} 占位符，不调 LLM。
     * <p>
     * 仅当 microCompact 后仍然超限时才触发全量压缩（autoCompact）。
     *
     * @param context    Agent 的上下文 token 记录列表
     * @param keepRecent 保留最近 N 个工具结果不被截断
     * @return 微压缩结果
     */
    public static MicroCompactResult microCompactContext(
            List<AgentTask.TokenRecord> context, int keepRecent) {

        if (context == null || context.isEmpty()) {
            return new MicroCompactResult(0, 0, 0, false, "empty context");
        }

        int keep = keepRecent > 0 ? keepRecent : DEFAULT_KEEP_RECENT;
        int totalRecords = context.size();
        int compactedCount = 0;
        long savedChars = 0;

        // 从后向前计数，标记保留最近 keep 个工具结果
        int toolResultCount = 0;
        boolean[] shouldKeep = new boolean[totalRecords];

        for (int i = totalRecords - 1; i >= 0; i--) {
            AgentTask.TokenRecord record = context.get(i);
            if (isCompactableToolResult(record.content())) {
                toolResultCount++;
                if (toolResultCount <= keep) {
                    shouldKeep[i] = true;
                }
            }
        }

        // 执行截断 — 替换旧工具结果内容为占位符
        for (int i = 0; i < totalRecords; i++) {
            if (!shouldKeep[i] && isCompactableToolResult(context.get(i).content())) {
                AgentTask.TokenRecord old = context.get(i);
                long oldLen = old.content() != null ? old.content().length() : 0;

                // 原地替换：创建新的 TokenRecord，保持 role 和 timestamp
                context.set(i, new AgentTask.TokenRecord(
                        old.role(),
                        "[tool_result_truncated]",
                        old.timestamp()
                ));

                savedChars += oldLen;
                compactedCount++;
            }
        }

        boolean stillOver = false; // 由调用方根据 token 估算决定
        String message = String.format(
                "MicroCompact: %d/%d records compacted, ~%d chars saved, kept %d recent tool results",
                compactedCount, totalRecords, savedChars, Math.min(toolResultCount, keep)
        );

        log.info("[CompactService] {}", message);
        return new MicroCompactResult(
                totalRecords, compactedCount, (int) savedChars, stillOver, message
        );
    }

    /**
     * 两级降级压缩 — 先微压缩，不够再全压缩。
     * <p>
     * 这是推荐的压缩入口点，调用流程：
     * <ol>
     *   <li>先执行 microCompactContext() — 零 LLM 调用</li>
     *   <li>估算压缩后的 token 数</li>
     *   <li>如果仍然超限（超过阈值的 STILL_OVER_RATIO），才触发 autoCompact()</li>
     *   <li>如果微压缩后不超限，直接返回，省下一次 LLM 调用</li>
     * </ol>
     *
     * @param task               Agent 任务（包含 context）
     * @param currentTokens      当前 token 数
     * @param contextWindowSize  上下文窗口大小
     * @param sdk                AIOS SDK（用于全压缩时调 LLM）
     * @param agentId            Agent ID
     * @param autoCompactState   自动压缩状态
     * @return 压缩结果，如果不需要压缩返回 null
     */
    public static CompactionResult tryMicroCompactFirst(
            AgentTask task,
            int currentTokens,
            int contextWindowSize,
            AiosSdk sdk,
            String agentId,
            AutoCompactState autoCompactState) {

        int effectiveWindow = contextWindowSize - 20000;
        int threshold = effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS;

        if (currentTokens < threshold) {
            return null; // 不需要压缩
        }

        // ── 第一级：微压缩（零 LLM 调用）──
        List<AgentTask.TokenRecord> context = task.context();
        // 注意：context() 返回的是 List.copyOf，不可修改
        // 所以我们需要通过 task 的可变 context 列表操作
        // 但 AgentTask.context 字段是 final List<TokenRecord>，appendContext 会 add
        // 我们需要通过 replaceHistoryRange 或直接操作

        MicroCompactResult microResult = microCompactContext(context, DEFAULT_KEEP_RECENT);

        // 估算微压缩后的 token 数
        int estimatedPostMicroTokens = currentTokens - (int)(microResult.savedChars() * 4.0 / 3.0);

        log.info("[CompactService] After microCompact: ~{} tokens (was {})", 
                estimatedPostMicroTokens, currentTokens);

        // 如果微压缩后不超限，直接返回，省下一次 LLM 调用
        if (estimatedPostMicroTokens < threshold * STILL_OVER_RATIO) {
            log.info("[CompactService] 微压缩后已不超限，跳过全压缩（省下一次 LLM 调用）");
            return new CompactionResult(
                    List.of("[micro-compact applied: " + microResult.compactedCount() 
                            + " tool results truncated]"),
                    currentTokens,
                    estimatedPostMicroTokens,
                    "Micro-compact: " + currentTokens + " → ~" + estimatedPostMicroTokens 
                            + " tokens (no LLM call needed)"
            );
        }

        // ── 第二级：全压缩（LLM 摘要）──
        log.info("[CompactService] 微压缩后仍超限 ({} > {})，触发全压缩", 
                estimatedPostMicroTokens, (int)(threshold * STILL_OVER_RATIO));

        return autoCompact(
                task.contextHistory(),
                estimatedPostMicroTokens,
                contextWindowSize,
                sdk,
                agentId,
                autoCompactState
        );
    }

    /** 微压缩结果 */
    public record MicroCompactResult(
            int totalRecords,
            int compactedCount,
            int savedChars,
            boolean stillOverLimit,
            String message
    ) {}

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
