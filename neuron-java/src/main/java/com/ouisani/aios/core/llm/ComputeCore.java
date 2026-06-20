package com.ouisani.aios.core.llm;

/**
 * 算力核心层级 — AIOS 的 ARM big.LITTLE 架构。
 * <p>
 * 借鉴 ARM big.LITTLE 的设计思想，将大模型算力分为两个层级：
 * <ul>
 *   <li>{@link #P_CORE} — Performance Core（性能大核），对应 GPT-4o / Claude 3.5，
 *       适合复杂推理、代码生成、架构设计等高智力密度任务</li>
 *   <li>{@link #E_CORE} — Efficiency Core（能效小核），对应 GPT-4o-mini / Gemini Flash，
 *       适合文本总结、格式化、后台闲置任务等低智力密度任务</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>ARM big.LITTLE</th><th>AIOS ComputeCore</th><th>说明</th></tr>
 *   <tr><td>Cortex-X4 (大核)</td><td>P_CORE</td><td>高功耗、高性能</td></tr>
 *   <tr><td>Cortex-A520 (小核)</td><td>E_CORE</td><td>低功耗、高能效</td></tr>
 *   <tr><td>taskset -c 0-3</td><td>computeAffinity=E_CORE</td><td>绑定到小核</td></tr>
 *   <tr><td>Turbo Boost</td><td>动态拉升到 P_CORE</td><td>突发性能</td></tr>
 * </table>
 *
 * @see LlmProvider
 * @see ComputeAffinity
 */
public enum ComputeCore {

    /**
     * Performance Core — 性能大核。
     * <p>
     * 对应 GPT-4o / Claude 3.5 等旗舰模型。
     * 适合：复杂推理、代码生成、架构设计、Bug 分析。
     * 成本：高（$5-15/1M tokens）。
     */
    P_CORE(0, "Performance Core", 10.0),

    /**
     * Efficiency Core — 能效小核。
     * <p>
     * 对应 GPT-4o-mini / Gemini Flash 等轻量模型。
     * 适合：文本总结、格式化、翻译、后台任务、DreamDaemon。
     * 成本：低（$0.15-0.60/1M tokens）。
     */
    E_CORE(1, "Efficiency Core", 1.0);

    /** 层级序号（0 = 最高性能） */
    public final int ordinal;
    /** 描述 */
    public final String description;
    /** 相对成本系数（P_CORE = 10x, E_CORE = 1x） */
    public final double relativeCost;

    ComputeCore(int ordinal, String description, double relativeCost) {
        this.ordinal = ordinal;
        this.description = description;
        this.relativeCost = relativeCost;
    }

    /**
     * 判断是否可以降级到目标核心。
     * P_CORE → E_CORE：允许（降级省成本）
     * E_CORE → P_CORE：不允许（只能通过 Turbo Boost 拉升）
     */
    public boolean canDowngradeTo(ComputeCore target) {
        return this.ordinal <= target.ordinal;
    }

    /**
     * 判断是否需要拉升到目标核心。
     * E_CORE → P_CORE：需要（Turbo Boost）
     */
    public boolean needsTurboBoostTo(ComputeCore target) {
        return this.ordinal > target.ordinal;
    }
}
