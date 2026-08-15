package com.ouisani.aios.core.memory.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * An immutable Typed Memory Graph V2 edge.
 *
 * <p>Edges do not need a separately assigned id in the wire schema.  {@link
 * #id()} deterministically derives one from the endpoints, relation and
 * validity interval, allowing an adjacency map to replace a newer observation
 * without introducing duplicate relationships.</p>
 */
public record MemoryEdge(
        String sourceId,
        String targetId,
        MemoryEdgeType type,
        double confidence,
        List<String> evidenceIds,
        Long validFrom,
        Long validTo) {

    public MemoryEdge {
        sourceId = required(sourceId, "sourceId");
        targetId = required(targetId, "targetId");
        type = Objects.requireNonNull(type, "type must not be null");
        confidence = normalizeConfidence(confidence);
        evidenceIds = normalizeEvidenceIds(evidenceIds);
        validFrom = normalizeTimestamp(validFrom, "validFrom");
        validTo = normalizeTimestamp(validTo, "validTo");
        if (validFrom != null && validTo != null && validTo < validFrom) {
            throw new IllegalArgumentException("validTo must be >= validFrom");
        }
    }

    /** Convenience constructor for an unbounded, fully trusted relation. */
    public MemoryEdge(String sourceId, String targetId, MemoryEdgeType type) {
        this(sourceId, targetId, type, 1.0, List.of(), null, null);
    }

    /** Stable logical key used by the adjacency index and persistence layer. */
    public String id() {
        return sourceId + "|" + type.name() + "|" + targetId + "|"
                + (validFrom == null ? "" : validFrom) + "|"
                + (validTo == null ? "" : validTo);
    }

    public boolean isValidAt(long epochMillis) {
        return (validFrom == null || epochMillis >= validFrom)
                && (validTo == null || epochMillis <= validTo);
    }

    public MemoryEdge withEvidenceIds(List<String> nextEvidenceIds) {
        return new MemoryEdge(sourceId, targetId, type, confidence,
                nextEvidenceIds, validFrom, validTo);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static List<String> normalizeEvidenceIds(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) unique.add(value.trim());
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    private static Long normalizeTimestamp(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return value;
    }

    private static double normalizeConfidence(double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
        return value;
    }
}
