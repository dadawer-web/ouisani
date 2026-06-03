package com.ouisani.aios.core.ipc;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
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
}
