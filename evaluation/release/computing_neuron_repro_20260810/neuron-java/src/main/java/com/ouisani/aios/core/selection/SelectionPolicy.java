package com.ouisani.aios.core.selection;

/**
 * 角色选择策略 — 借鉴 DyLAN（arXiv:2310.02170）的 listwise agent team selection。
 * <p>
 * DyLAN 在第 3 层起用 listwise ranker 选 top-K 角色继续发言。neuron-java 第一版
 * （方案 C 混合渐进）在 WorkflowEngine 执行前根据 query 裁剪角色池，未选中角色对应
 * 的节点标记 SKIPPED。{@code minLayer} 字段为层间动态选择预留，第一版不接线。
 * <p>
 * 与 importance 互补：importance 离线选角色池（跨 session 累积），listwise 在线裁剪
 * 当前激活集（单次 query-adaptive）。
 *
 * <h2>类型</h2>
 * <ul>
 *   <li>{@link #LISTWISE_TOP_K} — listwise ranker 选 top-K（本策略的核心）</li>
 *   <li>{@link #NONE} — 显式无策略哨兵（区别于 {@code null} = 未声明）</li>
 * </ul>
 *
 * <h2>字段语义</h2>
 * <ul>
 *   <li>{@code k} — 选 top-K 角色（listwise_top_k 生效）</li>
 *   <li>{@code minLayer} — 层间选择预热层数（第一版未接线，follow-up 层式调度时用）</li>
 *   <li>{@code minAgents} — 候选角色数 ≤ 此值时不触发 LLM（省成本，对齐 DyLAN "agents>3 触发"）</li>
 * </ul>
 *
 * @param type      策略类型
 * @param k         top-K（listwise_top_k 生效）
 * @param minLayer  预热层数（预留，第一版不接线）
 * @param minAgents 触发阈值（候选数 ≤ 此值不触发 LLM）
 */
public record SelectionPolicy(String type, int k, int minLayer, int minAgents) {

    /** 策略类型常量 — listwise top-K 选择 */
    public static final String LISTWISE_TOP_K = "listwise_top_k";

    /** 策略类型常量 — 显式无策略 */
    public static final String NONE = "none";

    /** 默认策略：listwise top-2，minLayer=2 预留，minAgents=3 对齐 DyLAN */
    public static final SelectionPolicy DEFAULT = new SelectionPolicy(LISTWISE_TOP_K, 2, 2, 3);

    /** 显式无策略哨兵 — 区别于 null（未声明） */
    public static final SelectionPolicy NONE_POLICY = new SelectionPolicy(NONE, 0, 0, 0);

    /**
     * 工厂 — 创建 listwise top-K 策略。
     *
     * @param k         top-K，必须 ≥ 1
     * @param minAgents 触发阈值，必须 ≥ 0
     * @return listwise_top_k 策略
     */
    public static SelectionPolicy listwiseTopK(int k, int minAgents) {
        return new SelectionPolicy(LISTWISE_TOP_K, k, 2, minAgents);
    }

    /** 紧凑构造器 — 规范化：type 空白 → none；k/minAgents 负数 → 0 */
    public SelectionPolicy {
        if (type == null || type.isBlank()) type = NONE;
        if (k < 0) k = 0;
        if (minLayer < 0) minLayer = 0;
        if (minAgents < 0) minAgents = 0;
    }

    /** 是否为 listwise top-K 策略（需触发 ranker） */
    public boolean isListwiseTopK() {
        return LISTWISE_TOP_K.equals(type);
    }

    /** 是否为无策略（none 或等价配置） */
    public boolean isNone() {
        return NONE.equals(type);
    }
}
