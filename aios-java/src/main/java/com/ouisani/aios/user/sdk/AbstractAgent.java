package com.ouisani.aios.user.sdk;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.team.AgentMailbox;
import com.ouisani.aios.core.team.MailMessage;
import com.ouisani.aios.vfs.GuiActionNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Agent 抽象基类 — 实现 Runnable 以兼容 TaskScheduler。
 * <p>
 * 开发者只需继承此类并实现生命周期方法，即可构建一个完整的 Agent。
 * 无需了解底层 Syscall 细节。
 *
 * <h3>核心字段</h3>
 * <ul>
 *   <li>{@code agentId} — 唯一 Agent 标识（如 "sys_init_1"）</li>
 *   <li>{@code priority} — 调度优先级（默认：NORMAL）</li>
 *   <li>{@code tokenBudget} — Token 配额</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * public class MyAgent extends AbstractAgent {
 *     public MyAgent() {
 *         super("my_agent_1", ProcessPriority.NORMAL, 50000);
 *     }
 *
 *     {@literal @}Override
 *     protected void onStart() {
 *         String screen = sdk.readFile(agentId, "/dev/gui/dom");
 *         String answer = sdk.think(agentId, "分析这个: " + screen);
 *     }
 *
 *     {@literal @}Override
 *     protected void onMessage(String msg) {
 *         sdk.think(agentId, "收到: " + msg);
 *     }
 * }
 * </pre>
 */
