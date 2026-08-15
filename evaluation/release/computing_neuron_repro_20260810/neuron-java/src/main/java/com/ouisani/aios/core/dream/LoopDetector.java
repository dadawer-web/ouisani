package com.ouisani.aios.core.dream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 循环检测器 — 借鉴 Headroom learn/loops.py。
 * <p>
 * <b>核心价值：</b>循环是最高价值的失败模式，因为浪费随重复次数线性增长，
 * 而一次性错误只浪费一次。番茄钟死循环跑了 50 轮，每轮浪费 2000 token，
 * 总浪费 100000 token — 这比任何一次性错误的代价都大。
 * <p>
 * <b>两种循环形态：</b>
 * <ol>
 *   <li><b>错误循环</b> — 同一个命令失败 N 次。每次重复都是纯浪费。</li>
 *   <li><b>重取循环</b> — 命令成功但输出不足，Agent 重跑变体
 *       （{@code grep foo | head -50} → {@code grep foo | head -100}）。
 *       首次调用是合法的，后续 N-1 次是冗余重取。</li>
 * </ol>
 * <p>
 * <b>关键算法 — Canonical Signature：</b>
 * 剥离分页参数（{@code | head -N}, {@code --max-count=N}, {@code LIMIT N}）和
 * 裸整数后，将工具调用归约为稳定签名。
 * {@code grep foo | head -50} 和 {@code grep foo | head -100} 映射到同一签名。
 * <p>
 * OS 类比：相当于 Linux 内核的 hung task detector — 检测重复卡住的任务。
 */
public class LoopDetector {

    private static final Logger log = LoggerFactory.getLogger(LoopDetector.class);

    /** 最小重复次数 — 3 次才算循环（区分"重试一次"和"死循环"） */
    public static final int DEFAULT_MIN_OCCURRENCES = 3;

    /** 粗略字节/token 比 */
    private static final int BYTES_PER_TOKEN = 4;

    // ════════════════════════════════════════════════════════════════
    //  分页参数模式 — 借鉴 Headroom _PAGINATION_PATTERNS
    //  这些片段在重取变体间变化，但不改变实际执行的命令
    // ════════════════════════════════════════════════════════════════

    private static final Pattern PAGINATION_PATTERN = Pattern.compile(
            "\\|\\s*head\\s+-n?\\s*\\d+" +           // | head -50, | head -n 50
            "|\\|\\s*tail\\s+-n?\\s*\\d+" +          // | tail -50
            "|-n\\s*\\d+" +                           // -n 50
            "|--max-count[= ]\\d+" +                  // grep --max-count=50
            "|--lines[= ]\\d+" +                      // --lines=50
            "|\\bhead\\s+-\\d+" +                     // head -50
            "|\\b(limit|offset)[= ]\\d+" +            // limit=50 / offset=100
            "|\\bLIMIT\\s+\\d+" +                     // SQL LIMIT 50
            "|\\bOFFSET\\s+\\d+",                     // SQL OFFSET 100
            Pattern.CASE_INSENSITIVE
    );

    /** 裸整数归约 — 将行号/字节偏移等替换为 N */
    private static final Pattern INT_PATTERN = Pattern.compile("\\b\\d+\\b");

    /** 空白归约 */
    private static final Pattern WS_PATTERN = Pattern.compile("\\s+");

    // ════════════════════════════════════════════════════════════════
    //  数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 循环模式 — 检测到的重复工具调用模式。
     * <p>
     * 借鉴 Headroom LoopPattern。{@code wastedTokens} 是<b>实测</b>下界
     * （来自真实输出大小），不是 LLM 猜测。
     */
    public static class LoopPattern {
        public final String tool;
        public final String signature;        // canonical, variant-collapsed
        public final String sampleInput;      // 人类可读的循环调用示例
        public final int count;
        public final boolean isErrorLoop;
        public final int wastedTokens;
        public final List<Integer> msgIndices;

        public LoopPattern(String tool, String signature, String sampleInput,
                           int count, boolean isErrorLoop, int wastedTokens,
                           List<Integer> msgIndices) {
            this.tool = tool;
            this.signature = signature;
            this.sampleInput = sampleInput;
            this.count = count;
            this.isErrorLoop = isErrorLoop;
            this.wastedTokens = wastedTokens;
            this.msgIndices = msgIndices;
        }

