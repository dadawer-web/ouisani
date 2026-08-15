package com.ouisani.aios.core.cache.kvstate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * KV Cache 注册表 — 管理所有 KV Cache 引用的全局注册表。
 * <p>
 * 借鉴 LMCache 的 StorageManager：多级存储调度 + pin/unpin 机制。
 * AIOS 的 {@link KvCacheRegistry} 是推理引擎 KV Cache 的"元数据索引"，
 * 不存储实际张量数据，只存储引用和元数据。
 * <p>
 * <h3>核心功能</h3>
 * <ul>
 *   <li>{@code register(ref)} — 注册一个新的 KV Cache 引用</li>
 *   <li>{@code lookup(uri)} — 按 URI 查找引用</li>
 *   <li>{@code lookupByHash(hash)} — 按内容哈希查找（用于 CacheBlend 匹配）</li>
 *   <li>{@code pin(uri) / unpin(uri)} — 引用计数管理，防止被驱逐</li>
 *   <li>{@code evictable()} — 返回所有 refCount==0 的引用（可被驱逐）</li>
 * </ul>
 * <p>
 * <h3>OS 类比</h3>
 * 类比 Linux 的 page cache 索引：内核不存储实际页数据，
 * 而是维护 address_space → page 映射。KvCacheRegistry 维护
 * URI → KvCacheRef 映射，实际张量数据由推理引擎管理。
 *
 * @see KvCacheRef
 * @see KvCacheVfsStore
 */
public final class KvCacheRegistry {

    private static final Logger log = LoggerFactory.getLogger(KvCacheRegistry.class);

    // ── Singleton ──

    private static final class Holder {
        static final KvCacheRegistry INSTANCE = new KvCacheRegistry();
    }

    public static KvCacheRegistry instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    /** URI → KvCacheRef 映射（主索引） */
    private final ConcurrentHashMap<String, KvCacheRef> refsByUri = new ConcurrentHashMap<>();

    /** contentHash → List<URI> 映射（用于 CacheBlend 按内容哈希匹配） */
    private final ConcurrentHashMap<String, List<String>> urisByHash = new ConcurrentHashMap<>();

    // ── 统计 ──

    private final AtomicLong totalRegistrations = new AtomicLong(0);
    private final AtomicLong totalLookups = new AtomicLong(0);
    private final AtomicLong totalHits = new AtomicLong(0);
    private final AtomicLong totalMisses = new AtomicLong(0);
    private final AtomicLong totalPins = new AtomicLong(0);
    private final AtomicLong totalUnpins = new AtomicLong(0);
    private final AtomicLong totalEvictions = new AtomicLong(0);

    private KvCacheRegistry() {
    }

    // ════════════════════════════════════════════════════════════════
    //  注册与查找
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册一个新的 KV Cache 引用。
     * <p>
     * 如果 URI 已存在，将被覆盖（类似 LMCache 的 put 操作）。
     *
     * @param ref KV Cache 引用
     */
    public void register(KvCacheRef ref) {
        Objects.requireNonNull(ref, "KvCacheRef cannot be null");

        refsByUri.put(ref.kvTensorUri(), ref);

        // 按内容哈希建立反向索引
        urisByHash.computeIfAbsent(ref.contentHash(), k -> new CopyOnWriteArrayList<>())
                .add(ref.kvTensorUri());

        totalRegistrations.incrementAndGet();
        log.debug("[KvCacheRegistry] 已注册: uri={}, model={}, tokens={}",
                ref.kvTensorUri(), ref.modelId(), ref.tokenCount());
    }

    /**
     * 按 URI 查找 KV Cache 引用。
     *
     * @param uri KV Cache 张量的唯一 URI
     * @return KvCacheRef，如果不存在返回 null
     */
    public KvCacheRef lookup(String uri) {
        totalLookups.incrementAndGet();
        KvCacheRef ref = refsByUri.get(uri);
        if (ref != null) {
            totalHits.incrementAndGet();
            // 记录访问
            KvCacheRef accessed = ref.recordAccess();
            refsByUri.put(uri, accessed);
        } else {
            totalMisses.incrementAndGet();
        }
        return ref;
    }

