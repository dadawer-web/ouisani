package com.ouisani.aios.core.syscall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等性账本 — idempotencyKey → 已记录结果，写操作去重的最后一道防线。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>写 syscall 进入 dispatcher，{@link #lookup} 命中终态 → 直接重放，跳过执行</li>
 *   <li>未命中 → {@link #tryReserve} 原子占位 PENDING_UNKNOWN（putIfAbsent）</li>
 *   <li>执行完成 → {@link #resolve} 覆盖为 COMMITTED/FAILED/ROLLED_BACK</li>
 *   <li>并发同 key：第二个调用者 lookup 命中 PENDING → 重放 pending 响应，不重复执行</li>
 * </ol>
 * <p>
 * 当前为进程内 {@link ConcurrentHashMap} 实现，TTL 驱逐避免无界增长。
 * 跨进程持久化（写 WAL）留待后续 P1 接入 EnvironmentSnapshotManager 时再做，不过度设计。
 */
public final class IdempotencyLedger {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyLedger.class);

    /** 默认 TTL：1 小时。长于绝大多数重试窗口，短到不会让账本无界增长。 */
    private static final long DEFAULT_TTL_MS = 60 * 60 * 1000L;

    private static final class Holder {
        static final IdempotencyLedger INSTANCE = new IdempotencyLedger();
    }

    public static IdempotencyLedger getInstance() {
        return Holder.INSTANCE;
    }

    /** 账本条目 — 记录某 idempotencyKey 的最终结果。 */
    public record LedgerEntry(
            ResultState resultState,
            String data,
            String errorMessage,
            long recordedAtMs,
            long resolvedAtMs
    ) {
        /** 转换回 SyscallResponse，用于命中重放。 */
        public SyscallResponse toResponse() {
            return new SyscallResponse(
                    resultState.isSuccess(),
                    data,
                    errorMessage,
                    resultState
            );
        }
    }

    private final ConcurrentHashMap<String, LedgerEntry> store = new ConcurrentHashMap<>();
    private final long ttlMs;

    public IdempotencyLedger() {
        this(DEFAULT_TTL_MS);
    }

    /** 测试/调参用：自定义 TTL。 */
    IdempotencyLedger(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** 查询账本。命中返回条目（含 PENDING_UNKNOWN）；未命中返回 empty。 */
    public Optional<LedgerEntry> lookup(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return Optional.empty();
        }
        LedgerEntry e = store.get(idempotencyKey);
        if (e == null) {
            return Optional.empty();
        }
        if (isExpired(e)) {
            store.remove(idempotencyKey, e);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    /**
     * 原子占位 PENDING_UNKNOWN。仅当 key 不存在时成功。
 *
     * @return true 表示占位成功，调用者应继续执行并最终 {@link #resolve}；
     *         false 表示 key 已存在（并发竞争或重复提交），调用者应 lookup 重放
     */
    public boolean tryReserve(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        LedgerEntry pending = new LedgerEntry(ResultState.PENDING_UNKNOWN, null, null, now, 0L);
        LedgerEntry prev = store.putIfAbsent(idempotencyKey, pending);
        if (prev == null) {
            log.debug("[Idempotency] 已占位 PENDING: key={}", idempotencyKey);
            return true;
        }
        if (isExpired(prev)) {
            // 过期条目：用 replace 尝试替换为新的 pending
            if (store.replace(idempotencyKey, prev, pending)) {
                log.debug("[Idempotency] 过期条目已替换并重新占位: key={}", idempotencyKey);
                return true;
            }
        }
        log.debug("[Idempotency] 占位失败（已存在）: key={}, state={}", idempotencyKey, prev.resultState());
        return false;
    }

    /**
     * 解析占位条目为终态。仅当当前条目是 PENDING_UNKNOWN 时才覆盖，
     * 避免覆盖并发先解析的同 key 结果（COMMITTED 一旦记录不可变）。
     */
    public void resolve(String idempotencyKey, ResultState state, String data, String errorMessage) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return;
        }
        if (state == ResultState.PENDING_UNKNOWN) {
            // resolve 不接受 pending；pending 只由 tryReserve 设置
            return;
        }
        long now = System.currentTimeMillis();
        LedgerEntry resolved = new LedgerEntry(state, data, errorMessage, now, now);
        store.computeIfPresent(idempotencyKey, (k, existing) -> {
            // 仅 PENDING 可被解析；已终态的保持不变（先到先得）
            if (existing.resultState() == ResultState.PENDING_UNKNOWN) {
                log.debug("[Idempotency] 已解析: key={}, state={}", k, state);
                return resolved;
            }
            return existing;
        });
    }

    /** 主动标记某 key 为 PENDING_UNKNOWN（如外层超时包装器检测到写操作超时）。 */
    public void markPending(String idempotencyKey, String reason) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        LedgerEntry pending = new LedgerEntry(ResultState.PENDING_UNKNOWN, null, reason, now, 0L);
        store.computeIfPresent(idempotencyKey, (k, existing) -> {
            // 已终态的不覆盖回 pending
            return existing.resultState().isTerminal() ? existing : pending;
        });
        store.putIfAbsent(idempotencyKey, pending);
    }

    /** 仅供测试：清空账本。 */
    void clear() {
        store.clear();
    }

    /** 仅供测试/监控：当前条目数。 */
    public int size() {
        return store.size();
    }

    private boolean isExpired(LedgerEntry e) {
        return System.currentTimeMillis() - e.recordedAtMs() > ttlMs;
    }
}
