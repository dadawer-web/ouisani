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
 * GUI Action Node — the AIOS kernel's input device (mouse + keyboard).
 * <p>
 * When a user clicks a button or types into an input on the frontend
 * dashboard, the event flows through this node:
 * <ol>
 *   <li>Frontend sends a JSON action event via WebSocket to
 *       {@code /ws/gui/action}</li>
 *   <li>{@code SyscallServer} routes the event to this node's
 *       {@link #write(String)} method</li>
 *   <li>The node resolves which Agent owns the target component</li>
 *   <li>The node delivers the event to the Agent via one of:
 *       <ul>
 *         <li>Enqueuing to the Agent's message queue (stdin)</li>
 *         <li>Sending a {@code SIGIO} signal to wake the Agent</li>
 *       </ul>
 * </ol>
 * <p>
 * <h3>Action Event Protocol</h3>
 * The frontend sends JSON action events in this format:
 * <pre>
 * {
 *   "agentId": "pm_agent",
 *   "action": "click",          // click | type | change | submit | scroll
 *   "componentId": "btn_confirm",
 *   "value": "optional text",   // for type/change events
 *   "timestamp": 1717584000000
 * }
 * </pre>
 * <p>
 * <h3>OS Analogy: /dev/input + SIGIO</h3>
 * In Linux, {@code /dev/input/event0} receives hardware interrupts from
 * the keyboard/mouse, and the kernel delivers them to the focused
 * application via {@code SIGIO} or {@code read()}. GuiActionNode is
 * the AIOS equivalent: it receives UI interaction events from the
 * frontend and delivers them to the owning Agent process.
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

    /** Pending actions per agent (agentId → queue of action events). */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> pendingActions = new ConcurrentHashMap<>();

    /** Last action result (for read() compatibility). */
    private volatile String lastResult = "{\"status\":\"idle\"}";

    /** Action event subscribers (agentId → callback). */
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
     * Read the last action result (legacy compatibility).
     */
    @Override
    public String read() {
        return lastResult;
    }

    /**
     * Read and drain all pending actions for a specific agent.
     * <p>
     * This is the "polling" approach — the Agent periodically calls
     * this to check for UI events. For interrupt-driven approach,
     * use {@link #subscribe(String, Consumer)}.
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
     * Check if there are pending actions for an agent.
     */
    public boolean hasPendingActions(String agentId) {
        ConcurrentLinkedQueue<String> queue = pendingActions.get(agentId);
        return queue != null && !queue.isEmpty();
    }

    // ════════════════════════════════════════════════════════════════
    //  Write: Receive action event from frontend
    // ════════════════════════════════════════════════════════════════

    /**
     * Write an action event from the frontend.
     * <p>
     * This method is called when the user interacts with the UI
     * rendered by an Agent. It:
     * <ol>
     *   <li>Enqueues the event to the Agent's action queue</li>
     *   <li>Notifies the Agent via subscriber callback (if registered)</li>
     *   <li>Sends SIGIO to the Agent process (interrupt-driven)</li>
     * </ol>
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
     * Deliver a SIGIO signal to the Agent process that owns the
     * target component.
     * <p>
     * This is the AIOS equivalent of the kernel sending SIGIO to
     * a process when I/O is possible on a file descriptor — the
     * Agent is woken up to handle the UI event.
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
     * Register a callback to be invoked when a UI action event
     * arrives for the specified agent.
     * <p>
     * This enables the Agent to receive UI events in an
     * interrupt-driven manner, rather than polling.
     *
     * @param agentId   the agent to subscribe for
     * @param callback  the callback to invoke with the action JSON
     */
    public void subscribe(String agentId, Consumer<String> callback) {
        actionSubscribers.put(agentId, callback);
        log.info("[GuiActionNode] Subscriber registered for agent={}", agentId);
    }

    /**
     * Unregister a previously registered subscriber.
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
