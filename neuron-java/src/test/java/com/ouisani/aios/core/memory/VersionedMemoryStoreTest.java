package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
 * {@link VersionedMemoryStore} 单元测试 — 覆盖 version+1 冲突处理、history 保留、
 * 域过滤、null key 透传、并发安全。
 * <p>
 * 用 {@link FakeProvider} 记录最后一次写入的 record，验证透传到后端的是
 * <b>version 已被 bump 过</b>的记录而非调用方原始 record。
 */
class VersionedMemoryStoreTest {

    private FakeProvider delegate;
    private VersionedMemoryStore store;

    @BeforeEach
    void setUp() {
        delegate = new FakeProvider();
        store = new VersionedMemoryStore(delegate);
    }

    // ── 基本版本递增与 history ──

    @Test
    void store_newKey_versionIsOne() {
        MemoryRecord r = record("k1", "v1", MemoryDomain.USER);
        assertTrue(store.store("agent-A", r));

        MemoryRecord current = store.current("agent-A", "k1");
        assertNotNull(current);
        assertEquals(1L, current.version());
        assertEquals("v1", current.content());
        assertTrue(store.history("agent-A", "k1").isEmpty());
        assertEquals(1L, delegate.lastStored.version(), "透传给后端的 version 应为 1");
    }

    @Test
    void store_sameKey_versionBumpedAndOldKeptInHistory() {
        MemoryRecord r1 = record("k1", "v1", MemoryDomain.USER);
        MemoryRecord r2 = record("k1", "v2", MemoryDomain.USER);

        store.store("agent-A", r1);
        store.store("agent-A", r2);

        MemoryRecord current = store.current("agent-A", "k1");
        assertEquals(2L, current.version(), "第二次写入 version 应为 2");
        assertEquals("v2", current.content());

        List<MemoryRecord> history = store.history("agent-A", "k1");
        assertEquals(1, history.size(), "history 应保留 1 条旧版本");
        assertEquals(1L, history.get(0).version());
        assertEquals("v1", history.get(0).content());

        assertEquals(2L, delegate.lastStored.version(), "透传给后端的是 bump 后的 version=2");
    }

    @Test
    void store_threeWrites_versionThreeHistoryTwo() {
        MemoryRecord r1 = record("k1", "v1", MemoryDomain.AGENT);
        MemoryRecord r2 = record("k1", "v2", MemoryDomain.AGENT);
        MemoryRecord r3 = record("k1", "v3", MemoryDomain.AGENT);

        store.store("agent-A", r1);
        store.store("agent-A", r2);
        store.store("agent-A", r3);

        assertEquals(3L, store.current("agent-A", "k1").version());
        assertEquals(2, store.history("agent-A", "k1").size());
        assertEquals("v1", store.history("agent-A", "k1").get(0).content());
        assertEquals("v2", store.history("agent-A", "k1").get(1).content());
    }

    @Test
    void store_versionZeroOnNewKey_normalizedToOne() {
        MemoryRecord r = new MemoryRecord(
                "k1", "v1", "src", 1L, 0.5, MemoryDomain.USER, 0L);
        store.store("agent-A", r);
        assertEquals(1L, store.current("agent-A", "k1").version());
        assertEquals(1L, delegate.lastStored.version());
    }

    // ── null key 透传 ──

    @Test
    void store_nullKey_bypassesVersionTable() {
        MemoryRecord r = MemoryRecord.legacy("legacy-content");
        assertTrue(store.store("agent-A", r));
        assertNull(r.key());

        // 版本表应为空
        assertTrue(store.listCurrent("agent-A").isEmpty());
        // 但 delegate 应被调用
        assertNotNull(delegate.lastStored);
        assertEquals("legacy-content", delegate.lastStored.content());
    }

    // ── 未知 key ──

    @Test
    void current_unknownKey_returnsNull() {
        assertNull(store.current("agent-X", "nope"));
    }

    @Test
    void history_unknownKey_returnsEmpty() {
        assertTrue(store.history("agent-X", "nope").isEmpty());
    }

    // ── 列表与域过滤 ──

    @Test
    void listCurrent_returnsAllCurrentForAgent() {
        store.store("agent-A", record("k1", "v1", MemoryDomain.USER));
        store.store("agent-A", record("k2", "v2", MemoryDomain.AGENT));
        store.store("agent-B", record("k1", "v1-B", MemoryDomain.USER));

        List<MemoryRecord> aCurrent = store.listCurrent("agent-A");
        assertEquals(2, aCurrent.size());

        List<MemoryRecord> bCurrent = store.listCurrent("agent-B");
        assertEquals(1, bCurrent.size());
        assertEquals("v1-B", bCurrent.get(0).content());
    }

    @Test
    void listByDomain_filtersByUserOrAgent() {
        store.store("agent-A", record("k-user-1", "u1", MemoryDomain.USER));
        store.store("agent-A", record("k-agent-1", "a1", MemoryDomain.AGENT));
        store.store("agent-A", record("k-user-2", "u2", MemoryDomain.USER));

        List<MemoryRecord> userRecords = store.listByDomain("agent-A", MemoryDomain.USER);
        assertEquals(2, userRecords.size());

        List<MemoryRecord> agentRecords = store.listByDomain("agent-A", MemoryDomain.AGENT);
        assertEquals(1, agentRecords.size());
        assertEquals("a1", agentRecords.get(0).content());
    }

