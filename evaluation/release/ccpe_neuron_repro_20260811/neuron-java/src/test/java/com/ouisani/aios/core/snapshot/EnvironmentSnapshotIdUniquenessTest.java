package com.ouisani.aios.core.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvironmentSnapshotManager snapshotId 唯一性测试 — P1 补强 ②。
 * <p>
 * <b>背景</b>：原 snapshotId 格式 {@code env-{ms}-{hex(scopeId.hashCode())}}
 * 在同毫秒、同 scopeId 并发捕获时会发生碰撞覆盖。修复后加入 AtomicLong 计数器，
 * 格式变为 {@code env-{ms}-{counter}-{hex}}，counter 单调递增保证全局唯一。
 * <p>
 * 本测试单独成类，专注于 ID 唯一性这一已知缺陷的回归保护，避免污染
 * {@link EnvironmentSnapshotManagerTest} 中的功能性测试。
 */
class EnvironmentSnapshotIdUniquenessTest {

    private final EnvironmentSnapshotManager manager = EnvironmentSnapshotManager.instance();

    // ════════════════════════════════════════════════════════════════
    //  ID 格式
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("snapshotId 仍以 'env-' 开头（保留可识别前缀）")
    void snapshotId_keepsEnvPrefix() {
        EnvironmentSnapshot snap = manager.capture("test-format-scope");
        try {
            assertTrue(snap.snapshotId().startsWith("env-"),
                    "ID 应以 'env-' 开头: " + snap.snapshotId());
            // 格式应为 env-{ms}-{counter}-{hex}
            String[] parts = snap.snapshotId().split("-");
            assertEquals(4, parts.length, "ID 应有 4 段 (env/ms/counter/hex): " + snap.snapshotId());
            assertEquals("env", parts[0]);
            assertDoesNotThrow(() -> Long.parseLong(parts[1]), "ms 段应为数字");
            assertDoesNotThrow(() -> Long.parseLong(parts[2]), "counter 段应为数字");
        } finally {
            manager.deleteSnapshot(snap.snapshotId());
        }
    }

    @Test
    @DisplayName("counter 段单调递增（连续 capture）")
    void snapshotId_counterMonotonic() {
        // 跑 5 次连续 capture，counter 段应严格递增
        List<Long> counters = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                EnvironmentSnapshot snap = manager.capture("test-monotonic");
                String[] parts = snap.snapshotId().split("-");
                counters.add(Long.parseLong(parts[2]));
                ids.add(snap.snapshotId());
            }
            for (int i = 1; i < counters.size(); i++) {
                assertTrue(counters.get(i) > counters.get(i - 1),
                        "counter 应单调递增: " + counters);
            }
        } finally {
            ids.forEach(manager::deleteSnapshot);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  唯一性 — 关键测试
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("【关键】同 scopeId 连续捕获 — ID 必须唯一")
    void snapshotId_uniqueForSameScopeSequential() {
        // 修复前：同 scopeId 在不同毫秒不会碰撞，但若同毫秒则碰撞
        // 修复后：counter 保证即使同毫秒也不碰撞
        Set<String> ids = new HashSet<>();
        List<String> toCleanup = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                EnvironmentSnapshot snap = manager.capture("same-scope");
                String id = snap.snapshotId();
                assertTrue(ids.add(id), "ID 必须唯一，重复: " + id + " (iter=" + i + ")");
                toCleanup.add(id);
            }
            assertEquals(100, ids.size(), "100 次 capture 应产生 100 个唯一 ID");
        } finally {
            toCleanup.forEach(manager::deleteSnapshot);
        }
    }

    @Test
    @DisplayName("【关键】并发捕获 — N 线程同时 capture 同 scopeId，ID 全部唯一")
    void snapshotId_uniqueUnderConcurrency() throws Exception {
        // 这是修复的核心目标场景：并发捕获同 scopeId 不丢快照
        int threadCount = 16;
        int capturesPerThread = 25;
        int totalCaptures = threadCount * capturesPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger(0);
        List<String> allIds = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int t = 0; t < threadCount; t++) {
                final int threadIdx = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < capturesPerThread; i++) {
                        // 所有线程用同一 scopeId — 这是碰撞高发场景
                        EnvironmentSnapshot snap = manager.capture("concurrent-scope-" + threadIdx % 4);
                        allIds.add(snap.snapshotId());
                        success.incrementAndGet();
                    }
                });
            }

            // 等所有线程就绪后同时开火，最大化并发碰撞概率
            assertTrue(ready.await(5, TimeUnit.SECONDS), "线程就绪超时");
            start.countDown();

            // 等所有 capture 完成
            long deadline = System.currentTimeMillis() + 30_000;
            while (success.get() < totalCaptures && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(totalCaptures, success.get(), "所有 capture 应完成");

            // 验证 ID 全部唯一
            Set<String> uniqueIds = new HashSet<>(allIds);
            assertEquals(totalCaptures, uniqueIds.size(),
                    "并发捕获 " + totalCaptures + " 次，应有 " + totalCaptures + " 个唯一 ID，"
                            + "实际 " + uniqueIds.size() + " — 发生碰撞！");

        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            // 清理：批量删除本次测试产生的快照
            for (String id : allIds) {
                manager.deleteSnapshot(id);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内存索引不丢
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("并发捕获后 listSnapshots() 包含全部 ID（store.put 不被覆盖）")
    void listSnapshots_containsAllAfterConcurrentCapture() throws Exception {
        // 修复前：store.put 同 ID 会覆盖前者，listSnapshots 数量 < 捕获次数
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<String> ids = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    EnvironmentSnapshot snap = manager.capture("list-test-scope");
                    ids.add(snap.snapshotId());
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发 capture 应在 10s 内完成");

            // 验证 listSnapshots 包含全部 ID
            Set<String> storeSnapshot = manager.listSnapshots();
            for (String id : ids) {
                assertTrue(storeSnapshot.contains(id),
                        "listSnapshots 应包含所有捕获的 ID: " + id);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            ids.forEach(manager::deleteSnapshot);
        }
    }
}
