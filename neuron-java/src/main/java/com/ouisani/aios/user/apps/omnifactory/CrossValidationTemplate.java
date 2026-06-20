package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.llm.LlmRouterHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多模型对抗与交叉审查宏拓扑 — 借鉴 OmniGent 的 Debby & Polly 模式。
 * <p>
 * 机制：
 * 1. 节点 A 使用 Provider-A（如 GPT-4）生成方案
 * 2. 节点 B 使用 Provider-B（如 Claude-3.5）生成方案
 * 3. 裁判节点 C 收集 A 和 B 的输出，对比找出漏洞
 * 4. 如果未达共识，触发循环路由（迭代节点），直到输出收敛
 * <p>
 * OmniGent 的 Debby 让多个模型同时辩论；Polly 让不同厂商的模型交叉 Review。
 * AIOS 借鉴此模式，利用 LlmRouter 的多 Provider 能力实现跨供应商审查。
 * <p>
 * 使用方式：
 * <pre>
 *   CrossValidationTemplate.Result result = CrossValidationTemplate.execute(
 *       "请为这个函数编写单元测试",
 *       "smart_model",     // Provider A
 *       "fast_model",      // Provider B
 *       3                  // 最大收敛轮次
 *   );
 * </pre>
 */
public class CrossValidationTemplate {

    private static final Logger log = LoggerFactory.getLogger(CrossValidationTemplate.class);

    /** 默认最大收敛轮次 */
    private static final int DEFAULT_MAX_ROUNDS = 3;

    /** 收敛阈值：两个方案相似度超过此值则认为收敛 */
    private static final double CONVERGENCE_THRESHOLD = 0.8;

    /**
     * 执行多模型对抗与交叉审查。
     *
     * @param task         任务描述
     * @param providerA    Provider A 名称（如 "gpt-4"）
     * @param providerB    Provider B 名称（如 "claude-3.5"）
     * @param maxRounds    最大收敛轮次
     * @return 审查结果
     */
    public static Result execute(String task, String providerA, String providerB, int maxRounds) {
        log.info("[CrossValidation] 启动多模型对抗: task='{}', A={}, B={}, maxRounds={}",
                task.substring(0, Math.min(task.length(), 80)), providerA, providerB, maxRounds);

        LlmRouter router = LlmRouterHolder.get();
        if (router == null) {
            log.warn("[CrossValidation] LlmRouter 未初始化，无法执行多模型对抗，降级返回空结果");
            return new Result("", "", "", false, 0, providerA, providerB);
        }

        LlmProvider llmA = router.getProvider(providerA);
        LlmProvider llmB = router.getProvider(providerB);

        if (llmA == null || llmB == null) {
            log.warn("[CrossValidation] Provider 不可用: A={} (found={}), B={} (found={})",
                    providerA, llmA != null, providerB, llmB != null);
            // 降级：用 LlmRouter 默认路由
            return executeWithFallback(task, router, maxRounds);
        }

        String solutionA = "";
        String solutionB = "";
        String lastFeedback = "";
        int round = 0;
        boolean converged = false;

        while (round < maxRounds && !converged) {
            round++;
            log.info("[CrossValidation] 第 {}/{} 轮对抗", round, maxRounds);

            // ── 节点 A：Provider-A 生成/修订方案 ──
            String promptA = buildGenerationPrompt(task, solutionB, lastFeedback, round);
            solutionA = llmA.think(promptA, buildSystemPrompt("Generator-A", providerA));
            log.info("[CrossValidation] Provider-A 第 {} 轮方案: {} chars", round, solutionA.length());

            // ── 节点 B：Provider-B 生成/修订方案 ──
            String promptB = buildGenerationPrompt(task, solutionA, lastFeedback, round);
            solutionB = llmB.think(promptB, buildSystemPrompt("Generator-B", providerB));
            log.info("[CrossValidation] Provider-B 第 {} 轮方案: {} chars", round, solutionB.length());

            // ── 裁判节点 C：交叉审查 ──
            String reviewPrompt = buildReviewPrompt(task, solutionA, solutionB, providerA, providerB);
            String reviewResult = router.think(reviewPrompt, buildSystemPrompt("Cross-Reviewer", "router"));
            log.info("[CrossValidation] 裁判审查完成: {} chars", reviewResult.length());

            // 解析审查结果
            ReviewOutcome outcome = parseReviewOutcome(reviewResult);

            if (outcome.converged) {
                converged = true;
                log.info("[CrossValidation] 第 {} 轮收敛！共识度: {}", round, outcome.consensusScore);
            } else {
                lastFeedback = outcome.feedback;
                log.info("[CrossValidation] 第 {} 轮未收敛，反馈: {}", round,
                        outcome.feedback.substring(0, Math.min(outcome.feedback.length(), 100)));
            }
        }

        // 选择最终方案
        String finalSolution = selectBestSolution(solutionA, solutionB, task, router);

        Result result = new Result(
                finalSolution,
                solutionA,
                solutionB,
                converged,
                round,
                providerA,
                providerB
        );

        log.info("[CrossValidation] 完成: converged={}, rounds={}, finalSolution={} chars",
                converged, round, finalSolution.length());

        return result;
    }

