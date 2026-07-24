package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 版本化记忆存储 — 同 key 冲突时 version+1，旧版本保留为 history。
 * <p>
 * 实现 P2「记忆元数据一等化」第二步：冲突处理先做最简版本，<b>不立刻做语义合并</b>。
 * <ul>
 *   <li><b>同 key 新写入</b>：把当前版本压入 {@code history}，新记录的 version 设为
 *       {@code current.version + 1}，覆盖 current。然后把新记录的 {@code content}
 *       透传给被包装的 {@link MemoryProvider} 做正文持久化。</li>
 *   <li><b>新 key 写入</b>：直接作为 current，version 强制为 1（若调用方传入 0）。</li>
 *   <li><b>key 为 null</b>：直接透传给 Provider，不进入版本表（兼容旧 store(String) 语义）。</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：使用 {@link ConcurrentHashMap} + {@link CopyOnWriteArrayList}，
 * 单条记录的 version+1 操作通过 {@code compute} 上的 synchronized 块串行化，
 * 保证同 key 并发写入不会丢版本。
 * <p>
 * <b>非侵入式</b>：本类不自动接管 {@code MemoryManager.currentProvider}，
 * 调用方需显式创建实例并在需要版本化时调用 {@link #store}。
 * 这样 P2 阶段只立数据模型，不影响现有 MemoryManager 的热路径。
 *
 * @see MemoryRecord
 * @see MemoryProvider
 */
public class VersionedMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(VersionedMemoryStore.class);

    /** 正文持久化后端 — 由调用方注入（如 TokenZramProvider、TwoLevelMemoryConnector）。 */
    private final MemoryProvider delegate;

    /** 版本表：compositeKey(agentId + ":" + recordKey) → 版本条目。 */
    private final ConcurrentHashMap<String, VersionedEntry> versionTable = new ConcurrentHashMap<>();

    /**
     * 创建版本化存储。
     *
     * @param delegate 正文持久化后端（不可为 null）
     */
    public VersionedMemoryStore(MemoryProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate provider must not be null");
    }

    /**
     * 存储一条记忆 — 同 key 冲突时 version+1 并保留 history。
     * <p>
     * <b>流程</b>：
     * <ol>
     *   <li>若 {@code record.key()} 为 null → 直接委托给 Provider，不进版本表
     *       （兼容旧 store(String) 语义）。</li>
     *   <li>若同 key 已有 current → 当前 current 压入 history，新记录 version 设为
     *       {@code current.version + 1}（忽略调用方传入的 version 字段）。</li>
     *   <li>若同 key 新增 → version 强制为 1（若调用方传入 0 或负值）。</li>
     *   <li>更新 current，把新记录的 {@code content()} 透传给 Provider 持久化。</li>
     * </ol>
     *
     * @param agentId Agent 标识
     * @param record  记忆条目（key 为 null 时不版本化）
     * @return Provider 写入结果
     */
    public boolean store(String agentId, MemoryRecord record) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(record, "record must not be null");

        // key 为 null：兼容旧语义，直接透传
        if (record.key() == null) {
            return delegate.store(agentId, record);
        }

        String compositeKey = compositeKey(agentId, record.key());
        MemoryRecord finalRecord = versionTable.compute(compositeKey, (k, existing) -> {
            if (existing == null) {
                // 新 key：version 至少为 1
                long v = record.version() <= 0 ? 1L : record.version();
                MemoryRecord normalized = v == record.version() ? record : record.withVersion(v);
                return new VersionedEntry(normalized, new CopyOnWriteArrayList<>());
            }
            // 同 key 冲突：version+1，旧 current 入 history
            long newVersion = existing.current.version() + 1;
            MemoryRecord bumped = record.withVersion(newVersion);
            List<MemoryRecord> newHistory = new CopyOnWriteArrayList<>(existing.history);
            newHistory.add(existing.current);
            return new VersionedEntry(bumped, newHistory);
        }).current;

        // 透传给 Provider 做正文持久化
        boolean ok = delegate.store(agentId, finalRecord);
        if (log.isDebugEnabled()) {
            log.debug("[VersionedMemoryStore] store: agent='{}', key='{}', version={}, domain={}, historySize={}",
                    agentId, record.key(), finalRecord.version(), finalRecord.domain(),
                    history(agentId, record.key()).size());
        }
        return ok;
    }

    /**
     * 获取指定 key 的当前版本记录。
     *
     * @param agentId Agent 标识
     * @param key     记忆逻辑主键
     * @return 当前记录；不存在返回 {@code null}
     */
    public MemoryRecord current(String agentId, String key) {
        VersionedEntry entry = versionTable.get(compositeKey(agentId, key));
        return entry != null ? entry.current : null;
    }

    /**
     * 获取指定 key 的历史版本列表（按写入时间升序，最旧在前）。
     *
     * @param agentId Agent 标识
     * @param key     记忆逻辑主键
     * @return 历史记录列表（不可变副本）；不存在返回空列表
     */
    public List<MemoryRecord> history(String agentId, String key) {
        VersionedEntry entry = versionTable.get(compositeKey(agentId, key));
        if (entry == null || entry.history.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(entry.history));
    }

    /**
     * 列出指定 Agent 的所有当前版本记录。
     *
     * @param agentId Agent 标识
     * @return 当前记录列表（不可变副本）；无记录返回空列表
     */
    public List<MemoryRecord> listCurrent(String agentId) {
        String prefix = agentId + ":";
        List<MemoryRecord> result = new ArrayList<>();
        for (Map.Entry<String, VersionedEntry> e : versionTable.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                result.add(e.getValue().current);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 按域过滤当前记录 — 为后续"记忆查看器"按 USER/AGENT 过滤铺路。
     *
     * @param agentId Agent 标识
     * @param domain  记忆域
     * @return 命中域的当前记录列表
     */
    public List<MemoryRecord> listByDomain(String agentId, MemoryDomain domain) {
        Objects.requireNonNull(domain, "domain must not be null");
        String prefix = agentId + ":";
        List<MemoryRecord> result = new ArrayList<>();
        for (Map.Entry<String, VersionedEntry> e : versionTable.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue().current.domain() == domain) {
                result.add(e.getValue().current);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 清除指定 Agent 的所有版本化记录（不调用 Provider.clear，仅清版本表）。
     * <p>
     * 若需同时清后端存储，调用方应另行调用 {@code delegate.clear(agentId)}。
     *
     * @param agentId Agent 标识
     * @return 被清除的版本表条目数
     */
    public int clearVersionTable(String agentId) {
        String prefix = agentId + ":";
        int removed = 0;
        var it = versionTable.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().startsWith(prefix)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * P3：更新单条记忆的元数据（confidence / domain）— 版本递增式更新。
     * <p>
     * 与 {@link #store} 不同，本方法不修改 content，仅刷新 confidence 和/或 domain。
     * 旧的 current 被压入 history，新记录的 version 为 {@code current.version + 1}，
     * timestamp 刷新为当前时间。新记录通过 {@link MemoryProvider#store} 透传给后端，
     * 让支持元数据持久化的 Provider 同步刷新。
     * <p>
     * <b>幂等性</b>：若 newConfidence 和 newDomain 都与当前值相同，仍会版本递增
     * （简化实现，避免调用方需要先比较）。调用方应自行比较以避免无谓写入。
     *
     * @param agentId       Agent 标识
     * @param key           记忆逻辑主键
     * @param newConfidence 新置信度 [0.0, 1.0]；null 表示不更新
     * @param newDomain     新记忆域；null 表示不更新
     * @return 更新成功返回 true；key 不存在返回 false
     */
    public boolean updateMetadata(String agentId, String key,
                                  Double newConfidence, MemoryDomain newDomain) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(key, "key must not be null");

        String compositeKey = compositeKey(agentId, key);
        VersionedEntry updated = versionTable.compute(compositeKey, (k, existing) -> {
            if (existing == null) {
                // 不存在 — 留个标记让外层知道，返回原值（null）
                return null;
            }
            MemoryRecord old = existing.current;
            double nextConf = newConfidence != null ? newConfidence : old.confidence();
            MemoryDomain nextDom = newDomain != null ? newDomain : old.domain();
            long nextVersion = old.version() + 1;
            MemoryRecord refreshed = new MemoryRecord(
                    old.key(),
                    old.content(),
                    old.source(),
                    System.currentTimeMillis(),
                    nextConf,
                    nextDom,
                    nextVersion
            );
            List<MemoryRecord> newHistory = new CopyOnWriteArrayList<>(existing.history);
            newHistory.add(old);
            return new VersionedEntry(refreshed, newHistory);
        });

        if (updated == null) {
            log.debug("[VersionedMemoryStore] updateMetadata: key not found, agent='{}', key='{}'", agentId, key);
            return false;
        }

        // 透传给 Provider（让支持元数据持久化的后端同步刷新）
        boolean ok = delegate.store(agentId, updated.current);
        if (log.isDebugEnabled()) {
            log.debug("[VersionedMemoryStore] updateMetadata: agent='{}', key='{}', v={}, conf={}, dom={}, ok={}",
                    agentId, key, updated.current.version(),
                    updated.current.confidence(), updated.current.domain(), ok);
        }
        return true;
    }

    /**
     * P3：删除单条记忆 — 仅从版本表移除。
     * <p>
     * <b>注意</b>：{@link MemoryProvider} 接口当前没有 per-key delete（只有
     * {@link MemoryProvider#clear(String)} 清整个 agent），因此本方法<b>不会</b>
     * 同步删除 delegate 后端中对应的正文。若后端需要同步删除，调用方应自行调用
     * Provider 提供的能力（如有）。
     * <p>
     * 删除后，该 key 的 history 也一并移除。如需保留审计轨迹，应在删除前调用
     * {@link #history} 复制出去。
     *
     * @param agentId Agent 标识
     * @param key     记忆逻辑主键
     * @return 删除成功返回 true；key 不存在返回 false
     */
    public boolean delete(String agentId, String key) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        VersionedEntry removed = versionTable.remove(compositeKey(agentId, key));
        if (log.isDebugEnabled()) {
            log.debug("[VersionedMemoryStore] delete: agent='{}', key='{}', existed={}",
                    agentId, key, removed != null);
        }
        return removed != null;
    }

    /** 返回被包装的 Provider — 供调用方访问后端能力。 */
    public MemoryProvider delegate() {
        return delegate;
    }

    // ── P3：全局 primary store 引用（供 MemoryViewerRoutes 等 HTTP 路由访问） ──

    /**
     * 全局 primary store — 供 HTTP 路由（如 MemoryViewerRoutes）访问。
     * <p>
     * 由应用启动时通过 {@link #setPrimaryStore} 注入；未注入时为 null，
     * 路由返回 503 Service Unavailable。
     * <p>
     * <b>设计权衡</b>：VMS 本身是"非侵入式"的（caller 创建实例），但 HTTP 路由
     * 需要一个全局访问点。这里用 static 字段而非单例，让 caller 仍可创建多个
     * VMS 实例（如多租户场景），只是其中一个被标记为 primary。
     */
    private static volatile VersionedMemoryStore primaryStore;

    /**
     * 注入全局 primary store — 供 HTTP 路由访问。
     *
     * @param store primary store 实例；传 null 清除引用
     */
    public static void setPrimaryStore(VersionedMemoryStore store) {
        primaryStore = store;
        log.info("[VersionedMemoryStore] primary store 已设置: {}",
                store == null ? "<null>" : "enabled");
    }

    /**
     * 获取全局 primary store — HTTP 路由通过此方法访问。
     *
     * @return primary store；未注入时返回 null
     */
    public static VersionedMemoryStore getPrimaryStore() {
        return primaryStore;
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private static String compositeKey(String agentId, String key) {
        return agentId + ":" + key;
    }

    /** 版本表条目 — current + history（最旧在前）。 */
    private static final class VersionedEntry {
        final MemoryRecord current;
        final List<MemoryRecord> history;

        VersionedEntry(MemoryRecord current, List<MemoryRecord> history) {
            this.current = current;
            this.history = history;
        }
    }
}
