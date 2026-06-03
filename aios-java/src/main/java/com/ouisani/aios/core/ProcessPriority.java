package com.ouisani.aios.core;

/**
 * Process priority levels for AIOS Agent processes, inspired by
 * Windows priority classes and POSIX nice values.
 *
 * <ul>
 *   <li>{@link #REALTIME} — Kernel-level privilege: bypasses cgroup token limits,
 *       never triggers OOM. Reserved for system PIDs (&lt; 100) or "sys_" prefixed agents.</li>
 *   <li>{@link #HIGH} — High priority: scheduled before NORMAL tasks.</li>
 *   <li>{@link #NORMAL} — Default priority for user agents.</li>
 *   <li>{@link #IDLE} — Lowest priority: only runs when no other work is pending.</li>
 * </ul>
 */
public enum ProcessPriority {

    /** Highest privilege: cgroup token limits completely bypassed. */
    REALTIME,

    /** High priority: preferred scheduling over NORMAL. */
    HIGH,

    /** Default priority for user agents. */
    NORMAL,

    /** Lowest priority: background/idle work only. */
    IDLE
}
