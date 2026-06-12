package com.ouisani.aios.openclaw.tools;

import com.ouisani.aios.core.tool.*;
import com.ouisani.aios.openclaw.gateway.GatewayException;
import com.ouisani.aios.openclaw.gateway.GatewayToolBridge;

import java.util.*;

/**
 * 消息工具 — 对标 OpenClaw 的 MessageTool（简化版）。
 * <p>
 * 通过 Gateway 向各渠道发送/读取消息。
 * 简化实现：只支持核心动作（send, read, react），
 * 去掉了 presentation/interactive/poll 等高级功能。
 */
public class MessageTool implements Tool<MessageTool.Input> {

    private final GatewayToolBridge bridge;

    public MessageTool(GatewayToolBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String name() { return "message"; }

    @Override
    public String description() {
        return "Send and manage messages across channels. Actions: send, read, react. "
                + "Use 'send' to send a text message to a channel, "
                + "'read' to read recent messages, "
                + "'react' to add a reaction to a message.";
    }

    @Override
    public String inputSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "action": { "type": "string", "enum": ["send","read","react"], "description": "Action to perform" },
            "channel": { "type": "string", "description": "Channel ID (e.g. telegram, discord)" },
            "text": { "type": "string", "description": "Message text (for send)" },
            "targetId": { "type": "string", "description": "Target chat/thread ID" },
            "messageId": { "type": "string", "description": "Message ID (for react)" },
            "emoji": { "type": "string", "description": "Reaction emoji (for react)" },
            "limit": { "type": "integer", "description": "Number of messages to read (default 20)" }
          },
          "required": ["action"]
        }
        """;
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            return switch (input.action()) {
                case "send" -> handleSend(input);
                case "read" -> handleRead(input);
                case "react" -> handleReact(input);
                default -> ToolOutput.fail("Unknown action: " + input.action());
            };
        } catch (GatewayException e) {
            return ToolOutput.fail("Gateway error: " + e.getMessage());
        }
    }

    private ToolOutput handleSend(Input input) throws GatewayException {
        if (input.text() == null || input.text().isBlank()) {
            return ToolOutput.fail("text is required for 'send' action");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "send");
        if (input.channel() != null) params.put("channel", input.channel());
        if (input.targetId() != null) params.put("targetId", input.targetId());
        params.put("text", sanitizeOutboundText(input.text()));

        String result = bridge.call("message.action", params);
        return ToolOutput.ok("Message sent:\n" + result);
    }

    private ToolOutput handleRead(Input input) throws GatewayException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "read");
        if (input.channel() != null) params.put("channel", input.channel());
        if (input.targetId() != null) params.put("targetId", input.targetId());
        params.put("limit", input.limit() > 0 ? input.limit() : 20);

        String result = bridge.call("message.action", params);
        return ToolOutput.ok("Messages:\n" + result);
    }

    private ToolOutput handleReact(Input input) throws GatewayException {
        if (input.messageId() == null || input.messageId().isBlank()) {
            return ToolOutput.fail("messageId is required for 'react' action");
        }
        if (input.emoji() == null || input.emoji().isBlank()) {
            return ToolOutput.fail("emoji is required for 'react' action");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "react");
        if (input.channel() != null) params.put("channel", input.channel());
        params.put("messageId", input.messageId());
        params.put("emoji", input.emoji());

        String result = bridge.call("message.action", params);
        return ToolOutput.ok("Reaction added:\n" + result);
    }

    /**
     * 出站文本净化 — 对标 OpenClaw 的三层净化。
     * <p>
     * 1. 剥离推理块（<think>...</think>）
     * 2. 剥离内部运行时上下文标记
     * 3. 防止 boot 提示词回显
     */
    private String sanitizeOutboundText(String text) {
        // 1. 剥离推理块
        String cleaned = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        // 2. 剥离内部标记
        cleaned = cleaned.replaceAll("\\[INTERNAL:[^\\]]+\\]", "").trim();
        // 3. 剥离 boot 回显
        cleaned = cleaned.replaceAll("(?s)You are a[^.]+\\..*?(?=\\n\\n)", "").trim();
        return cleaned;
    }

    @Override
    public boolean readOnly() { return false; }

    /** 工具输入 */
    public record Input(
            String action,
            String channel,
            String text,
            String targetId,
            String messageId,
            String emoji,
            int limit
    ) implements ToolInput {
        @Override
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"action\":\"").append(action).append("\"");
            if (channel != null) sb.append(",\"channel\":\"").append(channel).append("\"");
            if (text != null) sb.append(",\"text\":\"").append(text.replace("\"", "\\\"")).append("\"");
            if (targetId != null) sb.append(",\"targetId\":\"").append(targetId).append("\"");
            if (messageId != null) sb.append(",\"messageId\":\"").append(messageId).append("\"");
            if (emoji != null) sb.append(",\"emoji\":\"").append(emoji).append("\"");
            if (limit > 0) sb.append(",\"limit\":").append(limit);
            sb.append("}");
            return sb.toString();
        }
    }
}
