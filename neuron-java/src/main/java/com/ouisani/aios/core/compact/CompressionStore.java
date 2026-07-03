package com.ouisani.aios.core.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 可逆压缩缓存 (Compression Cache Registry) — 借鉴 Headroom 的 CCR 架构。
 * <p>
 * <b>核心理念：压缩不应该丢信息。</b>当 CompactService 压缩工具输出时，
 * 原始内容缓存到这里，prompt 里只留一个标记
 * {@code [N items compressed to M. Retrieve more: hash=abc123]}。
 * LLM 需要细节时调用 {@code ccr_retrieve(hash)} 工具取回原文。
 * <p>
 * <b>解决的问题：</b>番茄钟死循环的根本原因是上下文被不可逆压缩后丢失了关键信息，
 * 导致 Agent 不断重试。CCR 让压缩变成可逆操作——信息随时可取回。
 * <p>
 * <b>设计要点（借鉴 Headroom cache/compression_store.py）：</b>
 * <ul>
 *   <li>SHA-256[:24] 哈希索引（96 位，280 万亿级碰撞空间）</li>
 *   <li>TTL 过期（默认 30 分钟会话级）</li>
 *   <li>最小堆 O(log n) 淘汰（按 created_at 排序）</li>
 *   <li>堆陈旧度检测 + 50% 陈旧率时重建</li>
 *   <li>哈希碰撞检测（不同内容相同哈希时告警）</li>
 *   <li>BM25 关键词检索（在缓存内容内搜索）</li>
 *   <li>检索事件反馈追踪（retrieval_count + search_queries）</li>
 * </ul>
 * <p>
 * OS 类比：相当于 Linux 的 swap 分区 — 内存紧张时把数据换出到磁盘（压缩），
 * 需要时再换回内存（检索）。与不可逆的 {@code exponentialTruncateFallback} 不同，
 * CCR 压缩是可逆的。
 */
public class CompressionStore {

    private static final Logger log = LoggerFactory.getLogger(CompressionStore.class);

    /** 默认 TTL：30 分钟（会话级）— 借鉴 Headroom DEFAULT_CCR_TTL_SECONDS */
    public static final int DEFAULT_TTL_SECONDS = 1800;

    /** 默认最大条目数 */
    public static final int DEFAULT_MAX_ENTRIES = 1000;

    /** 堆陈旧度重建阈值 — 50% 陈旧时重建堆 */
    private static final double HEAP_REBUILD_THRESHOLD = 0.5;

    /** 检索查询保留数量上限 */
    private static final int MAX_SEARCH_QUERIES = 10;

    // ── 单例 ──
    private static final CompressionStore INSTANCE = new CompressionStore();

    public static CompressionStore instance() {
        return INSTANCE;
    }

