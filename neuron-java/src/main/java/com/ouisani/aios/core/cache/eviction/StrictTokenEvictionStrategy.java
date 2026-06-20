package com.ouisani.aios.core.cache.eviction;

import com.ouisani.aios.core.cache.SemanticCacheManager.CacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 严格 Token 驱逐策略 — 纯 OS 纪律的页面替换算法。
 * <p>
 * 模拟经典 OS 虚拟内存页面替换，采用混合 LRU/LFU 策略：
 * <ul>
 *   <li><b>LRU</b>: 按 {@code lastAccessTime} 排序——最久未访问的条目优先驱逐</li>
 *   <li><b>LFU</b>: 同分按 {@code accessCount} 决胜——访问次数少的优先驱逐</li>
 *   <li><b>Token 开销</b>: 最终决胜按 Token 消耗量——大页面优先释放，模拟 OS 偏好</li>
 * </ul>
 * <p>
 * 此策略代表传统操作系统内存管理器的冷酷、机械精度。
 * 没有情感，没有遗忘曲线——只有纯粹的功利优化。
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
