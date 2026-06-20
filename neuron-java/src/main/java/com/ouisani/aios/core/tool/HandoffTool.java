package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.team.MailMessage;
import com.ouisani.aios.core.team.TeamRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handoff 工具 — LLM 驱动的 Agent 切换。
 * <p>
 * 参考 OpenAI Agents Python 的 Handoff 设计：当当前 Agent 无法处理用户请求时，
 * LLM 自主决定调用此工具，将控制权移交给另一个更合适的 Agent。
 * <p>
 * 与 DAG 拓扑互补：
 * <ul>
 *   <li>DAG 拓扑：编译时确定的确定性流水线，WorkflowEngine 按拓扑顺序执行</li>
 *   <li>Handoff：运行时 LLM 自主决策的探索性切换，适用于不确定性任务</li>
 * </ul>
 * <p>
 * Handoff 不终止当前 Agent 的执行循环。它只是：
 * <ol>
 *   <li>向目标 Agent 发送 TASK_ASSIGN 消息（异步触发目标 Agent 执行）</li>
 *   <li>记录 Handoff 历史（供审计与可视化）</li>
 *   <li>广播 sys.agent.handoff 事件（供前端大屏渲染动效）</li>
 *   <li>当前 Agent 收到 handoff 确认后继续执行</li>
 * </ol>
 * <p>
 * OS 类比：相当于 Linux 的信号投递 + 上下文传递 —
 * 当前进程不退出，而是向目标进程发送任务并附带环境变量（context summary）。
 */
public class HandoffTool implements Tool<HandoffInput> {

    private static final Logger log = LoggerFactory.getLogger(HandoffTool.class);

    /** 工具名称 — 全局唯一，LLM 调用时的标识符 */
    public static final String TOOL_NAME = "transfer_to_agent";

