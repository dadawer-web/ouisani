package com.ouisani.aios.user.sdk;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.vfs.GuiActionNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Abstract base class for AIOS Agents — implements Runnable for
 * TaskScheduler compatibility.
 * <p>
 * Developers only need to extend this class and implement the lifecycle
 * methods to build a fully functional Agent. No raw Syscall knowledge
 * required.
 * <p>
 * Fields:
 * <ul>
 *   <li>{@code agentId} — unique Agent identifier (e.g. "sys_init_1")</li>
 *   <li>{@code priority} — scheduling priority (default: NORMAL)</li>
 *   <li>{@code tokenBudget} — token quota for this Agent</li>
 * </ul>
 * <p>
 * Example:
 * <pre>
 * public class MyAgent extends AbstractAgent {
 *     public MyAgent() {
 *         super("my_agent_1", ProcessPriority.NORMAL, 50000);
 *     }
 *
 *     {@literal @}Override
 *     protected void onStart() {
 *         String screen = sdk.readFile(agentId, "/dev/gui/dom");
 *         String answer = sdk.think(agentId, "Analyze this: " + screen);
 *     }
 *
 *     {@literal @}Override
 *     protected void onMessage(String msg) {
 *         sdk.think(agentId, "Got: " + msg);
 *     }
 * }
 * </pre>
 */
