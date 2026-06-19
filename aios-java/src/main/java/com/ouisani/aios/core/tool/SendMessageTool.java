package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 多代理团队通信工具 — 对标 Claude Code 的 SendMessageTool.ts。
 * <p>
 * 允许 Agent 向团队中的其他 Agent 发送消息，支持：
 * - 定向发送：指定接收者名称，通过 {@code agent.message.{to}} 频道路由
 * - 广播发送：to 为 "*" 时，通过 {@code agent.broadcast} 频道向所有 Agent 广播
 * <p>
 * OS 类比：相当于 Linux 的 kill / sigqueue 系统调用 —
 * 向指定进程（定向）或进程组（广播）发送信号（消息）。
 * 接收方通过 EventBus 订阅对应频道来处理消息。
 */
public class SendMessageTool implements Tool<SendMessageTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(SendMessageTool.class);

    /**
     * 消息工具输入参数。
     *
     * @param to      接收者名称，"*" 表示广播给所有 Agent
     * @param message 消息内容
     * @param summary 5-10 词的消息摘要，供接收方快速理解消息意图
     */
    public record Input(
            String to,
            String message,
            String summary
    ) implements ToolInput {

        public Input {
            if (to == null || to.isBlank()) throw new IllegalArgumentException("to 不能为空");
            if (message == null || message.isBlank()) throw new IllegalArgumentException("message 不能为空");
            if (summary == null) summary = "";
        }

        @Override
        public String toJson() {
            return "{\"to\":\"" + to.replace("\"", "\\\"")
                    + "\",\"message\":\"" + message.replace("\"", "\\\"")
                    + "\",\"summary\":\"" + summary.replace("\"", "\\\"") + "\"}";
        }
    }

    @Override
    public String name() {
        return "send_message";
    }

    @Override
    public String description() {
        return "多代理团队通信工具";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\","
                + "\"properties\":{"
                + "\"to\":{\"type\":\"string\",\"description\":\"接收者名称，\\\"*\\\" 表示广播给所有 Agent\"},"
                + "\"message\":{\"type\":\"string\",\"description\":\"消息内容\"},"
                + "\"summary\":{\"type\":\"string\",\"description\":\"5-10 词的消息摘要，供接收方快速理解消息意图\"}"
                + "},"
                + "\"required\":[\"to\",\"message\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        String from = context.agentId();
        String to = input.to();
        String message = input.message();
        String summary = input.summary();

        log.info("[SendMessageTool] 发送消息: from={}, to={}, summary={}", from, to, summary);

        // ── 数据面统一收口：所有 Agent 间消息通过 AgentMailbox 投递 ──
        // EventBus 降级为控制面，只负责系统级事件（UI 状态同步、告警等）
        // Agent 间通信统一走 TeamRegistry → AgentMailbox → MailMessage
        com.ouisani.aios.core.team.TeamRegistry registry = com.ouisani.aios.core.team.TeamRegistry.getInstance();

        if ("*".equals(to)) {
            // 广播模式：通过 TeamRegistry.broadcast 向所有 Agent 投递
            registry.broadcast(
                com.ouisani.aios.core.team.MailMessage.MessageType.STATUS_UPDATE,
                buildPayload(from, to, message, summary),
                from
            );
            log.info("[SendMessageTool] 广播消息（经 Mailbox）: from={}, summary={}", from, summary);
        } else {
            // 定向模式：通过 TeamRegistry.dispatch 精准投递
            // 先检查目标 Agent 是否在线
            if (!registry.isOnline(to)) {
                log.warn("[SendMessageTool] 目标 Agent '{}' 未在线，消息可能丢失", to);
                // 仍然尝试投递（TeamRegistry 内部会记录警告）
            }
            com.ouisani.aios.core.team.MailMessage mail = new com.ouisani.aios.core.team.MailMessage(
                from, to,
                com.ouisani.aios.core.team.MailMessage.MessageType.STATUS_UPDATE,
                buildPayload(from, to, message, summary)
            );
            registry.dispatch(mail);
            log.info("[SendMessageTool] 定向消息（经 Mailbox）: from={}, to={}", from, to);
        }

        // 控制面通知：仅用于前端 UI 动效渲染（不作为 Agent 间通信通道）
        try {
            String uiPayload = String.format(
                "{\"from\":\"%s\",\"to\":\"%s\",\"summary\":\"%s\",\"timestamp\":%d}",
                from.replace("\"", "\\\""),
                to.replace("\"", "\\\""),
                summary.replace("\"", "\\\""),
                System.currentTimeMillis()
            );
            EventBus.instance().broadcast("agent.message.ui", uiPayload);
        } catch (Exception ignore) {}

        // 记录遥测
        TelemetryService.instance().logEvent("agent.message_sent", Map.of(
                "from", from,
                "to", to,
                "isBroadcast", "*".equals(to),
                "summary", summary,
                "channel", "mailbox"
        ));

        String target = "*".equals(to) ? "所有 Agent（广播）" : to;
        return ToolOutput.ok("消息已发送\n"
                + "发送方: " + from + "\n"
                + "接收方: " + target + "\n"
                + "摘要: " + (summary.isBlank() ? "(无)" : summary));
    }

    /**
     * 构造消息载荷 JSON。
     * <p>
     * 包含发送方、接收方、消息内容和摘要，供接收方解析使用。
     */
    private String buildPayload(String from, String to, String message, String summary) {
        // 转义 JSON 特殊字符
        String escapedFrom = from.replace("\\", "\\\\").replace("\"", "\\\"");
        String escapedTo = to.replace("\\", "\\\\").replace("\"", "\\\"");
        String escapedMessage = message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String escapedSummary = summary
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

        return "{\"from\":\"" + escapedFrom
                + "\",\"to\":\"" + escapedTo
                + "\",\"message\":\"" + escapedMessage
                + "\",\"summary\":\"" + escapedSummary
                + "\",\"timestamp\":" + System.currentTimeMillis() + "}";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String prompt() {
        return "使用 send_message 向团队中的其他 Agent 发送消息。"
                + "to 指定接收者名称（\"*\" 表示广播），"
                + "message 为消息内容，"
                + "summary 为 5-10 词的消息摘要。";
    }
}
