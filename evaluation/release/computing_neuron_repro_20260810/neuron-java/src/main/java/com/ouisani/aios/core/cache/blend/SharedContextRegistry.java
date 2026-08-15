package com.ouisani.aios.core.cache.blend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 共享上下文注册表 — 跟踪跨 Agent 的共享上下文片段。
 * <p>
 * 借鉴 LMCache 的 BlendDirectory：维护跨 Agent 的指纹目录。
 * <p>
 * 当多个 Agent 的 Prompt 包含相同的长篇文档时（如 API 文档、项目源码树），
 * 引擎会自动把这篇文档提取出来作为公共挂载缓存，单独预热一次，
 * 然后通过内核分发给这两个 Agent。
 * <p>
 * <h3>工作流程</h3>
 * <ol>
 *   <li>Agent A 注册其 Prompt 的分块哈希 → {@link #register(String, List)}</li>
 *   <li>Agent B 注册其 Prompt 的分块哈希 → {@link #register(String, List)}</li>
 *   <li>引擎调用 {@link #findSharedContexts()} 发现 A 和 B 有 90% 的重合度</li>
 *   <li>引擎提取公共文档作为公共挂载缓存，单独预热一次</li>
 *   <li>通过内核分发给 A 和 B，避免重复 prefill</li>
 * </ol>
 *
 * @see CacheBlendEngine
 * @see PromptHasher
 */
public final class SharedContextRegistry {

    private static final Logger log = LoggerFactory.getLogger(SharedContextRegistry.class);

    /** 默认重合度阈值 — 超过此阈值才提取为共享上下文 */
    public static final double DEFAULT_OVERLAP_THRESHOLD = 0.5;

    // ── Singleton ──

    private static final class Holder {
        static final SharedContextRegistry INSTANCE = new SharedContextRegistry();
    }

    public static SharedContextRegistry instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    /** Agent ID → 该 Agent 的分块哈希列表 */
    private final ConcurrentHashMap<String, List<String>> agentChunkHashes = new ConcurrentHashMap<>();

    /** Agent ID → 该 Agent 的原始 Prompt 内容（用于提取共享片段） */
    private final ConcurrentHashMap<String, String> agentPrompts = new ConcurrentHashMap<>();

    /** 哈希 → 拥有此哈希的 Agent ID 集合（反向索引） */
    private final ConcurrentHashMap<String, Set<String>> hashToAgents = new ConcurrentHashMap<>();

    private SharedContextRegistry() {
    }

    // ════════════════════════════════════════════════════════════════
    //  注册
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册一个 Agent 的 Prompt 分块哈希。
     *
     * @param agentId     Agent 标识
     * @param chunkHashes 分块哈希列表
     * @param promptContent 原始 Prompt 内容（用于提取共享片段）
     */
    public void register(String agentId, List<String> chunkHashes, String promptContent) {
        Objects.requireNonNull(agentId, "agentId cannot be null");
        Objects.requireNonNull(chunkHashes, "chunkHashes cannot be null");

        // 清理旧数据
        unregister(agentId);

        agentChunkHashes.put(agentId, List.copyOf(chunkHashes));
        if (promptContent != null) {
            agentPrompts.put(agentId, promptContent);
        }

        // 建立反向索引
        for (String hash : chunkHashes) {
            hashToAgents.computeIfAbsent(hash, k -> ConcurrentHashMap.newKeySet()).add(agentId);
        }

        log.debug("[SharedContextRegistry] 已注册: agent={}, chunks={}",
                agentId, chunkHashes.size());
    }

    /**
     * 注销一个 Agent 的注册。
     *
     * @param agentId Agent 标识
     */
    public void unregister(String agentId) {
        List<String> oldHashes = agentChunkHashes.remove(agentId);
        agentPrompts.remove(agentId);
        if (oldHashes != null) {
            for (String hash : oldHashes) {
                Set<String> agents = hashToAgents.get(hash);
                if (agents != null) {
                    agents.remove(agentId);
                    if (agents.isEmpty()) {
                        hashToAgents.remove(hash);
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  共享上下文发现
    // ════════════════════════════════════════════════════════════════

    /**
     * 发现所有共享上下文片段 — 被多个 Agent 共同引用的哈希块。
     * <p>
     * 借鉴 LMCache 的 BlendDirectory：维护跨 Agent 的指纹目录，
     * 当多个 Agent 引用相同的哈希块时，将其提取为共享上下文。
     *
     * @return 共享哈希块列表（每个元素包含哈希和引用它的 Agent 列表）
     */
    public List<SharedChunk> findSharedContexts() {
        return findSharedContexts(DEFAULT_OVERLAP_THRESHOLD);
    }

    /**
     * 发现所有共享上下文片段。
     *
     * @param overlapThreshold 重合度阈值（0.0 ~ 1.0）
     * @return 共享哈希块列表
     */
    public List<SharedChunk> findSharedContexts(double overlapThreshold) {
        List<SharedChunk> shared = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : hashToAgents.entrySet()) {
            if (entry.getValue().size() >= 2) {
                // 至少 2 个 Agent 引用了此哈希块
                SharedChunk chunk = new SharedChunk(
                        entry.getKey(),
                        Set.copyOf(entry.getValue()),
                        entry.getValue().size()
                );
                shared.add(chunk);
            }
        }

        // 按引用数降序排列
        shared.sort(Comparator.comparingInt(SharedChunk::agentCount).reversed());

        log.debug("[SharedContextRegistry] 发现 {} 个共享上下文片段", shared.size());
        return shared;
    }

    /**
     * 计算两个 Agent 之间的 Prompt 重合度。
     *
     * @param agentId1 第一个 Agent
     * @param agentId2 第二个 Agent
     * @return 重合度（0.0 ~ 1.0）
     */
    public double overlapRatio(String agentId1, String agentId2) {
        List<String> hashes1 = agentChunkHashes.get(agentId1);
        List<String> hashes2 = agentChunkHashes.get(agentId2);
        if (hashes1 == null || hashes2 == null || hashes1.isEmpty() || hashes2.isEmpty()) {
            return 0.0;
        }
        Set<String> set1 = new HashSet<>(hashes1);
        set1.retainAll(hashes2);
        int minChunks = Math.min(hashes1.size(), hashes2.size());
        return minChunks == 0 ? 0.0 : (double) set1.size() / minChunks;
    }

    /**
     * 查找与指定 Agent 有高重合度的所有其他 Agent。
     *
     * @param agentId         Agent 标识
     * @param overlapThreshold 重合度阈值
     * @return Agent ID → 重合度 的映射
     */
    public Map<String, Double> findOverlappingAgents(String agentId, double overlapThreshold) {
        Map<String, Double> result = new HashMap<>();
        for (String otherId : agentChunkHashes.keySet()) {
            if (otherId.equals(agentId)) continue;
            double ratio = overlapRatio(agentId, otherId);
            if (ratio >= overlapThreshold) {
                result.put(otherId, ratio);
            }
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  查询
    // ════════════════════════════════════════════════════════════════

    /** 已注册的 Agent 数量 */
    public int agentCount() {
        return agentChunkHashes.size();
    }

    /** 总哈希块数量 */
    public int totalChunks() {
        return hashToAgents.size();
    }

    /** 共享哈希块数量（被 >= 2 个 Agent 引用） */
    public long sharedChunkCount() {
        return hashToAgents.values().stream().filter(s -> s.size() >= 2).count();
    }

    /** 清空所有注册（用于测试） */
    void clear() {
        agentChunkHashes.clear();
        agentPrompts.clear();
        hashToAgents.clear();
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 共享哈希块 — 被多个 Agent 共同引用的内容片段。
     *
     * @param chunkHash 分块哈希
     * @param agents    引用此哈希的 Agent 集合
     * @param agentCount 引用此哈希的 Agent 数量
     */
    public record SharedChunk(
            String chunkHash,
            Set<String> agents,
            int agentCount
    ) {}
}
