package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.network.EventBus;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 通用 Human-in-the-Loop 工具 — 借鉴 CopilotKit 的 renderAndWaitForResponse。
 * <p>
 * Agent 在正常执行流程中可以调用此工具，向人类请求输入。
 * 与故障恢复的 HITL 不同，这是 Agent 主动发起的"提问"。
 * <p>
 * 机制：
 * 1. Agent 调用 human_response 工具，传入问题和选项
 * 2. 工具通过 EventBus 广播 human_prompt 事件到前端
 * 3. 前端渲染交互 UI（问题 + 选项按钮 / 文本输入框）
 * 4. 用户响应后，前端通过 WebSocket 回传结果
 * 5. 工具的 CompletableFuture 被 complete，Agent 继续执行
 * <p>
 * Virtual Thread 的阻塞等待不消耗物理线程，不违反异步 IPC 原则。
 * <p>
 * OS 类比：Linux 的 wait_for_completion() — 内核线程可以阻塞等待某个条件满足，
 * 但不占用 CPU 资源，由调度器在条件满足时唤醒。
 */
public class HumanResponseTool implements Tool<HumanResponseTool.HumanResponseInput> {

    private static final Logger log = LoggerFactory.getLogger(HumanResponseTool.class);
    private static final Gson gson = new Gson();

    /** 等待人类响应的超时时间（分钟） */
    private static final long TIMEOUT_MINUTES = 5;

    /** 待处理的人类响应请求（requestId → CompletableFuture） */
    private static final ConcurrentHashMap<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    /** requestId → agentId 映射（用于路由响应） */
    private static final ConcurrentHashMap<String, String> requestAgentMap = new ConcurrentHashMap<>();

    @Override
    public String name() { return "human_response"; }

    @Override
    public String description() {
        return "向人类用户请求输入。当你需要人类做出决策、确认操作或提供额外信息时使用此工具。"
                + "问题会显示在前端 UI 上，用户可以选择选项或输入自定义文本。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "question": {
                            "type": "string",
                            "description": "向人类提出的问题"
                        },
                        "options": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "可选的选项列表（可选）。如果提供，前端会渲染为按钮。"
                        },
                        "context": {
                            "type": "string",
                            "description": "问题的背景信息（可选）"
                        }
                    },
                    "required": ["question"]
                }
                """;
    }

    @Override
    public ToolOutput call(HumanResponseInput input, ToolContext context) {
        String requestId = UUID.randomUUID().toString();
        String agentId = context.agentId();

        log.info("[HumanResponse] Agent '{}' 请求人类输入: requestId={}, question='{}'",
                agentId, requestId, input.question);

        // 创建 CompletableFuture（Virtual Thread 友好，阻塞不消耗物理线程）
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        requestAgentMap.put(requestId, agentId);

        // 构建事件 payload
        JsonObject payload = new JsonObject();
        payload.addProperty("requestId", requestId);
        payload.addProperty("agentId", agentId);
        if (input.question != null) {
            payload.addProperty("question", input.question);
        }
        if (input.options != null && !input.options.isEmpty()) {
            payload.add("options", gson.toJsonTree(input.options));
        }
        if (input.context != null && !input.context.isBlank()) {
            payload.addProperty("context", input.context);
        }
        payload.addProperty("timestamp", System.currentTimeMillis());

        // 广播到前端
        EventBus.instance().broadcast("human_prompt", payload.toString());

        // 阻塞等待人类响应（Virtual Thread 友好）
        try {
            String response = future.get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            log.info("[HumanResponse] 收到人类响应: requestId={}, response='{}'",
                    requestId, response == null ? "(null)" : response.substring(0, Math.min(response.length(), 100)));
            return ToolOutput.ok(response);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[HumanResponse] 等待人类响应超时（{}分钟）: requestId={}", TIMEOUT_MINUTES, requestId);
            cleanup(requestId);
            return ToolOutput.ok("人类响应超时（" + TIMEOUT_MINUTES + "分钟），请继续执行。");
        } catch (Exception e) {
            log.error("[HumanResponse] 等待人类响应异常: requestId={}, error={}", requestId, e.getMessage());
            cleanup(requestId);
            return ToolOutput.fail("人类响应异常: " + e.getMessage());
        }
    }

    /** 清理请求资源 */
    private static void cleanup(String requestId) {
        pendingRequests.remove(requestId);
        requestAgentMap.remove(requestId);
    }

    // ════════════════════════════════════════════════════════════════
    //  前端回调入口 — 供 AppGateway 调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 提交人类响应 — 前端通过 AppGateway WebSocket 调用此方法。
     *
     * @param requestId 请求 ID（来自 human_prompt 事件）
     * @param response  人类的响应文本
     * @return true=成功提交, false=请求不存在或已超时
     */
    public static boolean submitResponse(String requestId, String response) {
        CompletableFuture<String> future = pendingRequests.remove(requestId);
        if (future == null) {
            log.warn("[HumanResponse] 找不到请求: requestId={}", requestId);
            return false;
        }
        requestAgentMap.remove(requestId);
        future.complete(response);
        return true;
    }

    /** 获取所有待处理的人类响应请求 */
    public static Map<String, String> getPendingRequests() {
        return Map.copyOf(requestAgentMap);
    }

    /** 输入类型 */
    public static class HumanResponseInput implements ToolInput {
        public String question;
        public java.util.List<String> options;
        public String context;

        @Override
        public String toJson() {
            return gson.toJson(this);
        }
    }
}
