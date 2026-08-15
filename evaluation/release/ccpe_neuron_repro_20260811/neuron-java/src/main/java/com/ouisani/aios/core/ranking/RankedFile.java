package com.ouisani.aios.core.ranking;

/**
 * 排名后的文件 — 镜像 jcode {@code RankedRepo}（repo_ranking.rs）。
 *
 * @param path          VFS 路径
 * @param score         综合热度分数（衰减权重 × 频度因子）
 * @param lastAccessMs  最近活动时间戳
 * @param readCount     读取次数
 * @param editCount     编辑次数
 */
public record RankedFile(
        String path, double score,
        long lastAccessMs, long readCount, long editCount
) {}
