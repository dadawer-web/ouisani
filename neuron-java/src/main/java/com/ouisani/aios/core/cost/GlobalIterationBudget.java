package com.ouisani.aios.core.cost;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局迭代预算 — 任务级 LLM 调用次数熔断器（SoA {@code max_iters} 等价物）。
 * <p>
 * 修复"递归/重试无全局帽"的 OOM 隐患：{@code OmniMotherAgent} 原本只有 per-node
 * {@code MAX_SELF_HEAL_RETRIES}（每节点最多重试 N 次），但 N 节点 × M 重试 = N×M 次
 * LLM 调用无顶层熔断。借鉴 SoA (self-organized-agent) 的 {@code for i in range(max_iterations)}
 * 顶层全局帽，本类提供任务级迭代次数预算，超限时触发 wind-down（剩余节点跳过）。
 * <p>
 * 与 {@link CostTracker} 的关系：
 * <ul>
 *   <li>{@link CostTracker} 管 <b>token 维度</b>（进程级，所有 agent 共享）</li>
 *   <li>{@code GlobalIterationBudget} 管 <b>迭代次数维度</b>（任务级，单次工作流独占）</li>
 *   <li>两者正交，互为补充：token 烧光或迭代次数超限都会熔断，双重防线</li>
 * </ul>
 * <p>
 * 线程安全：{@link AtomicInteger} 保证 {@link #trySpend()} 原子递增，
 * 可在虚拟线程并发场景下安全使用（虽然 OmniMotherAgent 节点循环当前是顺序的）。
 * <p>
 * OS 类比：相当于 Linux cgroup 的 pids.max（进程数限制）——不是按资源量（token/内存）
 * 熔断，而是按操作次数熔断，防止"每次操作很便宜但次数无限"导致的累积型 OOM。
 */
public final class GlobalIterationBudget {

    /** 默认全局迭代帽：20 节点 × 3 重试 = 60，覆盖 TopologyCompiler 最坏情况 */
    public static final int DEFAULT_MAX_ITERS = 60;

    private final int maxIters;
    private final AtomicInteger spent = new AtomicInteger(0);

    /** 按指定上限创建预算 */
    public GlobalIterationBudget(int maxIters) {
        if (maxIters <= 0) {
            throw new IllegalArgumentException("maxIters must be positive, got: " + maxIters);
        }
        this.maxIters = maxIters;
    }

    /** 用默认上限（{@value #DEFAULT_MAX_ITERS}）创建预算 */
    public static GlobalIterationBudget withDefault() {
        return new GlobalIterationBudget(DEFAULT_MAX_ITERS);
    }

    /**
     * 尝试消耗一次迭代。
     * <p>
     * 原子地递增已花费计数，若递增后未超上限则允许（返回 true），否则拒绝（返回 false）。
     * 调用方在每次 LLM 调用前调用本方法，返回 false 时应 wind-down（跳过剩余工作）。
     *
     * @return true 表示预算允许本次迭代；false 表示已超限，应 wind-down
     */
    public boolean trySpend() {
        return spent.incrementAndGet() <= maxIters;
    }

    /** 预算是否已耗尽（后续 trySpend 必返回 false）— 用于外层循环开头提前 wind-down */
    public boolean isExhausted() {
        return spent.get() >= maxIters;
    }

    /** 已花费的迭代次数 */
    public int spent() {
        return spent.get();
    }

    /** 迭代次数上限 */
    public int maxIters() {
        return maxIters;
    }

    /** 剩余迭代次数（不低于 0） */
    public int remaining() {
        return Math.max(0, maxIters - spent.get());
    }

    @Override
    public String toString() {
        return "GlobalIterationBudget{spent=" + spent.get() + "/" + maxIters + "}";
    }
}
