package com.ouisani.aios.core.ipc;

import com.ouisani.aios.core.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 信号拦截器 — AIOS 内核级中断处理工具。
 * <p>
 * 在 LLM 请求或 WASM 执行之前调用，检查并处理当前 Agent 任务中的待处理信号。
 *
 * <h3>OS 类比: Linux Signal Handler / Windows APC</h3>
 * Linux 的信号处理器 (signal handler) 在进程收到信号时被异步调用，
 * Windows 的 APC (Asynchronous Procedure Call) 在线程可 alert 时投递。
 * SignalInterceptor 采用类似模型：在 Agent 的"可中断点"（LLM 调用前）
 * 检查并处理信号，实现异步中断。
 *
 * <h3>信号处理策略：</h3>
 * <ul>
 *   <li>{@link SignalType#SIGTERM} → 抛出 {@link InterruptedException} 终止 Agent</li>
 *   <li>{@link SignalType#SIGUSR1} → 返回系统中断前缀，注入到 Prompt 中</li>
 *   <li>{@link SignalType#SIGINT}  → 抛出 {@link InterruptedException} 优雅中断</li>
 *   <li>{@link SignalType#SIG_CONTEXT_UPDATE} → 由 Agent 的信号处理器自行处理</li>
 * </ul>
 *
 * @see SignalType
 */
public final class SignalInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SignalInterceptor.class);

    /** SIGUSR1 注入前缀：强制 Agent 暂停当前思维并处理中断 */
    public static final String SIGUSR1_PREFIX =
            "[SYSTEM INTERRUPT: You have received a SIGUSR1 signal from the OS. "
            + "You MUST pause your current thought and handle this interrupt immediately!] ";

    private SignalInterceptor() {}

    /**
     * 检查并排空指定 Agent 任务的所有待处理信号。
     *
     * @param task 当前 Agent 任务
     * @return 如果收到 SIGUSR1 则返回 Prompt 前缀，否则返回 null
     * @throws InterruptedException 如果收到 SIGTERM 或 SIGINT
     */
    public static String checkAndDrain(AgentTask task) throws InterruptedException {
        if (task == null || !task.hasPendingSignals()) {
            return null;
        }

        boolean sigusr1Received = false;

        SignalType signal;
        while ((signal = task.pollSignal()) != null) {
            switch (signal) {
                case SIGTERM -> {
                    log.warn("[SignalInterceptor] Agent#{} received SIGTERM, throwing InterruptedException", task.pid());
                    throw new InterruptedException("SIGTERM received by Agent#" + task.pid());
                }
                case SIGINT -> {
                    log.warn("[SignalInterceptor] Agent#{} received SIGINT, throwing InterruptedException", task.pid());
                    throw new InterruptedException("SIGINT received by Agent#" + task.pid());
                }
                case SIGUSR1 -> {
                    log.info("[SignalInterceptor] Agent#{} received SIGUSR1, injecting interrupt prefix", task.pid());
                    sigusr1Received = true;
                }
                case SIG_CONTEXT_UPDATE -> {
                    log.info("[SignalInterceptor] Agent#{} received SIG_CONTEXT_UPDATE — 共享潜意识已变更",
                            task.pid());
                    // SIG_CONTEXT_UPDATE 由 Agent 的信号处理器处理，
                    // 不通过 Prompt 注入。Agent 应在收到此信号后
                    // 检查其 SemanticMemoryBlock。
                }
            }
        }

        return sigusr1Received ? SIGUSR1_PREFIX : null;
    }

    /**
     * 检查待处理信号并返回（可能修改后的）Prompt。
     * 如果 SIGUSR1 待处理，中断前缀会被添加到 Prompt 前面。
     * 如果 SIGTERM/SIGINT 待处理，抛出 InterruptedException。
     *
     * @param task   当前 Agent 任务
     * @param prompt 原始 Prompt
     * @return Prompt，可能带有 SIGUSR1 前缀
     * @throws InterruptedException 如果收到 SIGTERM 或 SIGINT
     */
    public static String interceptPrompt(AgentTask task, String prompt) throws InterruptedException {
        String prefix = checkAndDrain(task);
        if (prefix != null) {
            return prefix + prompt;
        }
        return prompt;
    }

    /**
     * 检查 Agent 是否有待处理的 SIG_CONTEXT_UPDATE 信号。
     * <p>
     * 与 {@link #checkAndDrain(AgentTask)} 不同，此方法<b>不会</b>排空信号队列 —
     * 只做窥探。用于 Agent 的事件循环中检测上下文更新，而不消费其他信号。
     *
     * @param task 当前 Agent 任务
     * @return true 如果 SIG_CONTEXT_UPDATE 待处理
     */
    public static boolean hasContextUpdate(AgentTask task) {
        if (task == null || !task.hasPendingSignals()) {
            return false;
        }
        for (SignalType signal : task.pendingSignals()) {
            if (signal == SignalType.SIG_CONTEXT_UPDATE) {
                return true;
            }
        }
        return false;
    }

    /**
     * 排空所有 SIG_CONTEXT_UPDATE 信号，保留其他信号不变。
     *
     * @param task 当前 Agent 任务
     * @return 排空的 SIG_CONTEXT_UPDATE 信号数量
     */
    public static int drainContextUpdates(AgentTask task) {
        if (task == null) return 0;
        int count = 0;
        SignalType signal;
        while ((signal = task.pollSignal()) != null) {
            if (signal == SignalType.SIG_CONTEXT_UPDATE) {
                count++;
            } else {
                // 将非 CONTEXT_UPDATE 信号放回队列
                task.sendSignal(signal);
            }
        }
        if (count > 0) {
            log.info("[SignalInterceptor] Agent#{} drained {} SIG_CONTEXT_UPDATE signals",
                    task.pid(), count);
        }
        return count;
    }
}
