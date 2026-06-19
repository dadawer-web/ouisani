package com.ouisani.aios.core.network;

import com.ouisani.aios.core.ipc.VariablePool;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双向状态同步通道 — 借鉴 CopilotKit 的前端状态与 Agent 状态双向同步。
 * <p>
 * 机制：
 * 1. 前端 → Agent：前端通过 WebSocket 发送 state_update 消息，
 *    写入 VariablePool 的 SESSION 作用域，并广播 state_changed 事件通知 Agent
 * 2. Agent → 前端：Agent 通过 VariablePool.set() 修改状态时，
 *    StateSyncChannel 自动广播 state_snapshot 事件到前端
 * <p>
 * AIOS 的优势：VariablePool.interpolate() 变量插值引擎可以直接在 Prompt 模板中
 * 引用 {{session.xxx}}，这是 CopilotKit 没有的能力。
 * <p>
 * OS 类比：Linux 的 inotify — 文件系统变更自动通知监听者。
 */
public class StateSyncChannel {

    private static final Logger log = LoggerFactory.getLogger(StateSyncChannel.class);
    private static final Gson gson = new Gson();

    /** 单例 */
    private static final StateSyncChannel INSTANCE = new StateSyncChannel();

    /** 已连接的前端会话（sessionId → WebSocket 上下文信息） */
    private final Set<String> connectedSessions = ConcurrentHashMap.newKeySet();

    /** 状态变更监听器（key → 回调列表） */
    private final Map<String, java.util.List<java.util.function.Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    /** EventBus 订阅 ID */
    private String eventBusSubId;

    private StateSyncChannel() {
        // 订阅 Agent 状态变更事件（Agent 通过 EventBus 广播）
        eventBusSubId = EventBus.instance().subscribe("agent.state.update", this::handleAgentStateUpdate);
        log.info("[StateSyncChannel] 双向状态同步通道已启动");
    }

