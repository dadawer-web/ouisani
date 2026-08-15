package com.ouisani.aios.core.syscall;

/**
 * 系统调用响应 — AIOS 内核返回给 Agent 的唯一合法返回值。
 * <p>
 * OS 类比: Linux syscall 的返回值——成功返回数据，失败返回 errno。
 * <p>
 * <b>结果状态机</b>：除 {@link #success()} 布尔外，补充 {@link ResultState} 四态
 * （COMMITTED / ROLLED_BACK / PENDING_UNKNOWN / FAILED），供 {@link IdempotencyLedger}
 * 与 {@link SyscallRetryPolicy} 精确区分写操作是否已落地。
 * 超时必须用 {@link #pendingUnknown(String)}，绝不可用 {@link #fail(String)} 掩盖。
 *
 * @param success      syscall 是否业务成功（仅 COMMITTED 为 true）
 * @param data         响应数据（JSON 字符串、纯文本等）
 * @param errorMessage 失败时的错误描述，成功时为 null
 * @param resultState  结果状态机，详见 {@link ResultState}
 */
public record SyscallResponse(
        boolean success,
        String data,
        String errorMessage,
        ResultState resultState
) {

    /**
     * 向后兼容构造器：由 success 推断 resultState（true→COMMITTED，false→FAILED）。
     * <p>
     * 保留此构造器使所有未迁移到状态机的旧调用点源码兼容。
     */
    public SyscallResponse(boolean success, String data, String errorMessage) {
        this(success, data, errorMessage, success ? ResultState.COMMITTED : ResultState.FAILED);
    }

    /** Create a successful response (COMMITTED). */
    public static SyscallResponse ok(String data) {
        return new SyscallResponse(true, data, null, ResultState.COMMITTED);
    }

    /** Create a successful response with no data (COMMITTED). */
    public static SyscallResponse ok() {
        return new SyscallResponse(true, "", null, ResultState.COMMITTED);
    }

    /** 显式 COMMITTED 工厂：用于写操作成功提交。等价于 {@link #ok(String)} 但语义更明确。 */
    public static SyscallResponse committed(String data) {
        return new SyscallResponse(true, data, null, ResultState.COMMITTED);
    }

    /** Create a failure response (FAILED). */
    public static SyscallResponse fail(String errorMessage) {
        return new SyscallResponse(false, null, errorMessage, ResultState.FAILED);
    }

    /** Create a failure response from an exception (FAILED). */
    public static SyscallResponse fail(Throwable e) {
        return new SyscallResponse(false, null,
                e.getClass().getSimpleName() + ": " + e.getMessage(), ResultState.FAILED);
    }

    /**
     * PENDING_UNKNOWN 响应 — 写操作超时/中断，是否已落地未知。
     * <p>
     * <b>关键</b>：外层超时包装器捕获写操作超时时必须用此工厂，而非 {@link #fail}。
     * 调用方据此决定是否在 governance 层确认后重试。
     */
    public static SyscallResponse pendingUnknown(String reason) {
        return new SyscallResponse(false, null, reason, ResultState.PENDING_UNKNOWN);
    }

    /** ROLLED_BACK 响应 — 执行后已回滚，等价于未执行，可安全重试。 */
    public static SyscallResponse rolledBack(String reason) {
        return new SyscallResponse(false, null, reason, ResultState.ROLLED_BACK);
    }

    /** 通用工厂：按显式状态构造。 */
    public static SyscallResponse of(ResultState state, String data, String errorMessage) {
        return new SyscallResponse(state.isSuccess(), data, errorMessage, state);
    }

    @Override
    public String toString() {
        if (success) {
            return "SyscallResponse{OK[%s], dataLen=%d}".formatted(
                    resultState, data != null ? data.length() : 0);
        }
        return "SyscallResponse{FAIL[%s], error='%s'}".formatted(resultState, errorMessage);
    }
}