public abstract class AbstractAgent implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AbstractAgent.class);

    /** SDK 实例 — 子类通过它与 AIOS 内核交互 */
    protected final AiosSdk sdk = AiosSdk.getInstance();

    /** 唯一 Agent 标识 */
    protected final String agentId;

    /** 调度优先级 */
    protected final ProcessPriority priority;

    /** Token 配额 */
    protected final int tokenBudget;

    /** 消息循环的内部消息队列 */
    private final ConcurrentLinkedQueue<String> messageQueue = new ConcurrentLinkedQueue<>();

    // ── Team Mailbox 机制 (Actor Mode) ──
    /** Agent 的私人信箱 — 支持异步消息协作，Virtual Thread 安全阻塞 */
    protected final AgentMailbox mailbox;

    private volatile boolean running = false;
    private int pid = -1;

    // ── Constructor ──

    protected AbstractAgent(String agentId, ProcessPriority priority, int tokenBudget) {
        this.agentId = agentId;
        this.priority = priority != null ? priority : ProcessPriority.NORMAL;
        this.tokenBudget = tokenBudget;
        this.mailbox = new AgentMailbox(agentId);
    }

    // ── Lifecycle (abstract) ──

    /**
     * Agent 启动时调用。实现此方法定义 Agent 的初始化逻辑。
     */
    protected abstract void onStart();

    /**
     * Agent 收到消息时调用（来自其他 Agent、IntentRouter 或消息队列）。
     *
     * @param msg 收到的消息
     */
    protected abstract void onMessage(String msg);

    /** 优雅停止此 Agent */
    public void exit() {
        running = false;
        log.info("[Agent:{}] Exiting", agentId);
        System.out.printf("  ■ [Agent:%s] Exited%n", agentId);
    }

    /** 获取 Agent 的私人信箱 */
    public AgentMailbox getMailbox() {
        return this.mailbox;
    }

    // ════════════════════════════════════════════════════════════════
    //  Team Mailbox: Actor 事件循环 (oh-my-openagent Team Mode)
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动 Actor 事件循环 (Team Mode 核心)。
     * <p>
     * Agent 将在这个循环中常驻，处理任务、回复询问，直到收到死亡药丸。
     * 结合 Java 21 Virtual Threads，阻塞等待时不消耗 OS 线程。
     * <p>
     * 与 {@link #run()} 的区别：
     * <pre>
     *   run()            — 一次性执行模式（DAG 节点调度用）
     *   startEventLoop() — 常驻 Actor 模式（Team 协作用）
     * </pre>
     */
    public void startEventLoop() {
        log.info("[{}] Agent Event Loop started. Listening to mailbox...", this.agentId);
        System.out.printf("  ▶ [Agent:%s] Event Loop started (Team Mode)%n", this.agentId);
        boolean isAlive = true;

        while (isAlive) {
            try {
                // 阻塞等待新邮件（利用 Virtual Threads，此处完全不浪费物理 CPU）
                MailMessage mail = this.mailbox.readNext(1, TimeUnit.DAYS);

                if (mail != null) {
                    switch (mail.getType()) {
                        case TASK_ASSIGN -> {
                            log.info("[{}] Received TASK from {}. Processing...", this.agentId, mail.getSenderId());
                            handleTask(mail.getPayload());
                        }
                        case STATUS_UPDATE -> {
                            log.debug("[{}] Received STATUS_UPDATE from {}.", this.agentId, mail.getSenderId());
                            handleStatusUpdate(mail);
                        }
                        case QUESTION -> {
                            log.info("[{}] Received QUESTION from {}.", this.agentId, mail.getSenderId());
                            handleQuestion(mail);
                        }
                        case REPLY -> {
                            log.info("[{}] Received REPLY from {}.", this.agentId, mail.getSenderId());
                            handleReply(mail);
                        }
                        case POISON_PILL -> {
                            log.info("[{}] Received POISON_PILL. Terminating Agent.", this.agentId);
                            System.out.printf("  ■ [Agent:%s] POISON_PILL received. Shutting down.%n", this.agentId);
                            isAlive = false;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[{}] Event loop interrupted.", this.agentId);
                isAlive = false;
            } catch (Exception e) {
                log.error("[{}] Exception in event loop.", this.agentId, e);
            }
        }

        // 清理信箱
        mailbox.clear();
        log.info("[{}] Agent Event Loop shut down.", this.agentId);
    }

    /**
     * 处理委派任务 — 子类重写实现具体业务逻辑。
     * <p>
     * 当 DAG 引擎通过 TeamRegistry 派发 TaskPayload 时，此方法被调用。
     * 默认回退到原有的 run() 逻辑，执行完毕后自动 complete 回执单。
     * <p>
     * 关键：如果 payload 是 TaskPayload，必须在执行完毕后 complete 回执单，
     * 否则 DAG 引擎会永远阻塞在 completionReceipt.join()。
     */
    protected void handleTask(Object payload) {
        try {
            if (payload instanceof com.ouisani.aios.core.team.TaskPayload taskPayload) {
                log.info("[{}] Executing DAG task: node={}", this.agentId, taskPayload.node().instanceId());
                this.run();
            } else {
                log.warn("[{}] handleTask with unknown payload type. Falling back to run().", this.agentId);
                this.run();
            }
        } finally {
            // 无论成功失败，都必须 complete 回执单，否则 DAG 引擎死锁
            if (payload instanceof com.ouisani.aios.core.team.TaskPayload taskPayload) {
                taskPayload.completionReceipt().complete(null);
            }
        }
    }

    /**
     * 处理状态更新 — 子类可重写。
     */
    protected void handleStatusUpdate(MailMessage mail) {
        log.debug("[{}] Status update from {}: {}", this.agentId, mail.getSenderId(), mail.getPayload());
    }

    /**
     * 处理反向提问 — 子类可重写。
     */
    protected void handleQuestion(MailMessage mail) {
        log.warn("[{}] Unhandled QUESTION from {}: {}", this.agentId, mail.getSenderId(), mail.getPayload());
    }

    /**
     * 处理答复 — 子类可重写。
     */
    protected void handleReply(MailMessage mail) {
        log.debug("[{}] Reply from {}: {}", this.agentId, mail.getSenderId(), mail.getPayload());
    }

    // ── Runnable implementation ──

    /**
     * Agent 主线程入口。调用 onStart() 后进入消息循环。
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
     * 向此 Agent 的消息队列发送消息。消息将在消息循环中由
     * {@link #onMessage(String)} 处理。
     */
    public void sendMessage(String msg) {
        messageQueue.add(msg);
        log.debug("[Agent:{}] Message queued: {}", agentId, msg.substring(0, Math.min(msg.length(), 60)));
    }

    // ── Spawn on TaskScheduler ──

    /** 在 TaskScheduler 上以虚拟线程方式生成此 Agent */
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
     * 渲染 UI 组件树到语义显示服务器。
     * <p>
     * 这是写入帧缓冲区的高级等价操作：Agent 构建一个 Virtual DOM 树，
     * 内核通过 WebSocket 将其推送到所有已连接的前端。
     *
     * @param component Virtual DOM 树的根组件
     */
    protected void renderUI(Map<String, Object> component) {
        String json = componentToJson(component);
        String payload = "{\"type\":\"render\",\"agentId\":\"" + agentId + "\",\"dom\":" + json + "}";
        sdk.writeFile(agentId, "/dev/gui/dom", payload);
        log.debug("[Agent:{}] UI rendered: {} bytes", agentId, json.length());
    }

    /**
     * 对当前 UI 应用增量补丁。
     * <p>
     * 类似脏矩形渲染：只更新变化的组件。
     *
     * @param patch 补丁操作
     */
    protected void patchUI(Map<String, Object> patch) {
        String json = componentToJson(patch);
        String payload = "{\"type\":\"patch\",\"agentId\":\"" + agentId + "\",\"dom\":" + json + "}";
        sdk.writeFile(agentId, "/dev/gui/dom", payload);
    }

    /** 清除此 Agent 渲染的 UI */
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
