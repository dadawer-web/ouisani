package com.ouisani.aios.vfs;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * GUI 动作节点 — AIOS 内核的输入设备（鼠标 + 键盘）。
 * <p>
 * 当用户在前端 dashboard 上点击按钮或在输入框中输入时，事件流经此节点：
 * <ol>
 *   <li>前端通过 WebSocket 将 JSON 动作事件发送到
 *       {@code /ws/gui/action}</li>
 *   <li>{@code SyscallServer} 将事件路由到此节点的
 *       {@link #write(String)} 方法</li>
 *   <li>节点解析目标组件所属的 Agent</li>
 *   <li>节点通过以下方式之一将事件投递给 Agent：
 *       <ul>
 *         <li>入队到 Agent 的消息队列（stdin）</li>
 *         <li>发送 {@code SIGIO} 信号唤醒 Agent</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>动作事件协议</h3>
 * 前端发送的 JSON 动作事件格式：
 * <pre>
 * {
 *   "agentId": "pm_agent",
 *   "action": "click",          // click | type | change | submit | scroll
 *   "componentId": "btn_confirm",
 *   "value": "可选文本",         // 用于 type/change 事件
 *   "timestamp": 1717584000000
 * }
 * </pre>
 *
 * <h3>OS 类比：/dev/input + SIGIO</h3>
 * 在 Linux 中，{@code /dev/input/event0} 接收来自键盘/鼠标的硬件中断，
 * 内核通过 {@code SIGIO} 或 {@code read()} 将它们投递给焦点应用程序。
 * GuiActionNode 是 AIOS 的等价物：它从前端接收 UI 交互事件，
 * 并投递给拥有该组件的 Agent 进程。
 *
 * @see GuiDomNode
 * @see EventBus
 */
public non-sealed class GuiActionNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(GuiActionNode.class);

    // ── VFS Fields ──

    private final String path;
    private int ownerUid;
    private int permissions;

    // ── Action State ──

    /** 待处理的动作队列（agentId → 动作事件队列） */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> pendingActions = new ConcurrentHashMap<>();

    /** 最近一次动作结果（用于 read() 兼容） */
    private volatile String lastResult = "{\"status\":\"idle\"}";

    /** 动作事件订阅者（agentId → 回调函数） */
    private final ConcurrentHashMap<String, Consumer<String>> actionSubscribers = new ConcurrentHashMap<>();

    public GuiActionNode(String path) {
        this(path, 0, 0666);
    }

    public GuiActionNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    // ════════════════════════════════════════════════════════════════
    //  VfsNode Interface
    // ════════════════════════════════════════════════════════════════

    @Override
    public VfsNodeType nodeType() { return VfsNodeType.DEVICE; }

    @Override
    public String path() { return path; }

    @Override
    public int ownerUid() { return ownerUid; }

    @Override
    public void setOwnerUid(int uid) { this.ownerUid = uid; }

    @Override
    public int permissions() { return permissions; }

    @Override
    public void setPermissions(int perms) { this.permissions = perms; }

    // ════════════════════════════════════════════════════════════════
    //  Read: Get pending actions
    // ════════════════════════════════════════════════════════════════

    /**
     * 读取最近一次动作结果（遗留兼容接口）。
     */
    @Override
    public String read() {
        return lastResult;
    }

    /**
     * 读取并排空指定 Agent 的所有待处理动作。
     * <p>
     * 这是"轮询"方式 — Agent 定期调用此方法检查 UI 事件。
     * 对于中断驱动方式，请使用 {@link #subscribe(String, Consumer)}。
     *
     * @param agentId Agent 标识
     * @return JSON 数组，包含所有待处理的动作事件
     */
    public String drainActions(String agentId) {
        ConcurrentLinkedQueue<String> queue = pendingActions.get(agentId);
        if (queue == null || queue.isEmpty()) return "[]";

        List<String> actions = new ArrayList<>();
        String action;
        while ((action = queue.poll()) != null) {
            actions.add(action);
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(actions.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 检查指定 Agent 是否有待处理的动作。
     *
     * @param agentId Agent 标识
     * @return 是否有待处理动作
     */
    public boolean hasPendingActions(String agentId) {
        ConcurrentLinkedQueue<String> queue = pendingActions.get(agentId);
        return queue != null && !queue.isEmpty();
    }

    // ════════════════════════════════════════════════════════════════
    //  Write: Receive action event from frontend
    // ════════════════════════════════════════════════════════════════

    /**
     * 接收来自前端的动作事件。
     * <p>
     * 当用户与 Agent 渲染的 UI 交互时调用此方法。它会：
     * <ol>
     *   <li>将事件入队到 Agent 的动作队列</li>
     *   <li>通过订阅者回调通知 Agent（如果已注册）</li>
     *   <li>向 Agent 进程发送 SIGIO 信号（中断驱动）</li>
     * </ol>
     *
     * @param payload JSON 格式的动作事件
     * @return 是否成功处理
     */
    @Override
    public boolean write(String payload) {
        if (payload == null || payload.isBlank()) return false;

        try {
            String agentId = extractField(payload, "agentId");
            String action = extractField(payload, "action");
            String componentId = extractField(payload, "componentId");

            if (agentId == null || agentId.isBlank()) {
                agentId = "default";
            }

            // Enqueue the action for the agent
            ConcurrentLinkedQueue<String> queue =
                    pendingActions.computeIfAbsent(agentId, k -> new ConcurrentLinkedQueue<>());
            queue.offer(payload);

            // Update last result
            lastResult = String.format(
                    "{\"status\":\"ok\",\"action\":\"%s\",\"componentId\":\"%s\",\"agentId\":\"%s\"}",
                    action != null ? action : "unknown",
                    componentId != null ? componentId : "unknown",
                    agentId);

            log.info("[GuiActionNode] Action received: agent={}, action={}, component={}",
                    agentId, action, componentId);

            // ── Notify the Agent (interrupt-driven) ──

            // 1. Call subscriber callback if registered
            Consumer<String> subscriber = actionSubscribers.get(agentId);
            if (subscriber != null) {
                try {
                    subscriber.accept(payload);
                } catch (Exception e) {
                    log.warn("[GuiActionNode] Subscriber callback error for agent {}: {}",
                            agentId, e.getMessage());
                }
            }

            // 2. Send SIGIO signal to the Agent process
            deliverSignalToAgent(agentId, componentId);

            // 3. Broadcast the action event on EventBus for logging/monitoring
            EventBus.instance().broadcast("ui_action", payload);

            return true;

        } catch (Exception e) {
            log.error("[GuiActionNode] Action processing failed: {}", e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Agent Notification: Signal Delivery
    // ════════════════════════════════════════════════════════════════

    /**
     * 向拥有目标组件的 Agent 进程投递 SIGIO 信号。
     * <p>
     * 这是 AIOS 中内核向进程发送 SIGIO 的等价操作 —
     * 当文件描述符上有 I/O 就绪时唤醒 Agent。
     *
     * @param agentId     Agent 标识
     * @param componentId 目标组件 ID
     */
    private void deliverSignalToAgent(String agentId, String componentId) {
        VfsManager vfs = VfsManager.instance();
        TaskScheduler scheduler = vfs.getTaskScheduler();
        if (scheduler == null) return;

        // Find the agent's task by iterating active tasks
        for (AgentTask task : scheduler.activeTasks().values()) {
            if (agentId.equals(task.payload())) {
                // Send SIGIO signal to the agent
                task.sendSignal(SignalType.SIGIO);
                log.debug("[GuiActionNode] SIGIO sent to agent={} (pid={})", agentId, task.pid());
                return;
            }
        }

        log.debug("[GuiActionNode] No running task found for agent={}", agentId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Subscriber Registration
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册回调 — 当指定 Agent 的 UI 动作事件到达时触发。
     * <p>
     * 这使 Agent 能以中断驱动方式接收 UI 事件，而非轮询。
     *
     * @param agentId  要订阅的 Agent 标识
     * @param callback 动作事件 JSON 的回调函数
     */
    public void subscribe(String agentId, Consumer<String> callback) {
        actionSubscribers.put(agentId, callback);
        log.info("[GuiActionNode] Agent 订阅者已注册: agent={}", agentId);
    }

    /**
     * 取消注册之前注册的订阅者。
     *
     * @param agentId 要取消订阅的 Agent 标识
     */
    public void unsubscribe(String agentId) {
        actionSubscribers.remove(agentId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Utility
    // ════════════════════════════════════════════════════════════════

    private static String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;

        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        } else {
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }
}
