package com.ouisani.aios.core.ipc;

/**
 * 调用方上下文 — 携带当前线程的 agentId + tenantId，供 EventBus/VFS 限流器读取调用方归属。
 * <p>
 * <b>OS 类比</b>：Linux 内核的 {@code current} 宏（{@code current->pid} / {@code current->cred}）。
 * 内核代码通过 {@code current} 拿到"谁在发起这次系统调用"，从而做 per-process 资源计量。
 * AIOS 的 CallerContext 同理：EventBus.broadcast / VfsManager.writeText 在内核深处执行时，
 * 通过 {@link #current()} 拿到"哪个 agent 在发起这次调用"，从而做 per-agent / per-tenant 限流。
 * <p>
 * <b>豁免语义</b>：内核守护进程（WatchdogDaemon / RecoveryOrchestrator / TaskScheduler 等）不 set
 * CallerContext → {@link #current()} 返回 null → 限流器豁免（与 {@code RateLimitSyscallFilter.isPrivilegedAgent}
 * 对 {@code sys_*} / {@code root_cli} / {@code kernel} 前缀豁免同构）。仅 agent 经 tool 触发的
 * EventBus/VFS 调用会被限流。
 * <p>
 * <b>线程模型</b>：{@link InheritableThreadLocal}，虚拟线程自动继承（与 {@link TraceContext} 一致）。
 * set/clear 由 {@code QueryEngine} 在 tool 执行前后负责（save/restore 模式，finally 里 clear 防泄漏）。
 * <p>
 * <b>与现有 ThreadLocal 的关系</b>：与 {@code VfsManager.AGENT_ROOT}（路径绑定）、
 * {@code TraceContext}（traceId）、{@code ProvenanceHook.CURRENT_AGENT_ID}（仅 agentId）并存。
 * 不复用 ProvenanceHook 是因为它只携带 agentId 无 tenantId，且职责单一（provenance 追溯）；
 * CallerContext 独立承载限流所需的 agentId + tenantId 双维度。
 *
 * @see com.ouisani.aios.core.network.EventBusRateLimiter
 * @see com.ouisani.aios.core.security.VfsRateLimiter
 */
public final class CallerContext {

    private static final InheritableThreadLocal<CallerContext> CURRENT = new InheritableThreadLocal<>();

    private final String agentId;
    private final String tenantId;

    public CallerContext(String agentId, String tenantId) {
        this.agentId = agentId;
        this.tenantId = tenantId;
    }

    public String agentId() { return agentId; }

    public String tenantId() { return tenantId; }

    /** 获取当前线程的调用方上下文；内核守护进程未 set 时返回 null。 */
    public static CallerContext current() {
        return CURRENT.get();
    }

    /**
     * 设置当前线程的调用方上下文。由 {@code QueryEngine} 在 tool 执行前调用。
     * {@code tenantId=null} 表示 legacy 调用者（未声明租户），per-tenant 限流退化为 skip，
     * per-agent 限流仍生效。
     */
    public static void set(String agentId, String tenantId) {
        CURRENT.set(new CallerContext(agentId, tenantId));
    }

    /** 清除当前线程的调用方上下文。由 {@code QueryEngine} 在 tool 执行后 finally 调用，防线程池泄漏。 */
    public static void clear() {
        CURRENT.remove();
    }
}
