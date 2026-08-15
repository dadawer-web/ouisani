package com.ouisani.aios.core.memory;

/** Result of a governed bridge operation; denied operations carry no value. */
public record MemoryBridgeResult<T>(MemoryBridgeDecision decision, T value) {

    public boolean allowed() {
        return decision != null && decision.allowed();
    }

    public boolean hasValue() {
        return value != null;
    }
}
