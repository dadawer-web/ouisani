package com.ouisani.aios.core.memory.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable Typed Memory Graph V2 node.
 *
 * <p>The record is intentionally storage-neutral.  {@link
 * TypedMemoryGraphStore} serializes it into the existing {@code
 * VersionedMemoryStore} and mirrors the latest JSON into VFS.  Keeping this
 * object immutable means a retrieved evidence subgraph cannot be changed by a
 * caller while it is being used to ground an answer.</p>
 *
 * @param id           stable logical node id within a graph scope
 * @param type         semantic node type
 * @param summary      compact human/LLM-readable evidence summary
 * @param validFrom    inclusive epoch-millis lower bound, or {@code null}
 * @param validTo      inclusive epoch-millis upper bound, or {@code null}
 * @param sourceRef    source pointer (VFS path, trace id, commit, etc.)
 * @param confidence   confidence in [0, 1]
 * @param provenance   structured source/production metadata
 * @param tenant       tenant boundary, when applicable
 * @param visibility   read boundary
 * @param schemaVersion schema version; V2 is the current version
 */
public record MemoryNode(
        String id,
        MemoryNodeType type,
        String summary,
        Long validFrom,
        Long validTo,
        String sourceRef,
        double confidence,
        Map<String, Object> provenance,
        String tenant,
        MemoryVisibility visibility,
        int schemaVersion) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public MemoryNode {
        id = required(id, "id");
        type = Objects.requireNonNull(type, "type must not be null");
        summary = summary == null ? "" : summary;
        validFrom = normalizeTimestamp(validFrom, "validFrom");
        validTo = normalizeTimestamp(validTo, "validTo");
        if (validFrom != null && validTo != null && validTo < validFrom) {
            throw new IllegalArgumentException("validTo must be >= validFrom");
        }
        sourceRef = clean(sourceRef);
        confidence = normalizeConfidence(confidence);
        provenance = immutableMap(provenance);
        tenant = clean(tenant);
        visibility = visibility == null ? MemoryVisibility.PRIVATE : visibility;
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
    }

    /** Minimal node constructor for callers that do not yet have timestamps. */
    public MemoryNode(String id, MemoryNodeType type, String summary) {
        this(id, type, summary, null, null, null, 1.0, Map.of(), null,
                MemoryVisibility.PRIVATE, CURRENT_SCHEMA_VERSION);
    }

    /** Common constructor for a software-agent fact with source and scope. */
    public MemoryNode(String id, MemoryNodeType type, String summary,
                      String sourceRef, double confidence,
                      Map<String, Object> provenance, String tenant,
                      MemoryVisibility visibility) {
        this(id, type, summary, null, null, sourceRef, confidence, provenance,
                tenant, visibility, CURRENT_SCHEMA_VERSION);
    }

    /** Create a node whose validity starts now. */
    public static MemoryNode now(String id, MemoryNodeType type, String summary,
                                 String sourceRef, double confidence,
                                 Map<String, Object> provenance, String tenant,
                                 MemoryVisibility visibility) {
        return new MemoryNode(id, type, summary, System.currentTimeMillis(), null,
                sourceRef, confidence, provenance, tenant, visibility,
                CURRENT_SCHEMA_VERSION);
    }

    public boolean isValidAt(long epochMillis) {
        return (validFrom == null || epochMillis >= validFrom)
                && (validTo == null || epochMillis <= validTo);
    }

    public MemoryNode withValidity(Long nextValidFrom, Long nextValidTo) {
        return new MemoryNode(id, type, summary, nextValidFrom, nextValidTo,
                sourceRef, confidence, provenance, tenant, visibility, schemaVersion);
    }

    public MemoryNode withSummary(String nextSummary) {
        return new MemoryNode(id, type, nextSummary, validFrom, validTo,
                sourceRef, confidence, provenance, tenant, visibility, schemaVersion);
    }

    public MemoryNode withProvenance(Map<String, Object> nextProvenance) {
        return new MemoryNode(id, type, summary, validFrom, validTo, sourceRef,
                confidence, nextProvenance, tenant, visibility, schemaVersion);
    }

    private static String required(String value, String name) {
        String normalized = clean(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = required(entry.getKey(), "provenance key");
            copy.put(key, freeze(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) continue;
                copy.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) copy.add(freeze(element));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof java.util.Set<?> set) {
            List<Object> copy = new ArrayList<>(set.size());
            for (Object element : set) copy.add(freeze(element));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
