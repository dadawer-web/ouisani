package com.ouisani.aios.core.syscall;

/**
 * Syscall 写操作结果状态机 — 区分"已提交 / 已回滚 / 未知 / 失败"四态。
 * <p>
 * 解决"写操作超时后无法区分'没执行'与'执行了但响应丢失'"的事故级风险。
 * 超时必须返回 {@link #PENDING_UNKNOWN}，由 {@link IdempotencyLedger} 与
 * {@link SyscallRetryPolicy} 决定是否安全重试，绝不可盲目重试写操作。
 * <ul>
 *   <li>{@link #COMMITTED}      — 已成功提交到后端，可幂等重放（返回已记录结果）</li>
 *   <li>{@link #ROLLED_BACK}    — 执行后已回滚，等价于未执行，可安全重试</li>
 *   <li>{@link #PENDING_UNKNOWN} — 超时/中断，是否已落地未知，重试需上层确认</li>
 *   <li>{@link #FAILED}         — 执行前/中明确失败，且未产生副作用（如参数校验失败）</li>
 * </ul>
 */
public enum ResultState {
    COMMITTED,
    ROLLED_BACK,
    PENDING_UNKNOWN,
    FAILED;

    /** 终态：COMMITTED / ROLLED_BACK / FAILED。PENDING_UNKNOWN 为非终态（可被 resolve 覆盖）。 */
    public boolean isTerminal() {
        return this != PENDING_UNKNOWN;
    }

    /** 业务成功语义：仅 COMMITTED 视为成功。 */
    public boolean isSuccess() {
        return this == COMMITTED;
    }
}
