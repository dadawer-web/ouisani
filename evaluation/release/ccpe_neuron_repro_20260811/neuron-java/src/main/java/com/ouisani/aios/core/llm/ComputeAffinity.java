package com.ouisani.aios.core.llm;

/**
 * 算力亲和性 — AgentTask 的"CPU 亲和性"。
 * <p>
 * 就像 Linux 进程有 CPU 亲和性（taskset -c 0-3 pid）一样，
 * AIOS 的 AgentTask 可以声明它的算力亲和性，决定它被路由到
 * P_CORE（性能大核）还是 E_CORE（能效小核）。
 *
 * <h3>亲和性策略</h3>
 * <ul>
 *   <li>{@link #REQUIRE_P_CORE} — 必须使用大核（如 CoderAgent 的主逻辑）</li>
 *   <li>{@link #PREFER_P_CORE} — 优先使用大核，但允许动态降级到小核</li>
 *   <li>{@link #PREFER_E_CORE} — 优先使用小核，仅在 Turbo Boost 时拉升到大核</li>
 *   <li>{@link #REQUIRE_E_CORE} — 必须使用小核（如 DreamDaemon 后台任务）</li>
 *   <li>{@link #AUTO} — 由 Router 根据任务上下文自动决定</li>
 * </ul>
 *
 * @see ComputeCore
 * @see LlmRouter
 */
public enum ComputeAffinity {

    /** 必须使用 P_CORE（性能大核），不允许降级 */
    REQUIRE_P_CORE(ComputeCore.P_CORE, true),

    /** 优先使用 P_CORE，但允许在低负载时降级到 E_CORE */
    PREFER_P_CORE(ComputeCore.P_CORE, false),

    /** 优先使用 E_CORE，仅在需要时 Turbo Boost 到 P_CORE */
    PREFER_E_CORE(ComputeCore.E_CORE, false),

    /** 必须使用 E_CORE（能效小核），不允许拉升 */
    REQUIRE_E_CORE(ComputeCore.E_CORE, true),

    /** 自动选择 — 由 Router 根据任务复杂度动态决定 */
    AUTO(null, false);

    /** 首选的算力核心 */
    public final ComputeCore preferredCore;

    /** 是否为硬性绑定（不允许动态升降级） */
    public final boolean pinned;

    ComputeAffinity(ComputeCore preferredCore, boolean pinned) {
        this.preferredCore = preferredCore;
        this.pinned = pinned;
    }

    /**
     * 根据 AgentTask 的 ProcessPriority 推断默认的算力亲和性。
     * <p>
     * REALTIME → REQUIRE_P_CORE（实时任务必须用大核）
     * HIGH → PREFER_P_CORE（高优先级优先大核）
     * NORMAL → AUTO（普通任务自动选择）
     * IDLE → REQUIRE_E_CORE（空闲任务必须用小核）
     */
    public static ComputeAffinity fromPriority(com.ouisani.aios.core.ProcessPriority priority) {
        return switch (priority) {
            case REALTIME -> REQUIRE_P_CORE;
            case HIGH -> PREFER_P_CORE;
            case NORMAL -> AUTO;
            case IDLE -> REQUIRE_E_CORE;
        };
    }
}
