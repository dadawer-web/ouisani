package com.ouisani.aios.core.telemetry;

/**
 * A single ETW event record.
 * Immutable, zero-allocation on write path (reuses slots in the ring buffer).
 */
public record EventRecord(
        long timestamp,
        String component,
        String eventType,
        String payload
) {
    @Override
    public String toString() {
        return "[%s] [%s] %d | %s".formatted(component, eventType, timestamp, payload);
    }
}
