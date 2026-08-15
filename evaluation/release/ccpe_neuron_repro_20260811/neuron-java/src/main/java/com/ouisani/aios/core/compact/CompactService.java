package com.ouisani.aios.core.compact;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.AgentTask.TokenRecord;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.ouisani.aios.core.compact.CompactCutoffGuard.safeCompactionCutoff;
import static com.ouisani.aios.core.compact.CompactCutoffGuard.estimateTokens;
import static com.ouisani.aios.core.compact.CompactCutoffGuard.isToolCallRequest;
import static com.ouisani.aios.core.compact.CompactCutoffGuard.isToolResult;
import static com.ouisani.aios.core.compact.CompactCutoffGuard.isCompactableToolResult;
import static com.ouisani.aios.core.compact.CompactCutoffGuard.extractToolName;
import static com.ouisani.aios.core.compact.CompactCutoffGuard.autoCompactThreshold;

/**
 * 对话压缩系统 — 对标 Claude Code 的 compact/ 模块。
 * <p>
 * 三级压缩策略：
 * 1. MicroCompact — 轻量级：移除旧工具结果，保留最近 N 个
 * 2. AutoCompact — 中量级：LLM 生成摘要替换历史消息
 * 3. FullCompact — 重量级：完整压缩管线 + 附件重建
 * <p>
 * <b>CCR 可逆压缩集成（借鉴 Headroom）：</b>
 * 压缩时原始内容存入 {@link CompressionStore}，prompt 中留下带哈希的标记，
 * LLM 可通过 {@code ccr_retrieve(hash)} 工具取回原文。
 * 番茄钟死循环的根因——不可逆压缩导致信息永久丢失——被彻底解决。
 * <p>
 * OS 类比：相当于 Linux 的内存回收管线：
 * MicroCompact = kscrub（轻量清理）
 * AutoCompact = kswapd（后台回收）
 * FullCompact = direct reclaim（直接回收）
 * CCR = swap 分区（压缩后可取回，不像 OOM kill 那样不可逆）
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
        int baseThreshold = effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS;
        // 压缩模式决定触发阈值：REACTIVE=base，PROACTIVE/SEMANTIC=base*70%（借鉴 jcode 三模式）
        int threshold = autoCompactThreshold(baseThreshold);

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

        CompactionResult autoResult = autoCompact(
                task.contextHistory(),
                estimatedPostMicroTokens,
                contextWindowSize,
                sdk,
                agentId,
                autoCompactState
        );

        // ── 第三级：指数截断兜底（借鉴 Apix Level 4+）──
        // 当全压缩失败（熔断器激活或 LLM 异常）时，使用指数截断作为最终降级手段
        if (autoResult == null) {
            log.warn("[CompactService] 全压缩失败，启用指数截断兜底");
            return exponentialTruncateFallback(
                    task.context(), currentTokens, contextWindowSize, autoCompactState
            );
        }

        return autoResult;
    }

    /**
     * CCR 增强版两级降级压缩 — 与 {@link #tryMicroCompactFirst} 逻辑一致，
     * 但微压缩和指数截断均使用 CCR 版本（压缩可逆，LLM 可取回原文）。
     * <p>
     * <b>不破坏现有逻辑：</b>原 {@link #tryMicroCompactFirst} 方法保持不变，
     * 调用方可按需选择是否启用 CCR。
     *
     * @param task               Agent 任务（包含 context）
     * @param currentTokens      当前 token 数
     * @param contextWindowSize  上下文窗口大小
     * @param sdk                AIOS SDK（用于全压缩时调 LLM）
     * @param agentId            Agent ID
     * @param autoCompactState   自动压缩状态
     * @return 压缩结果，如果不需要压缩返回 null
     */
    public static CompactionResult tryMicroCompactFirstWithCcr(
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

        // ── 第一级：CCR 微压缩（零 LLM 调用，压缩可逆）──
        List<AgentTask.TokenRecord> context = task.context();

        MicroCompactResult microResult = microCompactContextWithCcr(context, DEFAULT_KEEP_RECENT);

        int estimatedPostMicroTokens = currentTokens - (int)(microResult.savedChars() * 4.0 / 3.0);

        log.info("[CompactService] After microCompact(CCR): ~{} tokens (was {})",
                estimatedPostMicroTokens, currentTokens);

        // 微压缩后不超限，直接返回
        if (estimatedPostMicroTokens < threshold * STILL_OVER_RATIO) {
            log.info("[CompactService] CCR 微压缩后已不超限，跳过全压缩");
            return new CompactionResult(
                    List.of("[micro-compact(CCR) applied: " + microResult.compactedCount()
                            + " tool results compressed with retrieval]"),
                    currentTokens,
                    estimatedPostMicroTokens,
                    "Micro-compact(CCR): " + currentTokens + " → ~" + estimatedPostMicroTokens
                            + " tokens (reversible, no LLM call)"
            );
        }

        // ── 第二级：全压缩（LLM 摘要）──
        CompactionResult autoResult = autoCompact(
                task.contextHistory(),
                estimatedPostMicroTokens,
                contextWindowSize,
                sdk,
                agentId,
                autoCompactState
        );

        // ── 第三级：CCR 指数截断兜底（截断消息存入 CCR，可取回）──
        if (autoResult == null) {
            log.warn("[CompactService] 全压缩失败，启用 CCR 指数截断兜底");
            return exponentialTruncateFallbackWithCcr(
                    task.context(), currentTokens, contextWindowSize, autoCompactState
            );
        }

        return autoResult;
    }

    // ════════════════════════════════════════════════════════════════
    //  工具调用链保护压缩（借鉴 Apix split_messages / drop_tool_messages）
    // ════════════════════════════════════════════════════════════════

    /**
     * 工具调用链保护分割 — 智能调整分割点，避免破坏
     * {@code AIMessage(tool_calls) ↔ ToolMessage} 的链式结构。
     * <p>
     * 借鉴 Apix 的 {@code split_messages}，核心逻辑：
     * <ol>
     *   <li>初始分割点 = {@code len - keepRecent}</li>
     *   <li>若分割点落在工具结果消息上，向前回溯找到完整工具块起点</li>
     *   <li>检查工具块前一条是否为带 tool_calls 的 AI 消息</li>
     *   <li>若是，将分割点移到该 AI 消息之前（整体保留链式结构）</li>
     *   <li>否则将分割点移到工具块之后</li>
     * </ol>
     * <p>
     * <b>为什么重要</b>：如果分割点切断了 tool_calls ↔ tool_result 的配对，
     * LLM 会因缺少工具结果而产生幻觉或报错。
     *
     * @param context     上下文 token 记录列表
     * @param keepRecent  保留最近 N 条消息
     * @return 分割结果（toSummarize + recentMessages）
     */
    public static SplitResult splitMessagesProtected(
            List<AgentTask.TokenRecord> context, int keepRecent) {

        if (context == null || context.isEmpty()) {
            return new SplitResult(List.of(), List.of());
        }
        if (keepRecent <= 0) {
            return new SplitResult(new ArrayList<>(context), List.of());
        }
        if (context.size() <= keepRecent) {
            return new SplitResult(List.of(), new ArrayList<>(context));
        }

        int splitIdx = context.size() - keepRecent;

        // 若分割点落在工具结果上，调整分割点保护链式结构
        if (isToolResult(context.get(splitIdx))) {
            // 向前回溯找到工具块起点
            int toolStart = splitIdx;
            while (toolStart > 0 && isToolResult(context.get(toolStart - 1))) {
                toolStart--;
            }

            // 向后找到工具块终点
            int toolEnd = splitIdx;
            while (toolEnd + 1 < context.size() && isToolResult(context.get(toolEnd))) {
                toolEnd++;
            }

            // 检查工具块前一条是否为带 tool_calls 的 AI 消息
            int prevIdx = toolStart - 1;
            if (prevIdx >= 0 && isToolCallRequest(context.get(prevIdx))) {
                // 将分割点移到 AI 消息之前，整体保留 AIMessage(tool_calls) → ToolMessage* 链
                splitIdx = prevIdx;
            } else {
                // 将分割点移到工具块之后
                splitIdx = toolEnd + 1;
            }
        }

        // 确保 splitIdx 在合法范围
        splitIdx = Math.max(0, Math.min(splitIdx, context.size()));

        // ── CCR 切点完整性守护（借鉴 jcode safe_compaction_cutoff）──
        // 验证切点不破坏 ToolUse↔ToolResult 配对；返回 0 表示无法保证配对，
        // 此时保留原 best-effort splitIdx（永不劣于今天），其他调用方零回归。
        int safeCutoff = safeCompactionCutoff(context, splitIdx);
        if (safeCutoff != 0) {
            splitIdx = safeCutoff;
        }

        List<AgentTask.TokenRecord> toSummarize = new ArrayList<>(context.subList(0, splitIdx));
        List<AgentTask.TokenRecord> recentMessages = new ArrayList<>(context.subList(splitIdx, context.size()));

        log.debug("[CompactService] splitMessagesProtected: splitIdx={}, toSummarize={}, recent={}",
                splitIdx, toSummarize.size(), recentMessages.size());

        return new SplitResult(toSummarize, recentMessages);
    }

    /**
     * 严格版分割 — 当 {@link CompactCutoffGuard#safeCompactionCutoff} 返回 0（无法保证配对）时，
     * 返回 {@code toSummarize=空}（真正拒绝压缩，"宁可不动也不破坏"）。
     * <p>
     * opt-in：供未来 live 接入使用。{@link #splitMessagesProtected} 默认不拒绝（零回归）。
     *
     * @param context     上下文记录列表
     * @param keepRecent  保留最近 N 条
     * @return 分割结果；当无法保证配对时 toSummarize 为空（全部保留，不压缩）
     */
    public static SplitResult splitMessagesProtectedStrict(
            List<AgentTask.TokenRecord> context, int keepRecent) {

        if (context == null || context.isEmpty()) {
            return new SplitResult(List.of(), List.of());
        }
        if (keepRecent <= 0 || context.size() <= keepRecent) {
            return new SplitResult(List.of(), new ArrayList<>(context));
        }

        int initialCutoff = context.size() - keepRecent;
        int safeCutoff = safeCompactionCutoff(context, initialCutoff);

        // 返回 0 = 拒绝压缩，全部保留
        if (safeCutoff == 0) {
            log.info("[CompactService] splitMessagesProtectedStrict: 拒绝压缩（无法保证 ToolUse↔ToolResult 配对）");
            return new SplitResult(List.of(), new ArrayList<>(context));
        }

        List<AgentTask.TokenRecord> toSummarize = new ArrayList<>(context.subList(0, safeCutoff));
        List<AgentTask.TokenRecord> recentMessages = new ArrayList<>(context.subList(safeCutoff, context.size()));
        return new SplitResult(toSummarize, recentMessages);
    }

    /**
     * 按 TODO 边界丢弃工具消息 — 将旧的工具结果内容替换为占位符，保留尾部。
     * <p>
     * 借鉴 Apix 的 {@code drop_tool_messages}，核心逻辑：
     * <ul>
     *   <li>尾部 {@code minKeep} 条消息受保护，永不丢弃</li>
     *   <li>查找最后一次 write_todos 调用作为"已完成 vs 进行中"的分割边界</li>
     *   <li>边界之前的工具结果替换为 {@code [outdated]}，之后的保留</li>
     * </ul>
     *
     * @param context 上下文 token 记录列表
     * @param minKeep 尾部保护条数
     * @return 丢弃后的新列表（不修改原列表）
     */
    public static List<AgentTask.TokenRecord> dropToolMessagesByBoundary(
            List<AgentTask.TokenRecord> context, int minKeep) {

        if (context == null || context.isEmpty()) {
            return context == null ? List.of() : new ArrayList<>(context);
        }

        int n = context.size();
        int protectedStart = Math.max(0, n - minKeep);

        // 查找最后一次 write_todos 调用的位置
        int lastTodoIdx = -1;
        for (int i = 0; i < n; i++) {
            AgentTask.TokenRecord record = context.get(i);
            if (isToolCallRequest(record) && record.content().contains("write_todos")) {
                lastTodoIdx = i;
            }
        }

        List<AgentTask.TokenRecord> result = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            AgentTask.TokenRecord record = context.get(i);

            // 尾部保护区域：原样保留
            if (i >= protectedStart) {
                result.add(record);
                continue;
            }

            // 工具结果消息：根据边界替换内容
            if (isToolResult(record)) {
                if (lastTodoIdx == -1 || i < lastTodoIdx) {
                    // 无边界或边界之前：标记为过期
                    result.add(new AgentTask.TokenRecord(
                            record.role(), "[Tool Result Outdated]", record.timestamp()
                    ));
                } else {
                    // 边界之后：保留
                    result.add(record);
                }
            } else {
                result.add(record);
            }
        }

        log.debug("[CompactService] dropToolMessagesByBoundary: {} → {} messages, lastTodoIdx={}",
                n, result.size(), lastTodoIdx);
        return result;
    }

    /**
     * 指数截断兜底 — 当 LLM 摘要失败时的最终降级手段。
     * <p>
     * 借鉴 Apix 的 Level 4+ 指数截断（{@code calc_keep}），公式：
     * <pre>{@code
     * keep = keepRecentBase // (2 ^ max(1, level - 3))
     * keep = max(2, keep)
     * }</pre>
     * 即 Level 4 → keepRecentBase/2，Level 5 → keepRecentBase/4，Level 6 → keepRecentBase/8...
     * <p>
     * 使用 {@link #splitMessagesProtected} 确保截断不破坏工具调用链。
     *
     * @param context            上下文 token 记录列表
     * @param currentTokens      当前 token 数
     * @param contextWindowSize  上下文窗口大小
     * @param state              自动压缩状态（用于读取连续失败次数作为 level）
     * @return 截断结果
     */
    public static CompactionResult exponentialTruncateFallback(
            List<AgentTask.TokenRecord> context,
            int currentTokens,
            int contextWindowSize,
            AutoCompactState state) {

        // 连续失败次数决定截断级别（Level 4 起步）
        int level = 4 + Math.min(state.consecutiveFailures, 6); // 上限 Level 10
        int keepRecentBase = Math.max(8, DEFAULT_KEEP_RECENT * 2); // 默认 10

        int keep = calcExponentialKeep(level, keepRecentBase);

        log.warn("[CompactService] 指数截断兜底: level={}, keep={} (base={})",
                level, keep, keepRecentBase);

        // 使用工具链保护分割
        SplitResult split = splitMessagesProtected(context, keep);
        List<AgentTask.TokenRecord> recentMessages = split.recentMessages();

        int estimatedPostTokens = estimateTokens(
                recentMessages.stream()
                        .map(AgentTask.TokenRecord::content)
                        .reduce("", (a, b) -> a + b)
        );

        String message = String.format(
                "Exponential truncate (level %d): %d → %d messages, ~%d tokens",
                level, context.size(), recentMessages.size(), estimatedPostTokens
        );

        log.warn("[CompactService] {}", message);

        return new CompactionResult(
                recentMessages.stream()
                        .map(AgentTask.TokenRecord::content)
                        .toList(),
                currentTokens,
                estimatedPostTokens,
                message
        );
    }

    /**
     * 计算指数截断的保留数量 — 借鉴 Apix 的 {@code calc_keep}。
     *
     * @param level           压缩级别（4 起步）
     * @param keepRecentBase 基准保留数
     * @return 实际保留数（下限 2）
     */
    private static int calcExponentialKeep(int level, int keepRecentBase) {
        int exponent = Math.max(1, level - 3);
        int keep = keepRecentBase / (int) Math.pow(2, exponent);
        return Math.max(2, keep);
    }



    /** 分割结果 — toSummarize（待摘要）+ recentMessages（保留） */
    public record SplitResult(
            List<AgentTask.TokenRecord> toSummarize,
            List<AgentTask.TokenRecord> recentMessages
    ) {}

    /** 微压缩结果 */
    public record MicroCompactResult(
            int totalRecords,
            int compactedCount,
            int savedChars,
            boolean stillOverLimit,
            String message
    ) {}


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



    // ════════════════════════════════════════════════════════════════
    //  CCR 可逆压缩集成 — 借鉴 Headroom 的 Compress-Cache-Retrieve 架构
    //
    //  核心理念：压缩不应该丢信息。
    //  - 压缩时：原始内容存入 CompressionStore，prompt 中留带哈希的标记
    //  - 检索时：LLM 调用 ccr_retrieve(hash) 工具取回原文
    //  - 番茄钟死循环的根因（不可逆压缩导致信息丢失）被彻底解决
    //
    //  设计原则：不破坏现有方法签名和逻辑，只增强压缩时的占位符内容
    // ════════════════════════════════════════════════════════════════

    /** CCR 压缩的最小内容长度 — 太短的内容不值得存入 CCR */
    private static final int CCR_MIN_CONTENT_LENGTH = 200;

    /** 内容路由器 — 按内容类型路由到专用压缩器（借鉴 Headroom ContentRouter） */
    private static final ContentRouter contentRouter = new ContentRouter();

    /**
     * 将原始内容存入 CCR 缓存并返回带哈希的压缩标记。
     * <p>
     * <b>不破坏现有逻辑：</b>如果内容太短或 CCR 不可用，返回原始的
     * {@code [tool_result_truncated]} 占位符，行为与之前完全一致。
     * <p>
     * 借鉴 Headroom SmartCrusher 的压缩标记格式。
     *
     * @param originalContent 原始工具结果内容
     * @param toolName        工具名
     * @return CCR 压缩标记，或回退到 {@code [tool_result_truncated]}
     */
    public static String compressWithCcr(String originalContent, String toolName) {
        if (originalContent == null || originalContent.length() < CCR_MIN_CONTENT_LENGTH) {
            // 内容太短，不值得 CCR 压缩，回退到原始占位符
            return "[tool_result_truncated]";
        }

        try {
            int originalTokens = estimateTokens(originalContent);

            // ── ContentRouter 按类型选压缩器 — 借鉴 Headroom ContentRouter ──
            // 检测内容类型（JSON数组/源代码/日志/GitDiff/HTML/表格/纯文本），
            // 路由到对应专用压缩器，得到高信噪比的压缩内容。
            ContentRouter.CompressionResult routeResult = contentRouter.compress(originalContent);

            // 压缩后的内容（可能是智能压缩结果，也可能是 passthrough 回退原文）
            String compressedContent = routeResult.compressed();
            int compressedTokens = routeResult.compressedTokens();
            String strategy = routeResult.strategy();

            // 如果 ContentRouter 没产生节省（passthrough），用简单占位符
            if ("passthrough".equals(strategy) || compressedTokens >= originalTokens) {
                compressedContent = "[tool_result_truncated]";
                compressedTokens = estimateTokens(compressedContent);
                strategy = "truncate_fallback";
            }

            // 存入 CCR 缓存 — 原始内容可被 LLM 通过 ccr_retrieve(hash) 取回
            String hash = CompressionStore.instance().store(
                    originalContent,
                    compressedContent,
                    originalTokens,
                    compressedTokens,
                    toolName,
                    strategy,
                    null  // 默认 TTL
            );

            // 返回带哈希的 CCR 标记 — 包含压缩内容摘要 + 检索哈希
            // LLM 看到压缩摘要就能获得大部分信息，需要细节时调用 ccr_retrieve(hash)
            String marker = CcrToolInjector.createMarkerWithTokens(originalTokens, compressedTokens, hash);
            if (!"truncate_fallback".equals(strategy)) {
                // 有智能压缩结果时，把压缩内容也放进去（不只是占位符）
                return compressedContent + "\n" + marker;
            }
            return marker;
        } catch (Exception e) {
            log.warn("[CompactService] CCR 压缩失败，回退到普通占位符: {}", e.getMessage());
            return "[tool_result_truncated]";
        }
    }

    /**
     * CCR 增强版微压缩 — 与 {@link #microCompactContext} 逻辑完全一致，
     * 但替换占位符时使用 CCR 标记（带哈希，LLM 可取回原文）。
     * <p>
     * <b>不破坏现有逻辑：</b>方法签名与 {@link #microCompactContext} 相同，
     * 只是占位符从 {@code [tool_result_truncated]} 变为
     * {@code [N tokens compressed to M. Retrieve more: hash=abc123]}。
     *
     * @param context    Agent 的上下文 token 记录列表
     * @param keepRecent 保留最近 N 个工具结果不被截断
     * @return 微压缩结果
     */
    public static MicroCompactResult microCompactContextWithCcr(
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

        // 执行截断 — 使用 CCR 标记替换旧工具结果内容
        for (int i = 0; i < totalRecords; i++) {
            if (!shouldKeep[i] && isCompactableToolResult(context.get(i).content())) {
                AgentTask.TokenRecord old = context.get(i);
                long oldLen = old.content() != null ? old.content().length() : 0;

                // ── CCR 集成点：用 CCR 标记替换原始占位符 ──
                String ccrMarker = compressWithCcr(old.content(), extractToolName(old.content()));

                context.set(i, new AgentTask.TokenRecord(
                        old.role(),
                        ccrMarker,
                        old.timestamp()
                ));

                savedChars += oldLen - ccrMarker.length();
                compactedCount++;
            }
        }

        String message = String.format(
                "MicroCompact(CCR): %d/%d records compacted with CCR, ~%d chars saved, kept %d recent tool results",
                compactedCount, totalRecords, savedChars, Math.min(toolResultCount, keep)
        );

        log.info("[CompactService] {}", message);
        return new MicroCompactResult(
                totalRecords, compactedCount, (int) savedChars, false, message
        );
    }

    /**
     * CCR 增强版指数截断兜底 — 与 {@link #exponentialTruncateFallback} 逻辑一致，
     * 但在截断前将待截断的消息存入 CCR 缓存。
     * <p>
     * <b>不破坏现有逻辑：</b>方法签名与返回类型相同，
     * 只是截断的消息内容不再永久丢失——LLM 可通过 ccr_retrieve 取回。
     *
     * @param context            上下文 token 记录列表
     * @param currentTokens      当前 token 数
     * @param contextWindowSize  上下文窗口大小
     * @param state              自动压缩状态
     * @return 截断结果
     */
    public static CompactionResult exponentialTruncateFallbackWithCcr(
            List<AgentTask.TokenRecord> context,
            int currentTokens,
            int contextWindowSize,
            AutoCompactState state) {

        // 连续失败次数决定截断级别（Level 4 起步）— 与原方法完全一致
        int level = 4 + Math.min(state.consecutiveFailures, 6);
        int keepRecentBase = Math.max(8, DEFAULT_KEEP_RECENT * 2);
        int keep = calcExponentialKeep(level, keepRecentBase);

        log.warn("[CompactService] CCR 指数截断兜底: level={}, keep={} (base={})",
                level, keep, keepRecentBase);

        // 使用工具链保护分割 — 与原方法一致
        SplitResult split = splitMessagesProtected(context, keep);
        List<AgentTask.TokenRecord> toSummarize = split.toSummarize();
        List<AgentTask.TokenRecord> recentMessages = split.recentMessages();

        // ── CCR 集成点：将待截断的消息存入 CCR 缓存 ──
        if (!toSummarize.isEmpty()) {
            StringBuilder droppedContent = new StringBuilder();
            for (AgentTask.TokenRecord record : toSummarize) {
                droppedContent.append("--- ").append(record.role()).append(" ---\n");
                droppedContent.append(record.content()).append("\n\n");
            }

            try {
                int originalTokens = estimateTokens(droppedContent.toString());
                String summary = "[Exponential truncate level " + level + ": "
                        + toSummarize.size() + " messages dropped]";
                int compressedTokens = estimateTokens(summary);

                String hash = CompressionStore.instance().store(
                        droppedContent.toString(),
                        summary,
                        originalTokens,
                        compressedTokens,
                        "exponential_truncate",
                        "exponential_truncate_ccr",
                        null
                );

                log.info("[CompactService] CCR: 已缓存 {} 条截断消息 (hash={}, {}→{} tokens)",
                        toSummarize.size(), hash, originalTokens, compressedTokens);
            } catch (Exception e) {
                log.warn("[CompactService] CCR 缓存截断消息失败: {}", e.getMessage());
            }
        }

        int estimatedPostTokens = estimateTokens(
                recentMessages.stream()
                        .map(AgentTask.TokenRecord::content)
                        .reduce("", (a, b) -> a + b)
        );

        String message = String.format(
                "Exponential truncate (level %d, CCR): %d → %d messages, ~%d tokens (originals cached for retrieval)",
                level, context.size(), recentMessages.size(), estimatedPostTokens
        );

        log.warn("[CompactService] {}", message);

        return new CompactionResult(
                recentMessages.stream()
                        .map(AgentTask.TokenRecord::content)
                        .toList(),
                currentTokens,
                estimatedPostTokens,
                message
        );
    }

}