    /**
     * 按内容哈希查找 KV Cache 引用 — 用于 CacheBlend 非前缀匹配。
     * <p>
     * 借鉴 LMCache 的 BlendTokenRangeMatcherV3：通过哈希快速定位
     * 缓存中是否存在相同内容的片段。
     *
     * @param contentHash 原始文本内容的 SHA-256 哈希
     * @return 匹配的 KvCacheRef 列表（可能为空）
     */
    public List<KvCacheRef> lookupByHash(String contentHash) {
        totalLookups.incrementAndGet();
        List<String> uris = urisByHash.get(contentHash);
        if (uris == null || uris.isEmpty()) {
            totalMisses.incrementAndGet();
            return List.of();
        }
        totalHits.incrementAndGet();
        return uris.stream()
                .map(refsByUri::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 检查 URI 是否存在。
     *
     * @param uri KV Cache 张量的唯一 URI
     * @return true 如果存在
     */
    public boolean contains(String uri) {
        return refsByUri.containsKey(uri);
    }

    // ════════════════════════════════════════════════════════════════
    //  引用计数管理（pin / unpin）
    // ════════════════════════════════════════════════════════════════

    /**
     * 锁定 KV Cache 引用 — 增加引用计数，防止被驱逐。
     * <p>
     * 类比 LMCache 的 pin 机制。
     *
     * @param uri KV Cache 张量的唯一 URI
     * @return true 如果锁定成功
     */
    public boolean pin(String uri) {
        KvCacheRef ref = refsByUri.get(uri);
        if (ref == null) {
            log.warn("[KvCacheRegistry] pin 失败: URI 不存在: {}", uri);
            return false;
        }
        refsByUri.put(uri, ref.pin());
        totalPins.incrementAndGet();
        log.debug("[KvCacheRegistry] 已锁定: uri={}, refCount={}", uri, ref.refCount() + 1);
        return true;
    }

    /**
     * 解锁 KV Cache 引用 — 减少引用计数，允许被驱逐。
     *
     * @param uri KV Cache 张量的唯一 URI
     * @return true 如果解锁成功
     */
    public boolean unpin(String uri) {
        KvCacheRef ref = refsByUri.get(uri);
        if (ref == null) {
            log.warn("[KvCacheRegistry] unpin 失败: URI 不存在: {}", uri);
            return false;
        }
        refsByUri.put(uri, ref.unpin());
        totalUnpins.incrementAndGet();
        log.debug("[KvCacheRegistry] 已解锁: uri={}, refCount={}", uri, Math.max(0, ref.refCount() - 1));
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  驱逐管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 返回所有可被驱逐的 KV Cache 引用（refCount == 0）。
     *
     * @return 可驱逐的引用列表
     */
    public List<KvCacheRef> evictable() {
        return refsByUri.values().stream()
                .filter(ref -> !ref.isPinned())
                .collect(Collectors.toList());
    }

    /**
     * 驱逐指定的 KV Cache 引用。
     *
     * @param uri 要驱逐的 URI
     * @return true 如果驱逐成功
     */
    public boolean evict(String uri) {
        KvCacheRef ref = refsByUri.get(uri);
        if (ref == null) {
            return false;
        }
        if (ref.isPinned()) {
            log.warn("[KvCacheRegistry] 驱逐失败: URI 被锁定: {}", uri);
            return false;
        }
        refsByUri.remove(uri);
        // 清理反向索引
        List<String> uris = urisByHash.get(ref.contentHash());
        if (uris != null) {
            uris.remove(uri);
            if (uris.isEmpty()) {
                urisByHash.remove(ref.contentHash());
            }
        }
        totalEvictions.incrementAndGet();
        log.info("[KvCacheRegistry] 已驱逐: uri={}", uri);
        return true;
    }

    /**
     * 驱逐所有可驱逐的引用。
     *
     * @return 被驱逐的数量
     */
    public int evictAll() {
        List<KvCacheRef> toEvict = evictable();
        for (KvCacheRef ref : toEvict) {
            evict(ref.kvTensorUri());
        }
        return toEvict.size();
    }

    // ════════════════════════════════════════════════════════════════
    //  查询与统计
    // ════════════════════════════════════════════════════════════════

    /** 获取所有已注册的 URI */
    public Set<String> listUris() {
        return Collections.unmodifiableSet(refsByUri.keySet());
    }

    /** 已注册的引用数量 */
    public int size() {
        return refsByUri.size();
    }

    /** 被锁定的引用数量 */
    public long pinnedCount() {
        return refsByUri.values().stream().filter(KvCacheRef::isPinned).count();
    }

    /** 总 Token 数（所有引用的 tokenCount 之和） */
    public long totalTokens() {
        return refsByUri.values().stream().mapToLong(KvCacheRef::tokenCount).sum();
    }

    /** 命中率 */
    public double hitRate() {
        long lookups = totalLookups.get();
        return lookups == 0 ? 0.0 : (double) totalHits.get() / lookups;
    }

    /** 统计报告 */
    public String getStatsReport() {
        return """
                ┌─ KvCacheRegistry Stats ────────────────────────────
                │  Total Registrations : %d
                │  Total Lookups       : %d
                │  Total Hits          : %d
                │  Total Misses        : %d
                │  Hit Rate            : %.2f%%
                │  Total Pins          : %d
                │  Total Unpins        : %d
                │  Total Evictions     : %d
                │  Current Size        : %d
                │  Pinned              : %d
                │  Total Tokens        : %d
                └─────────────────────────────────────────────────""".formatted(
                totalRegistrations.get(), totalLookups.get(), totalHits.get(), totalMisses.get(),
                hitRate() * 100, totalPins.get(), totalUnpins.get(), totalEvictions.get(),
                size(), pinnedCount(), totalTokens());
    }

    /** 清空所有引用（用于测试） */
    void clear() {
        refsByUri.clear();
        urisByHash.clear();
        totalRegistrations.set(0);
        totalLookups.set(0);
        totalHits.set(0);
        totalMisses.set(0);
        totalPins.set(0);
        totalUnpins.set(0);
        totalEvictions.set(0);
    }

    /** 获取所有引用的快照列表（用于持久化） */
    public List<KvCacheRef> snapshot() {
        return List.copyOf(refsByUri.values());
    }

    /** 从快照列表恢复（用于从磁盘加载） */
    public void restore(List<KvCacheRef> refs) {
        for (KvCacheRef ref : refs) {
            register(ref);
        }
        log.info("[KvCacheRegistry] 从快照恢复了 {} 个 KV Cache 引用", refs.size());
    }
}
