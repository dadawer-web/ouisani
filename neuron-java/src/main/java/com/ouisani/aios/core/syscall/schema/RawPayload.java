package com.ouisani.aios.core.syscall.schema;

import java.util.Collections;
import java.util.Map;

/**
 * 原始/遗留载荷 — 包装无类型 {@code Map<String, Object>}，用于尚未迁移到强类型 schema 的命名空间。
 * <p>
 * 此类在过渡期间为现有 syscall（vfs、handle、coreutils、apt、bin）提供向后兼容。
 * 新代码应优先使用类型化载荷（{@link LlmPayload}、{@link MemoryPayload} 等）。
 * <p>
 * OS 类比: 旧版 ioctl 的 void* 参数——能用但不安全，最终应被淘汰。
 *
 * @param parameters 原始键值参数
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
