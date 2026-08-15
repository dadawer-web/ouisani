package com.ouisani.aios.core.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件热度排名纯函数层 — 镜像 jcode {@code repo_ranking.rs:103-128}。
 * <p>
 * 所有方法无副作用，依赖 now 时间锚 + opts 参数注入，便于单测。
 * 镜像 {@code PlanGraphQuery} 的 package-private final class + 全 static 模式。
 *
 * @see com.ouisani.aios.core.memory.MemoryDir 7 天半衰期黄金公式同构参考
 */
final class FileHeatRanking {

    private FileHeatRanking() {}

    /**
     * 单条记录的衰减权重 — 镜像 jcode {@code recency_weight}（repo_ranking.rs:103-111）。
     * <p>
     * 公式：{@code 0.5^(age_days / half_life).max(floor_weight)}
     * <p>
     * 数学等价于 {@code MemoryDir.java:657} 的 {@code Math.exp(-ageDays * Math.log(2) / 7.0)}：
     * {@code 0.5^x = e^(x·ln 0.5) = e^(-x·ln 2)}。
     *
     * @param record 访问记录
     * @param nowMs  当前时间戳（毫秒），生产传 System.currentTimeMillis()，测试传固定值
     * @param opts   排名选项（半衰期 + 下限）
     * @return 衰减权重，范围 [floorWeight, 1.0]
     */
    static double decayWeight(FileAccessRecord record, long nowMs, RankOptions opts) {
        long last = record.lastAccessMs();
        if (last <= 0) return opts.floorWeight();
        double ageDays = Math.max(0, (nowMs - last) / 86_400_000.0);
        double decayed = Math.pow(0.5, ageDays / opts.halfLifeDays());
        return Math.max(opts.floorWeight(), decayed);
    }

    /**
     * 排名文件列表 — 镜像 jcode {@code rank_repositories_with}（repo_ranking.rs:121-128）。
     * <p>
     * score = decayWeight(record) × (1 + log1p(weightedAccessCount))；
     * tie-break 链（确定性，镜像 jcode）：
     * <ol>
     *   <li>score 降序（高热度优先）</li>
     *   <li>lastAccessMs 降序（最近访问优先）</li>
     *   <li>weightedAccessCount 降序（高频优先）</li>
     *   <li>path 升序（确定性 tie-break）</li>
     * </ol>
     *
     * @param records 访问记录列表
     * @param nowMs   当前时间戳
     * @param opts    排名选项
     * @return 排名后的文件列表（已排序）
     */
    static List<RankedFile> rankFiles(
            List<FileAccessRecord> records, long nowMs, RankOptions opts) {
        List<RankedFile> ranked = new ArrayList<>();
        if (records == null || records.isEmpty()) return ranked;
        for (FileAccessRecord r : records) {
            double w = decayWeight(r, nowMs, opts);
            double score = w * (1.0 + Math.log1p(r.weightedAccessCount()));
            ranked.add(new RankedFile(r.path(), score, r.lastAccessMs(),
                    r.readCount(), r.editCount()));
        }
        ranked.sort(Comparator
                .comparingDouble((RankedFile f) -> -f.score())            // score 降序
                .thenComparingLong((RankedFile f) -> -f.lastAccessMs())  // 最近访问优先
                .thenComparingLong((RankedFile f) -> -weightedCount(f))  // 访问次数优先
                .thenComparing(RankedFile::path));                       // path 升序（确定性）
        return ranked;
    }

    private static long weightedCount(RankedFile f) {
        return f.readCount() + 2 * f.editCount();
    }
}