public abstract class AbstractAgent implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AbstractAgent.class);

    /** The SDK instance — subclasses use this to interact with the AIOS kernel. */
    protected final AiosSdk sdk = AiosSdk.getInstance();

    /** Unique Agent identifier. */
    protected final String agentId;

    /** Scheduling priority. */
    protected final ProcessPriority priority;

    /** Token budget for this Agent. */
    protected final int tokenBudget;

    /** Internal message queue for the message loop. */
    private final ConcurrentLinkedQueue<String> messageQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private int pid = -1;

    // ── Constructor ──

    protected AbstractAgent(String agentId, ProcessPriority priority, int tokenBudget) {
        this.agentId = agentId;
        this.priority = priority != null ? priority : ProcessPriority.NORMAL;
        this.tokenBudget = tokenBudget;
    }

    // ── Lifecycle (abstract) ──

    /**
     * Called when the Agent starts. Implement this to define
     * the Agent's initialization logic.
     */
    protected abstract void onStart();

    /**
     * Called when the Agent receives a message (from another Agent,
     * the Intent Router, or the message queue).
     *
     * @param msg the incoming message
     */
    protected abstract void onMessage(String msg);

    /**
     * Stop this Agent gracefully.
     */
    public void exit() {
        running = false;
        log.info("[Agent:{}] Exiting", agentId);
        System.out.printf("  ■ [Agent:%s] Exited%n", agentId);
    }

    // ── Runnable implementation ──

    /**
     * The Agent's main thread entry point. Calls onStart() and then
     * enters a simple message loop.
     */
    @Override
    public void run() {
        this.running = true;
        log.info("[Agent:{}] Starting (priority={}, budget={})", agentId, priority, tokenBudget);
        System.out.printf("  ▶ [Agent:%s] Starting... (priority=%s, budget=%d)%n", agentId, priority, tokenBudget);

        try {
            onStart();
            log.info("[Agent:{}] onStart() completed", agentId);
        } catch (Exception e) {
            log.error("[Agent:{}] onStart() failed: {}", agentId, e.getMessage(), e);
            System.err.printf("  🚨 [Agent:%s] onStart() failed: %s%n", agentId, e.getMessage());
        }

        // Message loop — process queued messages until stopped
        while (running) {
            String msg = messageQueue.poll();
            if (msg != null) {
                try {
                    onMessage(msg);
                } catch (Exception e) {
                    log.error("[Agent:{}] onMessage() failed: {}", agentId, e.getMessage());
                }
            } else {
                // No messages — sleep briefly to avoid busy-wait
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("[Agent:{}] Message loop ended", agentId);
    }

    // ── Message passing ──

    /**
     * Send a message to this Agent's queue. Will be processed
     * by {@link #onMessage(String)} in the message loop.
     */
    public void sendMessage(String msg) {
        messageQueue.add(msg);
        log.debug("[Agent:{}] Message queued: {}", agentId, msg.substring(0, Math.min(msg.length(), 60)));
    }

    // ── Spawn on TaskScheduler ──

    /**
     * Spawn this Agent as a virtual thread on the TaskScheduler.
     */
    public void spawn(TaskScheduler scheduler) {
        AgentTask task = new AgentTask(
                scheduler.nextPid(),
                AgentTask.TaskStatus.READY,
                "agents",
                "/dev/null",
                "/dev/null",
                java.util.List.of()
        );
        task.setProcessPriority(priority);
        this.pid = task.pid();
        scheduler.spawn(task, this, "/");
    }

    // ── Status ──

    public boolean isRunning() {
        return running;
    }

    public String getAgentId() {
        return agentId;
    }

    public int getPid() {
        return pid;
    }

    public ProcessPriority getPriority() {
        return priority;
    }

    public int getTokenBudget() {
        return tokenBudget;
    }

    // ════════════════════════════════════════════════════════════════
    //  Semantic Display Server: UI Rendering & Action Handling
    // ════════════════════════════════════════════════════════════════

    /**
     * Render a UI component tree to the Semantic Display Server.
     * <p>
     * This is the high-level equivalent of writing to the framebuffer:
     * the Agent constructs a Virtual DOM tree, and the kernel pushes
     * it to all connected frontends via WebSocket.
     * <p>
     * Example:
     * <pre>
     * renderUI(Map.of(
     *     "id", "root",
     *     "type", "container",
     *     "props", Map.of("direction", "column"),
     *     "children", List.of(
     *         Map.of("id", "title", "type", "text", "props", Map.of("value", "Hello AIOS", "style", "heading")),
     *         Map.of("id", "btn_ok", "type", "button", "props", Map.of("label", "OK", "variant", "primary"))
     *     )
     * ));
     * </pre>
     *
     * @param component the root component of the Virtual DOM tree
     */
    protected void renderUI(Map<String, Object> component) {
        String json = componentToJson(component);
        String payload = "{\"type\":\"render\",\"agentId\":\"" + agentId + "\",\"dom\":" + json + "}";
        sdk.writeFile(agentId, "/dev/gui/dom", payload);
        log.debug("[Agent:{}] UI rendered: {} bytes", agentId, json.length());
    }

    /**
     * Apply an incremental patch to the current UI.
     * <p>
     * Like dirty-rect rendering: only update the changed components.
     *
     * @param patch the patch operations
     */
    protected void patchUI(Map<String, Object> patch) {
        String json = componentToJson(patch);
        String payload = "{\"type\":\"patch\",\"agentId\":\"" + agentId + "\",\"dom\":" + json + "}";
        sdk.writeFile(agentId, "/dev/gui/dom", payload);
    }

    /**
     * Clear the UI rendered by this agent.
     */
    protected void clearUI() {
        String payload = "{\"type\":\"render\",\"agentId\":\"" + agentId + "\",\"dom\":{\"id\":\"root\",\"type\":\"container\",\"props\":{},\"children\":[]}}";
        sdk.writeFile(agentId, "/dev/gui/dom", payload);
    }

    /**
     * Register a callback to handle UI action events from the frontend.
     * <p>
     * When a user clicks a button or types into an input, the
     * GuiActionNode receives the event and delivers it to this
     * Agent via the registered callback.
     * <p>
     * This is the AIOS equivalent of setting up a SIGIO handler:
     * the Agent registers interest in UI events and is woken up
     * when they occur.
     *
     * @param callback the callback to invoke with action JSON
     */
    protected void onAction(Consumer<String> callback) {
        GuiActionNode actionNode = resolveActionNode();
        if (actionNode != null) {
            actionNode.subscribe(agentId, callback);
            log.info("[Agent:{}] Action handler registered", agentId);
        }
    }

    /**
     * Poll for pending UI action events (non-blocking).
     * <p>
     * Returns a JSON array of all pending actions, or "[]" if none.
     */
    protected String pollActions() {
        GuiActionNode actionNode = resolveActionNode();
        if (actionNode != null) {
            return actionNode.drainActions(agentId);
        }
        return "[]";
    }

    // ── Utility ──

    private GuiActionNode resolveActionNode() {
        try {
            return VfsManager.instance().resolve("/dev/gui/action")
                    .filter(n -> n instanceof GuiActionNode)
                    .map(n -> (GuiActionNode) n)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String componentToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            sb.append(valueToJson(entry.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String valueToJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Map) return componentToJson((Map<String, Object>) v);
        if (v instanceof java.util.List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                sb.append(valueToJson(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(v.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
