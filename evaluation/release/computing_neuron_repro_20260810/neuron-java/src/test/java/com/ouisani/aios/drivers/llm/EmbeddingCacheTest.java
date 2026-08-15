package com.ouisani.aios.drivers.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmbeddingCache 单元测试 — LRU 驱逐、命中/未命中、容量上限。
 */
class EmbeddingCacheTest {

    @Test
    void miss_returns_null() {
        EmbeddingCache cache = new EmbeddingCache();
        assertNull(cache.get("missing"));
    }

    @Test
    void hit_returns_put_value() {
        EmbeddingCache cache = new EmbeddingCache();
        float[] vec = {0.1f, 0.2f, 0.3f};
        cache.put("hello", vec);
        assertSame(vec, cache.get("hello"));
    }

    @Test
    void same_text_hits_same_entry() {
        EmbeddingCache cache = new EmbeddingCache();
        float[] vec = {1.0f};
        cache.put("dup", vec);
        assertSame(vec, cache.get("dup"));
        assertSame(vec, cache.get("dup"));
    }

    @Test
    void lru_evicts_oldest_when_over_capacity() {
        EmbeddingCache cache = new EmbeddingCache();
        // 填满到上限
        for (int i = 0; i < EmbeddingCache.MAX_CAPACITY; i++) {
            cache.put("k" + i, new float[]{i});
        }
        assertEquals(EmbeddingCache.MAX_CAPACITY, cache.size());
        // 触发驱逐：k0 是最久未用（插入后未访问）
        cache.put("overflow", new float[]{999});
        // k0 被驱逐
        assertNull(cache.get("k0"));
        // overflow 命中
        assertNotNull(cache.get("overflow"));
        assertEquals(EmbeddingCache.MAX_CAPACITY, cache.size());
    }

    @Test
    void lru_access_reorders_eviction() {
        EmbeddingCache cache = new EmbeddingCache();
        for (int i = 0; i < EmbeddingCache.MAX_CAPACITY; i++) {
            cache.put("k" + i, new float[]{i});
        }
        // 访问 k0 → k0 移到末尾，不再是最久未用
        cache.get("k0");
        // k1 现在是最久未用
        cache.put("overflow", new float[]{999});
        assertNull(cache.get("k1"));  // k1 被驱逐
        assertNotNull(cache.get("k0")); // k0 因被访问而保留
    }

    @Test
    void clear_empties_cache() {
        EmbeddingCache cache = new EmbeddingCache();
        cache.put("a", new float[]{1});
        cache.put("b", new float[]{2});
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
    }

    @Test
    void large_text_uses_hash_key_not_raw() {
        // 确认大文本不会因 key 机制异常，且相同内容命中
        EmbeddingCache cache = new EmbeddingCache();
        StringBuilder sb = new StringBuilder(8192);
        for (int i = 0; i < 8192; i++) sb.append('x');
        String big = sb.toString();
        float[] vec = {1.0f};
        cache.put(big, vec);
        assertSame(vec, cache.get(big));
    }
}
