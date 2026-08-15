package com.ouisani.aios.core.dream;

import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话分析器 — 借鉴 Headroom learn/analyzer.py。
 * <p>
 * 从失败会话中提取修正规则，流程：
 * <pre>
 * 会话历史 → 工具调用提取 → 循环检测 → 构建 Digest → LLM 分析 → 推荐规则 → 循环权重提升
 * </pre>
 * <p>
 * 核心价值：番茄钟死循环跑了 50 轮，系统没有自动生成"下次别这么干"的规则。
 * SessionAnalyzer 自动从失败中学习，生成持久化护栏规则。
 * <p>
 * OS 类比：相当于 Linux 内核的 panic 日志分析器 — 从崩溃中提取可复用的修复规则。
 */
public class SessionAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SessionAnalyzer.class);

    /** Digest 最大 token 数 — 借鉴 Headroom _MAX_DIGEST_TOKENS */
    private static final int MAX_DIGEST_TOKENS = 80_000;

    /** 粗略字符/token 比 */
    private static final int CHARS_PER_TOKEN = 4;

    /** 错误检测模式 */
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "\\b(error|failed|failure|exception|traceback|fatal|crash)\\b",
            Pattern.CASE_INSENSITIVE);

    /** tool_call 格式：提取工具名和输入 */
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "<tool_call[^>]*name=\"([^\"]+)\"[^>]*>(.*?)<",
            Pattern.DOTALL);

    /** tool_result 格式：提取工具名和输出 */
    private static final Pattern TOOL_RESULT_PATTERN = Pattern.compile(
            "<tool_result[^>]*name=\"([^\"]+)\"[^>]*>(.*?)<",
            Pattern.DOTALL);

    // ════════════════════════════════════════════════════════════════
    //  主分析方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 分析会话历史，提取修正规则 — 借鉴 Headroom SessionAnalyzer.analyze()。
     * <p>
     * 流程：
     * <ol>
     *   <li>从 contextHistory 提取工具调用</li>
     *   <li>检测循环</li>
     *   <li>构建 LLM Digest（循环优先 + 失败调用摘要）</li>
     *   <li>调用 LLM 生成推荐规则</li>
     *   <li>循环权重提升</li>
     *   <li>按 estimatedTokensSaved 降序排列</li>
     * </ol>
     *
     * @param contextHistory  会话上下文历史（AgentTask.contextHistory()）
     * @param sdk             AIOS SDK（用于调用 LLM）
     * @param agentId         Agent ID
     * @return 推荐规则列表（可能为空）
     */
    public static List<LearnRecommendation> analyze(List<String> contextHistory,
                                                     AiosSdk sdk, String agentId) {
        if (contextHistory == null || contextHistory.isEmpty()) {
            log.info("[SessionAnalyzer] 无会话历史，跳过分析");
            return List.of();
        }

        // 1. 提取工具调用
        List<LoopDetector.ToolCallRecord> toolCalls = extractToolCalls(contextHistory);
        log.info("[SessionAnalyzer] 提取到 {} 个工具调用", toolCalls.size());

        // 2. 检测循环
        List<LoopDetector.LoopPattern> loops = LoopDetector.detectLoops(toolCalls);
        if (!loops.isEmpty()) {
            log.info("[SessionAnalyzer] 检测到 {} 个循环", loops.size());
        }

        // 3. 提取失败调用
        List<LoopDetector.ToolCallRecord> failedCalls = new ArrayList<>();
        for (LoopDetector.ToolCallRecord tc : toolCalls) {
            if (tc.isError()) {
                failedCalls.add(tc);
            }
        }

        // 4. 如果没有失败也没有循环，不需要分析
        if (failedCalls.isEmpty() && loops.isEmpty()) {
            log.info("[SessionAnalyzer] 无失败调用也无循环，跳过分析");
            return List.of();
        }

        // 5. 构建 Digest
        String digest = buildDigest(contextHistory, loops, failedCalls);
        log.info("[SessionAnalyzer] Digest 大小: {} chars (~{} tokens)",
                digest.length(), digest.length() / CHARS_PER_TOKEN);

        // 6. 调用 LLM 分析
        List<LearnRecommendation> recommendations;
        try {
            String llmResponse = callLlm(sdk, agentId, digest);
            recommendations = parseLlmResponse(llmResponse);
        } catch (Exception e) {
            log.warn("[SessionAnalyzer] LLM 分析失败: {}", e.getMessage());
            // 降级：从循环直接生成规则
            recommendations = generateRulesFromLoops(loops);
        }

        // 7. 循环权重提升
        LoopDetector.applyLoopWeighting(recommendations, loops);

        // 8. 按 estimatedTokensSaved 降序排列
        recommendations.sort((a, b) -> Integer.compare(b.estimatedTokensSaved(), a.estimatedTokensSaved()));

        log.info("[SessionAnalyzer] 生成 {} 条推荐规则 (循环护栏: {})",
                recommendations.size(),
                recommendations.stream().filter(LearnRecommendation::isLoopGuardrail).count());

        return recommendations;
    }

    // ════════════════════════════════════════════════════════════════
    //  工具调用提取
    // ════════════════════════════════════════════════════════════════

    /**
     * 从会话历史中提取工具调用记录。
     */
    public static List<LoopDetector.ToolCallRecord> extractToolCalls(List<String> contextHistory) {
        List<LoopDetector.ToolCallRecord> calls = new ArrayList<>();
        int msgIndex = 0;

        for (String entry : contextHistory) {
            if (entry == null || entry.isBlank()) {
                msgIndex++;
                continue;
            }

            // 提取 tool_call（输入）
            Matcher callMatcher = TOOL_CALL_PATTERN.matcher(entry);
            while (callMatcher.find()) {
                String toolName = callMatcher.group(1);
                String input = callMatcher.group(2).strip();
                calls.add(new LoopDetector.ToolCallRecord(toolName, input, "", false, msgIndex));
            }

            // 提取 tool_result（输出）
            Matcher resultMatcher = TOOL_RESULT_PATTERN.matcher(entry);
            while (resultMatcher.find()) {
                String toolName = resultMatcher.group(1);
                String output = resultMatcher.group(2).strip();
                boolean isError = ERROR_PATTERN.matcher(output).find();

                // 尝试与最近的同名工具调用配对
                for (int i = calls.size() - 1; i >= 0; i--) {
                    LoopDetector.ToolCallRecord tc = calls.get(i);
                    if (tc.name().equals(toolName) && tc.output().isEmpty()) {
                        calls.set(i, new LoopDetector.ToolCallRecord(
                                toolName, tc.inputSummary(), output, isError, tc.msgIndex(),
                                output.length()
                        ));
                        break;
                    }
                }
            }

            msgIndex++;
        }

        return calls;
    }

    // ════════════════════════════════════════════════════════════════
    //  Digest 构建 — 借鉴 Headroom _build_digest()
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建 LLM 分析用的 Digest。
     * <p>
     * 结构：
     * <pre>
     * === Detected Loops (HIGHEST PRIORITY) ===
     *   [error-loop] bash: "grep foo" repeated 5x, ~5000 tokens wasted
     *
     * === Failed Tool Calls ===
     *   [ERROR] bash: "rm /tmp/nonexistent" → "No such file or directory"
     *
     * === Session Excerpt ===
     *   (截断的会话历史)
     * </pre>
     */
    private static String buildDigest(List<String> contextHistory,
                                       List<LoopDetector.LoopPattern> loops,
                                       List<LoopDetector.ToolCallRecord> failedCalls) {

        StringBuilder sb = new StringBuilder();

        // 循环段（最高优先级）
        String loopSection = LoopDetector.formatLoopsForDigest(loops);
        if (!loopSection.isEmpty()) {
            sb.append(loopSection);
        }

        // 失败调用段
        if (!failedCalls.isEmpty()) {
            sb.append("=== Failed Tool Calls ===\n");
            int limit = Math.min(failedCalls.size(), 20);
            for (int i = 0; i < limit; i++) {
                LoopDetector.ToolCallRecord tc = failedCalls.get(i);
                String output = tc.output();
                if (output != null && output.length() > 200) {
                    output = output.substring(0, 200) + "...";
                }
                sb.append(String.format("- [ERROR] %s: \"%s\" → %s\n",
                        tc.name(),
                        tc.inputSummary().substring(0, Math.min(tc.inputSummary().length(), 80)),
                        output));
            }
            sb.append("\n");
        }

        // 会话摘录段
        sb.append("=== Session Excerpt ===\n");
        int budget = MAX_DIGEST_TOKENS * CHARS_PER_TOKEN - sb.length();
        if (budget > 0) {
            StringBuilder excerpt = new StringBuilder();
            for (String entry : contextHistory) {
                if (excerpt.length() + entry.length() > budget) {
                    int remaining = budget - excerpt.length();
                    if (remaining > 100) {
                        excerpt.append(entry, 0, remaining).append("... [truncated]");
                    }
                    break;
                }
                excerpt.append(entry).append("\n---\n");
            }
            sb.append(excerpt);
        }

        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  LLM 调用 — 借鉴 Headroom _call_llm()
    // ════════════════════════════════════════════════════════════════

    /**
     * 调用 LLM 分析 Digest，返回推荐规则。
     */
    private static String callLlm(AiosSdk sdk, String agentId, String digest) {
        String prompt = """
                You are analyzing a failed AI agent session to extract reusable guardrail rules.

                Below is a digest of the session. Analyze the failed tool calls and detected loops,
                then produce actionable rules that would prevent similar failures in future sessions.

                For EACH rule, output in this format:
                ---
                SECTION: <rule title>
                CONTENT: <what the agent should do differently>
                TOKENS_SAVED: <estimated tokens saved per session>
                ---

                Focus on:
                1. Loops — rules that prevent repeating the same failing command
                2. Common errors — rules that prevent one-off failures
                3. Better alternatives — suggest the correct approach

                Session Digest:
                """ + digest;

        return sdk.think(agentId, prompt);
    }

    /**
     * 解析 LLM 响应为推荐规则列表。
     */
    private static List<LearnRecommendation> parseLlmResponse(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        List<LearnRecommendation> recs = new ArrayList<>();
        String[] blocks = response.split("---");
        for (String block : blocks) {
            String section = extractField(block, "SECTION:");
            String content = extractField(block, "CONTENT:");
            String tokensStr = extractField(block, "TOKENS_SAVED:");
            int tokensSaved = 0;
            try {
                if (tokensStr != null) {
                    tokensSaved = Integer.parseInt(tokensStr.replaceAll("[^0-9]", ""));
                }
            } catch (NumberFormatException ignored) {}

            if (section != null && content != null) {
                recs.add(new LearnRecommendation(
                        LearnRecommendation.Target.CONTEXT_FILE,
                        section.strip(), content.strip(),
                        0.7, 1, tokensSaved
                ));
            }
        }
        return recs;
    }

    /** 从文本块中提取字段值 */
    private static String extractField(String block, String fieldName) {
        int idx = block.indexOf(fieldName);
        if (idx < 0) return null;
        int start = idx + fieldName.length();
        int end = block.length();
        for (String nextField : new String[]{"SECTION:", "CONTENT:", "TOKENS_SAVED:"}) {
            int nextIdx = block.indexOf(nextField, start);
            if (nextIdx >= 0 && nextIdx < end) {
                end = nextIdx;
            }
        }
        return block.substring(start, end).strip();
    }

    /**
     * 降级：从循环直接生成规则（LLM 不可用时）。
     */
    private static List<LearnRecommendation> generateRulesFromLoops(List<LoopDetector.LoopPattern> loops) {
        List<LearnRecommendation> recs = new ArrayList<>();
        for (LoopDetector.LoopPattern lp : loops) {
            String section = "Loop Guardrail: " + lp.tool + " - " + lp.kind();
            String content;
            if (lp.isErrorLoop) {
                content = String.format(
                        "The command \"%s\" failed %d times in a row. " +
                        "Do NOT retry this command more than once. " +
                        "If it fails twice, try a fundamentally different approach " +
                        "(check the file exists, verify permissions, read documentation).",
                        lp.sampleInput, lp.count);
            } else {
                content = String.format(
                        "The command \"%s\" was re-run %d times with different pagination params. " +
                        "When a command's output is truncated, do NOT re-run with larger limits. " +
                        "Instead, pipe to grep to filter, or read the specific section you need.",
                        lp.sampleInput, lp.count);
            }
            recs.add(new LearnRecommendation(
                    LearnRecommendation.Target.CONTEXT_FILE,
                    section, content, 0.9, lp.count, lp.wastedTokens
            ));
        }
        return recs;
    }
}
