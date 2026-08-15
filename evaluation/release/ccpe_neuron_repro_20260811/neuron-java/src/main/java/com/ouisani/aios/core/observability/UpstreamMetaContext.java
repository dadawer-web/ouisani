package com.ouisani.aios.core.observability;

/**
 * 上游调用元数据上下文 — ThreadLocal 容器，沿同步调用链传递 {@link UpstreamMeta}。
 * <p>
 * 完全仿 {@code ImpersonationContext} 的 ThreadLocal + try-with-resources 范式，
 * 提供两种使用方式：
 *
 * <h3>用法</h3>
 * <pre>{@code
 * // 方式 1: try-with-resources（推荐）
 * UpstreamMeta meta = new UpstreamMeta("llm.think", 842, 200, null, 1536, null,
 *         System.currentTimeMillis(), "agent_5", "sess_abc");
 * try (var ignored = UpstreamMetaContext.bind(meta)) {
 *     // 此代码块及任何同步调用的下游代码可通过 UpstreamMetaContext.current() 读取 meta
 *     EventBus.instance().broadcastWithCurrentMeta("sys.task.done", "{\"ok\":true}");
 * }
 *
 * // 方式 2: 回调
 * UpstreamMetaContext.runWithMeta(meta, () -> {
 *     // 同样可通过 current() 读取
 * });
 * }</pre>
 *
 * <h3>嵌套策略：堆栈式（save/restore previous）</h3>
 * 与 {@code ImpersonationContext.impersonate} 一致 — 内层 {@link #bind} 保存
 * 外层 previous meta，内层 close 时恢复 previous。保证可重入调用链（如
 * {@code kernel.forge_tool} 内部再发 {@code llm.think} syscall）不丢失外层 meta。
 *
 * <h3>虚拟线程约束（重要）</h3>
 * {@code Thread.startVirtualThread} 创建的子线程<b>不继承</b>父线程的 ThreadLocal
 * （这是虚拟线程的故意设计，{@code InheritableThreadLocal} 也不继承）。
 * <p>
 * <b>影响</b>：EventBus 内部订阅者在虚拟线程上执行（见
 * {@code EventBus.broadcast} 第 103 行），<b>看不到</b> {@link #current()}。
 * 订阅者必须通过 EventBus 伴生事件 {@code sys.upstream.meta} 的 payload 获取 meta。
 * <p>
 * <b>UpstreamMetaContext 仅供同一调用链上的同步代码使用</b>（如 SyscallDispatcher
 * 的 finally 块内 EventBus 广播、TraceSpan 桥接等）。
 *
 * <h3>OS 类比</h3>
 * 相当于 Linux 的 per-task {@code current} 宏 — 内核线程上下文指针，
 * 同步代码访问当前任务的元数据。但虚拟线程是用户态调度，不会跨线程传播。
 *
 * @see UpstreamMeta
 * @see com.ouisani.aios.core.security.ImpersonationContext
 */
public final class UpstreamMetaContext {

    /**
     * 当前线程的上游调用元数据。
     * <p>
     * 设计为 ThreadLocal 而非方法参数，避免污染 {@code EventBus.broadcast} 签名
     * （broadcast 有多个调用方，改签名成本过高；通过 ThreadLocal 实现透明注入）。
     */
    public static final ThreadLocal<UpstreamMeta> CURRENT_META = new ThreadLocal<>();

    private UpstreamMetaContext() {}

    // ════════════════════════════════════════════════════════════════
    //  bind / runWithMeta
    // ════════════════════════════════════════════════════════════════

    /**
     * 绑定 UpstreamMeta 到当前线程，返回 AutoCloseable 用于 try-with-resources。
     * <p>
     * 关闭时恢复 previous meta（堆栈式语义）。null meta 也允许绑定（{@link #current()}
     * 返回 null，下游广播跳过伴生事件）。
     *
     * @param meta 要绑定的 UpstreamMeta（允许 null）
     * @return AutoCloseable，关闭时恢复原 meta
     */
    public static AutoCloseable bind(UpstreamMeta meta) {
        UpstreamMeta previous = CURRENT_META.get();
        CURRENT_META.set(meta);
        return () -> {
            if (previous != null) {
                CURRENT_META.set(previous);
            } else {
                CURRENT_META.remove();
            }
        };
    }

    /**
     * 以指定 UpstreamMeta 执行操作，保证执行后恢复原 meta。
     * <p>
     * 与 {@code ImpersonationContext.runAs} 对偶的回调范式。
     *
     * @param meta   要绑定的 UpstreamMeta（允许 null）
     * @param action 要执行的操作
     */
    public static void runWithMeta(UpstreamMeta meta, Runnable action) {
        UpstreamMeta previous = CURRENT_META.get();
        try {
            CURRENT_META.set(meta);
            action.run();
        } finally {
            if (previous != null) {
                CURRENT_META.set(previous);
            } else {
                CURRENT_META.remove();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取当前线程的上游调用元数据（可能为 null）。
     */
    public static UpstreamMeta current() {
        return CURRENT_META.get();
    }

    /**
     * 清除当前线程的 UpstreamMeta 绑定。
     * <p>
     * 测试场景或显式清理时使用。正常流程应通过 {@link #bind} 的 AutoCloseable
     * 或 {@link #runWithMeta} 的 finally 自动恢复。
     */
    public static void clear() {
        CURRENT_META.remove();
    }
}
