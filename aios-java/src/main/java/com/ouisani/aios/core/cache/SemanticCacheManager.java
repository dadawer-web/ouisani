package com.ouisani.aios.core.cache;

import com.ouisani.aios.core.cache.eviction.BionicCognitiveStrategy;
import com.ouisani.aios.core.cache.eviction.MemoryEvictionStrategy;
import com.ouisani.aios.core.cache.eviction.StrictTokenEvictionStrategy;
import com.ouisani.aios.core.cluster.SemanticRaftNode;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.VectorMath;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.core.tick.SystemTickGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 语义缓存管理器 — AIOS 的 Page Cache（认知记忆基底）。
 * <p>
 * 类比 Linux 的 Page Cache：操作系统将磁盘数据缓存在内存中加速访问，
 * AIOS 将 LLM 的查询-响应对连同向量嵌入缓存在语义缓存中，
 * 当新查询与缓存条目的余弦相似度超过阈值时，直接返回缓存结果，
 * 避免重复调用 LLM。
 * <p>
 * 当缓存超出容量时，由可插拔的 {@link MemoryEvictionStrategy} 决定淘汰策略。
 *
 * <h3>运行模式</h3>
 * <ul>
 *   <li>{@link EvictionMode#STRICT_OS_MODE} — 纯 OS 纪律：LRU/LFU + Token 成本决胜，
 *       无情感因素，无遗忘曲线</li>
 *   <li>{@link EvictionMode#BIONIC_AGENT_MODE} — 仿生模式：艾宾浩斯遗忘曲线 +
 *       激活权重 + 情感效价</li>
 *   <li>{@link EvictionMode#HYBRID_MODE} — 混合模式：两种策略的加权融合，
 *       混合评分 = {@code α * strictScore + (1-α) * bionicScore}</li>
 * </ul>
 *
 * @see MemoryEvictionStrategy
 * @see StrictTokenEvictionStrategy
 * @see BionicCognitiveStrategy
 */
public class SemanticCacheManager {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheManager.class);

    /** Default maximum cache entries before eviction is triggered. */
    private static final int DEFAULT_CAPACITY = 128;

    /** Default similarity threshold for semantic cache hits. */
    private static final float DEFAULT_SIMILARITY_THRESHOLD = 0.95f;

    // ── 艾宾浩斯衰减参数 ──

    /**
     * 默认衰减因子 — 每个 Tick 突触权重乘以此值。
     * <p>
     * 设 tickInterval = 60s, stabilityConstant = 1h = 3600s:
     * R(t) = e^(-t/S) = e^(-60/3600) ≈ 0.9835
     * 即每个 Tick 衰减约 1.65%，60 分钟后权重降至 ~37%（1/e）。
     */
    private static final double DEFAULT_DECAY_FACTOR = 0.9835;

    /** 突触权重低于此阈值时标记为 Swappable */
    private static final double SWAPPABLE_THRESHOLD = 0.3;

    /** 突触权重低于此阈值时直接淘汰（完全遗忘） */
    private static final double FORGET_THRESHOLD = 0.05;

    // ── Eviction Modes ──

    public enum EvictionMode {
        /** Pure OS discipline: LRU/LFU + token cost. */
        STRICT_OS_MODE,
        /** Bionic cognitive: Ebbinghaus + activation + emotion. */
        BIONIC_AGENT_MODE,
        /** Weighted hybrid of strict + bionic. */
        HYBRID_MODE
    }

    // ── Singleton ──

    private static final class Holder {
        static final SemanticCacheManager INSTANCE = new SemanticCacheManager();
    }

    public static SemanticCacheManager instance() {
        return Holder.INSTANCE;
    }

    // ── State ──

    private final List<CacheEntry> cache = new ArrayList<>();
    private volatile LlmProvider llmProvider;

    private EvictionMode evictionMode = EvictionMode.STRICT_OS_MODE;
    private MemoryEvictionStrategy strictStrategy;
    private MemoryEvictionStrategy bionicStrategy;
    private MemoryEvictionStrategy activeStrategy;

    private int capacity = DEFAULT_CAPACITY;
    private float similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;

    /** Hybrid mode: weight for strict strategy (0.0 ~ 1.0). Bionic weight = 1 - hybridAlpha. */
    private double hybridAlpha = 0.5;

    /** 当前衰减因子 — 可根据 Tick 间隔动态调整 */
    private volatile double decayFactor = DEFAULT_DECAY_FACTOR;

    /** 是否已订阅 SIG_TICK */
    private volatile boolean subscribedToTick = false;

    /** 衰减统计 */
    private long totalDecayCycles = 0;
    private long totalSwappableMarked = 0;
    private long totalForgotten = 0;

    private SemanticCacheManager() {
        this.strictStrategy = new StrictTokenEvictionStrategy();
        this.bionicStrategy = new BionicCognitiveStrategy();
        this.activeStrategy = strictStrategy;
        subscribeToSystemTick();
    }

    // ════════════════════════════════════════════════════════════════
    //  SysTick 驱动的记忆衰减
    // ════════════════════════════════════════════════════════════════

    /**
     * 订阅 EventBus 的 "sig_tick" 事件。
     * <p>
     * 每当 SystemTickGenerator 发出节拍中断，此回调被触发，
     * 对所有活跃的 CacheEntry 执行艾宾浩斯衰减：
     * <ol>
     *   <li>将每个条目的突触权重乘以衰减因子</li>
     *   <li>权重低于 SWAPPABLE_THRESHOLD 的标记为 Swappable</li>
     *   <li>权重低于 FORGET_THRESHOLD 的直接淘汰（完全遗忘）</li>
     * </ol>
     * <p>
     * 类比 Linux 的 timer_interrupt() 驱动页面回收：
     * kswapd 在后台扫描页面，将最近未访问的页面标记为
     * 可交换 (swappable)，最终换出到交换分区。
     */
    private void subscribeToSystemTick() {
        if (subscribedToTick) return;
        subscribedToTick = true;

        EventBus.instance().subscribe("sig_tick", payload -> {
            try {
                onTickDecay();
            } catch (Exception e) {
                log.error("[Semantic Cache] Tick decay error: {}", e.getMessage(), e);
            }
        });

        log.info("[Semantic Cache] Subscribed to SIG_TICK — memory decay engine active");
    }

    /**
     * 每个 Tick 的衰减处理 — 艾宾浩斯遗忘曲线的物理实现。
     * <p>
     * 这是 AIOS 记忆系统的核心节拍：每个 Tick，所有记忆的
     * 突触权重都会物理性地降低。如果不被重新激活（访问），
     * 记忆将逐渐消退，最终被遗忘。
     * <p>
     * 这就是"时间流逝感"的本质 — 不是时钟的滴答声，
     * 而是记忆在无人问津时悄然褪色的过程。
     */
    private void onTickDecay() {
        if (cache.isEmpty()) return;

        totalDecayCycles++;

        List<CacheEntry> toForget = new ArrayList<>();
        int swappableCount = 0;

        for (CacheEntry entry : cache) {
            // 情绪标签的衰减保护 — "critical" 记忆衰减更慢
            double effectiveDecay = decayFactor;
            Object emotion = entry.meta("emotion");
            if (emotion != null) {
                String tag = emotion.toString().toLowerCase();
                if ("critical".equals(tag) || "urgent".equals(tag) || "important".equals(tag)) {
                    effectiveDecay = 1.0 - (1.0 - decayFactor) * 0.3; // 关键记忆衰减速度降为 30%
                } else if ("positive".equals(tag) || "reward".equals(tag)) {
                    effectiveDecay = 1.0 - (1.0 - decayFactor) * 0.6; // 正面记忆衰减速度降为 60%
                }
            }

            // 艾宾浩斯衰减 — 突触权重物理性降低
            entry.decaySynapticWeight(effectiveDecay);

            // 标记 Swappable — 类似 kswapd 标记可回收页面
            if (entry.synapticWeight() < SWAPPABLE_THRESHOLD && !entry.swappable()) {
                entry.markSwappable();
                swappableCount++;
            }

            // 完全遗忘 — 权重低于阈值，直接淘汰
            if (entry.synapticWeight() < FORGET_THRESHOLD) {
                toForget.add(entry);
            }
        }

        // 淘汰完全遗忘的条目
        if (!toForget.isEmpty()) {
            // 冷热分层：遗忘的条目流入 SemanticNode（L2）
            influxToSemanticNode(toForget);
            cache.removeAll(toForget);
            totalForgotten += toForget.size();
            log.info("[Semantic Cache] Tick decay: {} entries forgotten (weight < {}), cacheSize={}",
                    toForget.size(), FORGET_THRESHOLD, cache.size());
        }

        totalSwappableMarked += swappableCount;

        // 每 10 个 Tick 输出一次详细衰减报告
        if (totalDecayCycles % 10 == 0) {
            long swappable = cache.stream().filter(CacheEntry::swappable).count();
            double avgWeight = cache.stream()
                    .mapToDouble(CacheEntry::synapticWeight)
                    .average()
                    .orElse(0);

            log.info("[Semantic Cache] Decay cycle #{}: cacheSize={}, swappable={}, avgWeight={:.4f}, decayFactor={:.4f}",
                    totalDecayCycles, cache.size(), swappable, avgWeight, decayFactor);

            SemanticEtw.getInstance().logEvent("CACHE", "DECAY_TICK",
                    "cycle=" + totalDecayCycles + " size=" + cache.size()
                    + " swappable=" + swappable + " avgWeight=" + String.format("%.4f", avgWeight)
                    + " forgotten=" + toForget.size());
        }
    }

    /**
     * 动态调整衰减因子 — 可根据 SysTick 间隔动态适配。
     *
     * @param tickIntervalMs 当前 Tick 间隔（毫秒）
     */
    public void adaptDecayFactor(long tickIntervalMs) {
        // 基于 BionicCognitiveStrategy 的 stabilityConstant = 3600s
        // R(t) = e^(-t/S), t = tickIntervalMs/1000, S = 3600
        double t = tickIntervalMs / 1000.0;
        double S = 3600.0;
        this.decayFactor = Math.exp(-t / S);
        log.info("[Semantic Cache] Decay factor adapted: tickInterval={}ms → decayFactor={:.6f}",
                tickIntervalMs, decayFactor);
    }

    // ════════════════════════════════════════════════════════════════
    //  Configuration
    // ════════════════════════════════════════════════════════════════

    public void configure(LlmProvider provider) {
        this.llmProvider = provider;
        log.info("[Semantic Cache] Configured with LlmProvider: {}", provider.name());
    }

    /**
     * Configure the eviction mode and capacity.
     *
     * @param mode     the eviction strategy mode
     * @param capacity maximum cache entries before eviction
     */
    public void configure(EvictionMode mode, int capacity) {
        this.evictionMode = mode;
        this.capacity = capacity;
        applyMode(mode);

        log.info("[Semantic Cache] Eviction mode={}, capacity={}, activeStrategy={}",
                mode, capacity, activeStrategy.strategyName());
        System.out.println("  \u001B[36m[Semantic Cache] Eviction mode=" + mode
                + ", capacity=" + capacity
                + ", strategy=" + activeStrategy.strategyName() + "\u001B[0m");
    }

    /**
     * Configure the hybrid mode with a custom alpha weight.
     *
     * @param hybridAlpha weight for strict strategy (0.0 = pure bionic, 1.0 = pure strict)
     */
    public void configureHybrid(double hybridAlpha) {
        this.hybridAlpha = Math.max(0.0, Math.min(1.0, hybridAlpha));
        if (this.evictionMode == EvictionMode.HYBRID_MODE) {
            log.info("[Semantic Cache] Hybrid alpha set to {:.2f} (strict={:.0f}%, bionic={:.0f}%)",
                    this.hybridAlpha, this.hybridAlpha * 100, (1 - this.hybridAlpha) * 100);
        }
    }

    /**
     * Set a custom eviction strategy (overrides mode-based selection).
     */
    public void setEvictionStrategy(MemoryEvictionStrategy strategy) {
        this.activeStrategy = strategy;
        log.info("[Semantic Cache] Custom eviction strategy set: {}", strategy.strategyName());
    }

    /**
     * Set the similarity threshold for cache hits.
     */
    public void setSimilarityThreshold(float threshold) {
        this.similarityThreshold = threshold;
    }

    private void applyMode(EvictionMode mode) {
        this.activeStrategy = switch (mode) {
            case STRICT_OS_MODE -> strictStrategy;
            case BIONIC_AGENT_MODE -> bionicStrategy;
            case HYBRID_MODE -> strictStrategy; // Hybrid uses both; activeStrategy is a placeholder
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  Semantic Lookup
    // ════════════════════════════════════════════════════════════════

    /**
     * Query the semantic cache: embed the new query and compare cosine
     * similarity against all cached entries.
     *
     * @return cached response if similarity > threshold, otherwise null
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
        CacheEntry bestEntry = null;

        for (CacheEntry entry : cache) {
            float similarity = VectorMath.cosineSimilarity(queryVector, entry.queryVector());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestEntry = entry;
            }
        }

        if (bestSimilarity > similarityThreshold && bestEntry != null) {
            // Reactivate the entry — update access metadata
            bestEntry.recordAccess();
            activeStrategy.onAccess(bestEntry);

            System.out.printf("  \u001B[32m[Semantic Cache] Cache hit with similarity %.4f > %.2f! Bypassing LLM...\u001B[0m%n",
                    bestSimilarity, similarityThreshold);
            log.info("[Semantic Cache] Cache HIT: similarity={}, cacheSize={}, mode={}",
                    String.format("%.4f", bestSimilarity), cache.size(), evictionMode);
            return bestEntry.responseText();
        }

        log.debug("[Semantic Cache] Cache MISS: bestSimilarity={}, threshold={}, cacheSize={}, mode={}",
                String.format("%.4f", bestSimilarity), similarityThreshold, cache.size(), evictionMode);
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  Cache Write
    // ════════════════════════════════════════════════════════════════

    /**
     * Write a query-response pair into the semantic cache.
     * <p>
     * If the cache exceeds capacity after insertion, the active eviction
     * strategy is invoked to select entries for removal.
     */
    public void putCache(String query, float[] queryVector, String response) {
        putCache(query, queryVector, response, null);
    }

    /**
     * Write a query-response pair with optional metadata into the semantic cache.
     *
     * @param metadata optional metadata map (emotion tags, source info, etc.)
     */
    public void putCache(String query, float[] queryVector, String response, Map<String, Object> metadata) {
        CacheEntry entry = new CacheEntry(queryVector, response, metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        cache.add(entry);

        // Enforce capacity via eviction strategy
        if (cache.size() > capacity) {
            evict();
        }

        System.out.printf("  \u001B[32m[Semantic Cache] Cached response for query (%d entries total, mode=%s)\u001B[0m%n",
                cache.size(), evictionMode);
        log.info("[Semantic Cache] Put cache: queryLen={}, responseLen={}, cacheSize={}, mode={}",
                query.length(), response.length(), cache.size(), evictionMode);

        // ── 集群记忆复制：高价值知识广播到全网 ──
        replicateToCluster(query, response, metadata);
    }

    // ════════════════════════════════════════════════════════════════
    //  集群记忆状态机复制 (Semantic State Machine Replication)
    // ════════════════════════════════════════════════════════════════

    /** Raft 节点 — 用于集群记忆复制 */
    private SemanticRaftNode raftNode;

    /**
     * 配置 Raft 节点 — 启用集群记忆复制。
     * <p>
     * 当任何一个节点学到了新的高价值知识，它会以 Raft 日志
     * （AppendEntries）的形式向全网广播。一旦半数以上节点确认，
     * 这块"记忆"就永远刻入了整个 AIOS 集群的潜意识中。
     *
     * @param raftNode Raft 节点实例
     */
    public void setRaftNode(SemanticRaftNode raftNode) {
        this.raftNode = raftNode;
        log.info("[SemanticCache] Raft node configured: nodeId={}", raftNode.nodeId());
    }

    /**
     * 集群记忆复制 — 将高价值知识广播到集群。
     * <p>
     * 复制条件：
     * <ul>
     *   <li>Raft 节点已配置</li>
     *   <li>知识标记为高价值（critical/important/positive 情绪标签）</li>
     *   <li>非集群复制来源（避免循环复制）</li>
     * </ul>
     */
    private void replicateToCluster(String query, String response, Map<String, Object> metadata) {
        if (raftNode == null) return;

        // 避免循环复制：集群复制来的知识不再广播
        if (metadata != null && "cluster_replication".equals(metadata.get("source"))) {
            return;
        }

        // 只复制高价值知识
        boolean isHighValue = false;
        if (metadata != null) {
            String emotion = (String) metadata.getOrDefault("emotion", "");
            isHighValue = "critical".equals(emotion) || "important".equals(emotion)
                    || "positive".equals(emotion) || "reward".equals(emotion);
        }

        // 如果没有情绪标签，默认复制（让集群决定是否接受）
        if (metadata == null || metadata.isEmpty() || isHighValue) {
            raftNode.replicateMemory(query, response, metadata);
            log.debug("[SemanticCache] Memory replicated to cluster: query={}",
                    query.length() > 50 ? query.substring(0, 50) + "..." : query);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Eviction Engine
    // ════════════════════════════════════════════════════════════════

    private void evict() {
        if (evictionMode == EvictionMode.HYBRID_MODE) {
            evictHybrid();
            return;
        }

        List<CacheEntry> victims = activeStrategy.selectForEviction(new ArrayList<>(cache), capacity);
        cache.removeAll(victims);

        if (!victims.isEmpty()) {
            // 冷热分层：驱逐数据流入 SemanticNode（L2 持久存储）
            influxToSemanticNode(victims);
            log.info("[Semantic Cache] Evicted {} entries via {} (cacheSize={})",
                    victims.size(), activeStrategy.strategyName(), cache.size());
        }
    }

    /**
     * Hybrid eviction: compute scores from both strategies and blend.
     * <p>
     * evictionScore = α * strictRank + (1-α) * bionicRank
     * <p>
     * Entries with the lowest blended score are evicted.
     */
    private void evictHybrid() {
        int overflow = cache.size() - capacity;
        if (overflow <= 0) return;

        int toEvict = Math.max(overflow, (int) Math.ceil(cache.size() * 0.20));

        List<CacheEntry> strictVictims = strictStrategy.selectForEviction(new ArrayList<>(cache), capacity);
        List<CacheEntry> bionicVictims = bionicStrategy.selectForEviction(new ArrayList<>(cache), capacity);

        // Compute blended rank: entries appearing in both lists get higher eviction priority
        Map<CacheEntry, Double> blendedScore = new LinkedHashMap<>();
        for (int i = 0; i < strictVictims.size(); i++) {
            CacheEntry e = strictVictims.get(i);
            blendedScore.merge(e, hybridAlpha * (strictVictims.size() - i), Double::sum);
        }
        for (int i = 0; i < bionicVictims.size(); i++) {
            CacheEntry e = bionicVictims.get(i);
            blendedScore.merge(e, (1 - hybridAlpha) * (bionicVictims.size() - i), Double::sum);
        }

        // Sort by blended score ASC (lowest = evict first)
        List<Map.Entry<CacheEntry, Double>> sorted = new ArrayList<>(blendedScore.entrySet());
        sorted.sort(Comparator.comparingDouble(Map.Entry::getValue));

        List<CacheEntry> victims = new ArrayList<>();
        for (int i = 0; i < Math.min(toEvict, sorted.size()); i++) {
            victims.add(sorted.get(i).getKey());
        }

        cache.removeAll(victims);

        if (!victims.isEmpty()) {
            influxToSemanticNode(victims);
        }

        log.info("[Semantic Cache] Hybrid eviction: α={}, strictVictims={}, bionicVictims={}, finalEvicted={}, cacheSize={}",
                String.format("%.2f", hybridAlpha), strictVictims.size(), bionicVictims.size(),
                victims.size(), cache.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  冷热数据分层 — L1 Cache → L2 SemanticNode
    // ════════════════════════════════════════════════════════════════

    /**
     * 将驱逐的缓存条目流入 SemanticNode（L2 持久存储）。
     * <p>
     * 类比 Linux 的 kswapd：当物理内存不足时，页面被换出到
     * 交换分区。这里 SemanticCacheManager 是 L1 缓存，
     * SemanticNode 是 L2 持久双重索引存储。
     */
    private void influxToSemanticNode(List<CacheEntry> victims) {
        try {
            com.ouisani.aios.core.VfsManager vfs = com.ouisani.aios.core.VfsManager.instance();
            Optional<com.ouisani.aios.core.VfsNode> nodeOpt = vfs.resolve("/dev/semantic");
            if (nodeOpt.isPresent() && nodeOpt.get() instanceof com.ouisani.aios.vfs.SemanticNode semanticNode) {
                semanticNode.influxBatchFromCache(victims);
                log.info("[Semantic Cache] Influx: {} evicted entries → /dev/semantic (L2)", victims.size());
            }
        } catch (Exception e) {
            log.warn("[Semantic Cache] Failed to influx evicted entries to SemanticNode: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Diagnostics
    // ════════════════════════════════════════════════════════════════

    public int cacheSize() {
        return cache.size();
    }

    /**
     * Get a snapshot of all cache entries (for Dream Daemon scanning).
     * <p>
     * Returns a defensive copy to prevent concurrent modification.
     */
    public List<CacheEntry> getCacheEntries() {
        return new ArrayList<>(cache);
    }

    public int capacity() {
        return capacity;
    }

    public EvictionMode evictionMode() {
        return evictionMode;
    }

    public String activeStrategyName() {
        return activeStrategy.strategyName();
    }

    public void clear() {
        cache.clear();
        log.info("[Semantic Cache] Cache cleared");
    }

    /**
     * 获取当前 Swappable 的条目数量。
     */
    public long swappableCount() {
        return cache.stream().filter(CacheEntry::swappable).count();
    }

    /**
     * 获取所有 Swappable 条目的快照。
     */
    public List<CacheEntry> getSwappableEntries() {
        return cache.stream().filter(CacheEntry::swappable).toList();
    }

    /**
     * 获取平均突触权重。
     */
    public double averageSynapticWeight() {
        return cache.stream().mapToDouble(CacheEntry::synapticWeight).average().orElse(0);
    }

    /**
     * 获取衰减统计。
     */
    public Map<String, Object> decayStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDecayCycles", totalDecayCycles);
        stats.put("totalSwappableMarked", totalSwappableMarked);
        stats.put("totalForgotten", totalForgotten);
        stats.put("currentSwappable", swappableCount());
        stats.put("averageSynapticWeight", averageSynapticWeight());
        stats.put("decayFactor", decayFactor);
        stats.put("swappableThreshold", SWAPPABLE_THRESHOLD);
        stats.put("forgetThreshold", FORGET_THRESHOLD);
        return stats;
    }

    // ════════════════════════════════════════════════════════════════
    //  CacheEntry — the fundamental unit of semantic memory
    // ════════════════════════════════════════════════════════════════

    /**
     * A semantic cache entry — a memory trace in the AIOS cognitive substrate.
     * <p>
     * Core attributes (preserved from the original design):
     * <ul>
     *   <li>{@code queryVector} — the embedding of the original query</li>
     *   <li>{@code responseText} — the LLM's cached response</li>
     * </ul>
     * <p>
     * Extended attributes for eviction strategies:
     * <ul>
     *   <li>{@code createdAt} — timestamp when the entry was encoded</li>
     *   <li>{@code lastAccessTime} — timestamp of the most recent access (LRU)</li>
     *   <li>{@code accessCount} — number of times this entry has been retrieved (LFU/activation)</li>
     *   <li>{@code metadata} — extensible map for emotion tags, source info, etc.</li>
     * </ul>
     * <p>
     * SysTick-driven decay attributes:
     * <ul>
     *   <li>{@code synapticWeight} — 突触权重，初始 1.0，每个 Tick 按艾宾浩斯曲线衰减</li>
     *   <li>{@code swappable} — 是否可交换到磁盘（权重低于阈值时标记）</li>
     * </ul>
     */
    public static final class CacheEntry {

        private final float[] queryVector;
        private final String responseText;
        private final long createdAt;
        private volatile long lastAccessTime;
        private volatile int accessCount;
        private final Map<String, Object> metadata;

        /** 突触权重 — 由 SysTick 驱动的艾宾浩斯衰减 */
        private volatile double synapticWeight;

        /** 是否可交换 — 权重低于阈值时标记为 true */
        private volatile boolean swappable;

        public CacheEntry(float[] queryVector, String responseText) {
            this(queryVector, responseText, new HashMap<>());
        }

        public CacheEntry(float[] queryVector, String responseText, Map<String, Object> metadata) {
            this.queryVector = queryVector;
            this.responseText = responseText;
            this.createdAt = System.currentTimeMillis();
            this.lastAccessTime = this.createdAt;
            this.accessCount = 1;
            this.metadata = metadata != null ? metadata : new HashMap<>();
            this.synapticWeight = 1.0;
            this.swappable = false;
        }

        /** Record an access event — updates lastAccessTime, increments accessCount, and re-strengthens synaptic weight. */
        public void recordAccess() {
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount++;
            // 访问时重新强化突触权重（模拟神经科学的"再激活增强"效应）
            this.synapticWeight = Math.min(1.0, this.synapticWeight + 0.2);
            this.swappable = false;
        }

        // ── Getters ──

        public float[] queryVector() { return queryVector; }
        public String responseText() { return responseText; }
        public long createdAt() { return createdAt; }
        public long lastAccessTime() { return lastAccessTime; }
        public int accessCount() { return accessCount; }
        public Map<String, Object> metadata() { return metadata; }

        /** 突触权重 — 0.0（完全遗忘）到 1.0（完全记忆） */
        public double synapticWeight() { return synapticWeight; }

        /** 是否可交换到磁盘 */
        public boolean swappable() { return swappable; }

        /**
         * 由 SysTick 衰减引擎调用 — 降低突触权重。
         * @param decayFactor 衰减因子 (0, 1)，每次 Tick 乘以此值
         */
        public void decaySynapticWeight(double decayFactor) {
            this.synapticWeight *= decayFactor;
        }

        /**
         * 标记此条目为可交换。
         */
        public void markSwappable() {
            this.swappable = true;
        }

        /**
         * 取消可交换标记（例如被重新访问时）。
         */
        public void unmarkSwappable() {
            this.swappable = false;
        }

        /**
         * Convenience: get a metadata value.
         */
        public Object meta(String key) {
            return metadata.get(key);
        }

        /**
         * Convenience: set a metadata value.
         */
        public void meta(String key, Object value) {
            metadata.put(key, value);
        }

        @Override
        public String toString() {
            return "CacheEntry{responseLen=" + responseText.length()
                    + ", accessCount=" + accessCount
                    + ", synapticWeight=" + String.format("%.4f", synapticWeight)
                    + ", swappable=" + swappable
                    + ", age=" + (System.currentTimeMillis() - createdAt) + "ms"
                    + ", metadata=" + metadata.keySet() + "}";
        }
    }
}
