package com.ouisani.aios.core.learning.instinct;

import com.ouisani.aios.core.cache.SemanticCacheManager.CacheEntry;
import com.ouisani.aios.core.cache.eviction.MemoryEvictionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Instinct 感知的缓存淘汰策略 — 根据学习到的 instinct 动态调整淘汰行为。
 * <p>
 * 借鉴 ECC 的 instinct 渐进学习系统，让缓存策略根据使用模式自动调优。
 * <p>
 * 核心思想：当 InstinctStore 中存在与缓存条目相关的 instinct 时，
 * 根据 instinct 的置信度调整该条目的保留优先级。
 * <ul>
 *   <li>高置信度 instinct 关联的条目 → 保留优先级提升（降低淘汰概率）</li>
 *   <li>低置信度或被反驳的 instinct 关联的条目 → 保留优先级降低（提高淘汰概率）</li>
 * </ul>
 *
 * <h3>OS 类比: Linux Kernel Swappiness Tuning</h3>
 * 类似 Linux 内核的 swappiness 参数，但更精细：
 * 不是全局统一的交换倾向，而是基于学习到的使用模式动态调整每个页面的交换优先级。
 *
 * @see Instinct
 * @see InstinctStore
 * @see MemoryEvictionStrategy
 */
public final class InstinctAwareEvictionStrategy implements MemoryEvictionStrategy {

    private static final Logger log = LoggerFactory.getLogger(InstinctAwareEvictionStrategy.class);

    /** 默认驱逐比例 */
    private static final double DEFAULT_EVICTION_RATIO = 0.20;

    /** Instinct 置信度的保留权重 — 置信度越高，保留优先级越高 */
    private static final double CONFIDENCE_WEIGHT = 0.5;

    /** 基础保留分数 */
    private static final double BASE_RETENTION = 0.5;

    private final double evictionRatio;
    private final String projectHash;
    private final AtomicLong instinctAdjustments = new AtomicLong(0);

    /**
     * @param projectHash 项目哈希（用于查询项目级 instinct）
     */
    public InstinctAwareEvictionStrategy(String projectHash) {
        this(projectHash, DEFAULT_EVICTION_RATIO);
    }

    public InstinctAwareEvictionStrategy(String projectHash, double evictionRatio) {
        if (evictionRatio <= 0 || evictionRatio > 1) {
            throw new IllegalArgumentException("Eviction ratio must be in (0, 1]");
        }
        this.projectHash = projectHash;
        this.evictionRatio = evictionRatio;
    }

    @Override
    public List<CacheEntry> selectForEviction(List<CacheEntry> entries, int capacity) {
        if (entries.size() <= capacity) {
            return List.of();
        }

        int overflow = entries.size() - capacity;
        int toEvict = Math.max(overflow, (int) Math.ceil(entries.size() * evictionRatio));

        // 获取项目的 instinct 列表
        List<Instinct> instincts = InstinctStore.instance().getForProject(projectHash);
        Map<String, Double> instinctScores = buildInstinctScoreMap(instincts);

        // 按保留分数升序排序（最低分先淘汰）
        long now = System.currentTimeMillis();
        entries.stream()
                .sorted((a, b) -> Double.compare(
                        computeRetentionScore(a, now, instinctScores),
                        computeRetentionScore(b, now, instinctScores)
                ))
                .limit(toEvict)
                .toList();

        List<CacheEntry> victims = entries.stream()
                .sorted((a, b) -> Double.compare(
                        computeRetentionScore(a, now, instinctScores),
                        computeRetentionScore(b, now, instinctScores)
                ))
                .limit(toEvict)
                .toList();

        if (!victims.isEmpty()) {
            log.debug("[InstinctAware] 淘汰 {} 个条目, instinct 调整次数={}",
                    victims.size(), instinctAdjustments.get());
        }

        return victims;
    }

    @Override
    public void onAccess(CacheEntry entry) {
        // 缓存命中时，检查是否有相关 instinct
        if (entry.metadata() == null) return;

        String toolName = getMetadataString(entry, "tool");
        if (toolName == null) return;

        // 检查是否有关于此工具的 instinct
        List<Instinct> instincts = InstinctStore.instance().getForProject(projectHash);
        for (Instinct instinct : instincts) {
            if (instinct.action().contains(toolName) && instinct.confidence() > 0.7) {
                // 高置信度 instinct 命中，确认此 instinct
                instinct.confirm();
                instinctAdjustments.incrementAndGet();
                break;
            }
        }
    }

    @Override
    public String strategyName() {
        return "InstinctAware(project=" + projectHash + ")";
    }

    /**
     * 计算缓存条目的保留分数。
     * <p>
     * 保留分数 = 基础保留 + instinct 调整 + 访问频率加成
     * <ul>
     *   <li>分数越高 → 保留优先级越高（越不容易被淘汰）</li>
     *   <li>分数越低 → 越容易被淘汰</li>
     * </ul>
     *
     * @param entry          缓存条目
     * @param now            当前时间戳
     * @param instinctScores instinct 分数映射
     * @return 保留分数 [0.0, 1.0]
     */
    private double computeRetentionScore(CacheEntry entry, long now,
                                         Map<String, Double> instinctScores) {
        // 基础保留分数
        double retention = BASE_RETENTION;

        // 访问频率加成（访问次数越多，保留分数越高）
        int accessCount = entry.accessCount();
        retention += Math.min(0.3, accessCount * 0.05);

        // 时间衰减（越久没访问，保留分数越低）
        long elapsedMs = Math.max(0, now - entry.lastAccessTime());
        double timeDecay = Math.exp(-elapsedMs / 3_600_000.0); // 1小时时间常数
        retention *= (0.5 + 0.5 * timeDecay); // 时间衰减最多减半

        // Instinct 调整
        String toolName = getMetadataString(entry, "tool");
        if (toolName != null && instinctScores.containsKey(toolName)) {
            double instinctScore = instinctScores.get(toolName);
            // instinct 分数越高，保留分数提升越多
            retention += CONFIDENCE_WEIGHT * instinctScore;
        }

        return Math.max(0.0, Math.min(1.0, retention));
    }

    /**
     * 构建 instinct 分数映射: toolName → 置信度。
     */
    private Map<String, Double> buildInstinctScoreMap(List<Instinct> instincts) {
        Map<String, Double> map = new ConcurrentHashMap<>();
        for (Instinct instinct : instincts) {
            // 从 action 中提取工具名
            // action 格式如 "prefer_file_read_over_bash_cat" → 提取 "file_read"
            String action = instinct.action();
            if (action.startsWith("prefer_")) {
                String rest = action.substring(7); // "file_read_over_bash_cat"
                int overIdx = rest.indexOf("_over_");
                if (overIdx > 0) {
                    String preferredTool = rest.substring(0, overIdx);
                    map.merge(preferredTool, instinct.confidence(), Math::max);
                }
            } else if (action.startsWith("auto_chain:")) {
                // 工作流模式，提取序列中的工具
                String seq = action.substring(11);
                String[] tools = seq.split("→");
                for (String tool : tools) {
                    map.merge(tool.trim(), instinct.confidence() * 0.5, Math::max);
                }
            }
        }
        return map;
    }

    private String getMetadataString(CacheEntry entry, String key) {
        if (entry.metadata() == null) return null;
        Object val = entry.metadata().get(key);
        return val != null ? val.toString() : null;
    }

    /** 获取 instinct 调整统计 */
    public long getInstinctAdjustmentCount() {
        return instinctAdjustments.get();
    }
}
