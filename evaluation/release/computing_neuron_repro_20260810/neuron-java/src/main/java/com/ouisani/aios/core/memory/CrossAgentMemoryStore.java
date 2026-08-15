package com.ouisani.aios.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 跨 Agent 记忆去重 + 回声防护存储 — 借鉴 Headroom memory/sync.py。
 * <p>
 * <b>解决三个痛点：</b>
 * <ol>
 *   <li><b>内容去重</b> — OmniMotherAgent 和 SubAgent 会重复存储相同信息。
 *       用 sha256(content)[:16] 做内容级去重（不只是 entity 级）。</li>
 *   <li><b>回声防护</b> — 从 Agent A 导入的记忆不会再导回 Agent A。
 *       通过 source_agent + sync_direction 元数据标记防止循环。</li>
 *   <li><b>记忆冒泡</b> — importance 高的记忆自动从 TURN 级提升到 USER 级别。
 *       避免高价值信息随会话结束而丢失。</li>
 * </ol>
 * <p>
 * <b>OS 类比：</b>相当于 Linux 的 inode 去重（硬链接）+ 网络防环（TTL）+
 * 内存晋升（kswapd 把热页从 inactive 提升到 active LRU）。
 */
public class CrossAgentMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(CrossAgentMemoryStore.class);

    /** 单例 */
    private static final CrossAgentMemoryStore INSTANCE = new CrossAgentMemoryStore();

    public static CrossAgentMemoryStore instance() {
        return INSTANCE;
    }

    private CrossAgentMemoryStore() {}

    // ════════════════════════════════════════════════════════════════
    //  数据结构
    // ════════════════════════════════════════════════════════════════

    /** 同步方向 — 借鉴 Headroom sync_direction */
    public enum SyncDirection {
        IMPORT,   // 从 Agent 文件导入到共享 DB
        EXPORT,   // 从共享 DB 导出到 Agent 文件
        LOCAL     // Agent 本地写入（非同步来源）
    }

    /** 记忆作用域级别 — 借鉴 Headroom 的 memory scope */
    public enum MemoryScope {
        TURN,     // 会话级（随会话结束而清除）
        SESSION,  // 会话批次级（跨多轮但同一会话）
        USER      // 用户级（永久持久化）
    }

    /**
     * 跨 Agent 记忆条目 — 借鉴 Headroom AgentMemory + DB Memory。
     * <p>
     * 每条记忆携带来源元数据，用于去重和回声防护。
     */
    public static class CrossAgentMemory {
        final String id;
        final String content;
        final String contentHash;        // sha256(content)[:16]
        final String sourceAgent;        // 产生此记忆的 Agent
        final SyncDirection syncDirection; // 导入/导出/本地
        volatile MemoryScope scope;      // 作用域（可被冒泡提升）
        volatile double importance;      // 重要性 0.0-1.0
        final long createdAt;
        volatile long lastAccessed;
        volatile int accessCount;

        public CrossAgentMemory(String id, String content, String sourceAgent,
                                SyncDirection syncDirection, MemoryScope scope,
                                double importance) {
            this.id = id;
            this.content = content;
            this.contentHash = computeHash(content);
            this.sourceAgent = sourceAgent;
            this.syncDirection = syncDirection;
            this.scope = scope;
            this.importance = importance;
            this.createdAt = System.currentTimeMillis() / 1000;
            this.lastAccessed = this.createdAt;
            this.accessCount = 0;
        }

        public String content() { return content; }
        public String contentHash() { return contentHash; }
        public String sourceAgent() { return sourceAgent; }
        public SyncDirection syncDirection() { return syncDirection; }
        public MemoryScope scope() { return scope; }
        public double importance() { return importance; }
        public int accessCount() { return accessCount; }

        void setScope(MemoryScope s) { this.scope = s; }
        void setImportance(double i) { this.importance = i; }
        void recordAccess() {
            accessCount++;
            lastAccessed = System.currentTimeMillis() / 1000;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  存储 — content hash → 记忆条目
    // ════════════════════════════════════════════════════════════════

    /** 全局内容哈希索引 — 用于跨 Agent 去重 */
    private final ConcurrentHashMap<String, CrossAgentMemory> hashIndex = new ConcurrentHashMap<>();

    /** 按 Agent 分组的记忆 ID 集合 — 用于回声防护检查 */
    private final ConcurrentHashMap<String, Set<String>> agentMemoryIds = new ConcurrentHashMap<>();

    /** 按 scope 分组的记忆 — 用于冒泡提升 */
    private final ConcurrentHashMap<MemoryScope, Set<String>> scopeIndex = new ConcurrentHashMap<>();

    private final ReentrantLock lock = new ReentrantLock();

    // ════════════════════════════════════════════════════════════════
    //  核心方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 存储记忆（带去重） — 借鉴 Headroom sync_import()。
     * <p>
     * 如果内容哈希已存在（任何 Agent 存过相同内容），跳过存储并返回已有条目。
     * 这实现了<b>内容级去重</b> — 不只是 entity 级，是 content 级。
     *
     * @param agentId         Agent 标识
     * @param content         记忆内容
     * @param importance      重要性 0.0-1.0
     * @param scope           作用域
     * @return 存储结果（DEDUPED 表示去重跳过，STORED 表示新存储）
     */
    public StoreResult store(String agentId, String content,
                             double importance, MemoryScope scope) {
        return store(agentId, content, agentId, SyncDirection.LOCAL, importance, scope);
    }

    /**
     * 存储记忆（完整参数） — 带去重和回声防护元数据。
     *
     * @param agentId         目标 Agent 标识
     * @param content         记忆内容
     * @param sourceAgent     产生此记忆的源 Agent
     * @param syncDirection   同步方向
     * @param importance      重要性
     * @param scope           作用域
     * @return 存储结果
     */
    public StoreResult store(String agentId, String content,
                             String sourceAgent, SyncDirection syncDirection,
                             double importance, MemoryScope scope) {
        String hash = computeHash(content);

        lock.lock();
        try {
            // 1. 内容哈希去重 — 借鉴 Headroom existing_hashes 检查
            CrossAgentMemory existing = hashIndex.get(hash);
            if (existing != null) {
                log.debug("[CrossAgent] 内容去重命中: hash={}, sourceAgent={}, existingAgent={}",
                        hash, sourceAgent, existing.sourceAgent);
                existing.recordAccess();
                return new StoreResult(existing, StoreResult.Status.DEDUPED, hash);
            }

            // 2. 新建记忆条目
            String id = UUID.randomUUID().toString();
            CrossAgentMemory memory = new CrossAgentMemory(
                    id, content, sourceAgent, syncDirection, scope, importance
            );

            hashIndex.put(hash, memory);
            agentMemoryIds.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet()).add(id);
            scopeIndex.computeIfAbsent(scope, k -> ConcurrentHashMap.newKeySet()).add(id);

            log.debug("[CrossAgent] 存储新记忆: hash={}, agent={}, scope={}, importance={}",
                    hash, agentId, scope, String.format("%.2f", importance));
            return new StoreResult(memory, StoreResult.Status.STORED, hash);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 检索记忆 — 带回声防护过滤。
     * <p>
     * 借鉴 Headroom sync_export() 的回声防护：
     * 从 Agent A 导入的记忆（source_agent=A && sync_direction=IMPORT）
     * 不会再导回 Agent A。
     *
     * @param agentId  目标 Agent 标识
     * @param query    语义查询（简化版：子串匹配）
     * @param maxResults 最大返回数
     * @return 过滤后的记忆列表（不含回声）
     */
    public List<CrossAgentMemory> retrieve(String agentId, String query, int maxResults) {
        List<CrossAgentMemory> results = new ArrayList<>();
        String queryLower = query != null ? query.toLowerCase() : "";

        lock.lock();
        try {
            for (CrossAgentMemory mem : hashIndex.values()) {
                // ── 回声防护 — 借鉴 Headroom echo prevention ──
                // 如果记忆是从当前 Agent 导入的（agent → DB → agent 循环），跳过
                if (isEcho(mem, agentId)) {
                    continue;
                }

                // 简化版语义匹配：查询词在内容中出现
                if (!queryLower.isEmpty() && !mem.content.toLowerCase().contains(queryLower)) {
                    continue;
                }

                mem.recordAccess();
                results.add(mem);
                if (results.size() >= maxResults) break;
            }
        } finally {
            lock.unlock();
        }

        // 按重要性降序排列
        results.sort((a, b) -> Double.compare(b.importance, a.importance));
        return results;
    }

    /**
     * 检查是否为回声 — 借鉴 Headroom echo prevention。
     * <p>
     * 回声定义：记忆的 source_agent == 目标 agentId
     * 且 sync_direction == IMPORT（从该 Agent 导入的）。
     * 这种记忆不应再导回同一个 Agent。
     */
    public boolean isEcho(CrossAgentMemory memory, String targetAgentId) {
        return memory.sourceAgent.equals(targetAgentId)
                && memory.syncDirection == SyncDirection.IMPORT;
    }

    /**
     * 获取 Agent 已有的内容哈希集合 — 用于批量去重检查。
     * 借鉴 Headroom existing_hashes 构建。
     */
    public Set<String> existingHashes() {
        return Collections.unmodifiableSet(hashIndex.keySet());
    }

    /**
     * 检查内容哈希是否已存在。
     */
    public boolean exists(String content) {
        return hashIndex.containsKey(computeHash(content));
    }

    // ════════════════════════════════════════════════════════════════
    //  记忆冒泡 — 借鉴 Headroom memory promotion
    // ════════════════════════════════════════════════════════════════

    /** 冒泡触发阈值 — importance 超过此值的 TURN 级记忆会被提升 */
    private static final double BUBBLE_IMPORTANCE_THRESHOLD = 0.7;

    /** 冒泡触发阈值 — 访问次数超过此值的记忆会被提升 */
    private static final int BUBBLE_ACCESS_THRESHOLD = 3;

    /**
     * 记忆冒泡 — 将高重要性/高访问的记忆从低级别提升到高级别。
     * <p>
     * 借鉴 Headroom 的 memory promotion 机制：
     * - importance > 0.7 的 TURN 级记忆自动提升到 SESSION 级
     * - importance > 0.7 且 accessCount > 3 的 SESSION 级记忆提升到 USER 级
     * <p>
     * OS 类比：kswapd 把热页从 inactive LRU 提升到 active LRU。
     *
     * @return 提升的记忆数量
     */
    public int bubbleUp() {
        lock.lock();
        try {
            int promoted = 0;

            for (CrossAgentMemory mem : hashIndex.values()) {
                if (mem.scope == MemoryScope.TURN && mem.importance >= BUBBLE_IMPORTANCE_THRESHOLD) {
                    // TURN → SESSION
                    promote(mem, MemoryScope.SESSION);
                    promoted++;
                    log.debug("[Bubble] TURN→SESSION: hash={}, importance={}",
                            mem.contentHash, String.format("%.2f", mem.importance));
                } else if (mem.scope == MemoryScope.SESSION
                        && mem.importance >= BUBBLE_IMPORTANCE_THRESHOLD
                        && mem.accessCount >= BUBBLE_ACCESS_THRESHOLD) {
                    // SESSION → USER
                    promote(mem, MemoryScope.USER);
                    promoted++;
                    log.debug("[Bubble] SESSION→USER: hash={}, importance={}, access={}",
                            mem.contentHash, String.format("%.2f", mem.importance), mem.accessCount);
                }
            }

            if (promoted > 0) {
                log.info("[CrossAgent] 记忆冒泡: {} 条记忆被提升", promoted);
            }
            return promoted;
        } finally {
            lock.unlock();
        }
    }

    /** 执行作用域提升 */
    private void promote(CrossAgentMemory mem, MemoryScope newScope) {
        scopeIndex.getOrDefault(mem.scope, Collections.emptySet()).remove(mem.id);
        mem.setScope(newScope);
        scopeIndex.computeIfAbsent(newScope, k -> ConcurrentHashMap.newKeySet()).add(mem.id);
    }

    /**
     * 清理过期的 TURN 级记忆 — 会话结束时调用。
     *
     * @return 清理的数量
     */
    public int cleanupTurnScope() {
        lock.lock();
        try {
            int removed = 0;
            Iterator<Map.Entry<String, CrossAgentMemory>> it = hashIndex.entrySet().iterator();
            while (it.hasNext()) {
                CrossAgentMemory mem = it.next().getValue();
                if (mem.scope == MemoryScope.TURN) {
                    it.remove();
                    Set<String> ids = agentMemoryIds.get(mem.sourceAgent);
                    if (ids != null) ids.remove(mem.id);
                    scopeIndex.getOrDefault(MemoryScope.TURN, Collections.emptySet()).remove(mem.id);
                    removed++;
                }
            }
            if (removed > 0) {
                log.info("[CrossAgent] 清理 TURN 级记忆: {} 条", removed);
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalMemories", hashIndex.size());
        stats.put("turnScope", scopeIndex.getOrDefault(MemoryScope.TURN, Set.of()).size());
        stats.put("sessionScope", scopeIndex.getOrDefault(MemoryScope.SESSION, Set.of()).size());
        stats.put("userScope", scopeIndex.getOrDefault(MemoryScope.USER, Set.of()).size());
        stats.put("agentCount", agentMemoryIds.size());
        return stats;
    }

    // ════════════════════════════════════════════════════════════════
    //  存储结果
    // ════════════════════════════════════════════════════════════════

    /** 存储结果 */
    public record StoreResult(
            CrossAgentMemory memory,
            Status status,
            String contentHash
    ) {
        public enum Status {
            STORED,    // 新存储
            DEDUPED    // 内容去重跳过（哈希已存在）
        }

        public boolean isStored() { return status == Status.STORED; }
        public boolean isDeduped() { return status == Status.DEDUPED; }
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /** 计算 SHA-256[:16] 哈希 — 借鉴 Headroom AgentMemory.__post_init__ */
    public static String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {  // 8 bytes = 16 hex chars
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
