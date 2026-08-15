package com.ouisani.aios.operator.session;

/**
 * 内容块 — assistant 消息的 content blocks。
 * <p>
 * 对标 OpenClaw 的 TextContent / ImageContent / ToolCallContent。
 */
public class ContentBlock {

    public enum Type { TEXT, IMAGE, TOOL_CALL, THINKING }

    private final Type type;
    private final String text;
    private final String toolCallId;
    private final String toolName;
    private final String toolInput;

    private ContentBlock(Type type, String text, String toolCallId, String toolName, String toolInput) {
        this.type = type;
        this.text = text;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolInput = toolInput;
    }

    public static ContentBlock text(String text) {
        return new ContentBlock(Type.TEXT, text, null, null, null);
    }

    public static ContentBlock image(String url) {
        return new ContentBlock(Type.IMAGE, url, null, null, null);
    }

    public static ContentBlock toolCall(String id, String name, String input) {
        return new ContentBlock(Type.TOOL_CALL, null, id, name, input);
    }

    public static ContentBlock thinking(String text) {
        return new ContentBlock(Type.THINKING, text, null, null, null);
    }

    public Type type() { return type; }
    public String text() { return text; }
    public String toolCallId() { return toolCallId; }
    public String toolName() { return toolName; }
    public String toolInput() { return toolInput; }
}
