package com.ouisani.aios.core.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ouisani.aios.core.llm.LlmProvider.ChatMessage;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.llm.LlmRouterHolder;
import com.ouisani.aios.core.memory.MemoryManager;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 普通对话 HTTP 路由 — POST /api/chat，SSE 逐 token 流式。
 * <p>
 * 与工作流拓扑执行（/api/workflow/compile + deploy）并存的「纯对话」入口：
 * 用户消息 + 历史 → {@link LlmRouter#thinkWithHistoryStream} → 逐 token SSE 推送。
 * 对话前从 {@link MemoryManager} 检索相关记忆注入 system prompt，让 AI 记得住事。
 * <p>
 * OS 类比：相当于 /dev/chat — 一个直连 LLM 算力的字符设备，不走 DAG 调度。
 */
public final class ChatRoutes {

    private static final Logger log = LoggerFactory.getLogger(ChatRoutes.class);
    private static final Gson GSON = new Gson();

    /** 普通对话的记忆 namespace — 固定 agentId，可在 MemoryViewer 查看/清理 */
    private static final String CHAT_AGENT_ID = "chat";

    private static final String BASE_SYSTEM_PROMPT =
            "You are AIOS Atelier, a helpful AI assistant in a chat-first workbench. " +
            "Answer concisely and naturally in the user's language. " +
            "When relevant memories are provided, use them to personalize the response.";

    private ChatRoutes() {}

    public static void attachTo(Javalin app) {
        // ── POST /api/chat — SSE 流式对话 ──
        app.post("/api/chat", ctx -> {
            ctx.contentType("text/event-stream; charset=utf-8");
            ctx.header("Cache-Control", "no-cache");
            ctx.header("Connection", "keep-alive");
            ctx.header("X-Accel-Buffering", "no"); // 禁用 nginx 缓冲，确保逐块推送

            OutputStream out = ctx.outputStream();

            try {
                ChatRequest req = parseRequest(ctx.body());
                if (req.messages.isEmpty()) {
                    writeEvent(out, Map.of("error", "missing 'messages' field"));
                    out.flush();
                    return;
                }

                LlmRouter router = LlmRouterHolder.get();
                if (router == null) {
                    writeEvent(out, Map.of("error", "LLM router not initialized (backend LLM key may be missing)"));
                    out.flush();
                    return;
                }

                // ── 记忆注入：按最后一条 user 消息检索相关记忆 ──
                String systemPrompt = BASE_SYSTEM_PROMPT;
                String lastUserQuery = req.messages.stream()
                        .filter(m -> "user".equals(m.role()))
                        .map(ChatMessage::contentAsString)
                        .reduce((first, second) -> second)
                        .orElse("");
                String recalled = retrieveMemorySafely(req.agentId, lastUserQuery);
                if (recalled != null && !recalled.isBlank()) {
                    systemPrompt += "\n\n[Relevant memories for this conversation]\n" + recalled;
                }

                log.info("[ChatRoutes] /api/chat: agentId={}, messages={}, memoryInjected={}",
                        req.agentId, req.messages.size(), recalled != null && !recalled.isBlank());

                // ── 阻塞流式调用：onDelta 在同线程同步回调，逐 token 写 SSE ──
                final OutputStream sink = out;
                router.thinkWithHistoryStream(req.messages, systemPrompt, delta -> {
                    try {
                        sink.write(("data: " + GSON.toJson(Map.of("delta", delta)) + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        sink.flush();
                    } catch (Exception e) {
                        log.warn("[ChatRoutes] 写 SSE delta 失败: {}", e.getMessage());
                    }
                });

                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                log.error("[ChatRoutes] /api/chat 失败: {}", e.getMessage(), e);
                try {
                    writeEvent(out, Map.of("error", e.getMessage() == null ? "unknown error" : e.getMessage()));
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignore) { /* stream already closed */ }
            }
        });

        // ── CORS 预检 ──
        app.options("/api/chat", ctx -> {
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        log.info("[ChatRoutes] 路由已挂载: POST /api/chat (SSE streaming)");
        System.out.println("  ✓ [ChatRoutes] 路由: POST /api/chat (SSE streaming)");
    }

    // ════════════════════════════════════════════════════════════════
    //  内部工具
    // ════════════════════════════════════════════════════════════════

    /** 解析请求体：{ agentId?, messages:[{role,content}], systemPrompt? } */
    private static ChatRequest parseRequest(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String agentId = root.has("agentId") && !root.get("agentId").isJsonNull()
                ? root.get("agentId").getAsString() : CHAT_AGENT_ID;

        List<ChatMessage> messages = new ArrayList<>();
        if (root.has("messages") && root.get("messages").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("messages");
            for (var elem : arr) {
                if (!elem.isJsonObject()) continue;
                JsonObject m = elem.getAsJsonObject();
                String role = m.has("role") ? m.get("role").getAsString() : "user";
                String content = m.has("content") && m.get("content").isJsonPrimitive()
                        ? m.get("content").getAsString() : "";
                switch (role) {
                    case "assistant" -> messages.add(ChatMessage.assistant(content));
                    case "system" -> messages.add(ChatMessage.system(content));
                    default -> messages.add(ChatMessage.user(content));
                }
            }
        }
        return new ChatRequest(agentId, messages);
    }

    /** 安全检索记忆 — 任何异常都跳过（记忆是可选增强，不阻断对话） */
    private static String retrieveMemorySafely(String agentId, String query) {
        if (agentId == null || query == null || query.isBlank()) return "";
        try {
            var provider = MemoryManager.getInstance().currentProvider();
            if (provider == null) return "";
            return provider.retrieve(agentId, query);
        } catch (Exception e) {
            log.warn("[ChatRoutes] 记忆检索失败（已跳过）: {}", e.getMessage());
            return "";
        }
    }

    private static void writeEvent(OutputStream out, Map<String, String> payload) throws Exception {
        out.write(("data: " + GSON.toJson(payload) + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private record ChatRequest(String agentId, List<ChatMessage> messages) {}
}
