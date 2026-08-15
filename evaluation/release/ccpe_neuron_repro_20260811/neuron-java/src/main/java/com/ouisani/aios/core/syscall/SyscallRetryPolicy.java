package com.ouisani.aios.core.syscall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Syscall 重试策略 — 严格区分读/写。
 * <p>
 * <b>读操作</b>（{@link SyscallClassifier#isRead} 或 request.readSafe）：指数退避 + 抖动，
     * 最多 {@code maxRetries} 次。
 * <p>
 * <b>写操作</b>：默认不重试。仅在以下条件<b>同时</b>满足时允许重试一次：
 * <ol>
 *   <li>最近一次响应为 {@link ResultState#PENDING_UNKNOWN}</li>
 *   <li>{@link IdempotencyLedger} 中该 key 无 COMMITTED 记录</li>
 *   <li>{@code allowWriteRetryOnPending} 为 true（默认 false，需上层/governance 显式开启）</li>
 * </ol>
 * 写操作的重试必须携带同一 idempotencyKey，由 ledger 保证不重复执行。
 */
public final class SyscallRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(SyscallRetryPolicy.class);

    /** 读操作默认策略：3 次重试，100ms 起步指数退避，上限 2s。 */
    public static final SyscallRetryPolicy DEFAULT_READ = new SyscallRetryPolicy(
            3, 100L, 2000L, false);

    /** 严格策略：写操作完全不重试，读操作 1 次。 */
    public static final SyscallRetryPolicy STRICT = new SyscallRetryPolicy(
            1, 100L, 1000L, false);

    private final int maxRetries;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final boolean allowWriteRetryOnPending;

    public SyscallRetryPolicy(int maxRetries, long baseBackoffMs, long maxBackoffMs,
                              boolean allowWriteRetryOnPending) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (baseBackoffMs < 0 || maxBackoffMs < baseBackoffMs) {
            throw new IllegalArgumentException("invalid backoff bounds");
        }
        this.maxRetries = maxRetries;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.allowWriteRetryOnPending = allowWriteRetryOnPending;
    }

    public int maxRetries() { return maxRetries; }
    public boolean allowWriteRetryOnPending() { return allowWriteRetryOnPending; }

    /**
     * 判定是否应重试。
     *
     * @param request       原始请求
     * @param attempt       已尝试次数（从 1 开始）
     * @param lastResponse  最近一次响应（null 表示未拿到响应，如网络中断）
     * @param ledger        幂等账本（用于写操作查 COMMITTED 记录）
     * @return true 表示应重试
     */
    public boolean shouldRetry(SyscallRequest request, int attempt,
                               SyscallResponse lastResponse, IdempotencyLedger ledger) {
        if (attempt >= maxRetries + 1) {
            return false;
        }

        boolean retrySafe = SyscallClassifier.isRetrySafe(
                request.namespace(), request.action(), request.readSafe());

        if (retrySafe) {
            // 读操作：失败即可重试（指数退避）
            return lastResponse == null || !lastResponse.success();
        }

        // 写操作：默认不重试
        if (!allowWriteRetryOnPending) {
            return false;
        }
        // 仅当 PENDING_UNKNOWN 且 ledger 无 COMMITTED 时重试一次
        if (lastResponse == null) {
            // 无响应视为潜在 pending
            return noCommittedRecord(request, ledger);
        }
        if (lastResponse.resultState() != ResultState.PENDING_UNKNOWN) {
            return false;
        }
        return noCommittedRecord(request, ledger);
    }

    private boolean noCommittedRecord(SyscallRequest request, IdempotencyLedger ledger) {
        String key = request.idempotencyKey();
        if (key == null) {
            // 无幂等键的写操作：拒绝重试（无法保证不重复）
            return false;
        }
        return ledger.lookup(key)
                .map(e -> e.resultState() != ResultState.COMMITTED)
                .orElse(true);
    }

    /**
     * 计算下一次重试的退避时长（指数 + 抖动）。
     *
     * @param attempt 已尝试次数（从 1 开始）
     * @return 退避毫秒数
     */
    public long nextBackoffMs(int attempt) {
        if (attempt < 1) {
            return 0L;
        }
        long exp = baseBackoffMs << (attempt - 1);
        long capped = Math.min(exp, maxBackoffMs);
        // 抖动：[0.5, 1.0) * capped，避免重试风暴同步
        double jitter = 0.5 + ThreadLocalRandom.current().nextDouble(0.5);
        long backoff = (long) (capped * jitter);
        log.debug("[Retry] attempt={} backoffMs={}", attempt, backoff);
        return backoff;
    }
}
