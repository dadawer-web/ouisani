package com.ouisani.aios.core.syscall.schema;

import java.util.Collections;
import java.util.Map;

/**
 * Raw/legacy payload — wraps an untyped {@code Map<String, Object>} for
 * namespaces that have not yet been migrated to a strongly-typed schema.
 * <p>
 * This exists for backward compatibility with existing syscalls (vfs, handle,
 * coreutils, apt, bin) during the transition period. New code should prefer
 * the typed payloads ({@link LlmPayload}, {@link MemoryPayload}, etc.).
 *
 * @param parameters the raw key-value parameters
 */
public record RawPayload(
        Map<String, Object> parameters
) implements SyscallPayload {

    public RawPayload {
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
    }

    /**
     * Get a parameter value, returning null if absent.
     */
    public Object param(String key) {
        return parameters.get(key);
    }

    /**
     * Get a parameter value with a default.
     */
    public Object param(String key, Object defaultValue) {
        return parameters.getOrDefault(key, defaultValue);
    }

    /**
     * Get a string parameter.
     */
    public String paramString(String key) {
        Object v = parameters.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * Get an integer parameter.
     */
    public int paramInt(String key, int defaultValue) {
        Object v = parameters.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    /**
     * Get an immutable copy of the parameters.
     */
    public Map<String, Object> params() {
        return Collections.unmodifiableMap(parameters);
    }
}
