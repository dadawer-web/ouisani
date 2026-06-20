package com.ouisani.aios.core.memory.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 存储连接器接口 — 扩展 {@link com.ouisani.aios.core.memory.providers.MemoryProvider}，
 * 增加批量操作、异步操作和能力探测。
 * <p>
 * 参考 LMCache 的 {@code RemoteConnector}，适配 AIOS 的记忆后端。
 * <p>
 * <b>能力探测模式</b>：上层调用前通过 {@code support*()} 方法探测后端能力，
 * 避免 try/catch 回退的复杂性。不支持批量/异步的后端返回 {@code false}，
 * 上层据此回退到逐条同步调用（由本接口的 default 方法自动完成）。
 *
 * @see com.ouisani.aios.core.memory.providers.MemoryProvider
 */
public interface MemoryConnector extends com.ouisani.aios.core.memory.providers.MemoryProvider {

    /**
     * 异步执行器 — 基于 Java 21 虚拟线程的每任务一线程执行器，
     * 供 {@link #storeAsync} / {@link #retrieveAsync} 的默认实现使用。
     * <p>
     * 生命周期与 JVM 相同，无需显式关闭（类比 ForkJoinPool 的 commonPool）。
     */
    ExecutorService ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    // ── 能力探测 ──────────────────────────────────────────────────

    /**
     * 探测后端是否支持批量存储。
     *
     * @return 支持原生批量存储返回 {@code true}，否则上层应回退到逐条 {@link #store}
     */
    boolean supportBatchedStore();

    /**
     * 探测后端是否支持批量检索。
     *
     * @return 支持原生批量检索返回 {@code true}，否则上层应回退到逐条 {@link #retrieve}
     */
    boolean supportBatchedRetrieve();

    /**
     * 探测后端是否支持异步操作。
     *
     * @return 支持异步操作返回 {@code true}
     */
    boolean supportAsync();

    /**
     * 探测后端是否支持健康检查（ping）。
     *
     * @return 支持 ping 返回 {@code true}
     */
    boolean supportPing();

    // ── 批量操作（默认实现回退到单条） ─────────────────────────────

    /**
     * 批量存储记忆。默认实现逐条调用 {@link #store}，子类可在支持原生批量时覆盖以提升吞吐。
     *
     * @param agentIds Agent 标识列表
     * @param contents 对应的记忆内容列表（与 agentIds 按下标配对）
     * @return 每条存储结果列表，{@code true} 表示成功；列表长度为两入参长度的较小值
     */
    default List<Boolean> batchedStore(List<String> agentIds, List<String> contents) {
        int size = Math.min(agentIds.size(), contents.size());
        List<Boolean> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            results.add(store(agentIds.get(i), contents.get(i)));
        }
        return results;
    }

    /**
     * 批量检索记忆。默认实现逐条调用 {@link #retrieve}，子类可在支持原生批量时覆盖以降低往返开销。
     *
     * @param agentIds Agent 标识列表
     * @param queries  对应的语义查询列表（与 agentIds 按下标配对）
     * @return 每条检索结果列表；列表长度为两入参长度的较小值
     */
    default List<String> batchedRetrieve(List<String> agentIds, List<String> queries) {
        int size = Math.min(agentIds.size(), queries.size());
        List<String> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            results.add(retrieve(agentIds.get(i), queries.get(i)));
        }
        return results;
    }

    // ── 异步操作（默认实现回退到同步，运行于虚拟线程） ─────────────

    /**
     * 异步存储记忆。默认实现使用 {@link #ASYNC_EXECUTOR}（虚拟线程）在后台执行同步 {@link #store}。
     *
     * @param agentId Agent 标识
     * @param content 记忆内容
     * @return 异步返回存储结果，{@code true} 表示成功
     */
    default CompletableFuture<Boolean> storeAsync(String agentId, String content) {
        return CompletableFuture.supplyAsync(() -> store(agentId, content), ASYNC_EXECUTOR);
    }

    /**
     * 异步检索记忆。默认实现使用 {@link #ASYNC_EXECUTOR}（虚拟线程）在后台执行同步 {@link #retrieve}。
     *
     * @param agentId Agent 标识
     * @param query   语义查询
     * @return 异步返回检索到的记忆内容（可能为空）
     */
    default CompletableFuture<String> retrieveAsync(String agentId, String query) {
        return CompletableFuture.supplyAsync(() -> retrieve(agentId, query), ASYNC_EXECUTOR);
    }

    // ── 健康检查 ──────────────────────────────────────────────────

    /**
     * 健康检查。默认实现返回 {@code 0} 表示成功。
     *
     * @return {@code 0} 表示成功，非 0 表示后端错误码
     */
    default int ping() {
        return 0; // 0 = success
    }

    // ── 连接管理 ──────────────────────────────────────────────────

    /**
     * 关闭连接器，释放底层资源（网络连接、线程池、文件句柄等）。
     */
    void close();
}
