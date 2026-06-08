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

        // 构造包含发送方信息的完整消息载荷
        String payload = buildPayload(from, to, message, summary);

        if ("*".equals(to)) {
            // 广播模式：通过 agent.broadcast 频道发送
            EventBus.instance().broadcast("agent.broadcast", payload);
            log.info("[SendMessageTool] 广播消息: from={}, summary={}", from, summary);
        } else {
            // 定向模式：通过 agent.message.{to} 频道路由
            String channel = "agent.message." + to;
            EventBus.instance().broadcast(channel, payload);
            log.info("[SendMessageTool] 定向消息: from={}, to={}, channel={}", from, to, channel);
        }

        // 记录遥测
        TelemetryService.instance().logEvent("agent.message_sent", Map.of(
                "from", from,
                "to", to,
                "isBroadcast", "*".equals(to),
                "summary", summary
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
