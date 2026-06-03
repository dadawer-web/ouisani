package com.ouisani.aios.core.cache;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.VectorMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SemanticCacheManager {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheManager.class);

    private static final class Holder {
        static final SemanticCacheManager INSTANCE = new SemanticCacheManager();
    }

    private final List<CacheEntry> cache = new ArrayList<>();
    private volatile LlmProvider llmProvider;

    private SemanticCacheManager() {}

    public static SemanticCacheManager instance() {
        return Holder.INSTANCE;
    }

    public void configure(LlmProvider provider) {
        this.llmProvider = provider;
        log.info("[Semantic Cache] Configured with LlmProvider: {}", provider.name());
    }

    /**
     * 查询语义缓存：将 newQuery 向量化后与缓存条目比较余弦相似度
     *
     * @return 相似度 > 0.95 时返回缓存响应，否则返回 null
     */
    public String getCachedResponse(String newQuery) {
        if (llmProvider == null || cache.isEmpty()) return null;

        float[] queryVector;
        try {
            queryVector = llmProvider.embed(newQuery);
        } catch (Exception e) {
            log.warn("[Semantic Cache] Embedding failed, skipping cache lookup: {}", e.getMessage());
            return null;
        }

        float bestSimilarity = -1.0f;
        String bestResponse = null;

        for (CacheEntry entry : cache) {
            float similarity = VectorMath.cosineSimilarity(queryVector, entry.queryVector);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestResponse = entry.responseText;
            }
        }

        if (bestSimilarity > 0.95f) {
            System.out.printf("  \u001B[32m[Semantic Cache] Cache hit with similarity %.4f > 0.95! Bypassing LLM...\u001B[0m%n", bestSimilarity);
            log.info("[Semantic Cache] Cache HIT: similarity={}, cacheSize={}", String.format("%.4f", bestSimilarity), cache.size());
            return bestResponse;
        }

        log.debug("[Semantic Cache] Cache MISS: bestSimilarity={}, threshold=0.95, cacheSize={}",
                String.format("%.4f", bestSimilarity), cache.size());
        return null;
    }

    /**
     * 写入语义缓存
     */
    public void putCache(String query, float[] queryVector, String response) {
        cache.add(new CacheEntry(queryVector, response));
        System.out.printf("  \u001B[32m[Semantic Cache] Cached response for query (%d entries total)\u001B[0m%n", cache.size());
        log.info("[Semantic Cache] Put cache: queryLen={}, responseLen={}, cacheSize={}",
                query.length(), response.length(), cache.size());
    }

    public int cacheSize() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
        log.info("[Semantic Cache] Cache cleared");
    }

    record CacheEntry(float[] queryVector, String responseText) {}
}
