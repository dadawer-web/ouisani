package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具幻觉检查器 — 闭环能力枚举与反幻觉。
 * <p>
 * 借鉴 PAI (Personal AI Infrastructure) 的闭环枚举设计：
 * LLM 如果调用了不存在的工具，直接被视为 CRITICAL FAILURE。
 * <p>
 * 在 InstructionDecoder 解码 LLM 输出后、进入 DAG 引擎前，
 * 对解码结果中的工具名进行严格的字典校验 (Dictionary Check)。
 * <p>
 * 防护策略：
 * <ul>
 *   <li><b>精准匹配</b> — 工具名必须在 ToolRegistry 注册列表中</li>
 *   <li><b>Levenshtein 距离</b> — 防微小拼写错误，距离 ≤ 2 时给出建议</li>
 *   <li><b>Fail-Fast</b> — 发现不存在的工具立刻拦截，抛出 InstructionDecodeException</li>
 * </ul>
 *
 * <h3>OS 类比: CPU Illegal Opcode Exception</h3>
 * 类似 CPU 遇到无效指令码时的异常 — 指令解码器发现不存在的操作码，
 * 立即触发异常，不会尝试执行。防止 LLM 幻觉导致引擎空转。
 *
 * @see InstructionDecoder
 * @see com.ouisani.aios.core.llm.InstructionDecodeException
 */
public final class ToolHallucinationChecker {

    private static final Logger log = LoggerFactory.getLogger(ToolHallucinationChecker.class);

    /** Levenshtein 距离阈值 — 超过此距离认为不是拼写错误 */
    private static final int MAX_LEVENSHTEIN_DISTANCE = 2;

    /**
     * 从 LLM 输出中提取工具名的正则模式。
     * 匹配常见 JSON 格式中的 tool/name 字段。
     */
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile(
            "(?:\"tool\"\\s*:\\s*\"([^\"]+)\""
                    + "|\"name\"\\s*:\\s*\"([^\"]+)\""
                    + "|\"tool_name\"\\s*:\\s*\"([^\"]+)\""
                    + "|\"action\"\\s*:\\s*\"([^\"]+)\""
                    + "|\"function\"\\s*:\\s*\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\""
                    + ")",
            Pattern.CASE_INSENSITIVE
    );

    private ToolHallucinationChecker() {}

    /**
     * 扫描 LLM 原始输出，提取所有工具名引用并校验。
     * <p>
     * 如果发现任何不在 ToolRegistry 中的工具名，返回包含详细诊断信息的
     * HallucinationResult，调用方可据此抛出 InstructionDecodeException。
     *
     * @param llmOutput LLM 的原始文本输出
     * @return 校验结果
     */
    public static HallucinationResult scanForToolNames(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return HallucinationResult.ok();
        }

        // 获取已注册的工具名集合
        Set<String> registeredNames = getRegisteredToolNames();
        if (registeredNames.isEmpty()) {
            // ToolRegistry 尚未初始化，跳过检查
            return HallucinationResult.ok();
        }

        // 提取 LLM 输出中引用的工具名
        Set<String> referencedNames = extractToolNames(llmOutput);
        if (referencedNames.isEmpty()) {
            return HallucinationResult.ok();
        }

        // 逐个校验
        Set<String> hallucinated = new HashSet<>();
        Set<String> suggestions = new HashSet<>();

        for (String name : referencedNames) {
            if (!registeredNames.contains(name)) {
                hallucinated.add(name);
                // 尝试找到最接近的已注册工具名
                String suggestion = findClosestMatch(name, registeredNames);
                if (suggestion != null) {
                    suggestions.add(suggestion);
                }
            }
        }

        if (hallucinated.isEmpty()) {
            return HallucinationResult.ok();
        }

        return new HallucinationResult(false, hallucinated, suggestions, registeredNames);
    }

    /**
     * 校验单个工具名是否已注册。
     *
     * @param toolName 待校验的工具名
     * @return true 如果工具已注册
     */
    public static boolean isToolRegistered(String toolName) {
        if (toolName == null || toolName.isBlank()) return false;
        return getRegisteredToolNames().contains(toolName);
    }

    /**
     * 获取所有已注册的工具名集合。
     */
    private static Set<String> getRegisteredToolNames() {
        Set<String> names = new HashSet<>();
        Collection<Tool<? extends ToolInput>> tools = ToolRegistry.instance().all();
        for (Tool<?> tool : tools) {
            names.add(tool.name());
        }
        return names;
    }

    /**
     * 从 LLM 输出中提取工具名引用。
     */
    private static Set<String> extractToolNames(String llmOutput) {
        Set<String> names = new HashSet<>();
        Matcher matcher = TOOL_NAME_PATTERN.matcher(llmOutput);
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String name = matcher.group(i);
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        return names;
    }

    /**
     * 使用 Levenshtein 距离找到最接近的已注册工具名。
     *
     * @param hallucinated 幻觉工具名
     * @param registered   已注册工具名集合
     * @return 最接近的匹配，无匹配返回 null
     */
    private static String findClosestMatch(String hallucinated, Set<String> registered) {
        String bestMatch = null;
        int minDistance = Integer.MAX_VALUE;

        for (String name : registered) {
            int dist = levenshteinDistance(hallucinated.toLowerCase(), name.toLowerCase());
            if (dist < minDistance) {
                minDistance = dist;
                bestMatch = name;
            }
        }

        // 只有距离在阈值内才返回建议
        if (minDistance <= MAX_LEVENSHTEIN_DISTANCE) {
            return bestMatch;
        }
        return null;
    }

    /**
     * 计算两个字符串的 Levenshtein 编辑距离。
     * <p>
     * 标准动态规划实现，O(m*n) 时间复杂度。
     */
    private static int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();

        if (m == 0) return n;
        if (n == 0) return m;

        int[][] dp = new int[m + 1][n + 1];

        for (int i = 0; i <= m; i++) dp[i][0] = i;
        for (int j = 0; j <= n; j++) dp[0][j] = j;

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[m][n];
    }

    /**
     * 幻觉检查结果。
     *
     * @param valid           是否通过校验（true=无幻觉）
     * @param hallucinatedTools  幻觉工具名集合
     * @param suggestions     Levenshtein 建议的修正工具名集合
     * @param availableTools   当前可用的工具名集合
     */
    public record HallucinationResult(
            boolean valid,
            Set<String> hallucinatedTools,
            Set<String> suggestions,
            Set<String> availableTools
    ) {
        /** 通过校验的便捷工厂 */
        public static HallucinationResult ok() {
            return new HallucinationResult(true, Set.of(), Set.of(), Set.of());
        }

        /**
         * 生成诊断消息 — 供 RecoveryOrchestrator 注入反思提示。
         */
        public String diagnosticMessage() {
            if (valid) return "OK";

            StringBuilder sb = new StringBuilder();
            sb.append("Tool Hallucination Detected!\n");
            sb.append("Hallucinated tools: ").append(hallucinatedTools).append("\n");

            if (!suggestions.isEmpty()) {
                sb.append("Did you mean: ").append(suggestions).append("?\n");
            }

            sb.append("Available tools: ").append(availableTools).append("\n");
            sb.append("Please use only registered tools from the list above.");
            return sb.toString();
        }
    }
}
