package com.ouisani.aios.core.memory;

/**
 * The two deliberately separate memory planes in Neuron.
 *
 * <p>{@link #EXECUTION} is the short-lived, workflow-scoped kernel state
 * ({@code PRIVATE/TASK/TEAM}). {@link #EXPERIENCE_SIDECAR} is Neuron's durable
 * in-process cross-session lifecycle ({@code L0/L1/L2/L3}). Data may cross the boundary
 * only through {@link GovernedMemoryBridge}; a plane is not an implicit
 * fallback for the other plane.</p>
 */
public enum MemoryPlane {
    /** Neuron kernel state used for coordination, recovery and audit. */
    EXECUTION("execution", "PRIVATE/TASK/TEAM", "single workflow or controlled task"),
    /** Long-term experience plane used for search, synthesis and recall. */
    EXPERIENCE_SIDECAR("experience_sidecar", "L0/L1/L2/L3", "cross workflow and cross Agent");

    private final String wireName;
    private final String boundary;
    private final String lifecycle;

    MemoryPlane(String wireName, String boundary, String lifecycle) {
        this.wireName = wireName;
        this.boundary = boundary;
        this.lifecycle = lifecycle;
    }

    public String wireName() {
        return wireName;
    }

    public String boundary() {
        return boundary;
    }

    public String lifecycle() {
        return lifecycle;
    }
}
