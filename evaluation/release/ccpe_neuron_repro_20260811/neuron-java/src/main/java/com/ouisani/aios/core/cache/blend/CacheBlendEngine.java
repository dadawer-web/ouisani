package com.ouisani.aios.core.cache.blend;

import com.ouisani.aios.core.cache.kvstate.KvCacheRef;
import com.ouisani.aios.core.cache.kvstate.KvCacheRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CacheBlend 引擎 — 智能截断与拼装，实现非前缀缓存复用。
 * <p>
 * 借鉴 LMCache 的 CacheBlend 机制：
 * <ul>
 *   <li>LMCache 有个绝活叫非前缀复用（CacheBlend）</li>
 *   <li>通过滚动哈希 + 直接寻址表实现 O(1) 的非前缀 chunk 匹配</li>
 *   <li>配合 RoPE 重旋转实现位置无关复用</li>
 * </ul>
 * <p>
 * <h3>AIOS 落地场景</h3>
 * 结合 AIOS 的 LlmRouter（模型路由器），在调度任务前：
 * <ol>
 *   <li>计算当前请求的 Prompt AST Hash</li>
 *   <li>对比 SemanticCacheManager 中已有的上下文哈希</li>
 *   <li>如果发现给 Python_Coder 和 Code_Reviewer 的上下文有 90% 的重合度
 *       （比如都包含了某个长篇 API 文档）</li>
 *   <li>引擎会自动把这篇文档提取出来作为公共挂载缓存</li>
 *   <li>单独预热一次，然后通过内核分发给这两个 Agent</li>
 * </ol>
 * <p>
 * <h3>与 LMCache 的对应关系</h3>
 * <table>
 *   <tr><th>LMCache</th><th>AIOS CacheBlendEngine</th></tr>
 *   <tr><td>BlendTokenRangeMatcherV3</td><td>{@link #findOverlap}</td></tr>
 *   <tr><td>BlendDirectory</td><td>{@link SharedContextRegistry}</td></tr>
 *   <tr><td>rolling_hash_windows</td><td>{@link PromptHasher#chunkHashes}</td></tr>
 *   <tr><td>cb_unified_lookup</td><td>{@link #blendPrompt}</td></tr>
 *   <tr><td>MemoryObj</td><td>{@link KvCacheRef}</td></tr>
 * </table>
 *
 * @see PromptHasher
 * @see SharedContextRegistry
 * @see KvCacheRegistry
 */
public final class CacheBlendEngine {

    private static final Logger log = LoggerFactory.getLogger(CacheBlendEngine.class);

    /** 默认重合度阈值 — 超过此阈值才提取为共享上下文 */
    public static final double DEFAULT_OVERLAP_THRESHOLD = 0.5;

    /** 默认分块大小 */
    public static final int DEFAULT_CHUNK_SIZE = PromptHasher.DEFAULT_CHUNK_SIZE;

    // ── Singleton ──

    private static final class Holder {
        static final CacheBlendEngine INSTANCE = new CacheBlendEngine();
    }

    public static CacheBlendEngine instance() {
        return Holder.INSTANCE;
    }

    private CacheBlendEngine() {
    }

    // ════════════════════════════════════════════════════════════════
    //  哈希计算
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算 Prompt 的内容哈希 — 用于缓存匹配。
     * <p>
     * 借鉴 LMCache 的 AST Hash：计算 Prompt 的唯一标识。
     *
     * @param prompt Prompt 文本
     * @return SHA-256 哈希
     */
    public String computeHash(String prompt) {
        return PromptHasher.hash(prompt);
    }

    /**
     * 计算 Prompt 的分块哈希列表 — 用于非前缀匹配。
     * <p>
     * 借鉴 LMCache 的滚动哈希扫描：将 Prompt 按固定大小分块，
     * 每块独立计算哈希，使得非连续的相同片段也能被匹配到。
     *
     * @param prompt Prompt 文本
     * @return 分块哈希列表
     */
    public List<String> computeChunkHashes(String prompt) {
        return PromptHasher.chunkHashes(prompt, DEFAULT_CHUNK_SIZE);
    }

    // ════════════════════════════════════════════════════════════════
    //  重合度分析
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算两个 Prompt 之间的内容重合度。
     * <p>
     * 借鉴 LMCache 的 CacheBlend 匹配率计算。
     *
     * @param prompt1 第一个 Prompt
     * @param prompt2 第二个 Prompt
     * @return 重合度（0.0 ~ 1.0）
     */
    public double findOverlap(String prompt1, String prompt2) {
        return PromptHasher.similarity(prompt1, prompt2);
    }

    /**
     * 计算多个 Prompt 之间的两两重合度矩阵。
     *
     * @param prompts Prompt 列表
     * @return 重合度矩阵（prompts.length × prompts.length）
     */
    public double[][] overlapMatrix(List<String> prompts) {
        int n = prompts.size();
        double[][] matrix = new double[n][n];
        List<List<String>> allHashes = prompts.stream()
                .map(this::computeChunkHashes)
                .collect(Collectors.toList());

        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                if (i == j) {
                    matrix[i][j] = 1.0;
                } else {
                    Set<String> set1 = new HashSet<>(allHashes.get(i));
                    set1.retainAll(allHashes.get(j));
                    int minChunks = Math.min(allHashes.get(i).size(), allHashes.get(j).size());
                    matrix[i][j] = minChunks == 0 ? 0.0 : (double) set1.size() / minChunks;
                    matrix[j][i] = matrix[i][j];
                }
            }
        }
        return matrix;
    }

    // ════════════════════════════════════════════════════════════════
    //  共享上下文提取
    // ════════════════════════════════════════════════════════════════

    /**
     * 从多个 Prompt 中提取公共上下文片段。
     * <p>
     * 借鉴 LMCache 的 CacheBlend：当多个 Agent 的 Prompt 包含相同的长篇文档时，
     * 引擎会自动把这篇文档提取出来作为公共挂载缓存。
     *
     * @param prompts Prompt 列表
     * @return 提取的共享上下文片段列表
     */
    public List<SharedContext> extractSharedContext(List<String> prompts) {
        return extractSharedContext(prompts, DEFAULT_OVERLAP_THRESHOLD);
    }

    /**
     * 从多个 Prompt 中提取公共上下文片段。
     *
     * @param prompts          Prompt 列表
     * @param overlapThreshold 重合度阈值
     * @return 提取的共享上下文片段列表
     */
    public List<SharedContext> extractSharedContext(List<String> prompts, double overlapThreshold) {
        if (prompts == null || prompts.size() < 2) {
            return List.of();
        }

        // 计算所有 Prompt 的分块哈希
        List<List<String>> allChunkHashes = prompts.stream()
                .map(this::computeChunkHashes)
                .collect(Collectors.toList());

        // 统计每个哈希块被多少个 Prompt 引用
        Map<String, Integer> hashRefCount = new HashMap<>();
        for (List<String> chunks : allChunkHashes) {
            // 每个 Prompt 中去重
            Set<String> uniqueChunks = new HashSet<>(chunks);
            for (String hash : uniqueChunks) {
                hashRefCount.merge(hash, 1, Integer::sum);
            }
        }

        // 提取被 >= 2 个 Prompt 引用的哈希块
        int minAgents = (int) Math.ceil(prompts.size() * overlapThreshold);
        if (minAgents < 2) minAgents = 2;

        List<SharedContext> shared = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : hashRefCount.entrySet()) {
            if (entry.getValue() >= minAgents) {
                // 找到第一个包含此哈希的 Prompt，提取对应的文本片段
                String chunkContent = extractChunkContent(prompts, allChunkHashes, entry.getKey());
                if (chunkContent != null && !chunkContent.isEmpty()) {
                    shared.add(new SharedContext(
                            entry.getKey(),
                            chunkContent,
                            entry.getValue(),
                            prompts.size()
                    ));
                }
            }
        }

        // 按引用数降序排列
        shared.sort(Comparator.comparingInt(SharedContext::agentCount).reversed());

        log.info("[CacheBlendEngine] 从 {} 个 Prompt 中提取了 {} 个共享上下文片段 (阈值={})",
                prompts.size(), shared.size(), overlapThreshold);
        return shared;
    }

    /**
     * 注册 Agent 的 Prompt 到共享上下文注册表。
     *
     * @param agentId Agent 标识
     * @param prompt  Prompt 内容
     */
    public void registerAgentPrompt(String agentId, String prompt) {
        List<String> chunkHashes = computeChunkHashes(prompt);
        SharedContextRegistry.instance().register(agentId, chunkHashes, prompt);
    }

    /**
     * 注销 Agent 的 Prompt。
     *
     * @param agentId Agent 标识
     */
    public void unregisterAgentPrompt(String agentId) {
        SharedContextRegistry.instance().unregister(agentId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Prompt 拼装
    // ════════════════════════════════════════════════════════════════

    /**
     * 拼装最终 Prompt — 将共享上下文和原始 Prompt 合并。
     * <p>
     * 借鉴 LMCache 的 cb_unified_lookup：
     * <ol>
     *   <li>Prefix leg: 检查共享上下文是否已有 KV Cache</li>
     *   <li>Sparse leg: 对非前缀匹配片段提交稀疏预取</li>
     * </ol>
     * <p>
     * AIOS 的实现：将共享上下文放在前面（利用前缀缓存），
     * 原始 Prompt 的非共享部分放在后面。
     *
     * @param originalPrompt 原始 Prompt
     * @param sharedContexts 共享上下文列表
     * @return 拼装后的 Prompt
     */
    public String blendPrompt(String originalPrompt, List<SharedContext> sharedContexts) {
        if (sharedContexts == null || sharedContexts.isEmpty()) {
            return originalPrompt;
        }

        StringBuilder sb = new StringBuilder();

        // 共享上下文放在前面（利用前缀缓存）
        for (SharedContext ctx : sharedContexts) {
            sb.append(ctx.content());
            if (!ctx.content().endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n');
        }

        // 原始 Prompt 放在后面
        sb.append(originalPrompt);

        return sb.toString();
    }

    /**
     * 检查共享上下文是否已有 KV Cache — 如果有，Agent 可以直接引用而不需要重新 prefill。
     *
     * @param sharedContext 共享上下文
     * @return KvCacheRef 如果缓存存在，否则 null
     */
    public KvCacheRef checkCacheHit(SharedContext sharedContext) {
        List<KvCacheRef> refs = KvCacheRegistry.instance().lookupByHash(sharedContext.contentHash());
        return refs.isEmpty() ? null : refs.get(0);
    }

    // ════════════════════════════════════════════════════════════════
    //  内部方法
    // ════════════════════════════════════════════════════════════════

    private String extractChunkContent(
            List<String> prompts,
            List<List<String>> allChunkHashes,
            String targetHash) {
        for (int i = 0; i < prompts.size(); i++) {
            List<String> chunks = allChunkHashes.get(i);
            for (int j = 0; j < chunks.size(); j++) {
                if (chunks.get(j).equals(targetHash)) {
                    int start = j * DEFAULT_CHUNK_SIZE;
                    int end = Math.min(start + DEFAULT_CHUNK_SIZE, prompts.get(i).length());
                    return prompts.get(i).substring(start, end);
                }
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 共享上下文 — 被多个 Agent 共同引用的内容片段。
     *
     * @param contentHash 内容哈希
     * @param content      内容文本
     * @param agentCount   引用此内容的 Agent 数量
     * @param totalAgents  总 Agent 数量
     */
    public record SharedContext(
            String contentHash,
            String content,
            int agentCount,
            int totalAgents
    ) {

        /**
         * 覆盖率 — 引用此内容的 Agent 占比。
         *
         * @return 覆盖率（0.0 ~ 1.0）
         */
        public double coverage() {
            return totalAgents == 0 ? 0.0 : (double) agentCount / totalAgents;
        }

        @Override
        public String toString() {
            return "SharedContext{" +
                    "hash='" + contentHash.substring(0, Math.min(12, contentHash.length())) + "...'" +
                    ", len=" + content.length() +
                    ", agents=" + agentCount + "/" + totalAgents +
                    ", coverage=" + String.format("%.1f%%", coverage() * 100) +
                    '}';
        }
    }
}