    public static StateSyncChannel instance() {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════
    //  前端 → Agent 方向
    // ════════════════════════════════════════════════════════════════

    /**
     * 处理前端状态更新 — 前端通过 WebSocket 发送 state_update 消息时调用。
     * <p>
     * 将前端状态写入 VariablePool 的 SESSION 作用域，并通知相关 Agent。
     *
     * @param sessionId 前端会话 ID
     * @param key       状态键名
     * @param value     状态值
     */
    public void handleFrontendStateUpdate(String sessionId, String key, Object value) {
        log.debug("[StateSyncChannel] 前端 → Agent: session={}, key={}, value={}",
                sessionId, key, value);

        // 写入 VariablePool SESSION 作用域
        VariablePool.getInstance().set(VariablePool.Scope.SESSION, sessionId, key, value);

        // 广播状态变更事件（通知 Agent 和其他前端）
        JsonObject payload = new JsonObject();
        payload.addProperty("direction", "frontend_to_agent");
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("key", key);
        payload.add("value", gson.toJsonTree(value));
        payload.addProperty("timestamp", System.currentTimeMillis());
        EventBus.instance().broadcast("state_changed", payload.toString());

        // 通知本地监听器
        notifyListeners(key, value);
    }

    // ════════════════════════════════════════════════════════════════
    //  Agent → 前端方向
    // ════════════════════════════════════════════════════════════════

    /**
     * 处理 Agent 状态更新 — Agent 通过 EventBus 广播 agent.state.update 时调用。
     * <p>
     * 将状态写入 VariablePool，并广播到前端。
     */
    private void handleAgentStateUpdate(String payloadJson) {
        log.debug("[StateSyncChannel] Agent → 前端: {}", payloadJson);

        try {
            JsonObject payload = gson.fromJson(payloadJson, JsonObject.class);
            String agentId = payload.get("agentId").getAsString();
            String key = payload.get("key").getAsString();
            Object value = gson.fromJson(payload.get("value"), Object.class);

            // 写入 VariablePool TASK 作用域（Agent 状态属于任务级）
            VariablePool.getInstance().set(VariablePool.Scope.TASK, agentId, key, value);

            // 广播到前端（前端通过 SSE/WebSocket 接收）
            JsonObject frontendPayload = new JsonObject();
            frontendPayload.addProperty("direction", "agent_to_frontend");
            frontendPayload.addProperty("agentId", agentId);
            frontendPayload.addProperty("key", key);
            frontendPayload.add("value", payload.get("value"));
            frontendPayload.addProperty("timestamp", System.currentTimeMillis());
            EventBus.instance().broadcast("state_snapshot", frontendPayload.toString());

        } catch (Exception e) {
            log.error("[StateSyncChannel] 处理 Agent 状态更新失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Agent 主动推送状态（供 Agent 调用）
    // ════════════════════════════════════════════════════════════════

    /**
     * Agent 推送状态到前端 — Agent 修改状态后调用此方法。
     *
     * @param agentId Agent ID
     * @param key     状态键名
     * @param value   状态值
     */
    public static void pushAgentState(String agentId, String key, Object value) {
        JsonObject payload = new JsonObject();
        payload.addProperty("agentId", agentId);
        payload.addProperty("key", key);
        payload.add("value", gson.toJsonTree(value));
        payload.addProperty("timestamp", System.currentTimeMillis());
        EventBus.instance().broadcast("agent.state.update", payload.toString());
    }

    // ════════════════════════════════════════════════════════════════
    //  状态监听（供 Agent 注册回调）
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册状态变更监听器 — 当指定 key 的状态变更时回调。
     *
     * @param key      状态键名（null 表示监听所有变更）
     * @param callback 回调函数
     */
    public void addStateListener(String key, java.util.function.Consumer<Object> callback) {
        String mapKey = key != null ? key : "*";
        listeners.computeIfAbsent(mapKey, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(callback);
        log.debug("[StateSyncChannel] 状态监听器已注册: key='{}'", mapKey);
    }

    /** 移除状态变更监听器 */
    public void removeStateListener(String key, java.util.function.Consumer<Object> callback) {
        String mapKey = key != null ? key : "*";
        java.util.List<java.util.function.Consumer<Object>> list = listeners.get(mapKey);
        if (list != null) {
            list.remove(callback);
        }
    }

    private void notifyListeners(String key, Object value) {
        // 通知特定 key 的监听器
        java.util.List<java.util.function.Consumer<Object>> keyListeners = listeners.get(key);
        if (keyListeners != null) {
            for (var cb : keyListeners) {
                try { cb.accept(value); } catch (Exception e) {
                    log.warn("[StateSyncChannel] 监听器回调异常: {}", e.getMessage());
                }
            }
        }
        // 通知通配符监听器
        java.util.List<java.util.function.Consumer<Object>> wildcardListeners = listeners.get("*");
        if (wildcardListeners != null) {
            for (var cb : wildcardListeners) {
                try { cb.accept(value); } catch (Exception e) {
                    log.warn("[StateSyncChannel] 通配符监听器回调异常: {}", e.getMessage());
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  会话管理
    // ════════════════════════════════════════════════════════════════

    /** 前端会话连接 */
    public void sessionConnected(String sessionId) {
        connectedSessions.add(sessionId);
        log.info("[StateSyncChannel] 前端会话已连接: {} (共 {} 个)", sessionId, connectedSessions.size());
    }

    /** 前端会话断开 */
    public void sessionDisconnected(String sessionId) {
        connectedSessions.remove(sessionId);
        log.info("[StateSyncChannel] 前端会话已断开: {} (剩余 {} 个)", sessionId, connectedSessions.size());
    }

    /** 获取已连接的前端会话数 */
    public int getConnectedSessionCount() {
        return connectedSessions.size();
    }

    /**
     * 获取会话的完整状态快照 — 前端连接时可以请求一次完整快照。
     */
    public String getSessionSnapshot(String sessionId) {
        // 从 VariablePool 读取 SESSION 作用域的所有变量
        // VariablePool 没有直接遍历的方法，我们通过 EventBus 广播一个快照请求
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("sessionId", sessionId);
        snapshot.addProperty("timestamp", System.currentTimeMillis());
        snapshot.addProperty("type", "session_snapshot");

        // 读取已知的常见状态键
        String[] commonKeys = {"current_task", "workflow_id", "agent_status", "user_preferences"};
        JsonObject state = new JsonObject();
        for (String key : commonKeys) {
            Object val = VariablePool.getInstance().get(VariablePool.Scope.SESSION, sessionId, key);
            if (val != null) {
                state.add(key, gson.toJsonTree(val));
            }
        }
        snapshot.add("state", state);

        return snapshot.toString();
    }
}
