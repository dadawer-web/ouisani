package com.ouisani.aios.core.syscall;

import com.ouisani.aios.core.syscall.schema.LlmPayload;
import com.ouisani.aios.core.syscall.schema.RawPayload;
import com.ouisani.aios.core.syscall.schema.SyscallPayload;

import java.util.Map;

/**
 * 系统调用请求 — Agent 与 AIOS 内核交互的唯一合法凭证。
 * <p>
 * 遵循 POSIX 的 {@code namespace.action} 约定进行 syscall 标识，
 * 使用强类型 {@link SyscallPayload} 替代了原先的无类型 {@code Map<String, Object>}。
 * <p>
 * OS 类比: Linux 的 syscall(number, args...)，namespace.action 等同于 syscall 编号，
 * payload 等同于 syscall 参数结构体。
 * <p>
 * <b>幂等性</b>：写操作应携带 {@link #idempotencyKey}，由 {@link IdempotencyLedger}
 * 去重；{@link #readSafe} 标记无副作用读操作以允许 {@link SyscallRetryPolicy} 自由重试。
 *
 * @param namespace       syscall 命名空间（如 "llm"、"memory"、"storage"、"tool"、"vfs"）
 * @param action          命名空间内的操作（如 "think"、"store"、"read"）
 * @param payload         强类型 syscall 载荷
 * @param idempotencyKey  幂等键（可选，写操作必填）；同 key 重复提交仅执行一次
 * @param readSafe        标记为可安全重试的读操作（无副作用）；默认 false
 */
public record SyscallRequest(
        String namespace,
        String action,
        SyscallPayload payload,
        String idempotencyKey,
        boolean readSafe
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
     * Backward-compatible canonical constructor: namespace + action + payload.
     * <p>
     * 等价于 idempotencyKey=null、readSafe=false，使所有旧调用点源码兼容。
     */
    public SyscallRequest(String namespace, String action, SyscallPayload payload) {
        this(namespace, action, payload, null, false);
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
        this(extractNamespace(fullAction), extractAction(fullAction), payload, null, false);
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

    // ── 幂等性 wither（record 不可变拷贝）──

    /** 返回携带幂等键的新请求拷贝。用于写操作在调用点显式注入 key。 */
    public SyscallRequest withIdempotencyKey(String key) {
        return new SyscallRequest(namespace, action, payload, key, readSafe);
    }

    /** 返回标记为可安全重试读的新请求拷贝。 */
    public SyscallRequest withReadSafe(boolean readSafe) {
        return new SyscallRequest(namespace, action, payload, idempotencyKey, readSafe);
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
