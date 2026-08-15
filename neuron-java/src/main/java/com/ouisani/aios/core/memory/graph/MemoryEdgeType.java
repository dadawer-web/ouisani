package com.ouisani.aios.core.memory.graph;

import java.util.Locale;

/**
 * Typed Memory Graph V2 relationship kinds.
 *
 * <p>Edges are intentionally explicit.  In particular, temporal and
 * evidentiary relations are not collapsed into a generic semantic edge, so a
 * retriever can preserve why two facts are connected.</p>
 */
public enum MemoryEdgeType {
    OBSERVED_IN,
    LOCATED_AT,
    BEFORE,
    AFTER,
    SAME_IDENTITY,
    PRODUCED_BY,
    SUPERSEDES,
    SUPPORTS,
    CONTRADICTS;

    /** Parse JSON/configuration values without making callers care about case. */
    public static MemoryEdgeType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("memory edge type must not be blank");
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return valueOf(normalized);
    }
}
