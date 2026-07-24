package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 单元测试 — 验证 {@link VersionedMemoryStore#updateMetadata} 与 {@link VersionedMemoryStore#delete}。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>updateMetadata：版本递增、旧记录入 history、新 confidence/domain 生效、透传给 Provider</li>
 *   <li>updateMetadata：key 不存在返回 false</li>
 *   <li>updateMetadata：null 参数表示不更新（仅更新提供的字段）</li>
 *   <li>delete：删除存在的 key、不存在的 key 返回 false、删除后 history 也消失</li>
 *   <li>全局 primary store：setPrimaryStore / getPrimaryStore</li>
 * </ul>
 */
class VersionedMemoryStoreMetadataTest {

    private FakeProvider delegate;
    private VersionedMemoryStore store;

    @BeforeEach
    void setUp() {
        delegate = new FakeProvider();
        store = new VersionedMemoryStore(delegate);
    }

    @AfterEach
    void clearGlobalPrimary() {
        // 防止 primary store 测试污染其他测试
        VersionedMemoryStore.setPrimaryStore(null);
    }

    // ── updateMetadata ──

    @Test
    @DisplayName("updateMetadata：confidence + domain 同时更新，版本递增")
    void updateMetadata_bothFields_versionBumped() {
        MemoryRecord r = new MemoryRecord("k1", "content-1", "user-input",
                1000L, 0.5, MemoryDomain.AGENT, 1L);
        store.store("a1", r);

        boolean ok = store.updateMetadata("a1", "k1", 0.9, MemoryDomain.USER);

        assertTrue(ok);
        MemoryRecord current = store.current("a1", "k1");
        assertEquals(2L, current.version(), "版本应递增到 2");
        assertEquals(0.9, current.confidence(), 0.001);
        assertEquals(MemoryDomain.USER, current.domain());
        assertEquals("content-1", current.content(), "content 不应变");
        assertEquals("user-input", current.source(), "source 不应变");
        assertTrue(current.timestamp() >= 1000L, "timestamp 应刷新");
    }

    @Test
    @DisplayName("updateMetadata：旧记录压入 history")
    void updateMetadata_oldRecordPushedToHistory() {
        MemoryRecord r = new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L);
        store.store("a1", r);

        store.updateMetadata("a1", "k1", 0.9, MemoryDomain.USER);

        List<MemoryRecord> history = store.history("a1", "k1");
        assertEquals(1, history.size(), "应保留 1 条历史");
        assertEquals(1L, history.get(0).version());
        assertEquals(0.5, history.get(0).confidence(), 0.001);
        assertEquals(MemoryDomain.AGENT, history.get(0).domain());
    }

    @Test
    @DisplayName("updateMetadata：透传给 Provider 的是更新后的 record")
    void updateMetadata_propagatesToDelegate() {
        MemoryRecord r = new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L);
        store.store("a1", r);
        delegate.lastStored = null;  // 清掉 store 留下的痕迹

        store.updateMetadata("a1", "k1", 0.9, MemoryDomain.USER);

        assertNotNull(delegate.lastStored, "应透传给 Provider");
        assertEquals(2L, delegate.lastStored.version());
        assertEquals(0.9, delegate.lastStored.confidence(), 0.001);
        assertEquals(MemoryDomain.USER, delegate.lastStored.domain());
    }

    @Test
    @DisplayName("updateMetadata：key 不存在返回 false，不写 Provider")
    void updateMetadata_keyNotFound_returnsFalse() {
        boolean ok = store.updateMetadata("a1", "nonexistent", 0.9, MemoryDomain.USER);

        assertFalse(ok);
        assertNull(delegate.lastStored);
    }

    @Test
    @DisplayName("updateMetadata：confidence=null 表示不更新 confidence")
    void updateMetadata_nullConfidence_preservesOld() {
        MemoryRecord r = new MemoryRecord("k1", "v1", "src",
                1000L, 0.7, MemoryDomain.AGENT, 1L);
        store.store("a1", r);

        store.updateMetadata("a1", "k1", null, MemoryDomain.USER);

        MemoryRecord current = store.current("a1", "k1");
        assertEquals(0.7, current.confidence(), 0.001, "confidence 应保持原值");
        assertEquals(MemoryDomain.USER, current.domain(), "domain 应更新");
        assertEquals(2L, current.version());
    }

    @Test
    @DisplayName("updateMetadata：domain=null 表示不更新 domain")
    void updateMetadata_nullDomain_preservesOld() {
        MemoryRecord r = new MemoryRecord("k1", "v1", "src",
                1000L, 0.7, MemoryDomain.AGENT, 1L);
        store.store("a1", r);

        store.updateMetadata("a1", "k1", 0.99, null);

        MemoryRecord current = store.current("a1", "k1");
        assertEquals(0.99, current.confidence(), 0.001);
        assertEquals(MemoryDomain.AGENT, current.domain(), "domain 应保持原值");
    }

    @Test
    @DisplayName("updateMetadata：NPE 校验（agentId / key 不可为 null）")
    void updateMetadata_nullArgs_throwsNPE() {
        assertThrows(NullPointerException.class, () ->
                store.updateMetadata(null, "k1", 0.5, MemoryDomain.USER));
        assertThrows(NullPointerException.class, () ->
                store.updateMetadata("a1", null, 0.5, MemoryDomain.USER));
    }

    @Test
    @DisplayName("updateMetadata 多次：每次版本+1，history 累积")
    void updateMetadata_multipleTimes_historyGrows() {
        MemoryRecord r = new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L);
        store.store("a1", r);

        store.updateMetadata("a1", "k1", 0.6, null);
        store.updateMetadata("a1", "k1", 0.7, null);
        store.updateMetadata("a1", "k1", 0.8, MemoryDomain.USER);

        MemoryRecord current = store.current("a1", "k1");
        assertEquals(4L, current.version(), "store + 3 次 updateMetadata → v4");
        assertEquals(0.8, current.confidence(), 0.001);
        assertEquals(MemoryDomain.USER, current.domain());
        assertEquals(3, store.history("a1", "k1").size(), "history 应有 3 条");
    }

    // ── delete ──

    @Test
    @DisplayName("delete：存在的 key 返回 true，current/history 都消失")
    void delete_existingKey_returnsTrue() {
        MemoryRecord r = new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L);
        store.store("a1", r);
        // 再 store 一次让 history 不为空
        store.store("a1", new MemoryRecord("k1", "v2", "src",
                2000L, 0.6, MemoryDomain.AGENT, 1L));

        boolean ok = store.delete("a1", "k1");

        assertTrue(ok);
        assertNull(store.current("a1", "k1"));
        assertTrue(store.history("a1", "k1").isEmpty(), "history 也应被清除");
    }

    @Test
    @DisplayName("delete：不存在的 key 返回 false")
    void delete_nonexistentKey_returnsFalse() {
        boolean ok = store.delete("a1", "nonexistent");
        assertFalse(ok);
    }

    @Test
    @DisplayName("delete：不影响同 agent 的其他 key")
    void delete_preservesOtherKeys() {
        store.store("a1", new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));
        store.store("a1", new MemoryRecord("k2", "v2", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));

        store.delete("a1", "k1");

        assertNull(store.current("a1", "k1"));
        assertNotNull(store.current("a1", "k2"));
        assertEquals(1, store.listCurrent("a1").size());
    }

    @Test
    @DisplayName("delete：NPE 校验")
    void delete_nullArgs_throwsNPE() {
        assertThrows(NullPointerException.class, () -> store.delete(null, "k1"));
        assertThrows(NullPointerException.class, () -> store.delete("a1", null));
    }

    // ── 全局 primary store ──

    @Test
    @DisplayName("setPrimaryStore / getPrimaryStore：全局引用读写")
    void primaryStore_setAndGet() {
        assertNull(VersionedMemoryStore.getPrimaryStore(), "默认为 null");

        VersionedMemoryStore.setPrimaryStore(store);
        assertSame(store, VersionedMemoryStore.getPrimaryStore());

        VersionedMemoryStore.setPrimaryStore(null);
        assertNull(VersionedMemoryStore.getPrimaryStore());
    }

    // ── FakeProvider（与 VersionedMemoryStoreTest 对齐的简化版） ──

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