    @Test
    void listByDomain_userAndAgentAreIsolated() {
        store.store("agent-A", record("k1", "u1", MemoryDomain.USER));
        store.store("agent-A", record("k1", "a1", MemoryDomain.AGENT));
        // 同 key 第二次写入 version=2，current 是 a1（AGENT）

        List<MemoryRecord> userRecords = store.listByDomain("agent-A", MemoryDomain.USER);
        List<MemoryRecord> agentRecords = store.listByDomain("agent-A", MemoryDomain.AGENT);

        assertEquals(0, userRecords.size(), "USER 已被覆盖到 history，current 不应含 USER");
        assertEquals(1, agentRecords.size());
        assertEquals(2L, agentRecords.get(0).version(), "current 应是 version=2 的 AGENT 记录");
    }

    @Test
    void listByLayer_filtersLifecycleWithoutChangingDomain() {
        store.store("agent-A", MemoryRecord.raw("raw", "conversation", "chat", 1L, MemoryDomain.USER));
        store.store("agent-A", MemoryRecord.scenario("scenario", "project summary", "agent", 2L,
                0.8, MemoryDomain.AGENT));

        assertEquals(1, store.listByLayer("agent-A", MemoryLayer.L0).size());
        assertEquals(1, store.listByLayer("agent-A", MemoryLayer.L2).size());
        assertTrue(store.listByLayer("agent-A", MemoryLayer.L3).isEmpty());
    }

    @Test
    void versionBump_preservesLifecycleLayer() {
        store.store("agent-A", MemoryRecord.scenario("k", "v1", "agent", 1L, 0.8, MemoryDomain.AGENT));
        store.store("agent-A", MemoryRecord.atomic("k", "v2", "agent", 2L, 0.8, MemoryDomain.AGENT));
        assertEquals(MemoryLayer.L1, store.current("agent-A", "k").layer());
        assertEquals(MemoryLayer.L2, store.history("agent-A", "k").get(0).layer());
    }

    // ── 清理 ──

    @Test
    void clearVersionTable_removesOnlyThatAgent() {
        store.store("agent-A", record("k1", "v1", MemoryDomain.USER));
        store.store("agent-B", record("k1", "v1", MemoryDomain.USER));

        int removed = store.clearVersionTable("agent-A");
        assertEquals(1, removed);
        assertTrue(store.listCurrent("agent-A").isEmpty());
        assertEquals(1, store.listCurrent("agent-B").size(), "agent-B 不应被影响");
    }

    // ── delegate 访问 ──

    @Test
    void delegate_returnsWrappedProvider() {
        assertSame(delegate, store.delegate());
    }

    private static void assertSame(Object expected, Object actual) {
        assertTrue(expected == actual, "expected same instance");
    }

    // ── 并发安全：同 key 多线程写入不丢版本 ──

    @Test
    void store_concurrentSameKey_noLostVersions() throws InterruptedException {
        int threads = 16;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    store.store("agent-A", record("k1", "v" + idx, MemoryDomain.AGENT));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        MemoryRecord current = store.current("agent-A", "k1");
        assertNotNull(current);
        assertEquals(threads, current.version(), "version 应等于并发写入数");
        assertEquals(threads - 1, store.history("agent-A", "k1").size(),
                "history 应有 N-1 条旧版本");
    }

    // ── 边界 ──

    @Test
    void store_nullAgent_throws() {
        try {
            store.store(null, record("k1", "v1", MemoryDomain.USER));
            assertFalse(true, "应抛 NPE");
        } catch (NullPointerException expected) {
            // ok
        }
    }

    @Test
    void store_nullRecord_throws() {
        try {
            store.store("agent-A", null);
            assertFalse(true, "应抛 NPE");
        } catch (NullPointerException expected) {
            // ok
        }
    }

    @Test
    void constructor_nullDelegate_throws() {
        try {
            new VersionedMemoryStore(null);
            assertFalse(true, "应抛 NPE");
        } catch (NullPointerException expected) {
            // ok
        }
    }

    // ── 工具 ──

    private static MemoryRecord record(String key, String content, MemoryDomain domain) {
        return new MemoryRecord(
                key, content, "test", System.currentTimeMillis(), 0.9, domain, 1L);
    }

    /**
     * 简易内存 Provider — 记录最后一次写入的 record 供断言。
     */
    private static final class FakeProvider implements MemoryProvider {
        final List<MemoryRecord> stored = new ArrayList<>();
        volatile MemoryRecord lastStored = null;
        final AtomicInteger storeCount = new AtomicInteger();
        final ConcurrentHashMap<String, List<String>> pageStore = new ConcurrentHashMap<>();

        @Override
        public boolean store(String agentId, MemoryRecord record) {
            stored.add(record);
            lastStored = record;
            storeCount.incrementAndGet();
            pageStore.computeIfAbsent(agentId, k -> new ArrayList<>())
                    .add(record.content());
            return true;
        }

        @Override
        public String retrieve(String agentId, String query) {
            List<String> pages = pageStore.get(agentId);
            if (pages == null) return "";
            StringBuilder sb = new StringBuilder();
            for (String p : pages) {
                if (p.contains(query)) sb.append(p).append('\n');
            }
            return sb.toString().trim();
        }

        @Override
        public void clear(String agentId) {
            pageStore.remove(agentId);
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
