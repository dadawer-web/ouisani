package com.ouisani.aios.core.tool;

/**
 * 子 Agent 结果格式化器 —— 把子 Agent 的原文结果包装为 {@code <task_result>} 协议块。
 * <p>
 * 借鉴 OpenScience {@code task.ts}：subagent 结果回流父 Agent 时走压缩协议，
 * 超阈值的长结果截断为 head+tail+省略计数标记，防止父 Agent 上下文污染。
 * <p>
 * neuron-java 适配：OpenScience 用 {@code ARTIFACT_AGENTS} 枚举决定哪些 agent 压缩；
 * 本类用**长度启发式统一**（所有 subagent 结果都包装，超阈才截断），与 RoleBlueprint 解耦
 * （AgentTool 已移除 BuiltinAgentType 枚举）。
 *
 * <h3>格式</h3>
 * <pre>
 * &lt;task_result agent="sub_..." task="short description"&gt;
 * {result body — 原文 或 截断后的 head+omitted+tail}
 * &lt;/task_result&gt;
 * </pre>
 *
 * <h3>截断策略</h3>
 * <ul>
 *   <li>{@code result.length() <= THRESHOLD} → 原文入 body</li>
 *   <li>超阈 → 保留 head(600) + tail(600)，中间替换为
 *       {@code [... N chars omitted (subagent result truncated to prevent parent context pollution) ...]}</li>
 *   <li>{@code null}/空 → {@code [no output]}</li>
 * </ul>
 */
public final class SubagentResultFormatter {

    /** 超过此长度触发截断（package-private 供测试引用） */
    static final int THRESHOLD = 2000;
    /** 截断时保留的头部字符数 */
    static final int HEAD = 600;
    /** 截断时保留的尾部字符数 */
    static final int TAIL = 600;

    private SubagentResultFormatter() {}

    /**
     * 格式化子 Agent 结果为 {@code <task_result>} 协议块。
     *
     * @param agentId     子 Agent ID
     * @param description 简短任务描述（{@code AgentTool.Input.description}）；null/空则省略 task 属性
     * @param result      子 Agent 的最终文本结果（{@code QueryEngine.query} 返回的 last text part）
     * @return {@code <task_result>} 包装（可能截断）的结果
     */
    public static String format(String agentId, String description, String result) {
        StringBuilder sb = new StringBuilder();
        sb.append("<task_result agent=\"").append(escape(agentId)).append("\"");
        if (description != null && !description.isBlank()) {
            sb.append(" task=\"").append(escape(description)).append("\"");
        }
        sb.append(">\n");

        sb.append(formatBody(result));

        sb.append("\n</task_result>");
        return sb.toString();
    }

    private static String formatBody(String result) {
        if (result == null || result.isEmpty()) {
            return "[no output]";
        }
        if (result.length() <= THRESHOLD) {
            return result;
        }
        int omitted = result.length() - HEAD - TAIL;
        return result.substring(0, HEAD)
                + "\n[... " + omitted + " chars omitted (subagent result truncated to prevent parent context pollution) ...]\n"
                + result.substring(result.length() - TAIL);
    }

    /** 转义属性值中的反斜杠与双引号 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