    /** 使用默认路由降级执行（Provider 不可用时） */
    private static Result executeWithFallback(String task, LlmRouter router, int maxRounds) {
        log.warn("[CrossValidation] 降级到单模型模式");
        String solution = router.think(task, "You are a helpful assistant.");
        return new Result(solution, solution, solution, true, 1, "fallback", "fallback");
    }

    // ════════════════════════════════════════════════════════════════
    //  Prompt 构建
    // ════════════════════════════════════════════════════════════════

    private static String buildSystemPrompt(String role, String providerName) {
        return String.format("""
                You are %s, powered by %s. You are participating in a cross-validation process.
                Your goal is to produce the best possible solution, considering alternative perspectives.
                Be concise, rigorous, and open to feedback.""", role, providerName);
    }

    /**
     * 构建生成/修订 Prompt。
     * 第 1 轮：纯生成。后续轮次：参考对方方案 + 裁判反馈进行修订。
     */
    private static String buildGenerationPrompt(String task, String otherSolution, String feedback, int round) {
        if (round == 1) {
            return "## Task\n" + task + "\n\nPlease provide your best solution.";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("## Task\n").append(task).append("\n\n");
        prompt.append("## Other Model's Solution (for reference)\n");
        prompt.append(otherSolution != null ? otherSolution : "(none)").append("\n\n");
        prompt.append("## Reviewer Feedback\n");
        prompt.append(feedback != null && !feedback.isBlank() ? feedback : "(none)").append("\n\n");
        prompt.append("## Your Task\n");
        prompt.append("Based on the feedback and the other model's solution, revise your solution. ");
        prompt.append("Address any issues identified by the reviewer. ");
        prompt.append("If you agree with the other model's approach, incorporate it. ");
        prompt.append("If you disagree, explain why and provide a better alternative.");

        return prompt.toString();
    }

    /**
     * 构建裁判审查 Prompt。
     */
    private static String buildReviewPrompt(String task, String solutionA, String solutionB,
                                            String providerA, String providerB) {
        return String.format("""
                ## Task
                %s

                ## Solution A (by %s)
                %s

                ## Solution B (by %s)
                %s

                ## Your Role
                You are a cross-model reviewer. Compare the two solutions above.
                Evaluate them on: correctness, completeness, efficiency, and edge case handling.

                ## Output Format (JSON)
                ```json
                {
                    "converged": true/false,
                    "consensus_score": 0.0-1.0,
                    "feedback": "specific feedback for improvement",
                    "winner": "A" or "B" or "tie",
                    "reasoning": "why this solution is better"
                }
                ```

                Set "converged" to true if the two solutions are essentially equivalent in quality
                and approach (consensus_score >= 0.8). Otherwise set to false and provide
                specific feedback for both models to improve.""",
                task, providerA, solutionA, providerB, solutionB);
    }

    // ════════════════════════════════════════════════════════════════
    //  结果解析
    // ════════════════════════════════════════════════════════════════

    /** 审查结果 */
    private static class ReviewOutcome {
        boolean converged;
        double consensusScore;
        String feedback;
        String winner;

        ReviewOutcome(boolean converged, double consensusScore, String feedback, String winner) {
            this.converged = converged;
            this.consensusScore = consensusScore;
            this.feedback = feedback;
            this.winner = winner;
        }
    }

    private static ReviewOutcome parseReviewOutcome(String reviewResult) {
        try {
            // 提取 JSON
            String json = extractJson(reviewResult);
            if (json != null) {
                com.google.gson.Gson gson = new com.google.gson.Gson();
                var obj = gson.fromJson(json, com.google.gson.JsonObject.class);
                boolean converged = obj.has("converged") && obj.get("converged").getAsBoolean();
                double score = obj.has("consensus_score") ? obj.get("consensus_score").getAsDouble() : 0.5;
                String feedback = obj.has("feedback") ? obj.get("feedback").getAsString() : "";
                String winner = obj.has("winner") ? obj.get("winner").getAsString() : "tie";
                return new ReviewOutcome(converged, score, feedback, winner);
            }
        } catch (Exception e) {
            log.warn("[CrossValidation] 审查结果解析失败: {}", e.getMessage());
        }
        // 默认：未收敛
        return new ReviewOutcome(false, 0.5, reviewResult, "tie");
    }

    private static String extractJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    /**
     * 选择最终方案。
     */
    private static String selectBestSolution(String solutionA, String solutionB, String task, LlmRouter router) {
        // 如果两个方案相同或一个为空，直接返回非空的那个
        if (solutionA == null || solutionA.isBlank()) return solutionB;
        if (solutionB == null || solutionB.isBlank()) return solutionA;
        if (solutionA.equals(solutionB)) return solutionA;

        // 让 LLM 做最终裁决
        String judgePrompt = String.format("""
                ## Task
                %s

                ## Solution A
                %s

                ## Solution B
                %s

                Select the better solution. Output ONLY the selected solution, no explanation.""",
                task, solutionA, solutionB);

        String selected = router.think(judgePrompt, "You are a final judge. Select the best solution.");
        // 如果裁决失败，默认返回 A
        return (selected != null && !selected.isBlank()) ? selected : solutionA;
    }

    // ════════════════════════════════════════════════════════════════
    //  结果类型
    // ════════════════════════════════════════════════════════════════

    /**
     * 交叉审查结果。
     *
     * @param finalSolution 最终选定的方案
     * @param solutionA     Provider-A 的最终方案
     * @param solutionB     Provider-B 的最终方案
     * @param converged     是否收敛
     * @param rounds        实际执行的轮次
     * @param providerA     Provider-A 名称
     * @param providerB     Provider-B 名称
     */
    public record Result(
            String finalSolution,
            String solutionA,
            String solutionB,
            boolean converged,
            int rounds,
            String providerA,
            String providerB
    ) {}

    // ════════════════════════════════════════════════════════════════
    //  DAG 拓扑构建器 — 将 Cross-Validation 转为 DAG 节点
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建 Cross-Validation 的 DAG 节点拓扑。
     * <p>
     * 拓扑结构：
     * <pre>
     *   generator_a (Provider-A) ──┐
     *                              ├──→ reviewer (裁判)
     *   generator_b (Provider-B) ──┘
     * </pre>
     *
     * @param task      任务描述
     * @param providerA Provider-A 名称
     * @param providerB Provider-B 名称
     * @return DAG 节点列表
     */
    public static List<WorkflowNode> buildDagTopology(String task, String providerA, String providerB) {
        List<WorkflowNode> nodes = new ArrayList<>();

        // 节点 A：Provider-A 生成方案
        WorkflowNode nodeA = new WorkflowNode("cv_generator_a", "Generator-A (" + providerA + ")", "omni");
        nodeA.userParams().put("task", task);
        nodeA.userParams().put("provider", providerA);
        nodeA.userParams().put("role", "Generate solution using " + providerA);
        nodes.add(nodeA);

        // 节点 B：Provider-B 生成方案
        WorkflowNode nodeB = new WorkflowNode("cv_generator_b", "Generator-B (" + providerB + ")", "omni");
        nodeB.userParams().put("task", task);
        nodeB.userParams().put("provider", providerB);
        nodeB.userParams().put("role", "Generate solution using " + providerB);
        nodes.add(nodeB);

        // 裁判节点 C：交叉审查
        WorkflowNode reviewer = new WorkflowNode("cv_reviewer", "Cross-Reviewer", "omni");
        reviewer.addDependency("cv_generator_a");
        reviewer.addDependency("cv_generator_b");
        reviewer.userParams().put("task", task);
        reviewer.userParams().put("providerA", providerA);
        reviewer.userParams().put("providerB", providerB);
        reviewer.userParams().put("solutionA", "{{cv_generator_a.result}}");
        reviewer.userParams().put("solutionB", "{{cv_generator_b.result}}");
        reviewer.userParams().put("role", "Cross-review solutions from " + providerA + " and " + providerB);
        nodes.add(reviewer);

        return nodes;
    }
}
