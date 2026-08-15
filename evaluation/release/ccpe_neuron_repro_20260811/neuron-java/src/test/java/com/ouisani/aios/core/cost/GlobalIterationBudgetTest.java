package com.ouisani.aios.core.cost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GlobalIterationBudget} 单测 — SoA max_iters 等价物的任务级迭代熔断器。
 * <p>
 * 覆盖：预算内放行 / 超限拒绝 / 边界精确性 / 状态查询 / 默认值 / 非法入参 / 并发安全。
 */
class GlobalIterationBudgetTest {

    @Test
    void trySpendWithinBudget_returnsTrue() {
        GlobalIterationBudget budget = new GlobalIterationBudget(3);
        assertTrue(budget.trySpend(), "第1次应放行");
        assertTrue(budget.trySpend(), "第2次应放行");
        assertTrue(budget.trySpend(), "第3次应放行");
    }

    @Test
    void trySpendBeyondBudget_returnsFalse() {
        GlobalIterationBudget budget = new GlobalIterationBudget(2);
        budget.trySpend();
        budget.trySpend();
        assertFalse(budget.trySpend(), "第3次（超限）应拒绝");
        assertFalse(budget.trySpend(), "第4次仍应拒绝");
    }

    @Test
    void trySpend_exactBoundary_nTrueThenFalse() {
        GlobalIterationBudget budget = new GlobalIterationBudget(5);
        for (int i = 1; i <= 5; i++) {
            assertTrue(budget.trySpend(), "第" + i + "次（==maxIters）应放行");
        }
        assertFalse(budget.trySpend(), "第6次（>maxIters）应拒绝");
    }

    @Test
    void isExhausted_tracksStateCorrectly() {
        GlobalIterationBudget budget = new GlobalIterationBudget(2);
        assertFalse(budget.isExhausted(), "未花费前不应耗尽");
        budget.trySpend();
        assertFalse(budget.isExhausted(), "花费1次（<max）不应耗尽");
        budget.trySpend();
        assertTrue(budget.isExhausted(), "花费2次（==max）应耗尽");
    }

    @Test
    void remainingAndSpent_trackCorrectly() {
        GlobalIterationBudget budget = new GlobalIterationBudget(4);
        assertEquals(4, budget.remaining(), "初始剩余应==maxIters");
        assertEquals(0, budget.spent(), "初始花费应为0");
        budget.trySpend();
        budget.trySpend();
        assertEquals(2, budget.spent(), "花费2次后 spent=2");
        assertEquals(2, budget.remaining(), "花费2次后 remaining=2");
    }

    @Test
    void remaining_neverGoesNegative() {
        GlobalIterationBudget budget = new GlobalIterationBudget(1);
        budget.trySpend();
        budget.trySpend(); // 超限调用
        budget.trySpend(); // 再次超限
        assertEquals(0, budget.remaining(), "remaining 不应为负");
        assertEquals(1, budget.maxIters(), "maxIters 不变");
    }

    @Test
    void withDefault_uses60() {
        GlobalIterationBudget budget = GlobalIterationBudget.withDefault();
        assertEquals(GlobalIterationBudget.DEFAULT_MAX_ITERS, budget.maxIters());
        assertEquals(60, budget.maxIters(), "默认帽应为 60（20 节点 × 3 重试）");
    }

    @Test
    void invalidMaxIters_throws() {
        assertThrows(IllegalArgumentException.class, () -> new GlobalIterationBudget(0),
                "maxIters=0 应拒绝");
        assertThrows(IllegalArgumentException.class, () -> new GlobalIterationBudget(-1),
                "maxIters<0 应拒绝");
    }

    @Test
    void toString_includesSpentAndMax() {
        GlobalIterationBudget budget = new GlobalIterationBudget(10);
        budget.trySpend();
        budget.trySpend();
        String s = budget.toString();
        assertTrue(s.contains("2"), () -> "应显示 spent=2: " + s);
        assertTrue(s.contains("10"), () -> "应显示 maxIters=10: " + s);
    }

    /**
     * 并发安全：多个线程并发 trySpend，成功次数应恰好等于 maxIters（不多不少）。
     * <p>
     * 验证 AtomicInteger 的原子性：无 lost-update / double-spend。
     * 重复 5 次以覆盖不同线程调度。
     */
    @RepeatedTest(5)
    void concurrentTrySpend_noOverspend() throws InterruptedException {
        final int maxIters = 100;
        final int threads = 16;
        final int callsPerThread = 20; // 总调用 320 >> 100，强制竞争
        GlobalIterationBudget budget = new GlobalIterationBudget(maxIters);

        AtomicInteger granted = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < callsPerThread; i++) {
                            if (budget.trySpend()) {
                                granted.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发任务应在 10s 内完成");
        }

        assertEquals(maxIters, granted.get(),
                () -> "并发下放行次数应恰好==maxIters，实际=" + granted.get() + "（不应超发）");
        assertTrue(budget.isExhausted(), "并发后应已耗尽");
        assertTrue(budget.spent() >= maxIters, "spent 应 >= maxIters（含超限尝试的递增）");
    }
}
