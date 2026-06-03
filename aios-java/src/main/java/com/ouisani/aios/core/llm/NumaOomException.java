package com.ouisani.aios.core.llm;

/**
 * Thrown when an agent with {@link com.ouisani.aios.core.NumaAffinity#ANY}
 * attempts to route to a remote (expensive) LLM node but has insufficient
 * budget for cross-node execution.
 */
public class NumaOomException extends RuntimeException {

    private final int budget;
    private final int required;

    public NumaOomException(int budget, int required) {
        super("NUMA OOM: cross-node routing denied. Budget=" + budget
                + ", Required>100 for remote node access");
        this.budget = budget;
        this.required = required;
    }

    public int budget() {
        return budget;
    }

    public int required() {
        return required;
    }
}
