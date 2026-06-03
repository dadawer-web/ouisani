package com.ouisani.aios.core.syscall;

import java.util.Collections;
import java.util.Map;

/**
 * A strongly-typed system call request — the sole legal credential
 * for an Agent to interact with the AIOS kernel.
 *
 * @param action     the syscall action (e.g. "llm.think", "vfs.read", "tool.run")
 * @param parameters the action parameters as key-value pairs
 */
public record SyscallRequest(
        String action,
        Map<String, Object> parameters
) {
    public SyscallRequest {
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
    }

    /**
     * Convenience constructor for a single parameter.
     */
    public SyscallRequest(String action, String key, Object value) {
        this(action, Map.of(key, value));
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
