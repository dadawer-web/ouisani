package com.ouisani.aios.core.cache.eviction;

import com.ouisani.aios.core.cache.SemanticCacheManager.CacheEntry;

import java.util.List;

/**
 * 内存驱逐策略 — 语义缓存超容量时决定驱逐哪些条目的策略引擎。
 * <p>
 * 灵感来自 OS 虚拟内存页面替换算法，此接口将驱逐决策从缓存管理器中抽象出来，
 * 允许在运行时插入不同策略。
 * <p>
 * 实现类：
 * <ul>
 *   <li>{@link StrictTokenEvictionStrategy} — LRU/LFU 风格，纯 OS 纪律</li>
 *   <li>{@link BionicCognitiveStrategy} — 艾宾浩斯遗忘曲线 + 激活权重</li>
 * </ul>
 *
 * @see SemanticCacheManager
 */
public interface MemoryEvictionStrategy {

    /**
     * Select entries for eviction from the given cache snapshot.
     * <p>
     * The caller will remove all returned entries from the cache.
     * The number of entries to evict is determined by the strategy
     * based on the current cache size and the configured capacity.
     *
     * @param entries  the current cache entries (read-only snapshot)
     * @param capacity the maximum number of entries the cache should hold
     * @return the list of entries to evict; empty list if no eviction needed
     */
    List<CacheEntry> selectForEviction(List<CacheEntry> entries, int capacity);

    /**
     * Called when a cache entry is accessed (cache hit).
     * <p>
     * Strategies may use this to update internal bookkeeping
     * (e.g., access count, last access timestamp, activation weight).
     *
     * @param entry the entry that was accessed
     */
    void onAccess(CacheEntry entry);

    /**
     * Return the name of this strategy for logging and configuration.
     */
    String strategyName();
}
