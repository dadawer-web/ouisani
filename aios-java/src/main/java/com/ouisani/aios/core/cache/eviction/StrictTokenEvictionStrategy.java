package com.ouisani.aios.core.cache.eviction;

import com.ouisani.aios.core.cache.SemanticCacheManager.CacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Strict Token Eviction Strategy — pure OS discipline.
 * <p>
 * Models the classic OS virtual memory page replacement with a hybrid
 * LRU/LFU approach:
 * <ul>
 *   <li><b>LRU</b>: Entries are ranked by {@code lastAccessTime} — the
 *       least recently used entry is evicted first.</li>
 *   <li><b>LFU</b>: Ties are broken by {@code accessCount} — entries
 *       that have been accessed fewer times lose.</li>
 *   <li><b>Token cost</b>: As a final tiebreaker, entries consuming more
 *       tokens (longer response text) are preferentially evicted, modeling
 *       the OS preference for freeing large memory pages first.</li>
 * </ul>
 * <p>
 * This strategy represents the cold, mechanical precision of a traditional
 * operating system's memory manager. No sentiment, no forgetting curves —
 * just raw utilitarian optimization.
 */
public final class StrictTokenEvictionStrategy implements MemoryEvictionStrategy {

    private static final Logger log = LoggerFactory.getLogger(StrictTokenEvictionStrategy.class);

    /** Fraction of entries to evict when capacity is exceeded (e.g., 0.25 = evict 25%). */
    private final double evictionRatio;

    public StrictTokenEvictionStrategy() {
        this(0.25);
    }

    public StrictTokenEvictionStrategy(double evictionRatio) {
        if (evictionRatio <= 0 || evictionRatio > 1) {
            throw new IllegalArgumentException("Eviction ratio must be in (0, 1], got: " + evictionRatio);
        }
        this.evictionRatio = evictionRatio;
    }

    @Override
    public List<CacheEntry> selectForEviction(List<CacheEntry> entries, int capacity) {
        if (entries.size() <= capacity) {
            return List.of();
        }

        int overflow = entries.size() - capacity;
        int toEvict = Math.max(overflow, (int) Math.ceil(entries.size() * evictionRatio));

        // Sort by: (1) lastAccessTime ASC (LRU first), (2) accessCount ASC (LFU tiebreak),
        //          (3) responseText.length() DESC (evict large pages first)
        List<CacheEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparingLong(CacheEntry::lastAccessTime)
                .thenComparingInt(CacheEntry::accessCount)
                .thenComparing(Comparator.comparingInt((CacheEntry e) -> e.responseText().length()).reversed())
        );

        List<CacheEntry> victims = sorted.subList(0, Math.min(toEvict, sorted.size()));

        log.debug("[Strict Eviction] capacity={}, entries={}, toEvict={}, victims={}",
                capacity, entries.size(), toEvict, victims.size());

        return new ArrayList<>(victims);
    }

    @Override
    public void onAccess(CacheEntry entry) {
        // In strict OS mode, access bookkeeping is handled by the CacheEntry itself
        // (lastAccessTime and accessCount are updated by the cache manager).
        log.trace("[Strict Eviction] Access recorded for entry: lastAccess={}, count={}",
                entry.lastAccessTime(), entry.accessCount());
    }

    @Override
    public String strategyName() {
        return "StrictTokenEviction(LRU/LFU)";
    }
}
