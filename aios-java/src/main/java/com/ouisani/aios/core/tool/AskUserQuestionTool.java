package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 用户提问工具 — 对标 Claude Code 的 AskUserQuestionTool。
 * <p>
 * 让模型在执行中澄清需求、获取偏好：
 * - 1-4 个问题
 * - 每个问题 2-4 个选项
 * - 支持多选
 * <p>
 * OS 类比：相当于 Linux 的 /proc/sys 交互式参数调整。
 */
public class AskUserQuestionTool implements Tool<AskUserQuestionTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);

    /** 问题选项 */
    public record QuestionOption(
            String label,
            String description
    ) {}

    /** 问题 */
    public record Question(
            String question,
            String header,
            List<QuestionOption> options,
            boolean multiSelect
    ) {}

    public record Input(List<Question> questions) implements ToolInput {
        public Input {
            if (questions == null || questions.isEmpty() || questions.size() > 4) {
                throw new IllegalArgumentException("Must have 1-4 questions");
            }
        }

        @Override public String toJson() {
            StringBuilder sb = new StringBuilder("{\"questions\":[");
            for (int i = 0; i < questions.size(); i++) {
                if (i > 0) sb.append(",");
                Question q = questions.get(i);
                sb.append("{\"question\":\"").append(q.question().replace("\"", "\\\""))
                  .append("\",\"header\":\"").append(q.header())
                  .append("\",\"multiSelect\":").append(q.multiSelect()).append("}");
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    /** 回调接口 — 由 UI 层实现 */
    @FunctionalInterface
    public interface QuestionCallback {
        Map<String, String> askQuestions(List<Question> questions);
    }

    private static QuestionCallback callback = questions -> {
        // 默认实现：自动选择第一个选项
        Map<String, String> answers = new LinkedHashMap<>();
        for (Question q : questions) {
            if (!q.options().isEmpty()) {
                answers.put(q.question(), q.options().get(0).label());
            } else {
                answers.put(q.question(), "[auto]");
            }
        }
        return answers;
    };

    public static void setCallback(QuestionCallback cb) { callback = cb; }

    @Override public String name() { return "ask_user_question"; }

    @Override public String description() {
        return "Ask the user 1-4 questions to clarify requirements or get preferences. Each question can have 2-4 options.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"questions\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":4,\"items\":{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"},\"header\":{\"type\":\"string\"},\"options\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":4,\"items\":{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}}}},\"multiSelect\":{\"type\":\"boolean\"}},\"required\":[\"question\",\"header\",\"options\"]}}},\"required\":[\"questions\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        List<Question> questions = input.questions();

        log.info("[AskUserQuestion] Asking {} questions", questions.size());
        System.out.printf("[AskUserQuestion] Asking %d questions:%n", questions.size());

        for (Question q : questions) {
            System.out.printf("  [%s] %s%n", q.header(), q.question());
            for (QuestionOption opt : q.options()) {
                System.out.printf("    - %s: %s%n", opt.label(), opt.description());
            }
        }

        Map<String, String> answers = callback.askQuestions(questions);

        StringBuilder result = new StringBuilder("User responses:\n");
        answers.forEach((q, a) -> result.append("- ").append(q).append(" → ").append(a).append("\n"));

        return ToolOutput.ok(result.toString());
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use ask_user_question when you need clarification. Limit to 1-4 questions. Each question needs 2-4 options.";
    }
}
