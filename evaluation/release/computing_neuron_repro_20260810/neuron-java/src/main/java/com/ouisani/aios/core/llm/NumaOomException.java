package com.ouisani.aios.core.llm;

/**
 * NUMA 内存不足异常 — AIOS 的"跨节点 OOM"。
 * <p>
 * 类比 NUMA 架构中的跨节点内存分配失败：当 Agent 的 NUMA 亲和性为
 * {@link com.ouisani.aios.core.NumaAffinity#ANY} 且预算不足以访问远程（昂贵）
 * LLM 节点时，抛出此异常。类似于 Linux 内核在跨 NUMA 节点分配内存时
 * 因 cpuset 限制而失败的场景。
 *
 * @see LlmRouter
 */
public class NumaOomException extends RuntimeException {

    /** 当前预算 */
    private final int budget;
    /** 所需的最低预算阈值 */
    private final int required;

    /**
     * @param budget   当前任务预算
     * @param required 远程节点访问所需的最低预算阈值
     */
    public NumaOomException(int budget, int required) {
        super("NUMA OOM: cross-node routing denied. Budget=" + budget
                + ", Required>100 for remote node access");
        this.budget = budget;
        this.required = required;
    }

    /** 返回当前预算 */
    public int budget() {
        return budget;
    }

    /** 返回所需的最低预算阈值 */
    public int required() {
        return required;
    }
}
