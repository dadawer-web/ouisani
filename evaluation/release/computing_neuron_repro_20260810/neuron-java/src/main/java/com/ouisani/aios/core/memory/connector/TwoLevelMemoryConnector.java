package com.ouisani.aios.core.memory.connector;

import com.ouisani.aios.core.memory.CrossAgentMemoryStore;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 两级存储连接器 — 热缓存 + 持久化冷存储的 Cache-Aside + Write-Through 混合模式。
 * <p>
 * 借鉴 Apix 的 {@code DataServerManager} + {@code execute_layer.py} 两级存储架构，
 * 适配 Java 并发模型。核心能力：
 * <ul>
 *   <li><b>Write-Through（写穿透）</b>：写时先写冷存储（source of truth），
 *       再 best-effort 写热缓存。冷存储决定成败，热缓存失败不影响主流程</li>
 *   <li><b>Cache-Aside（读旁路缓存）</b>：读时先查热缓存，miss 后回源冷存储，
 *       再 best-effort 回填热缓存</li>
 *   <li><b>透明降级</b>：热缓存不可用时自动降级为直连冷存储</li>
 * </ul>
 * <p>
 * <b>跨 Agent 去重 + 回声防护（借鉴 Headroom memory/sync.py）：</b>
 * 新增 {@link #storeWithDedup} / {@link #retrieveWithoutEcho} 方法 —
 * 通过 {@link CrossAgentMemoryStore} 实现内容哈希去重、回声防护和记忆冒泡。
 * OmniMotherAgent 和 SubAgent 重复存储相同信息时自动去重；
 * 从 Agent A 导入的记忆不会再导回 Agent A。
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 Page Cache + 磁盘 —
 * 热缓存是内存页缓存（易失、快速），冷存储是磁盘（持久、慢速）。
 * 写时先写磁盘再更新页缓存，读时先查页缓存再回源磁盘。
 * CrossAgentMemoryStore = inode 去重（硬链接）+ 网络防环（TTL）。
 *
 * @see MemoryProvider
 * @see MemoryConnector
 * @see CrossAgentMemoryStore
 */
public class TwoLevelMemoryConnector implements MemoryConnector {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelMemoryConnector.class);

    /** 热缓存层 — 快速但易失（类比 Redis / Page Cache） */
    private final MemoryProvider hotStore;
    /** 冷持久化层 — 慢速但持久，是唯一真相源（类比 MySQL / 磁盘） */
    private final MemoryProvider coldStore;
    /** 跨 Agent 记忆去重存储 — 借鉴 Headroom cross-agent sync */
    private final CrossAgentMemoryStore crossAgentStore = CrossAgentMemoryStore.instance();

    /**
     * 创建两级存储连接器。
     *
     * @param hotStore  热缓存层（如内存、Redis）
     * @param coldStore 冷持久化层（如 MySQL、文件系统）
     */
    public TwoLevelMemoryConnector(MemoryProvider hotStore, MemoryProvider coldStore) {
        this.hotStore = hotStore;
        this.coldStore = coldStore;
    }

    // ════════════════════════════════════════════════════════════════
    //  Write-Through：先冷后热
    // ════════════════════════════════════════════════════════════════

    /**
     * 存储记忆 — Write-Through 模式。
     * <p>
     * <b>流程</b>：
     * <ol>
     *   <li>先写冷存储（source of truth）— 冷存储失败则整体失败</li>
     *   <li>best-effort 写热缓存 — 热缓存失败仅记录警告，不影响主流程</li>
     * </ol>
     * <p>
     * <b>元数据透传</b>：将完整的 {@link MemoryRecord}（含 source / timestamp /
     * confidence / domain / version）透传给冷热两层，确保后端可持久化一等元数据。
     *
     * @param agentId Agent 标识
     * @param record  记忆条目（含元数据）
     * @return 冷存储的写入结果
     */
    @Override
    public boolean store(String agentId, MemoryRecord record) {
        // 1. 先写冷存储（source of truth）
        boolean coldResult = coldStore.store(agentId, record);
        if (!coldResult) {
            log.warn("[TwoLevel] 冷存储写入失败: agentId={}", agentId);
            return false;
        }

        // 2. best-effort 写热缓存
        try {
            hotStore.store(agentId, record);
        } catch (Exception e) {
            log.warn("[TwoLevel] 热缓存写入失败（不影响主流程）: agentId={}, error={}", agentId, e.getMessage());
        }

        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  Cache-Aside：先热后冷，miss 回填
    // ════════════════════════════════════════════════════════════════

    /**
     * 检索记忆 — Cache-Aside 模式。
     * <p>
     * <b>流程</b>：
     * <ol>
     *   <li>先查热缓存 — 命中则直接返回</li>
     *   <li>未命中则回源冷存储</li>
     *   <li>冷存储命中则 best-effort 回填热缓存</li>
     *   <li>返回冷存储结果</li>
     * </ol>
     *
     * @param agentId Agent 标识
     * @param query   语义查询
     * @return 检索到的记忆内容（可能为空）
     */
    @Override
    public String retrieve(String agentId, String query) {
        // 1. 先查热缓存
        String cached = null;
        try {
            cached = hotStore.retrieve(agentId, query);
        } catch (Exception e) {
            log.warn("[TwoLevel] 热缓存读取失败（降级直连冷存储）: agentId={}, error={}", agentId, e.getMessage());
        }

        if (cached != null && !cached.isEmpty()) {
            log.debug("[TwoLevel] 热缓存命中: agentId={}", agentId);
            return cached;
        }

        // 2. 回源冷存储
        String coldResult = coldStore.retrieve(agentId, query);
        if (coldResult == null || coldResult.isEmpty()) {
            log.debug("[TwoLevel] 冷存储未命中: agentId={}", agentId);
            return coldResult != null ? coldResult : "";
        }

        // 3. best-effort 回填热缓存
        try {
            hotStore.store(agentId, coldResult);
            log.debug("[TwoLevel] 已回填热缓存: agentId={}", agentId);
        } catch (Exception e) {
            log.warn("[TwoLevel] 热缓存回填失败（不影响主流程）: agentId={}, error={}", agentId, e.getMessage());
        }

        return coldResult;
    }

    /**
     * 清除记忆 — 同时清除热缓存和冷存储。
     *
     * @param agentId Agent 标识
     */
    @Override
    public void clear(String agentId) {
        // 先清冷存储（source of truth）
        coldStore.clear(agentId);

        // best-effort 清热缓存
        try {
            hotStore.clear(agentId);
        } catch (Exception e) {
            log.warn("[TwoLevel] 热缓存清除失败（不影响主流程）: agentId={}, error={}", agentId, e.getMessage());
        }
    }

    @Override
    public String providerName() {
        return "two-level(" + hotStore.providerName() + "+" + coldStore.providerName() + ")";
    }

    // ════════════════════════════════════════════════════════════════
    //  能力探测 — 委托给冷存储（source of truth 决定能力）
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean supportBatchedStore() {
        return coldStore instanceof MemoryConnector mc && mc.supportBatchedStore();
    }

    @Override
    public boolean supportBatchedRetrieve() {
        return coldStore instanceof MemoryConnector mc && mc.supportBatchedRetrieve();
    }

    @Override
    public boolean supportAsync() {
        return coldStore instanceof MemoryConnector mc && mc.supportAsync();
    }

    @Override
    public boolean supportPing() {
        return coldStore instanceof MemoryConnector mc && mc.supportPing();
    }

    // ════════════════════════════════════════════════════════════════
    //  批量操作 — Write-Through 批量写入
    // ════════════════════════════════════════════════════════════════

    /**
     * 批量存储 — Write-Through 模式。
     * <p>
     * 先批量写冷存储，再 best-effort 批量写热缓存。
     */
    @Override
    public List<Boolean> batchedStore(List<String> agentIds, List<String> contents) {
        int size = Math.min(agentIds.size(), contents.size());
        List<Boolean> results = new ArrayList<>(size);

        // 1. 先批量写冷存储
        if (coldStore instanceof MemoryConnector mc && mc.supportBatchedStore()) {
            results = mc.batchedStore(agentIds, contents);
        } else {
            for (int i = 0; i < size; i++) {
                results.add(coldStore.store(agentIds.get(i), contents.get(i)));
            }
        }

        // 2. best-effort 批量写热缓存
        try {
            if (hotStore instanceof MemoryConnector mc && mc.supportBatchedStore()) {
                mc.batchedStore(agentIds, contents);
            } else {
                for (int i = 0; i < size; i++) {
                    try {
                        hotStore.store(agentIds.get(i), contents.get(i));
                    } catch (Exception ignored) {
                        // best-effort，逐条失败不影响其他
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[TwoLevel] 热缓存批量写入失败（不影响主流程）: error={}", e.getMessage());
        }

        return results;
    }

    /**
     * 批量检索 — Cache-Aside 模式。
     * <p>
     * 先批量查热缓存，未命中的回源冷存储并回填。
     */
    @Override
    public List<String> batchedRetrieve(List<String> agentIds, List<String> queries) {
        int size = Math.min(agentIds.size(), queries.size());
        List<String> results = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            results.add(retrieve(agentIds.get(i), queries.get(i)));
        }

        return results;
    }

    // ════════════════════════════════════════════════════════════════
    //  健康检查 + 连接管理
    // ════════════════════════════════════════════════════════════════

    @Override
    public int ping() {
        // 优先 ping 冷存储，热缓存 ping 失败不影响
        int coldPing = 0;
        if (coldStore instanceof MemoryConnector mc) {
            coldPing = mc.ping();
        }

        try {
            if (hotStore instanceof MemoryConnector mc) {
                mc.ping();
            }
        } catch (Exception ignored) {
            // 热缓存 ping 失败不影响整体健康状态
        }

        return coldPing;
    }

    @Override
    public void close() {
        try {
            if (hotStore instanceof MemoryConnector mc) {
                mc.close();
            }
        } catch (Exception e) {
            log.warn("[TwoLevel] 热缓存关闭失败: error={}", e.getMessage());
        }

        if (coldStore instanceof MemoryConnector mc) {
            mc.close();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Getter
    // ════════════════════════════════════════════════════════════════

    /** 获取热缓存层 */
    public MemoryProvider hotStore() {
        return hotStore;
    }

    /** 获取冷持久化层 */
    public MemoryProvider coldStore() {
        return coldStore;
    }

    /** 获取跨 Agent 去重存储 */
    public CrossAgentMemoryStore crossAgentStore() {
        return crossAgentStore;
    }

    // ════════════════════════════════════════════════════════════════
    //  跨 Agent 去重 + 回声防护 — 借鉴 Headroom memory/sync.py
    //
    //  以下方法不破坏现有 store/retrieve 逻辑，是新增的增强方法。
    //  调用方可按需选择是否启用跨 Agent 去重。
    // ════════════════════════════════════════════════════════════════

    /**
     * 带去重的存储 — 借鉴 Headroom sync_import()。
     * <p>
     * 在原有 Write-Through 基础上，先检查 {@link CrossAgentMemoryStore}
     * 是否已有相同内容哈希。如果已存在，跳过两级存储写入（去重）。
     * <p>
     * <b>不破坏现有逻辑：</b>原 {@link #store} 方法完全保留不变。
     * 此方法是在原方法外层包了去重检查。
     *
     * @param agentId    Agent 标识
     * @param content    记忆内容
     * @param importance 重要性 0.0-1.0
     * @param scope      作用域
     * @return 存储结果（DEDUPED 表示去重跳过，STORED 表示新存储）
     */
    public CrossAgentMemoryStore.StoreResult storeWithDedup(
            String agentId, String content,
            double importance, CrossAgentMemoryStore.MemoryScope scope) {

        // 1. 跨 Agent 去重检查
        CrossAgentMemoryStore.StoreResult dedupResult =
                crossAgentStore.store(agentId, content, importance, scope);

        if (dedupResult.isDeduped()) {
            log.info("[TwoLevel] 跨 Agent 去重命中: agent={}, hash={}",
                    agentId, dedupResult.contentHash());
            return dedupResult;
        }

        // 2. 去重通过，执行原有 Write-Through 两级存储
        boolean stored = store(agentId, content);
        if (!stored) {
            log.warn("[TwoLevel] 去重通过但两级存储失败: agent={}", agentId);
        }

        return dedupResult;
    }

    /**
     * 带去重 + 回声防护元数据的存储 — 借鉴 Headroom sync_import() 完整版。
     *
     * @param agentId       目标 Agent 标识
     * @param content       记忆内容
     * @param sourceAgent   产生此记忆的源 Agent
     * @param syncDirection 同步方向（IMPORT/EXPORT/LOCAL）
     * @param importance    重要性
     * @param scope         作用域
     * @return 存储结果
     */
    public CrossAgentMemoryStore.StoreResult storeWithDedup(
            String agentId, String content,
            String sourceAgent, CrossAgentMemoryStore.SyncDirection syncDirection,
            double importance, CrossAgentMemoryStore.MemoryScope scope) {

        CrossAgentMemoryStore.StoreResult dedupResult =
                crossAgentStore.store(agentId, content, sourceAgent,
                        syncDirection, importance, scope);

        if (dedupResult.isDeduped()) {
            log.info("[TwoLevel] 跨 Agent 去重命中: agent={}, source={}, hash={}",
                    agentId, sourceAgent, dedupResult.contentHash());
            return dedupResult;
        }

        boolean stored = store(agentId, content);
        if (!stored) {
            log.warn("[TwoLevel] 去重通过但两级存储失败: agent={}", agentId);
        }

        return dedupResult;
    }

    /**
     * 带回声防护的检索 — 借鉴 Headroom sync_export()。
     * <p>
     * 从跨 Agent 存储中检索记忆，自动过滤掉回声
     * （从当前 Agent 导入的记忆不会再导回当前 Agent）。
     * <p>
     * <b>不破坏现有逻辑：</b>原 {@link #retrieve} 方法完全保留不变。
     *
     * @param agentId   目标 Agent 标识
     * @param query     语义查询
     * @param maxResults 最大返回数
     * @return 过滤后的记忆列表（不含回声）
     */
    public List<CrossAgentMemoryStore.CrossAgentMemory> retrieveWithoutEcho(
            String agentId, String query, int maxResults) {
        return crossAgentStore.retrieve(agentId, query, maxResults);
    }

    /**
     * 触发记忆冒泡 — 借鉴 Headroom memory promotion。
     * <p>
     * 将高重要性/高访问的记忆从低级别提升到高级别：
     * TURN → SESSION → USER。
     * <p>
     * 建议在会话结束或空闲时调用。
     *
     * @return 提升的记忆数量
     */
    public int bubbleUpMemories() {
        return crossAgentStore.bubbleUp();
    }

    /**
     * 清理会话级记忆 — 会话结束时调用。
     *
     * @return 清理的数量
     */
    public int cleanupTurnScopeMemories() {
        return crossAgentStore.cleanupTurnScope();
    }
}
