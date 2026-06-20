package com.ouisani.aios.core.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Map;
import java.util.UUID;

/**
 * AIOS 标准化事件协议 — 借鉴 CopilotKit 的 AG-UI 协议。
 * <p>
 * 定义了 Agent 推理全生命周期的事件 Schema，覆盖 Run/Step/Message/ToolCall/State 五个层级。
 * 所有事件通过 EventBus 广播，前端通过 SSE/WebSocket 订阅。
 * <p>
 * 事件生命周期：
 * <pre>
 * RUN_STARTED
 *   → STEP_STARTED
 *     → TEXT_MESSAGE_START → TEXT_MESSAGE_CONTENT (delta) → TEXT_MESSAGE_END
 *     → TOOL_CALL_STARTED → TOOL_CALL_COMPLETED
 *   → STEP_FINISHED
 *   → (重复 STEP_* 直到所有步骤完成)
 * → RUN_FINISHED
 * </pre>
 * <p>
 * OS 类比：相当于 Linux 的 strace/ftrace — 将内核内部执行过程以标准化事件流暴露给用户态。
 */
public class AiosEventSchema {

    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * 事件类型枚举 — 覆盖 Agent 推理全生命周期。
     */
    public enum EventType {
        // Run 级（整个查询循环）
        RUN_STARTED,        // 查询循环开始
        RUN_FINISHED,       // 查询循环结束

        // Step 级（每轮 LLM 调用）
        STEP_STARTED,       // 一轮 LLM 调用开始
        STEP_FINISHED,      // 一轮 LLM 调用结束

        // Message 级（LLM 文本输出）
        TEXT_MESSAGE_START, // LLM 开始输出文本
        TEXT_MESSAGE_CONTENT, // LLM 文本 delta（流式）
        TEXT_MESSAGE_END,   // LLM 文本输出结束

        // ToolCall 级（工具调用）
        TOOL_CALL_STARTED,  // 工具调用开始
        TOOL_CALL_COMPLETED, // 工具调用完成

        // State 级（状态变更）
        STATE_SNAPSHOT      // 状态快照
    }

    /**
     * 标准化事件结构。
     *
     * @param eventId   事件唯一 ID
     * @param eventType 事件类型
     * @param agentId   Agent ID
     * @param runId     运行 ID（一次 query() 调用对应一个 runId）
     * @param step      步骤序号（第几轮 LLM 调用）
     * @param timestamp 时间戳（毫秒）
     * @param data      事件数据（类型相关）
     */
    public record AiosEvent(
            String eventId,
            String eventType,
            String agentId,
            String runId,
            int step,
            long timestamp,
            Map<String, Object> data
    ) {
        /** 创建事件实例 */
        public static AiosEvent of(EventType type, String agentId, String runId, int step, Map<String, Object> data) {
            return new AiosEvent(
                    UUID.randomUUID().toString(),
                    type.name(),
                    agentId,
                    runId,
                    step,
                    System.currentTimeMillis(),
                    data
            );
        }

        /** 序列化为 JSON 字符串（用于 EventBus 广播） */
        public String toJson() {
            return gson.toJson(this);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  事件工厂方法 — 快速构建标准化事件
    // ════════════════════════════════════════════════════════════════

    /** RUN_STARTED 事件 */
    public static AiosEvent runStarted(String agentId, String runId, String userMessage) {
        return AiosEvent.of(EventType.RUN_STARTED, agentId, runId, 0,
                Map.of("message", userMessage != null ? userMessage.substring(0, Math.min(userMessage.length(), 200)) : ""));
    }

    /** RUN_FINISHED 事件 */
    public static AiosEvent runFinished(String agentId, String runId, String finalResponse) {
        return AiosEvent.of(EventType.RUN_FINISHED, agentId, runId, 0,
                Map.of("response", finalResponse != null ? finalResponse.substring(0, Math.min(finalResponse.length(), 500)) : ""));
    }

    /** STEP_STARTED 事件 */
    public static AiosEvent stepStarted(String agentId, String runId, int step) {
        return AiosEvent.of(EventType.STEP_STARTED, agentId, runId, step, Map.of());
    }

    /** STEP_FINISHED 事件 */
    public static AiosEvent stepFinished(String agentId, String runId, int step) {
        return AiosEvent.of(EventType.STEP_FINISHED, agentId, runId, step, Map.of());
    }

    /** TEXT_MESSAGE_START 事件 */
    public static AiosEvent textMessageStart(String agentId, String runId, int step) {
        return AiosEvent.of(EventType.TEXT_MESSAGE_START, agentId, runId, step, Map.of());
    }

    /** TEXT_MESSAGE_CONTENT 事件（流式 delta） */
    public static AiosEvent textMessageContent(String agentId, String runId, int step, String delta) {
        return AiosEvent.of(EventType.TEXT_MESSAGE_CONTENT, agentId, runId, step,
                Map.of("delta", delta != null ? delta : ""));
    }

    /** TEXT_MESSAGE_END 事件 */
    public static AiosEvent textMessageEnd(String agentId, String runId, int step, String fullText) {
        return AiosEvent.of(EventType.TEXT_MESSAGE_END, agentId, runId, step,
                Map.of("text", fullText != null ? fullText.substring(0, Math.min(fullText.length(), 500)) : ""));
    }

    /** TOOL_CALL_STARTED 事件 */
    public static AiosEvent toolCallStarted(String agentId, String runId, int step, String toolName, String params) {
        return AiosEvent.of(EventType.TOOL_CALL_STARTED, agentId, runId, step,
                Map.of("tool", toolName,
                       "params", params != null ? params.substring(0, Math.min(params.length(), 300)) : ""));
    }

    /** TOOL_CALL_COMPLETED 事件 */
    public static AiosEvent toolCallCompleted(String agentId, String runId, int step, String toolName, String result, boolean success) {
        return AiosEvent.of(EventType.TOOL_CALL_COMPLETED, agentId, runId, step,
                Map.of("tool", toolName,
                       "result", result != null ? result.substring(0, Math.min(result.length(), 500)) : "",
                       "success", success));
    }

    /** STATE_SNAPSHOT 事件 */
    public static AiosEvent stateSnapshot(String agentId, String runId, int step, Map<String, Object> state) {
        return AiosEvent.of(EventType.STATE_SNAPSHOT, agentId, runId, step, state);
    }

    // ════════════════════════════════════════════════════════════════
    //  EventBus 集成 — 便捷广播方法
    // ════════════════════════════════════════════════════════════════

    /** 通过 EventBus 广播事件 */
    public static void emit(AiosEvent event) {
        EventBus.instance().broadcast("agent.event", event.toJson());
    }

    /** 通过 EventBus 广播事件（自定义通道） */
    public static void emit(String channel, AiosEvent event) {
        EventBus.instance().broadcast(channel, event.toJson());
    }
}
