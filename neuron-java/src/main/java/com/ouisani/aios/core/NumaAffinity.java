package com.ouisani.aios.core;

/**
 * NUMA 亲和性策略 — AIOS Agent 调度的 NUMA 节点绑定策略。
 * <p>
 * 控制 Agent 的 LLM 请求是否可以路由到远程（昂贵/慢速）模型节点，
 * 还是必须留在本地（廉价/快速）节点上。
 * <p>
 * OS 类比: Linux 的 numactl --preferred/--interleave 策略。
 * <ul>
 *   <li>{@link #LOCAL_ONLY} — 只允许本地/廉价模型，无论 prompt 复杂度如何都不跨节点。</li>
 *   <li>{@link #PREFER_LOCAL} — 优先本地模型；仅当 prompt 超过智能阈值时路由到远程。</li>
 *   <li>{@link #ANY} — 允许跨节点路由到远程模型，受预算约束。</li>
 * </ul>
 */
public enum NumaAffinity {

    /** Only local/cheap model allowed. No cross-node traffic. */
    LOCAL_ONLY,

    /** Prefer local; route to remote when prompt is complex. */
    PREFER_LOCAL,

    /** Allow cross-node routing, subject to budget. */
    ANY
}
