package com.ouisani.aios.core.syscall.schema;

import java.util.Collections;
import java.util.Map;

/**
 * Tool 命名空间载荷 — 动态工具/插件 syscall 的强类型契约。
 * <p>
 * 每次工具调用通过名称标识，以任意键值对参数化，
 * 使 AIOS 内核能够分发到 WASM 插件、Docker 沙箱或任何已注册的工具后端。
 * <p>
 * OS 类比: ioctl(fd, request, args) 的参数结构体。
 *
 * @param toolName  已注册的工具名称（如 "math"、"run_docker"）
 * @param arguments 工具参数键值对
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
