package com.ouisani.aios.core.memory.connector;

import com.ouisani.aios.core.memory.providers.MemoryRecord;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 带指标采集的连接器装饰器 — 包装真实连接器，自动采集延迟和吞吐量指标。
 * <p>
 * 参考 LMCache 的 {@code InstrumentedRemoteConnector}。
 * <p>
 * <b>装饰器模式</b>：对 {@link #store}、{@link #retrieve}、
 * {@link #batchedStore}、{@link #batchedRetrieve} 等方法注入耗时统计和
 * debug 日志，其余方法（能力探测、异步、ping、clear、close）纯转发给被包装对象。
 * <p>
 * 通过 {@link #getStoreCount()}、{@link #getRetrieveCount()}、
 * {@link #getAvgStoreTimeMs()}、{@link #getAvgRetrieveTimeMs()} 暴露累计指标。
 */
public class InstrumentedMemoryConnector implements MemoryConnector {

    private static final Logger log = LoggerFactory.getLogger(InstrumentedMemoryConnector.class);

    /** 被包装的真实连接器。 */
    private final MemoryConnector delegate;

    private final AtomicLong storeCount = new AtomicLong();
    private final AtomicLong retrieveCount = new AtomicLong();
    private final AtomicLong totalStoreTimeMs = new AtomicLong();
    private final AtomicLong totalRetrieveTimeMs = new AtomicLong();

    /**
     * 构造装饰器。
     *
     * @param delegate 被包装的真实连接器
     */
    public InstrumentedMemoryConnector(MemoryConnector delegate) {
        this.delegate = delegate;
    }

    // ── 记忆操作：计时 + 计数 + 日志 ──────────────────────────────

    /**
     * {@inheritDoc}
     * <p>
     * 计时、计数并记录 debug 日志后委托给被包装连接器。
     */
    @Override
    public boolean store(String agentId, MemoryRecord record) {
        long begin = System.nanoTime();
        try {
            return delegate.store(agentId, record);
        } finally {
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
            storeCount.incrementAndGet();
            totalStoreTimeMs.addAndGet(elapsedMs);
            log.debug("[Instrumented] store: agent='{}', elapsedMs={}, contentLen={}, domain={}, version={}",
                    agentId, elapsedMs,
                    record.content() != null ? record.content().length() : 0,
                    record.domain(), record.version());
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 计时、计数并记录 debug 日志后委托给被包装连接器。
     */
    @Override
    public String retrieve(String agentId, String query) {
        long begin = System.nanoTime();
        try {
            return delegate.retrieve(agentId, query);
        } finally {
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
            retrieveCount.incrementAndGet();
            totalRetrieveTimeMs.addAndGet(elapsedMs);
            log.debug("[Instrumented] retrieve: agent='{}', query='{}', elapsedMs={}",
                    agentId, query, elapsedMs);
        }
    }

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public void clear(String agentId) {
        delegate.clear(agentId);
    }

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public String providerName() {
        return delegate.providerName();
    }

    // ── 能力探测：纯转发 ─────────────────────────────────────────

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public boolean supportBatchedStore() {
        return delegate.supportBatchedStore();
    }

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public boolean supportBatchedRetrieve() {
        return delegate.supportBatchedRetrieve();
    }

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public boolean supportAsync() {
        return delegate.supportAsync();
    }

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public boolean supportPing() {
        return delegate.supportPing();
    }

    // ── 批量操作：计时 + 计数 + 日志 ──────────────────────────────

    /**
     * {@inheritDoc}
     * <p>
     * 计时、按条目数累加计数并记录 debug 日志后委托给被包装连接器。
     */
    @Override
    public List<Boolean> batchedStore(List<String> agentIds, List<String> contents) {
        int count = Math.min(agentIds.size(), contents.size());
        long begin = System.nanoTime();
        try {
            return delegate.batchedStore(agentIds, contents);
        } finally {
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
            storeCount.addAndGet(count);
            totalStoreTimeMs.addAndGet(elapsedMs);
            log.debug("[Instrumented] batchedStore: count={}, elapsedMs={}", count, elapsedMs);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 计时、按条目数累加计数并记录 debug 日志后委托给被包装连接器。
     */
    @Override
    public List<String> batchedRetrieve(List<String> agentIds, List<String> queries) {
        int count = Math.min(agentIds.size(), queries.size());
        long begin = System.nanoTime();
        try {
            return delegate.batchedRetrieve(agentIds, queries);
        } finally {
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
            retrieveCount.addAndGet(count);
            totalRetrieveTimeMs.addAndGet(elapsedMs);
            log.debug("[Instrumented] batchedRetrieve: count={}, elapsedMs={}", count, elapsedMs);
        }
    }

    // ── 异步操作：纯转发（委托给被包装连接器的异步实现） ───────────

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public CompletableFuture<Boolean> storeAsync(String agentId, String content) {
        return delegate.storeAsync(agentId, content);
    }

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public CompletableFuture<String> retrieveAsync(String agentId, String query) {
        return delegate.retrieveAsync(agentId, query);
    }

    // ── 健康检查与连接管理：纯转发 ─────────────────────────────────

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public int ping() {
        return delegate.ping();
    }

    /** {@inheritDoc} — 纯转发。 */
    @Override
    public void close() {
        delegate.close();
    }

    // ── 指标访问 ──────────────────────────────────────────────────

    /**
     * 返回被包装的真实连接器。
     *
     * @return 委托连接器
     */
    public MemoryConnector getWrappedConnector() {
        return delegate;
    }

    /**
     * 返回 store 操作累计次数（含批量操作的条目数）。
     *
     * @return 累计 store 次数
     */
    public long getStoreCount() {
        return storeCount.get();
    }

    /**
     * 返回 retrieve 操作累计次数（含批量操作的条目数）。
     *
     * @return 累计 retrieve 次数
     */
    public long getRetrieveCount() {
        return retrieveCount.get();
    }

    /**
     * 返回 store 平均耗时（毫秒）。
     *
     * @return 平均耗时；无操作时返回 {@code 0}
     */
    public long getAvgStoreTimeMs() {
        long count = storeCount.get();
        return count == 0 ? 0 : totalStoreTimeMs.get() / count;
    }

    /**
     * 返回 retrieve 平均耗时（毫秒）。
     *
     * @return 平均耗时；无操作时返回 {@code 0}
     */
    public long getAvgRetrieveTimeMs() {
        long count = retrieveCount.get();
        return count == 0 ? 0 : totalRetrieveTimeMs.get() / count;
    }
}