        /** 循环类型 */
        public String kind() {
            return isErrorLoop ? "error-loop" : "rtk-refetch-loop";
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: \"%s\" repeated %dx, ~%d tokens wasted",
                    kind(), tool, sampleInput, count, wastedTokens);
        }
    }

    /**
     * 工具调用记录 — 从会话历史中提取的工具调用。
     */
    public record ToolCallRecord(
            String name,           // 工具名（bash, file_read, grep 等）
            String inputSummary,   // 输入摘要（命令或参数）
            String output,         // 输出内容
            boolean isError,       // 是否失败
            int msgIndex,          // 消息索引
            int outputBytes        // 输出字节数
    ) {
        public ToolCallRecord(String name, String inputSummary, String output,
                               boolean isError, int msgIndex) {
            this(name, inputSummary, output, isError, msgIndex,
                    output != null ? output.length() : 0);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  核心方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测循环 — 借鉴 Headroom detect_loops()。
     * <p>
     * 按签名在<b>每个会话内</b>分组（循环是会话内现象），
     * 达到 {@code minOccurrences} 的组成为 LoopPattern。
     *
     * @param toolCalls      工具调用列表
     * @param minOccurrences 最小重复次数（默认 3）
     * @return 检测到的循环列表，按浪费 token 降序排列
     */
    public static List<LoopPattern> detectLoops(List<ToolCallRecord> toolCalls,
                                                 int minOccurrences) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        // 按签名分组
        Map<String, List<ToolCallRecord>> groups = new LinkedHashMap<>();
        for (ToolCallRecord tc : toolCalls) {
            String sig = canonicalSignature(tc);
            groups.computeIfAbsent(sig, k -> new ArrayList<>()).add(tc);
        }

        List<LoopPattern> loops = new ArrayList<>();
        for (Map.Entry<String, List<ToolCallRecord>> entry : groups.entrySet()) {
            List<ToolCallRecord> calls = entry.getValue();
            if (calls.size() < minOccurrences) continue;

            int count = calls.size();
            boolean isErrorLoop = calls.stream().filter(c -> c.isError).count() >= (count / 2);

            int wasted;
            if (isErrorLoop) {
                // 错误循环：每次重复都是浪费（包括首次，因为知道就不会跑）
                wasted = calls.stream().mapToInt(LoopDetector::estimateTokens).sum();
            } else {
                // 重取循环：首次合法，后续 N-1 次是冗余
                List<Integer> tokens = calls.stream()
                        .map(LoopDetector::estimateTokens)
                        .sorted(Comparator.reverseOrder())
                        .toList();
                wasted = tokens.subList(1, tokens.size()).stream().mapToInt(Integer::intValue).sum();
            }

            List<Integer> msgIndices = calls.stream()
                    .map(ToolCallRecord::msgIndex)
                    .sorted()
                    .toList();

            ToolCallRecord first = calls.get(0);
            String sample = first.inputSummary != null
                    ? first.inputSummary.substring(0, Math.min(first.inputSummary.length(), 120))
                    : "";

            loops.add(new LoopPattern(
                    first.name, entry.getKey(), sample,
                    count, isErrorLoop, wasted, msgIndices
            ));
        }

        // 按浪费 token 降序排列
        loops.sort((a, b) -> Integer.compare(b.wastedTokens, a.wastedTokens));

        if (!loops.isEmpty()) {
            log.info("[LoopDetector] 检测到 {} 个循环，总浪费 ~{} tokens",
                    loops.size(), loops.stream().mapToInt(l -> l.wastedTokens).sum());
        }

        return loops;
    }

    /** 使用默认最小次数的便捷方法 */
    public static List<LoopPattern> detectLoops(List<ToolCallRecord> toolCalls) {
        return detectLoops(toolCalls, DEFAULT_MIN_OCCURRENCES);
    }

    /**
     * 将循环格式化为 LLM 摘要的高优先级段落 — 借鉴 Headroom format_loops_for_digest()。
     */
    public static String formatLoopsForDigest(List<LoopPattern> loops) {
        if (loops == null || loops.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Detected Loops (HIGHEST PRIORITY) ===\n");
        sb.append("These tool-call patterns REPEATED within a session — the most ");
        sb.append("expensive kind of waste, since cost scales with repetition. ");
        sb.append("A rule that prevents a loop is worth far more than one that ");
        sb.append("prevents a one-off error. Emit a guardrail for EACH loop below ");
        sb.append("and set its estimated_tokens_saved to at least the measured ");
        sb.append("wasted tokens shown.\n\n");

        for (LoopPattern lp : loops) {
            sb.append(String.format("- [%s] %s: \"%s\" repeated %dx, ~%d tokens wasted (messages %s)\n",
                    lp.kind(), lp.tool, lp.sampleInput, lp.count,
                    lp.wastedTokens, lp.msgIndices));
        }
        sb.append("\n");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  Canonical Signature — 核心算法
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算工具调用的 canonical signature — 借鉴 Headroom _canonical_signature()。
     * <p>
     * 对于 shell 命令：剥离分页参数 + 归约裸整数 + 规范化空白。
     * 对于其他工具：仅规范化空白。
     *
     * @param tc 工具调用
     * @return 稳定签名（如 "bash::grep foo"）
     */
    public static String canonicalSignature(ToolCallRecord tc) {
        if (tc == null || tc.inputSummary == null) {
            return "unknown::";
        }

        String raw = tc.inputSummary.strip();
        String name = tc.name != null ? tc.name.toLowerCase() : "unknown";

        // shell/bash 命令：剥离分页参数 + 归约整数
        if ("bash".equals(name) || "shell".equals(name)) {
            raw = PAGINATION_PATTERN.matcher(raw).replaceAll(" ");
            raw = INT_PATTERN.matcher(raw).replaceAll("N");
        }

        // 规范化空白并转小写
        raw = WS_PATTERN.matcher(raw).replaceAll(" ").strip().toLowerCase();
        return name + "::" + raw;
    }

    /**
     * 从签名中提取词元集合 — 用于模糊规则匹配。
     * 借鉴 Headroom _signature_tokens()。
     */
    public static Set<String> signatureTokens(String signature) {
        if (signature == null || signature.isEmpty()) {
            return Set.of();
        }
        // 去掉 "toolname::" 前缀
        String body = signature.contains("::")
                ? signature.substring(signature.indexOf("::") + 2)
                : signature;
        Set<String> tokens = new HashSet<>();
        for (String t : body.split("[^a-z0-9]+")) {
            if (t.length() > 2) tokens.add(t);
        }
        return tokens;
    }

    // ════════════════════════════════════════════════════════════════
    //  循环权重提升 — 借鉴 Headroom apply_loop_weighting()
    // ════════════════════════════════════════════════════════════════

    /**
     * 循环权重提升 — 借鉴 Headroom apply_loop_weighting()。
     * <p>
     * 对于与检测到的循环签名重叠的推荐规则，将其 estimatedTokensSaved
     * 提升到至少该循环的实测浪费量，并标记为循环护栏。
     *
     * @param recommendations 推荐规则列表（会被原地修改）
     * @param loops           检测到的循环
     */
    public static void applyLoopWeighting(List<LearnRecommendation> recommendations,
                                           List<LoopPattern> loops) {
        if (loops == null || loops.isEmpty() || recommendations == null) {
            return;
        }

        for (LearnRecommendation rec : recommendations) {
            String haystack = (rec.section() + " " + rec.content()).toLowerCase();
            LoopPattern best = null;

            for (LoopPattern lp : loops) {
                Set<String> sigTokens = signatureTokens(lp.signature);
                if (sigTokens.isEmpty()) continue;

                // 计算重叠：签名中的显著词元有多少出现在规则文本中
                int overlap = 0;
                for (String t : sigTokens) {
                    if (haystack.contains(t)) overlap++;
                }

                // 要求多数签名词元出现，避免过度归因到通用规则
                int threshold = Math.max(1, (sigTokens.size() + 1) / 2);
                if (overlap >= threshold) {
                    if (best == null || lp.wastedTokens > best.wastedTokens) {
                        best = lp;
                    }
                }
            }

            if (best != null) {
                // 提升到至少循环的实测浪费量
                if (rec.estimatedTokensSaved() < best.wastedTokens) {
                    rec.setEstimatedTokensSaved(best.wastedTokens);
                }
                rec.setLoopGuardrail(true);
                rec.setLoopOccurrences(best.count);
            }
        }
    }

    // ── 辅助方法 ──

    private static int estimateTokens(ToolCallRecord tc) {
        int bytes = tc.outputBytes > 0 ? tc.outputBytes
                : (tc.output != null ? tc.output.length() : 0);
        return bytes / BYTES_PER_TOKEN;
    }
}
