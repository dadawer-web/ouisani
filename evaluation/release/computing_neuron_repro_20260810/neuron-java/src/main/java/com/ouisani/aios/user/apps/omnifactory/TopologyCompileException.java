package com.ouisani.aios.user.apps.omnifactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 拓扑编译异常 — 当 GraphValidator 检测到类型不兼容时抛出。
 * <p>
 * 类似 Java 编译器的编译错误：在运行前拦截大模型的荒谬幻觉。
 * 包含具体的修正建议，可发回给 LLM 重新生成。
 */
public class TopologyCompileException extends RuntimeException {

    private final List<String> validationErrors;
    private final List<String> fixSuggestions;

    public TopologyCompileException(String message, List<String> validationErrors, List<String> fixSuggestions) {
        super(message);
        this.validationErrors = validationErrors != null ? validationErrors : new ArrayList<>();
        this.fixSuggestions = fixSuggestions != null ? fixSuggestions : new ArrayList<>();
    }

    public List<String> validationErrors() {
        return validationErrors;
    }

    public List<String> fixSuggestions() {
        return fixSuggestions;
    }

    /**
     * 生成可发给 LLM 的修正提示词。
     */
    public String toLlmRetryPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你上一次生成的 DAG 拓扑图存在类型不兼容的错误，请修正后重新生成。\n\n");
        sb.append("【验证错误】\n");
        for (int i = 0; i < validationErrors.size(); i++) {
            sb.append(i + 1).append(". ").append(validationErrors.get(i)).append("\n");
        }
        sb.append("\n【修正建议】\n");
        for (int i = 0; i < fixSuggestions.size(); i++) {
            sb.append(i + 1).append(". ").append(fixSuggestions.get(i)).append("\n");
        }
        sb.append("\n请修正以上问题，重新输出完整的 DAG JSON。");
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getMessage()).append("\n");
        sb.append("验证错误 (").append(validationErrors.size()).append("):\n");
        for (String err : validationErrors) {
            sb.append("  - ").append(err).append("\n");
        }
        sb.append("修正建议:\n");
        for (String fix : fixSuggestions) {
            sb.append("  - ").append(fix).append("\n");
        }
        return sb.toString();
    }
}