    /** EventBus 事件通道 — 用于广播 handoff 事件供前端渲染 */
    public static final String HANDOFF_EVENT_CHANNEL = "sys.agent.handoff";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        // 动态描述：包含可用目标列表，供 LLM 决策
        return HandoffManager.instance().generateToolDescription();
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\","
                + "\"properties\":{"
                + "\"target_agent\":{\"type\":\"string\",\"description\":\"目标 Agent 的角色或 ID\"},"
                + "\"reason\":{\"type\":\"string\",\"description\":\"切换原因\"},"
                + "\"context_summary\":{\"type\":\"string\",\"description\":\"传递给目标 Agent 的上下文摘要\"}"
                + "},"
                + "\"required\":[\"target_agent\",\"reason\"]}";
    }

    @Override
    public ToolOutput call(HandoffInput input, ToolContext context) {
        String sourceAgentId = context.agentId();
        String targetKey = input.getTargetAgent();
        String reason = input.getReason();
        String contextSummary = input.getContextSummary();

        log.info("[HandoffTool] Handoff 请求: {} -> {} (reason={})", sourceAgentId, targetKey, reason);

        // ── 1. 查找目标 Agent ──
        // 先从 HandoffManager 查找已注册的 handoff 目标（按 agentId 或 role）
        HandoffManager.HandoffTarget target = HandoffManager.instance().findTarget(targetKey);
        String targetAgentId;

        if (target != null) {
            // 从 HandoffManager 找到目标，使用其 agentId
            targetAgentId = target.agentId();
        } else {
            // HandoffManager 中未注册，尝试直接作为 agentId 在 TeamRegistry 中查找
            targetAgentId = targetKey;
        }

        // ── 2. 校验目标 Agent 是否存在 ──
        TeamRegistry registry = TeamRegistry.getInstance();
        if (!registry.isOnline(targetAgentId)) {
            String errMsg = String.format("目标 Agent '%s' 不存在或未在线。可用目标: %s",
                    targetKey, formatAvailableTargets());
            log.warn("[HandoffTool] {}", errMsg);
            return ToolOutput.fail(errMsg);
        }

        // ── 3. 创建 HandoffEvent 并通过 EventBus 广播 ──
        String handoffEventPayload = buildHandoffEventPayload(
                sourceAgentId, targetAgentId, reason, contextSummary);
        try {
            EventBus.instance().broadcast(HANDOFF_EVENT_CHANNEL, handoffEventPayload);
            log.info("[HandoffTool] Handoff 事件已广播: {} -> {}", sourceAgentId, targetAgentId);
        } catch (Exception e) {
            log.warn("[HandoffTool] Handoff 事件广播失败: {}", e.getMessage());
        }

        // ── 4. 通过 TeamRegistry 向目标 Agent 发送 TASK_ASSIGN 消息 ──
        // 消息负载包含 context_summary，目标 Agent 据此继续处理
        String taskPayload = buildTaskPayload(sourceAgentId, targetAgentId, reason, contextSummary);
        MailMessage taskMessage = new MailMessage(
                sourceAgentId,
                targetAgentId,
                MailMessage.MessageType.TASK_ASSIGN,
                taskPayload
        );
        registry.dispatch(taskMessage);
        log.info("[HandoffTool] TASK_ASSIGN 已派发: {} -> {}", sourceAgentId, targetAgentId);

        // ── 5. 记录 Handoff 历史 ──
        HandoffManager.instance().recordHandoff(sourceAgentId, targetAgentId, reason, contextSummary);

        // ── 6. 返回 Handoff 确认信息 ──
        String confirmation = String.format(
                "Handoff 已完成。\n源 Agent: %s\n目标 Agent: %s\n切换原因: %s\n上下文摘要: %s\n"
                        + "目标 Agent 已收到 TASK_ASSIGN 消息，将异步处理任务。当前 Agent 继续执行。",
                sourceAgentId, targetAgentId,
                reason.isBlank() ? "(未提供)" : reason,
                contextSummary.isBlank() ? "(无)" : contextSummary);

        return ToolOutput.ok(confirmation);
    }

    @Override
    public boolean readOnly() {
        // Handoff 会触发目标 Agent 执行任务，属于有副作用的操作
        return false;
    }

    @Override
    public String prompt() {
        return "使用 transfer_to_agent 工具将控制权移交给另一个更合适的 Agent。"
                + "当当前 Agent 无法处理用户请求，或认为另一个 Agent 更适合处理时调用此工具。"
                + "target_agent 指定目标 Agent 的角色或 ID，"
                + "reason 说明切换原因，"
                + "context_summary 提供传递给目标 Agent 的上下文摘要。"
                + "调用后当前 Agent 不会终止，目标 Agent 将异步处理任务。";
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建 Handoff 事件 JSON 负载（供 EventBus 广播）。
     */
    private String buildHandoffEventPayload(String source, String target, String reason, String contextSummary) {
        return String.format(
                "{\"type\":\"handoff\",\"source\":\"%s\",\"target\":\"%s\","
                        + "\"reason\":\"%s\",\"context_summary\":\"%s\",\"timestamp\":%d}",
                escapeJson(source),
                escapeJson(target),
                escapeJson(reason),
                escapeJson(contextSummary),
                System.currentTimeMillis());
    }

    /**
     * 构建 TASK_ASSIGN 消息负载 JSON。
     * <p>
     * 目标 Agent 从此负载中读取上下文摘要和切换原因，继续处理任务。
     */
    private String buildTaskPayload(String source, String target, String reason, String contextSummary) {
        return String.format(
                "{\"type\":\"handoff_task\",\"source\":\"%s\",\"target\":\"%s\","
                        + "\"reason\":\"%s\",\"context_summary\":\"%s\",\"timestamp\":%d}",
                escapeJson(source),
                escapeJson(target),
                escapeJson(reason),
                escapeJson(contextSummary),
                System.currentTimeMillis());
    }

    /**
     * 格式化可用目标列表（用于错误消息）。
     */
    private String formatAvailableTargets() {
        var targets = HandoffManager.instance().getHandoffTargets();
        if (targets.isEmpty()) {
            return "(无已注册的 Handoff 目标)";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var t : targets) {
            if (!first) sb.append(", ");
            sb.append(t.agentId());
            if (!t.role().isBlank()) sb.append("(").append(t.role()).append(")");
            first = false;
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t");
    }
}
