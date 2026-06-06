package com.ouisani.aios.core.ipc;

import com.ouisani.aios.core.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signal interception utility for AIOS kernel-level interrupt handling.
 *
 * <p>Called before LLM requests or WASM executions to check and act on
 * pending signals in the current agent's task.</p>
 *
 * <ul>
 *   <li>{@link SignalType#SIGTERM} → throws {@link InterruptedException} to kill the agent.</li>
 *   <li>{@link SignalType#SIGUSR1} → returns a system interrupt prefix to inject into the prompt.</li>
 *   <li>{@link SignalType#SIGINT}  → throws {@link InterruptedException} for graceful interruption.</li>
 *   <li>{@link SignalType#SIG_CONTEXT_UPDATE} → returns context update metadata for the agent to process.</li>
 * </ul>
 */
public final class SignalInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SignalInterceptor.class);

    public static final String SIGUSR1_PREFIX =
            "[SYSTEM INTERRUPT: You have received a SIGUSR1 signal from the OS. "
            + "You MUST pause your current thought and handle this interrupt immediately!] ";

    private SignalInterceptor() {}

    /**
     * Check and drain all pending signals for the given agent task.
     *
     * @param task the current agent task
     * @return a prompt prefix if SIGUSR1 was received, otherwise null
     * @throws InterruptedException if SIGTERM or SIGINT was received
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
                    log.info("[SignalInterceptor] Agent#{} received SIG_CONTEXT_UPDATE — shared subconscious has changed",
                            task.pid());
                    // SIG_CONTEXT_UPDATE is handled by the agent's signal handler,
                    // not by prompt injection. The agent should check its
                    // SemanticMemoryBlock after receiving this signal.
                }
            }
        }

        return sigusr1Received ? SIGUSR1_PREFIX : null;
    }

    /**
     * Check pending signals and return a (possibly modified) prompt.
     * If SIGUSR1 is pending, the interrupt prefix is prepended to the prompt.
     * If SIGTERM/SIGINT is pending, throws InterruptedException.
     *
     * @param task   the current agent task
     * @param prompt the original prompt
     * @return the prompt, possibly with a SIGUSR1 prefix prepended
     * @throws InterruptedException if SIGTERM or SIGINT was received
     */
    public static String interceptPrompt(AgentTask task, String prompt) throws InterruptedException {
        String prefix = checkAndDrain(task);
        if (prefix != null) {
            return prefix + prompt;
        }
        return prompt;
    }

    /**
     * Check if the agent has a pending SIG_CONTEXT_UPDATE signal.
     * <p>
     * Unlike {@link #checkAndDrain(AgentTask)}, this method does NOT
     * drain the signal queue — it only peeks. Use this in an agent's
     * event loop to detect context updates without consuming other signals.
     *
     * @param task the current agent task
     * @return true if SIG_CONTEXT_UPDATE is pending
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
     * Drain all SIG_CONTEXT_UPDATE signals from the queue, leaving
     * other signals untouched.
     *
     * @param task the current agent task
     * @return the number of SIG_CONTEXT_UPDATE signals drained
     */
    public static int drainContextUpdates(AgentTask task) {
        if (task == null) return 0;
        int count = 0;
        SignalType signal;
        while ((signal = task.pollSignal()) != null) {
            if (signal == SignalType.SIG_CONTEXT_UPDATE) {
                count++;
            } else {
                // Put non-CONTEXT_UPDATE signals back
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
