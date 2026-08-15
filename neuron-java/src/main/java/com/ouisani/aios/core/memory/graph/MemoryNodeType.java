package com.ouisani.aios.core.memory.graph;

import java.util.Locale;

/**
 * Typed Memory Graph V2 node kinds.
 *
 * <p>The vocabulary deliberately describes software-agent evidence rather
 * than robot-specific objects: a repository/workspace is a PLACE, a tool
 * call or recovery is an EVENT, and a generated file is an ARTIFACT.</p>
 */
public enum MemoryNodeType {
    ENTITY,
    EVENT,
    PLACE,
    SESSION,
    EVIDENCE,
    ARTIFACT,
    DECISION;

    /** Parse JSON/configuration values without making callers care about case. */
    public static MemoryNodeType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("memory node type must not be blank");
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return valueOf(normalized);
    }
}
