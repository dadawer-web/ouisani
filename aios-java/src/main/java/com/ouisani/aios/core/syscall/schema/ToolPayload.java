package com.ouisani.aios.core.syscall.schema;

import java.util.Collections;
import java.util.Map;

/**
 * Tool namespace payload — strongly-typed contract for dynamic tool/plugin syscalls.
 * <p>
 * Each tool invocation is identified by name and parameterized by
 * an arbitrary key-value argument map, enabling the AIOS kernel
 * to dispatch to WASM plugins, Docker sandboxes, or any registered
 * tool backend.
 *
 * @param toolName  the registered tool name (e.g. "math", "run_docker")
 * @param arguments the tool arguments as key-value pairs
 */
public record ToolPayload(
        String toolName,
        Map<String, Object> arguments
) implements SyscallPayload {

    public ToolPayload {
        if (toolName == null || toolName.isEmpty()) {
            throw new IllegalArgumentException("Tool payload requires a non-empty toolName");
        }
        arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
    }

    /**
     * Convenience constructor with no arguments.
     */
    public ToolPayload(String toolName) {
        this(toolName, Map.of());
    }

    /**
     * Get an argument value.
     */
    public Object arg(String key) {
        return arguments.get(key);
    }

    /**
     * Get a string argument.
     */
    public String argString(String key) {
        Object v = arguments.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * Get an immutable copy of the arguments.
     */
    public Map<String, Object> args() {
        return Collections.unmodifiableMap(arguments);
    }
}
