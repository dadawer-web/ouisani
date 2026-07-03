package com.ouisani.aios.core.ranking;

/**
 * 排名选项 — 镜像 jcode {@code RankOptions}（repo_ranking.rs）。
 * <p>
 * 默认半衰期 7 天对齐 {@code MemoryDir.java:657} 既有黄金公式，
 * 默认 floor_weight=0.05 对齐 jcode。
 *
 * @param halfLifeDays 半衰期天数（age=halfLife 时权重=0.5）
 * @param floorWeight  权重下限（防止老记录权重趋近 0 失去排序意义）
 */
public record RankOptions(double halfLifeDays, double floorWeight) {

    /** 默认值：7 天半衰期，0.05 下限 */
    public static final RankOptions DEFAULT = new RankOptions(7.0, 0.05);

    public RankOptions {
        // 防 div-by-0，镜像 jcode .max(0.000_1)
        if (halfLifeDays < 0.0001) halfLifeDays = 0.0001;
        if (floorWeight < 0.0) floorWeight = 0.0;
        if (floorWeight > 1.0) floorWeight = 1.0;
    }
}