    // ── 存储 ──
    private final ConcurrentHashMap<String, CompressionEntry> entries = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    /** 最小堆：(created_at, hash_key) — O(log n) 淘汰 */
    private final PriorityQueue<long[]> evictionHeap =
            new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));

    /** 陈旧堆条目计数（被删除/替换但仍留在堆中的条目） */
    private volatile int staleHeapEntries = 0;

    private final int maxEntries;
    private final int defaultTtlSeconds;

    // ── 检索事件追踪（反馈循环）──
    private final List<RetrievalEvent> retrievalEvents = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_EVENTS = 1000;

    private CompressionStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_SECONDS);
    }

    private CompressionStore(int maxEntries, int defaultTtlSeconds) {
        this.maxEntries = maxEntries;
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    // ════════════════════════════════════════════════════════════════
    //  压缩条目数据结构 — 借鉴 Headroom CompressionEntry
    // ════════════════════════════════════════════════════════════════

    /**
     * 压缩缓存条目 — 存储原始内容、压缩内容及元数据。
     * <p>
     * 借鉴 Headroom 的 {@code CompressionEntry} dataclass。
     */
    public static class CompressionEntry {
        private final String hash;
        private final String originalContent;
        private final String compressedContent;
        private final int originalTokens;
        private final int compressedTokens;
        private final int originalItemCount;
        private final int compressedItemCount;
        private final String toolName;
        private final String toolCallId;
        private final String queryContext;
        private final long createdAt;
        private final int ttl;
        private final String compressionStrategy;

        // 反馈追踪（可变）
        private volatile int retrievalCount = 0;
        private volatile long lastAccessed = 0;
        private final List<String> searchQueries = Collections.synchronizedList(new ArrayList<>());

        public CompressionEntry(String hash, String originalContent, String compressedContent,
                                  int originalTokens, int compressedTokens,
                                  int originalItemCount, int compressedItemCount,
                                  String toolName, String toolCallId, String queryContext,
                                  long createdAt, int ttl, String compressionStrategy) {
            this.hash = hash;
            this.originalContent = originalContent;
            this.compressedContent = compressedContent;
            this.originalTokens = originalTokens;
            this.compressedTokens = compressedTokens;
            this.originalItemCount = originalItemCount;
            this.compressedItemCount = compressedItemCount;
            this.toolName = toolName;
            this.toolCallId = toolCallId;
            this.queryContext = queryContext;
            this.createdAt = createdAt;
            this.ttl = ttl;
            this.compressionStrategy = compressionStrategy;
        }

        public String hash() { return hash; }
        public String originalContent() { return originalContent; }
        public String compressedContent() { return compressedContent; }
        public int originalTokens() { return originalTokens; }
        public int compressedTokens() { return compressedTokens; }
        public int originalItemCount() { return originalItemCount; }
        public int compressedItemCount() { return compressedItemCount; }
        public String toolName() { return toolName; }
        public String compressionStrategy() { return compressionStrategy; }
        public int retrievalCount() { return retrievalCount; }

        /** 是否已过期 */
        public boolean isExpired() {
            return (System.currentTimeMillis() / 1000 - createdAt) > ttl;
        }

        /** 记录一次访问 — 用于反馈追踪 */
        public void recordAccess(String query) {
            retrievalCount++;
            lastAccessed = System.currentTimeMillis() / 1000;
            if (query != null && !query.isBlank() && !searchQueries.contains(query)) {
                searchQueries.add(query);
                while (searchQueries.size() > MAX_SEARCH_QUERIES) {
                    searchQueries.remove(0);
                }
            }
        }
    }

    /**
     * 检索事件 — 记录每次 CCR 检索用于反馈循环。
     */
    public record RetrievalEvent(
            String hash,
            String query,
            int itemsRetrieved,
            int totalItems,
            String toolName,
            long timestamp,
            String retrievalType  // "full" 或 "search"
    ) {}

    // ════════════════════════════════════════════════════════════════
    //  核心方法 — store / retrieve / search
    // ════════════════════════════════════════════════════════════════

    /**
     * 存储压缩内容并返回哈希键。
     * <p>
     * 借鉴 Headroom {@code CompressionStore.store()}。
     *
     * @param original      原始内容（压缩前）
     * @param compressed    压缩后内容
     * @param originalTokens 原始 token 数
     * @param compressedTokens 压缩后 token 数
     * @param toolName      产生此内容的工具名
     * @param compressionStrategy 压缩策略名
     * @param ttl          自定义 TTL（秒），null 用默认值
     * @return 哈希键（24 字符 hex），用于后续检索
     */
    public String store(String original, String compressed,
                         int originalTokens, int compressedTokens,
                         String toolName, String compressionStrategy,
                         Integer ttl) {

        // 计算 SHA-256[:24] 哈希
        String hashKey = computeHash(original);
        long now = System.currentTimeMillis() / 1000;
        int entryTtl = ttl != null ? ttl : defaultTtlSeconds;

        CompressionEntry entry = new CompressionEntry(
                hashKey, original, compressed,
                originalTokens, compressedTokens,
                0, 0,  // item counts (简化版不追踪)
                toolName, null, null,
                now, entryTtl, compressionStrategy
        );

        lock.lock();
        try {
            evictIfNeeded();

            // 哈希碰撞检测
            CompressionEntry existing = entries.get(hashKey);
            if (existing != null) {
                if (!existing.originalContent.equals(original)) {
                    // 真正的哈希碰撞（SHA-256[:24] 下极罕见）
                    log.warn("[CCR] 哈希碰撞: hash={} tool={} (existing_len={}, new_len={})",
                            hashKey, toolName, existing.originalContent.length(), original.length());
                } else {
                    log.debug("[CCR] 重复存储 hash={}, 更新条目", hashKey);
                }
                staleHeapEntries++;
            }

            entries.put(hashKey, entry);
            evictionHeap.offer(new long[]{now, hashKey.hashCode()});

            log.info("[CCR] 存储压缩内容: hash={}, tool={}, {}→{} tokens (策略={})",
                    hashKey, toolName, originalTokens, compressedTokens, compressionStrategy);
        } finally {
            lock.unlock();
        }

        return hashKey;
    }

    /**
     * 按哈希检索原始内容。
     * <p>
     * 借鉴 Headroom {@code CompressionStore.retrieve()}。
     *
     * @param hashKey store() 返回的哈希键
     * @param query   可选查询（用于反馈追踪，null 表示全量检索）
     * @return 压缩条目，未找到或已过期返回 null
     */
    public CompressionEntry retrieve(String hashKey, String query) {
        lock.lock();
        try {
            CompressionEntry entry = entries.get(hashKey);
            if (entry == null) {
                return null;
            }

            if (entry.isExpired()) {
                entries.remove(hashKey);
                staleHeapEntries++;
                log.debug("[CCR] 条目已过期: hash={}", hashKey);
                return null;
            }

            // 记录访问
            entry.recordAccess(query);

            // 记录检索事件
            logRetrieval(hashKey, query, entry.originalItemCount,
                    entry.originalItemCount, entry.toolName, "full");

            return entry;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在缓存内容内做关键词搜索（简化版 BM25）。
     * <p>
     * 借鉴 Headroom {@code CompressionStore.search()} — 用 BM25 评分
     * 过滤出与查询最相关的条目片段。
     *
     * @param hashKey     哈希键
     * @param query       搜索查询
     * @param maxResults  最大返回数
     * @return 匹配的文本片段列表（按相关度排序）
     */
    public List<String> search(String hashKey, String query, int maxResults) {
        CompressionEntry entry = retrieve(hashKey, query);
        if (entry == null) {
            return List.of();
        }

        String original = entry.originalContent;
        if (original == null || original.isBlank()) {
            return List.of();
        }

        // 将原文按行/段落分块
        List<String> chunks = splitIntoSearchableItems(original);

        // 简化版 BM25 评分
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        String[] queryTerms = tokenize(query);

        for (String chunk : chunks) {
            double score = bm25Score(chunk, queryTerms, chunks);
            if (score > 0.3) {  // 阈值
                scored.add(Map.entry(chunk, score));
            }
        }

        // 按分数降序排列，取前 maxResults
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 记录搜索事件
        logRetrieval(hashKey, query, Math.min(scored.size(), maxResults),
                chunks.size(), entry.toolName, "search");

        return scored.stream()
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 检查条目是否存在（不增加检索计数）。
     */
    public boolean exists(String hashKey) {
        CompressionEntry entry = entries.get(hashKey);
        return entry != null && !entry.isExpired();
    }

    /**
     * 获取条目元数据（不返回完整内容）。
     */
    public Map<String, Object> getMetadata(String hashKey) {
        CompressionEntry entry = entries.get(hashKey);
        if (entry == null || entry.isExpired()) {
            return null;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("hash", entry.hash);
        meta.put("toolName", entry.toolName);
        meta.put("originalTokens", entry.originalTokens);
        meta.put("compressedTokens", entry.compressedTokens);
        meta.put("compressionStrategy", entry.compressionStrategy);
        meta.put("retrievalCount", entry.retrievalCount);
        return meta;
    }

    /**
     * 获取存储统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("entryCount", entries.size());
        stats.put("heapSize", evictionHeap.size());
        stats.put("staleHeapEntries", staleHeapEntries);
        stats.put("maxEntries", maxEntries);
        stats.put("defaultTtlSeconds", defaultTtlSeconds);
        stats.put("totalRetrievals", retrievalEvents.size());
        return stats;
    }

    /**
     * 清理所有过期条目。
     */
    public int cleanupExpired() {
        lock.lock();
        try {
            int removed = 0;
            Iterator<Map.Entry<String, CompressionEntry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                CompressionEntry entry = it.next().getValue();
                if (entry.isExpired()) {
                    it.remove();
                    staleHeapEntries++;
                    removed++;
                }
            }
            if (removed > 0) {
                log.info("[CCR] 清理过期条目: {} 个", removed);
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部方法
    // ════════════════════════════════════════════════════════════════

    /** 计算 SHA-256[:24] 哈希 */
    private static String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {  // 12 bytes = 24 hex chars
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** 淘汰过期 + 超容量条目 — 借鉴 Headroom _evict_if_needed */
    private void evictIfNeeded() {
        // 1. 清理过期条目
        Iterator<Map.Entry<String, CompressionEntry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
                staleHeapEntries++;
            }
        }

        // 2. 堆重建（50% 陈旧时）
        int heapSize = evictionHeap.size();
        if (heapSize > 0 && (double) staleHeapEntries / heapSize >= HEAP_REBUILD_THRESHOLD) {
            rebuildHeap();
        }

        // 3. 超容量淘汰
        while (entries.size() >= maxEntries && !evictionHeap.isEmpty()) {
            long[] oldest = evictionHeap.poll();
            if (oldest == null) break;

            // 找到对应的 hash（通过 hashCode 反查需要遍历，这里简化）
            // 由于我们存的是 hashCode 而非 hash 字符串，需要重建
            // 更简单的做法：直接按 createdAt 找最老的
            String oldestKey = findOldestKey();
            if (oldestKey != null) {
                CompressionEntry removed = entries.remove(oldestKey);
                if (removed != null) {
                    if (removed.retrievalCount == 0) {
                        log.debug("[CCR] 淘汰从未检索的条目: hash={} tool={}",
                                oldestKey, removed.toolName);
                    }
                }
            }
        }
    }

    /** 重建淘汰堆 */
    private void rebuildHeap() {
        evictionHeap.clear();
        entries.forEach((k, v) -> evictionHeap.offer(new long[]{v.createdAt, k.hashCode()}));
        staleHeapEntries = 0;
        log.debug("[CCR] 堆重建完成: {} 条目", evictionHeap.size());
    }

    /** 找到 createdAt 最小的 key */
    private String findOldestKey() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, CompressionEntry> e : entries.entrySet()) {
            if (e.getValue().createdAt < oldestTime) {
                oldestTime = e.getValue().createdAt;
                oldestKey = e.getKey();
            }
        }
        return oldestKey;
    }

    /** 记录检索事件 */
    private void logRetrieval(String hash, String query, int itemsRetrieved,
                               int totalItems, String toolName, String type) {
        retrievalEvents.add(new RetrievalEvent(
                hash, query, itemsRetrieved, totalItems, toolName,
                System.currentTimeMillis() / 1000, type
        ));
        while (retrievalEvents.size() > MAX_EVENTS) {
            retrievalEvents.remove(0);
        }
    }

    // ── BM25 搜索辅助 ──

    /** 将文本分割为可搜索的块 */
    private List<String> splitIntoSearchableItems(String text) {
        // 按双换行（段落）分割，超长段落再按行分割
        List<String> chunks = new ArrayList<>();
        for (String para : text.split("\n\n")) {
            if (para.length() > 2000) {
                // 长段落按行分割，每块 2000 字符
                StringBuilder buf = new StringBuilder();
                for (String line : para.split("\n")) {
                    if (buf.length() + line.length() > 2000) {
                        if (buf.length() > 0) chunks.add(buf.toString());
                        buf.setLength(0);
                    }
                    buf.append(line).append("\n");
                }
                if (buf.length() > 0) chunks.add(buf.toString());
            } else {
                chunks.add(para);
            }
        }
        return chunks;
    }

    /** 简单分词 */
    private String[] tokenize(String text) {
        if (text == null || text.isBlank()) return new String[0];
        return text.toLowerCase().split("\\W+");
    }

    /** 简化版 BM25 评分 */
    private double bm25Score(String doc, String[] queryTerms, List<String> allDocs) {
        if (queryTerms.length == 0) return 0;
        String docLower = doc.toLowerCase();
        double score = 0;
        double k1 = 1.5;
        double b = 0.75;

        // 文档长度
        int docLen = docLower.split("\\W+").length;
        double avgDocLen = allDocs.stream()
                .mapToInt(d -> d.toLowerCase().split("\\W+").length)
                .average().orElse(1);

        for (String term : queryTerms) {
            if (term.isBlank()) continue;
            // 词频
            int tf = countOccurrences(docLower, term);
            if (tf == 0) continue;

            // 文档频率
            long df = allDocs.stream()
                    .filter(d -> d.toLowerCase().contains(term))
                    .count();
            if (df == 0) continue;

            // IDF
            double idf = Math.log((allDocs.size() - df + 0.5) / (df + 0.5) + 1);

            // BM25 分数
            double tfNorm = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * docLen / avgDocLen));
            score += idf * tfNorm;
        }
        return score;
    }

    /** 统计子串出现次数 */
    private int countOccurrences(String text, String term) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }
}
