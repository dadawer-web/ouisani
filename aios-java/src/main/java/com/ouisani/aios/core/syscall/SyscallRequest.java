package com.ouisani.aios.core.syscall;

import com.ouisani.aios.core.syscall.schema.LlmPayload;
import com.ouisani.aios.core.syscall.schema.RawPayload;
import com.ouisani.aios.core.syscall.schema.SyscallPayload;

import java.util.Map;

/**
 * A strongly-typed system call request — the sole legal credential
 * for an Agent to interact with the AIOS kernel.
 * <p>
 * Follows the POSIX convention of {@code namespace.action} for syscall
 * identification, with a strongly-typed {@link SyscallPayload} replacing
 * the former untyped {@code Map<String, Object>}.
 *
 * @param namespace the syscall namespace (e.g. "llm", "memory", "storage", "tool", "vfs")
 * @param action    the action within the namespace (e.g. "think", "store", "read")
 * @param payload   the strongly-typed syscall payload
 */
public record SyscallRequest(
        String namespace,
        String action,
        SyscallPayload payload
) {
    /**
     * Canonical constructor with validation.
     */
    public SyscallRequest {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("Syscall namespace must not be null or empty");
        }
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("Syscall action must not be null or empty");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Syscall payload must not be null");
        }
    }

    /**
     * Construct from a dot-notation full action and a typed payload.
     * <p>
     * Example: {@code new SyscallRequest("llm.think", new LlmPayload("hello"))}
     *
     * @param fullAction the full action in "namespace.action" format
     * @param payload    the typed syscall payload
     */
    public SyscallRequest(String fullAction, SyscallPayload payload) {
        this(extractNamespace(fullAction), extractAction(fullAction), payload);
    }

    /**
     * Backward-compatible constructor: dot-notation action + raw Map parameters.
     * <p>
     * Wraps the Map in a {@link RawPayload} for compatibility with
     * legacy syscalls that have not yet migrated to typed payloads.
     *
     * @param fullAction the full action in "namespace.action" format
     * @param parameters the action parameters as key-value pairs
     */
    public SyscallRequest(String fullAction, Map<String, Object> parameters) {
        this(fullAction, new RawPayload(parameters));
    }

    /**
     * Get the full action string in "namespace.action" format.
     * <p>
     * This is the POSIX-equivalent syscall number — a unique identifier
     * that the kernel dispatcher uses for routing.
     */
    public String fullAction() {
        return namespace + "." + action;
    }

    // ── Legacy convenience methods (delegate to RawPayload) ──

    /**
     * Get a parameter value from the payload.
     * Only works with {@link RawPayload}; returns null for typed payloads.
     */
    public Object param(String key) {
        if (payload instanceof RawPayload raw) {
            return raw.param(key);
        }
        return null;
    }

    /**
     * Get a parameter value with a default.
     * Only works with {@link RawPayload}; returns the default for typed payloads.
     */
    public Object param(String key, Object defaultValue) {
        if (payload instanceof RawPayload raw) {
            return raw.param(key, defaultValue);
        }
        return defaultValue;
    }

    /**
     * Get a string parameter.
     * Only works with {@link RawPayload}; returns null for typed payloads.
     */
    public String paramString(String key) {
        if (payload instanceof RawPayload raw) {
            return raw.paramString(key);
        }
        return null;
    }

    /**
     * Get an integer parameter.
     * Only works with {@link RawPayload}; returns the default for typed payloads.
     */
    public int paramInt(String key, int defaultValue) {
        if (payload instanceof RawPayload raw) {
            return raw.paramInt(key, defaultValue);
        }
        return defaultValue;
    }

    /**
     * Get an immutable copy of the raw parameters.
     * Only works with {@link RawPayload}; returns empty map for typed payloads.
     */
    public Map<String, Object> params() {
        if (payload instanceof RawPayload raw) {
            return raw.params();
        }
        return Map.of();
    }

    // ── Helpers ──

    private static String extractNamespace(String fullAction) {
        if (fullAction == null || fullAction.isEmpty()) {
            throw new IllegalArgumentException("Full action must not be null or empty");
        }
        int dot = fullAction.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException(
                    "Full action must be in 'namespace.action' format, got: " + fullAction);
        }
        return fullAction.substring(0, dot);
    }

    private static String extractAction(String fullAction) {
        if (fullAction == null || fullAction.isEmpty()) {
            throw new IllegalArgumentException("Full action must not be null or empty");
        }
        int dot = fullAction.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException(
                    "Full action must be in 'namespace.action' format, got: " + fullAction);
        }
        return fullAction.substring(dot + 1);
    }
}
