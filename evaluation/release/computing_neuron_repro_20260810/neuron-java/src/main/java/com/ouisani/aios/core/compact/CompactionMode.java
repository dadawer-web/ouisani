package com.ouisani.aios.core.compact;

/**
 * 压缩模式枚举 — 借鉴 jcode 的三模式 Reactive/Proactive/Semantic。
 * <p>
 * 不同模式决定 {@link CompactService#autoCompact} 的触发阈值：
 * <ul>
 *   <li>{@link #REACTIVE} — 仅当 token 逼近上下文窗口上限时触发（默认，与历史行为一致）</li>
 *   <li>{@link #PROACTIVE} — 在更低阈值（约 70%）后台预压缩，避免 Reactive 的紧急性</li>
 *   <li>{@link #SEMANTIC} — 按话题切换切，复用 P2 的 embedding 边界检测（需注入
 *       {@link SemanticBoundaryDetector}，默认 NOOP 即退化为 PROACTIVE 阈值）</li>
 * </ul>
 * <p>
 * OS 类比：REACTIVE = direct reclaim（内存紧张时直接回收），
 * PROACTIVE = kswapd（后台预回收），SEMANTIC = 按进程语义边界回收。
 */
public enum CompactionMode {
    REACTIVE,
    PROACTIVE,
    SEMANTIC;

    /**
     * 按模式计算实际触发阈值。
     *
     * @param mode           当前压缩模式
     * @param baseThreshold  REACTIVE 模式下的基准阈值
     * @return REACTIVE 返回 baseThreshold；PROACTIVE/SEMANTIC 返回 baseThreshold * 7 / 10（约 70%）
     */
    public static int thresholdForMode(CompactionMode mode, int baseThreshold) {
        if (mode == null || mode == REACTIVE) {
            return baseThreshold;
        }
        return baseThreshold * 7 / 10;
    }
}
