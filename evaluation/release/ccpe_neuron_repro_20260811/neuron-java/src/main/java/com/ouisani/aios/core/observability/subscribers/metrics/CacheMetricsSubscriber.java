package com.ouisani.aios.core.observability.subscribers.metrics;

import com.ouisani.aios.core.observability.EventSubscriber;
import com.ouisani.aios.core.observability.EventType;
import com.ouisani.aios.core.observability.ObservabilityEvent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 缓存 metrics 订阅器，订阅 {@link EventType#CACHE_HIT}/{@link EventType#CACHE_MISS}/
 * {@link EventType#CACHE_EVICT} 事件，维护 {@link AtomicLong} 计数器。
 * <p>
 * 参考 LMCache 的 {@code L1MetricsSubscriber}。
 */
public class CacheMetricsSubscriber extends EventSubscriber {

    private final AtomicLong cacheHitCount = new AtomicLong();
    private final AtomicLong cacheMissCount = new AtomicLong();
    private final AtomicLong cacheEvictCount = new AtomicLong();

    @Override
    public Map<EventType, Consumer<ObservabilityEvent>> getSubscriptions() {
        return Map.of(
                EventType.CACHE_HIT, e -> cacheHitCount.incrementAndGet(),
                EventType.CACHE_MISS, e -> cacheMissCount.incrementAndGet(),
                EventType.CACHE_EVICT, e -> cacheEvictCount.incrementAndGet()
        );
    }

    /**
     * 返回缓存命中累计次数。
     *
     * @return 命中次数
     */
    public long getCacheHitCount() {
        return cacheHitCount.get();
    }

    /**
     * 返回缓存未命中累计次数。
     *
     * @return 未命中次数
     */
    public long getCacheMissCount() {
        return cacheMissCount.get();
    }

    /**
     * 返回缓存淘汰累计次数。
     *
     * @return 淘汰次数
     */
    public long getCacheEvictCount() {
        return cacheEvictCount.get();
    }

    /**
     * 返回缓存命中率（命中数 / (命中数 + 未命中数)）。
     * <p>
     * 命中数与未命中数之和为 0 时返回 {@code 0.0}。
     *
     * @return 命中率，范围 [0.0, 1.0]
     */
    public double getHitRate() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        return total == 0L ? 0.0 : (double) hits / (double) total;
    }
}
