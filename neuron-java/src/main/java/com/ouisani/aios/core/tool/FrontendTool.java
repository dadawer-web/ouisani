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
 * 前端工具 — 借鉴 CopilotKit 的 Frontend Tool 机制。
 * <p>
 * Agent 可以调用浏览器端注册的函数，handler 在前端执行，结果返回给 Agent。
 * 前端是 Agent 运行时的一部分。
 * <p>
 * 机制：
 * 1. 前端连接时通过 WebSocket 注册可用的前端工具（名称 + Schema）
 * 2. Agent 调用前端工具时，通过 EventBus 广播 frontend_tool_call 事件
 * 3. 前端执行 handler，通过 WebSocket 回传结果
 * 4. 工具的 CompletableFuture 被 complete，Agent 继续执行
 * <p>
 * OS 类比：Linux 的 RPC（远程过程调用）— 本地进程可以调用远程节点上的函数，
 * 调用方无需知道函数在哪里执行。
 */
public class FrontendTool implements Tool<FrontendTool.FrontendToolInput> {

    private static final Logger log = LoggerFactory.getLogger(FrontendTool.class);
    private static final Gson gson = new Gson();

    /** 等待前端响应的超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 30;

    /** 待处理的前端工具调用（callId → CompletableFuture） */
    private static final ConcurrentHashMap<String, CompletableFuture<String>> pendingCalls = new ConcurrentHashMap<>();

    /** 已注册的前端工具（toolName → schema） */
    private static final ConcurrentHashMap<String, String> registeredTools = new ConcurrentHashMap<>();

    @Override
    public String name() { return "frontend_tool"; }

    @Override
    public String description() {
        return "调用前端（浏览器）注册的工具。前端工具在用户的浏览器中执行，"
                + "可以访问浏览器能力（如文件选择、剪贴板、地理位置、DOM 操作等）。"
                + "可用的前端工具列表会动态更新。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "tool_name": {
                            "type": "string",
                            "description": "要调用的前端工具名称"
                        },
                        "params": {
                            "type": "object",
                            "description": "传递给前端工具的参数"
                        }
                    },
                    "required": ["tool_name"]
                }
                """;
    }

    @Override
    public ToolOutput call(FrontendToolInput input, ToolContext context) {
        String callId = UUID.randomUUID().toString();
        String agentId = context.agentId();

        log.info("[FrontendTool] Agent '{}' 调用前端工具: callId={}, tool='{}'",
                agentId, callId, input.tool_name);

        // 检查前端工具是否已注册
        if (!registeredTools.containsKey(input.tool_name)) {
            log.warn("[FrontendTool] 前端工具 '{}' 未注册，已注册: {}", input.tool_name, registeredTools.keySet());
            return ToolOutput.fail("前端工具 '" + input.tool_name + "' 未注册。可用工具: " + registeredTools.keySet());
        }

        // 创建 CompletableFuture
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingCalls.put(callId, future);

        // 构建事件 payload
        JsonObject payload = new JsonObject();
        payload.addProperty("callId", callId);
        payload.addProperty("agentId", agentId);
        payload.addProperty("toolName", input.tool_name);
        if (input.params != null) {
            payload.add("params", gson.toJsonTree(input.params));
        }
        payload.addProperty("timestamp", System.currentTimeMillis());

        // 广播到前端
        EventBus.instance().broadcast("frontend_tool_call", payload.toString());

        // 阻塞等待前端响应
        try {
            String result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("[FrontendTool] 收到前端响应: callId={}, tool='{}'", callId, input.tool_name);
            return ToolOutput.ok(result);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[FrontendTool] 等待前端响应超时（{}秒）: callId={}, tool='{}'",
                    TIMEOUT_SECONDS, callId, input.tool_name);
            pendingCalls.remove(callId);
            return ToolOutput.ok("前端工具 '" + input.tool_name + "' 响应超时");
        } catch (Exception e) {
            log.error("[FrontendTool] 等待前端响应异常: callId={}, error={}", callId, e.getMessage());
            pendingCalls.remove(callId);
            return ToolOutput.fail("前端工具调用异常: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  前端注册和回调入口 — 供 AppGateway 调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册前端工具 — 前端连接时调用。
     */
    public static void registerFrontendTool(String toolName, String schema) {
        registeredTools.put(toolName, schema != null ? schema : "{}");
        log.info("[FrontendTool] 前端工具已注册: '{}' (schema: {} chars)", toolName,
                schema != null ? schema.length() : 0);
    }

    /**
     * 注销前端工具 — 前端断开时调用。
     */
    public static void unregisterFrontendTool(String toolName) {
        registeredTools.remove(toolName);
        log.info("[FrontendTool] 前端工具已注销: '{}'", toolName);
    }

    /**
     * 提交前端工具调用结果 — 前端执行完成后调用。
     *
     * @param callId 调用 ID
     * @param result 执行结果
     * @return true=成功提交, false=调用不存在或已超时
     */
    public static boolean submitResult(String callId, String result) {
        CompletableFuture<String> future = pendingCalls.remove(callId);
        if (future == null) {
            log.warn("[FrontendTool] 找不到调用: callId={}", callId);
            return false;
        }
        future.complete(result);
        return true;
    }

    /** 获取所有已注册的前端工具 */
    public static Map<String, String> getRegisteredTools() {
        return Map.copyOf(registeredTools);
    }

    /** 输入类型 */
    public static class FrontendToolInput implements ToolInput {
        public String tool_name;
        public Map<String, Object> params;

        @Override
        public String toJson() {
            return gson.toJson(this);
        }
    }
}
