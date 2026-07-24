package com.ouisani.aios.core.syscall;

import com.ouisani.aios.core.syscall.schema.LlmPayload;
import com.ouisani.aios.core.syscall.schema.ToolPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SyscallRetryPolicy} 单元测试 — 验证读/写重试差异化语义。
 */
class SyscallRetryPolicyTest {

    private static SyscallRequest readRequest() {
        // llm.think = 已知读操作
        return new SyscallRequest("llm", "think", new LlmPayload("hi"));
    }

    private static SyscallRequest writeRequest(String idemKey) {
        // tool.call = 写操作（tool 命名空间默认视为写）
        SyscallRequest req = new SyscallRequest("tool", "call", new ToolPayload("book_ticket"));
        return idemKey != null ? req.withIdempotencyKey(idemKey) : req;
    }

    @Test
    void readOp_retriesOnFailure_upToMax() {
        SyscallRetryPolicy policy = SyscallRetryPolicy.DEFAULT_READ; // maxRetries=3
        SyscallRequest req = readRequest();
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);
        SyscallResponse failed = SyscallResponse.fail("transient");

        // attempt 1,2,3 应重试（未达 maxRetries+1=4）
        assertTrue(policy.shouldRetry(req, 1, failed, ledger));
        assertTrue(policy.shouldRetry(req, 2, failed, ledger));
        assertTrue(policy.shouldRetry(req, 3, failed, ledger));
        // attempt 4 = maxRetries+1，停止
        assertFalse(policy.shouldRetry(req, 4, failed, ledger));
    }

    @Test
    void readOp_doesNotRetryOnSuccess() {
        SyscallRetryPolicy policy = SyscallRetryPolicy.DEFAULT_READ;
        SyscallRequest req = readRequest();
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);
        SyscallResponse ok = SyscallResponse.ok("result");

        assertFalse(policy.shouldRetry(req, 1, ok, ledger), "读操作成功不应重试");
    }

    @Test
    void readOp_retriesWhenNoResponse() {
        SyscallRetryPolicy policy = SyscallRetryPolicy.DEFAULT_READ;
        SyscallRequest req = readRequest();
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);

        // lastResponse=null 表示网络中断等，读操作应重试
        assertTrue(policy.shouldRetry(req, 1, null, ledger));
    }

    @Test
    void writeOp_doesNotRetryByDefault() {
        SyscallRetryPolicy policy = SyscallRetryPolicy.DEFAULT_READ; // allowWriteRetryOnPending=false
        SyscallRequest req = writeRequest("key-1");
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);

        // 即使 PENDING_UNKNOWN，默认策略也不重试写操作
        SyscallResponse pending = SyscallResponse.pendingUnknown("timeout");
        assertFalse(policy.shouldRetry(req, 1, pending, ledger));
    }

    @Test
    void writeOp_retriesOnPending_whenAllowedAndNoCommittedRecord() {
        SyscallRetryPolicy policy = new SyscallRetryPolicy(3, 10L, 100L, true);
        SyscallRequest req = writeRequest("key-2");
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);

        // ledger 中该 key 仅为 PENDING（无 COMMITTED）→ 允许重试
        ledger.tryReserve("key-2");
        SyscallResponse pending = SyscallResponse.pendingUnknown("timeout");
        assertTrue(policy.shouldRetry(req, 1, pending, ledger));
    }

    @Test
    void writeOp_doesNotRetryWhenCommittedRecordExists() {
        SyscallRetryPolicy policy = new SyscallRetryPolicy(3, 10L, 100L, true);
        SyscallRequest req = writeRequest("key-3");
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);

        // ledger 已记录 COMMITTED → 即使响应是 pending，也不重试（已落地）
        ledger.tryReserve("key-3");
        ledger.resolve("key-3", ResultState.COMMITTED, "done", null);

        SyscallResponse pending = SyscallResponse.pendingUnknown("timeout");
        assertFalse(policy.shouldRetry(req, 1, pending, ledger),
                "ledger 已 COMMITTED 时不应重试写操作（避免重复下单）");
    }

    @Test
    void writeOpWithoutIdempotencyKey_neverRetriesEvenIfAllowed() {
        SyscallRetryPolicy policy = new SyscallRetryPolicy(3, 10L, 100L, true);
        SyscallRequest req = writeRequest(null); // 无幂等键
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);

        SyscallResponse pending = SyscallResponse.pendingUnknown("timeout");
        assertFalse(policy.shouldRetry(req, 1, pending, ledger),
                "无幂等键的写操作禁止重试（无法保证不重复）");
    }

    @Test
    void writeOp_doesNotRetryOnFailedState() {
        SyscallRetryPolicy policy = new SyscallRetryPolicy(3, 10L, 100L, true);
        SyscallRequest req = writeRequest("key-4");
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);

        // FAILED（非 pending）不重试
        SyscallResponse failed = SyscallResponse.fail("validation error");
        assertFalse(policy.shouldRetry(req, 1, failed, ledger));
    }

    @Test
    void readSafeOverride_enablesRetryForWriteNamespace() {
        SyscallRetryPolicy policy = SyscallRetryPolicy.DEFAULT_READ;
        // tool 命名空间通常视为写，但显式 readSafe=true 覆盖为可重试读
        SyscallRequest req = new SyscallRequest("tool", "call",
                new ToolPayload("query_only")).withReadSafe(true);
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);

        SyscallResponse failed = SyscallResponse.fail("transient");
        assertTrue(policy.shouldRetry(req, 1, failed, ledger),
                "readSafe=true 的 tool 调用应按读操作重试");
    }

    @Test
    void backoff_isExponentialAndCapped() {
        SyscallRetryPolicy policy = new SyscallRetryPolicy(5, 100L, 1000L, false);
        long b1 = policy.nextBackoffMs(1);
        long b2 = policy.nextBackoffMs(2);
        long b3 = policy.nextBackoffMs(3);
        long b4 = policy.nextBackoffMs(4);

        // 抖动后范围 [0.5*cap, 1.0*cap)
        assertTrue(b1 >= 50 && b1 < 100, "attempt1 backoff in [50,100): " + b1);
        assertTrue(b2 >= 100 && b2 < 200, "attempt2 backoff in [100,200): " + b2);
        assertTrue(b3 >= 200 && b3 < 400, "attempt3 backoff in [200,400): " + b3);
        // attempt4: exp=800, cap=1000 → capped=800 → [400,800)
        assertTrue(b4 >= 400 && b4 < 800, "attempt4 backoff in [400,800): " + b4);
        // attempt0 不退避
        assertEquals(0L, policy.nextBackoffMs(0));
    }

    @Test
    void strictPolicy_minimalRetry() {
        SyscallRetryPolicy policy = SyscallRetryPolicy.STRICT; // maxRetries=1
        SyscallRequest req = readRequest();
        IdempotencyLedger ledger = new IdempotencyLedger(60_000L);
        SyscallResponse failed = SyscallResponse.fail("x");

        assertTrue(policy.shouldRetry(req, 1, failed, ledger), "attempt1 < maxRetries+1=2");
        assertFalse(policy.shouldRetry(req, 2, failed, ledger), "attempt2 达上限");
    }
}
