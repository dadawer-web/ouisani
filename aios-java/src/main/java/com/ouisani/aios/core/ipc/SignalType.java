package com.ouisani.aios.core.ipc;

/**
 * Semantic POSIX signal types for AIOS Agent processes.
 *
 * <ul>
 *   <li>{@link #SIGTERM} — Terminate the target agent immediately.</li>
 *   <li>{@link #SIGINT}  — Interrupt the current operation (graceful).</li>
 *   <li>{@link #SIGUSR1} — Inject a high-priority system interrupt into the
 *         agent's next LLM prompt, forcing it to pause and handle the signal.</li>
 * </ul>
 */
public enum SignalType {

    /** Terminate: force-kill the agent (throws InterruptedException). */
    SIGTERM,

    /** Interrupt: graceful interruption of the current operation. */
    SIGINT,

    /** User-defined signal 1: injects a system interrupt into the next prompt. */
    SIGUSR1
}
