package com.ouisani.aios.core.syscall;

/**
 * A strongly-typed system call response — the sole legal return value
 * from the AIOS kernel to an Agent.
 *
 * @param success      whether the syscall succeeded
 * @param data         the response data (JSON string, plain text, etc.)
 * @param errorMessage error description if success is false, null otherwise
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
