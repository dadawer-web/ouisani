package com.ouisani.aios.drivers.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ouisani.aios.core.llm.LlmProvider.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-turn Ephemeral Context 单元测试 — 验证 {@link OpenAiAdapter#appendEphemeralBlock} 的行为。
 * <p>
 * 借鉴 OpenWorker engine.py:966-985：ephemeralContext 作为 {@code <system-context>} 块
 * 追加到最后一条 user message（send-time only，永不持久化）。
 * <p>
 * 覆盖：字符串/多模态/最后一条 user/空 no-op/无 user no-op/精确块格式/不污染原始 ChatMessage。
 */
class EphemeralContextTest {

    private static final String EXPECTED_BLOCK = "\n\n<system-context>\nRAG_RESULT_123\n</system-context>";

    private JsonObject userMsg(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", content);
        return m;
    }

    private JsonObject assistantMsg(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        m.addProperty("content", content);
        return m;
    }

    private JsonObject systemMsg(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "system");
        m.addProperty("content", content);
        return m;
    }

    @Test
    void appendEphemeralBlock_stringContent_appendedToLastUser() {
        JsonArray arr = new JsonArray();
        arr.add(userMsg("hello"));

        OpenAiAdapter.appendEphemeralBlock(arr, "RAG_RESULT_123");

        String content = arr.get(0).getAsJsonObject().get("content").getAsString();
        assertEquals("hello" + EXPECTED_BLOCK, content);
        assertTrue(content.endsWith("</system-context>"));
    }

    @Test
    void appendEphemeralBlock_multimodal_addsTextPart() {
        JsonArray arr = new JsonArray();
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        JsonArray contentParts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "describe this image");
        contentParts.add(textPart);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        contentParts.add(imagePart);
        m.add("content", contentParts);
        arr.add(m);

        OpenAiAdapter.appendEphemeralBlock(arr, "RAG_RESULT_123");

        JsonArray parts = arr.get(0).getAsJsonObject().get("content").getAsJsonArray();
        // 原有 2 个 part + 新增 1 个 text part = 3
        assertEquals(3, parts.size());
        JsonObject appended = parts.get(2).getAsJsonObject();
        assertEquals("text", appended.get("type").getAsString());
        assertEquals(EXPECTED_BLOCK, appended.get("text").getAsString());
    }

    @Test
    void appendEphemeralBlock_appendsToLastUserOnly() {
        JsonArray arr = new JsonArray();
        arr.add(systemMsg("sys"));
        arr.add(userMsg("first user"));
        arr.add(assistantMsg("reply"));
        arr.add(userMsg("second user"));

        OpenAiAdapter.appendEphemeralBlock(arr, "RAG_RESULT_123");

        // system / first user / assistant 不变
        assertEquals("sys", arr.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("first user", arr.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals("reply", arr.get(2).getAsJsonObject().get("content").getAsString());
        // 只有最后一条 user 被追加
        assertEquals("second user" + EXPECTED_BLOCK,
                arr.get(3).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void appendEphemeralBlock_emptyContext_noOp() {
        JsonArray arr = new JsonArray();
        arr.add(userMsg("hi"));

        OpenAiAdapter.appendEphemeralBlock(arr, null);
        assertEquals("hi", arr.get(0).getAsJsonObject().get("content").getAsString());

        OpenAiAdapter.appendEphemeralBlock(arr, "");
        assertEquals("hi", arr.get(0).getAsJsonObject().get("content").getAsString());

        OpenAiAdapter.appendEphemeralBlock(arr, "   \n\t  ");
        assertEquals("hi", arr.get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void appendEphemeralBlock_noUserMessage_noOp() {
        JsonArray arr = new JsonArray();
        arr.add(systemMsg("sys"));
        arr.add(assistantMsg("reply"));

        OpenAiAdapter.appendEphemeralBlock(arr, "RAG_RESULT_123");

        // 无 user 消息 → 不做任何改动，不抛异常
        assertEquals("sys", arr.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("reply", arr.get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void appendEphemeralBlock_exactBlockFormat() {
        JsonArray arr = new JsonArray();
        arr.add(userMsg(""));

        OpenAiAdapter.appendEphemeralBlock(arr, "CTX");

        // 验证精确块格式（借鉴 OpenWorker engine.py:971）
        assertEquals("\n\n<system-context>\nCTX\n</system-context>",
                arr.get(0).getAsJsonObject().get("content").getAsString());
    }

    /**
     * 验证「永不持久化」保证：appendEphemeralBlock 只改请求体内的 JSON 拷贝，
     * 不回写原始 ChatMessage 的 content。这是 send-time only 语义的核心。
     */
    @Test
    void appendEphemeralBlock_doesNotMutateOriginalChatMessage() {
        ChatMessage original = ChatMessage.user("original-content");
        // 模拟 buildRequestBody 内部：从 ChatMessage 新建 JsonObject（拷贝）
        JsonArray arr = new JsonArray();
        JsonObject copy = new JsonObject();
        copy.addProperty("role", original.role());
        copy.addProperty("content", original.contentAsString());
        arr.add(copy);

        OpenAiAdapter.appendEphemeralBlock(arr, "EPHEMERAL");

        // JSON 拷贝被追加了块
        assertTrue(copy.get("content").getAsString().endsWith("</system-context>"));
        // 但原始 ChatMessage 未被污染（永不持久化）
        assertEquals("original-content", original.contentAsString());
        assertFalse(original.contentAsString().contains("system-context"));
    }
}
