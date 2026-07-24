package com.ouisani.aios.core.syscall;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IdempotencyLedger} 单元测试 — 验证占位/解析/重放/并发的原子语义。
 * <p>
 * 用包级构造器 {@code new IdempotencyLedger(ttlMs)} 构造独立实例，避免污染全局单例。
 */
class IdempotencyLedgerTest {

    private IdempotencyLedger freshLedger() {
        return new IdempotencyLedger(60_000L);
    }

    @Test
    void tryReserve_thenResolveCommitted_replaysCommitted() {
        IdempotencyLedger ledger = freshLedger();
        String key = "order-123";

        assertTrue(ledger.tryReserve(key), "首次占位应成功");
        ledger.resolve(key, ResultState.COMMITTED, "{\"orderId\":123}", null);

        Optional<IdempotencyLedger.LedgerEntry> hit = ledger.lookup(key);
        assertTrue(hit.isPresent());
        assertEquals(ResultState.COMMITTED, hit.get().resultState());

        SyscallResponse replay = hit.get().toResponse();
        assertTrue(replay.success());
        assertEquals(ResultState.COMMITTED, replay.resultState());
        assertEquals("{\"orderId\":123}", replay.data());
    }

    @Test
    void duplicateKey_afterCommitted_doesNotReserveAgain() {
        IdempotencyLedger ledger = freshLedger();
        String key = "k1";

        ledger.tryReserve(key);
        ledger.resolve(key, ResultState.COMMITTED, "ok", null);

        // 已终态的 key：tryReserve 应失败（不可重新占位）
        assertFalse(ledger.tryReserve(key), "已 COMMITTED 的 key 不应被重新占位");
    }

    @Test
    void pendingState_replaysPendingResponse() {
        IdempotencyLedger ledger = freshLedger();
        String key = "k-pending";

        assertTrue(ledger.tryReserve(key));
        // 未 resolve，仍为 PENDING_UNKNOWN
        Optional<IdempotencyLedger.LedgerEntry> hit = ledger.lookup(key);
        assertTrue(hit.isPresent());
        assertEquals(ResultState.PENDING_UNKNOWN, hit.get().resultState());

        SyscallResponse replay = hit.get().toResponse();
        assertFalse(replay.success(), "PENDING 不应视为成功");
        assertEquals(ResultState.PENDING_UNKNOWN, replay.resultState());
    }

    @Test
    void resolve_onlyOverwritesPending_terminalIsImmutable() {
        IdempotencyLedger ledger = freshLedger();
        String key = "k-immutable";

        ledger.tryReserve(key);
        ledger.resolve(key, ResultState.COMMITTED, "first", null);
        // 尝试用 FAILED 覆盖已 COMMITTED —— 应被忽略（先到先得）
        ledger.resolve(key, ResultState.FAILED, null, "late-failure");

        Optional<IdempotencyLedger.LedgerEntry> hit = ledger.lookup(key);
        assertTrue(hit.isPresent());
        assertEquals(ResultState.COMMITTED, hit.get().resultState(), "终态不可被覆盖");
        assertEquals("first", hit.get().data());
    }

    @Test
    void resolve_rejectsPendingState() {
        IdempotencyLedger ledger = freshLedger();
        String key = "k-no-pending-resolve";

        ledger.tryReserve(key);
        // resolve 不接受 PENDING_UNKNOWN（pending 只由 tryReserve/markPending 设置）
        ledger.resolve(key, ResultState.PENDING_UNKNOWN, null, null);

        Optional<IdempotencyLedger.LedgerEntry> hit = ledger.lookup(key);
        assertTrue(hit.isPresent());
        // 仍为 tryReserve 设置的 PENDING，resolve 未生效
        assertEquals(ResultState.PENDING_UNKNOWN, hit.get().resultState());
    }

    @Test
    void markPending_overwritesPending_butNotTerminal() {
        IdempotencyLedger ledger = freshLedger();
        String key = "k-markpending";

        // 已 COMMITTED 的不应被 markPending 覆盖
        ledger.tryReserve(key);
        ledger.resolve(key, ResultState.COMMITTED, "ok", null);
        ledger.markPending(key, "timeout");
        assertEquals(ResultState.COMMITTED, ledger.lookup(key).get().resultState());

        // PENDING 的可被 markPending 刷新原因
        String key2 = "k-markpending-2";
        ledger.tryReserve(key2);
        ledger.markPending(key2, "timeout-reason");
        Optional<IdempotencyLedger.LedgerEntry> hit2 = ledger.lookup(key2);
        assertTrue(hit2.isPresent());
        assertEquals(ResultState.PENDING_UNKNOWN, hit2.get().resultState());
        assertEquals("timeout-reason", hit2.get().errorMessage());
    }

    @Test
    void emptyOrNullKey_isNoop() {
        IdempotencyLedger ledger = freshLedger();
        assertFalse(ledger.tryReserve(null));
        assertFalse(ledger.tryReserve(""));
        assertTrue(ledger.lookup(null).isEmpty());
        assertTrue(ledger.lookup("").isEmpty());
        // resolve/markPending 对 null 不抛异常
        ledger.resolve(null, ResultState.COMMITTED, "x", null);
        ledger.markPending(null, "x");
    }

    @Test
    void expiredEntry_isEvictedOnLookup() throws InterruptedException {
        IdempotencyLedger ledger = new IdempotencyLedger(50L); // 50ms TTL
        String key = "k-expire";

        ledger.tryReserve(key);
        ledger.resolve(key, ResultState.COMMITTED, "ok", null);
        assertTrue(ledger.lookup(key).isPresent());

        Thread.sleep(80);
        assertTrue(ledger.lookup(key).isEmpty(), "过期条目应被驱逐");
        assertEquals(0, ledger.size());
    }

    @Test
    void concurrentSameKey_onlyOneReserves_othersReplayPending() throws Exception {
        IdempotencyLedger ledger = freshLedger();
        String key = "k-concurrent";
        int threads = 16;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger(0);
        AtomicInteger replayed = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (ledger.tryReserve(key)) {
                    reserved.incrementAndGet();
                    return null;
                }
                // 占位失败 → 应能查到 pending 并重放
                Optional<IdempotencyLedger.LedgerEntry> hit = ledger.lookup(key);
                if (hit.isPresent()
                        && hit.get().resultState() == ResultState.PENDING_UNKNOWN) {
                    replayed.incrementAndGet();
                }
                return null;
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, reserved.get(), "仅一个线程应成功占位");
        assertEquals(threads - 1, replayed.get(), "其余线程应重放 pending");
    }

    @Test
    void toResponse_preservesAllFields() {
        IdempotencyLedger ledger = freshLedger();
        String key = "k-roundtrip";

        ledger.tryReserve(key);
        ledger.resolve(key, ResultState.FAILED, null, "bad-args");

        SyscallResponse r = ledger.lookup(key).get().toResponse();
        assertFalse(r.success());
        assertEquals(ResultState.FAILED, r.resultState());
        assertNull(r.data());
        assertEquals("bad-args", r.errorMessage());
        assertNotNull(r.toString());
    }
}
