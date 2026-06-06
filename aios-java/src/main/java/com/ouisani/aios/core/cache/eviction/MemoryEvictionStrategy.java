package com.ouisani.aios.core.cache.eviction;

import com.ouisani.aios.core.cache.SemanticCacheManager.CacheEntry;

import java.util.List;

/**
 * Memory Eviction Strategy — the policy engine that decides which cache
 * entries to evict when the semantic cache exceeds its capacity.
 * <p>
 * Inspired by the OS virtual memory page replacement algorithms, this
 * interface abstracts the eviction decision from the cache manager itself,
 * allowing different strategies to be plugged in at runtime.
 * <p>
 * Implementations:
 * <ul>
 *   <li>{@link StrictTokenEvictionStrategy} — LRU/LFU style, pure OS discipline</li>
 *   <li>{@link BionicCognitiveStrategy} — Ebbinghaus forgetting curve + activation weight</li>
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
