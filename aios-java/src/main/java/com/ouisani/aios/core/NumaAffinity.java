package com.ouisani.aios.core;

/**
 * NUMA affinity policy for AIOS Agent scheduling.
 * <p>
 * Controls whether an agent's LLM requests may be routed to remote
 * (expensive/slow) model nodes or must stay on local (cheap/fast) nodes.
 *
 * <ul>
 *   <li>{@link #LOCAL_ONLY} — Only the local/cheap model is allowed.
 *       No cross-node traffic regardless of prompt complexity.</li>
 *   <li>{@link #PREFER_LOCAL} — Prefer the local model; route to remote
 *       only when the prompt exceeds the smart threshold.</li>
 *   <li>{@link #ANY} — Allow cross-node routing to the remote model,
 *       subject to budget constraints.</li>
 * </ul>
 */
public enum NumaAffinity {

    /** Only local/cheap model allowed. No cross-node traffic. */
    LOCAL_ONLY,

    /** Prefer local; route to remote when prompt is complex. */
    PREFER_LOCAL,

    /** Allow cross-node routing, subject to budget. */
    ANY
}
