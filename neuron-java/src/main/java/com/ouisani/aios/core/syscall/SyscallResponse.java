package com.ouisani.aios.core.syscall;

/**
 * 系统调用响应 — AIOS 内核返回给 Agent 的唯一合法返回值。
 * <p>
 * OS 类比: Linux syscall 的返回值——成功返回数据，失败返回 errno。
 *
 * @param success      syscall 是否成功
 * @param data         响应数据（JSON 字符串、纯文本等）
 * @param errorMessage 失败时的错误描述，成功时为 null
 */
public record SyscallResponse(
        boolean success,
        String data,
        String errorMessage
) {
    /**
     * Create a successful response.
     */
    public static SyscallResponse ok(String data) {
        return new SyscallResponse(true, data, null);
    }

    /**
     * Create a successful response with no data.
     */
    public static SyscallResponse ok() {
        return new SyscallResponse(true, "", null);
    }

    /**
     * Create a failure response.
     */
    public static SyscallResponse fail(String errorMessage) {
        return new SyscallResponse(false, null, errorMessage);
    }

    /**
     * Create a failure response from an exception.
     */
    public static SyscallResponse fail(Throwable e) {
        return new SyscallResponse(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    @Override
    public String toString() {
        if (success) {
            return "SyscallResponse{OK, dataLen=%d}".formatted(data != null ? data.length() : 0);
        }
        return "SyscallResponse{FAIL, error='%s'}".formatted(errorMessage);
    }
}
