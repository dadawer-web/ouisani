package com.ouisani.aios.operator.session;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 消息 — 对标 OpenClaw 的 AgentMessage 联合类型。
 * <p>
 * 支持的 role：user, assistant, toolResult, bashExecution, custom,
 * branchSummary, compactionSummary
 */
public class AgentMessage {

    public enum Role {
        USER, ASSISTANT, TOOL_RESULT, BASH_EXECUTION, CUSTOM,
        BRANCH_SUMMARY, COMPACTION_SUMMARY
    }

    private final Role role;
    private final String text;
    private final List<ContentBlock> contentBlocks;
    private String toolCallId;
    private String toolName;

    private AgentMessage(Role role, String text) {
        this.role = role;
        this.text = text;
        this.contentBlocks = new ArrayList<>();
    }

    private AgentMessage(Role role, List<ContentBlock> blocks) {
        this.role = role;
        this.text = null;
        this.contentBlocks = blocks != null ? new ArrayList<>(blocks) : new ArrayList<>();
    }

    // ── 工厂方法 ──

    public static AgentMessage user(String text) {
        return new AgentMessage(Role.USER, text);
    }

    public static AgentMessage assistant(String text) {
        return new AgentMessage(Role.ASSISTANT, text);
    }

    public static AgentMessage assistant(List<ContentBlock> blocks) {
        return new AgentMessage(Role.ASSISTANT, blocks);
    }

    public static AgentMessage toolResult(String toolCallId, String text) {
        AgentMessage m = new AgentMessage(Role.TOOL_RESULT, text);
        m.toolCallId = toolCallId;
        return m;
    }

    public static AgentMessage bashExecution(String command, String output) {
        AgentMessage m = new AgentMessage(Role.BASH_EXECUTION,
                "Ran `" + command + "`\n```\n" + output + "\n```");
        return m;
    }

    public static AgentMessage custom(String text) {
        return new AgentMessage(Role.CUSTOM, text);
    }

    public static AgentMessage branchSummary(String summary) {
        return new AgentMessage(Role.BRANCH_SUMMARY,
                "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n"
                        + summary + "\n</summary>");
    }

    public static AgentMessage compactionSummary(String summary) {
        return new AgentMessage(Role.COMPACTION_SUMMARY,
                "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"
                        + summary + "\n</summary>");
    }

    // ── Getters ──

    public Role role() { return role; }
    public String text() { return text; }
    public List<ContentBlock> contentBlocks() { return contentBlocks; }
    public String toolCallId() { return toolCallId; }
    public String toolName() { return toolName; }

    /**
     * 估算 token 数 — 保守启发式：4 字符 ≈ 1 token。
     */
    public int estimateTokens() {
        String t = text != null ? text : contentBlocks.stream()
                .filter(b -> b.type == ContentBlock.Type.TEXT)
                .map(b -> b.text)
                .reduce("", (a, b) -> a + b);
        return (int) Math.ceil((double) t.length() / 4);
    }

    /**
     * 是否为 user 角色（LLM 视角）。
     * bashExecution, custom, branchSummary, compactionSummary 在 LLM 上下文中都作为 user 消息。
     */
    public boolean isUserFacing() {
        return role == Role.USER || role == Role.BASH_EXECUTION
                || role == Role.CUSTOM || role == Role.BRANCH_SUMMARY
                || role == Role.COMPACTION_SUMMARY;
    }

    @Override
    public String toString() {
        return "AgentMessage{" + role + " len=" + (text != null ? text.length() : contentBlocks.size()) + "}";
    }
}
