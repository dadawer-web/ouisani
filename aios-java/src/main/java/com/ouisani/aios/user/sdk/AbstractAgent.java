package com.ouisani.aios.user.sdk;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

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
}
