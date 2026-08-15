package com.ouisani.aios.core.tool;

/**
 * Handoff 工具输入 — LLM 驱动的 Agent 切换参数。
 * <p>
 * 参考 OpenAI Agents Python 的 Handoff 设计：当当前 Agent 无法处理用户请求时，
 * LLM 自主决定将控制权移交给另一个更合适的 Agent。
 * <p>
 * OS 类比：相当于 Linux 的 execve() — 当前进程主动替换为目标进程，
 * 但保留上下文摘要作为"环境变量"传递给目标。
 *
 * @param targetAgent    目标 Agent 的角色或 ID
 * @param reason         切换原因（供审计与 Tracing）
 * @param contextSummary 传递给目标 Agent 的上下文摘要
 */
public final class HandoffInput implements ToolInput {

    private final String targetAgent;
    private final String reason;
    private final String contextSummary;

    public HandoffInput(String targetAgent, String reason, String contextSummary) {
        if (targetAgent == null || targetAgent.isBlank()) {
            throw new IllegalArgumentException("target_agent 不能为空");
        }
        this.targetAgent = targetAgent;
        this.reason = reason != null ? reason : "";
        this.contextSummary = contextSummary != null ? contextSummary : "";
    }

    public String getTargetAgent() {
        return targetAgent;
    }

    public String getReason() {
        return reason;
    }

    public String getContextSummary() {
        return contextSummary;
    }

    @Override
    public String toJson() {
        return "{\"target_agent\":\"" + escape(targetAgent)
                + "\",\"reason\":\"" + escape(reason)
                + "\",\"context_summary\":\"" + escape(contextSummary) + "\"}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t");
    }
}
