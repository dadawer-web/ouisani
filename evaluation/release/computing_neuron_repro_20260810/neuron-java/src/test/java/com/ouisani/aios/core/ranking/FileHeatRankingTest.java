package com.ouisani.aios.core.ranking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileHeatRanking 纯函数测试 — 镜像 jcode repo_ranking.rs 测试样例精神。
 * <p>
 * 覆盖 decayWeight 衰减公式（与 MemoryDir.java:657 同构验证）和 rankFiles 排序 tie-break。
 */
class FileHeatRankingTest {

    private static final long DAY_MS = 86_400_000L;
    private static final long NOW = 100L * DAY_MS;  // 固定时间锚，便于断言

    private static FileAccessRecord rec(String path, long lastAccessMs, long reads, long edits) {
        return new FileAccessRecord(path, lastAccessMs, lastAccessMs, reads, edits);
    }

    @Test
    void decayWeight_zeroAge_returnsOne() {
        // age=0 → 0.5^0 = 1.0
        FileAccessRecord r = rec("/a", NOW, 1, 0);
        double w = FileHeatRanking.decayWeight(r, NOW, RankOptions.DEFAULT);
        assertEquals(1.0, w, 1e-9, "age=0 → weight=1.0");
    }

    @Test
    void decayWeight_atHalfLife_returnsHalf() {
        // age=halfLife → 0.5^(1) = 0.5
        RankOptions opts = new RankOptions(7.0, 0.05);
        FileAccessRecord r = rec("/a", NOW - 7 * DAY_MS, 1, 0);
        double w = FileHeatRanking.decayWeight(r, NOW, opts);
        assertEquals(0.5, w, 1e-9, "age=halfLife → weight=0.5");
    }

    @Test
    void decayWeight_floorWeightEnforced() {
        // age=很大 → 衰减到下限 floor_weight
        RankOptions opts = new RankOptions(7.0, 0.05);
        FileAccessRecord r = rec("/a", NOW - 365 * DAY_MS, 1, 0);
        double w = FileHeatRanking.decayWeight(r, NOW, opts);
        assertEquals(0.05, w, 1e-9, "age=很大 → floorWeight");
    }

    @Test
    void decayWeight_noTimestamp_returnsFloor() {
        // lastAccessMs=0 → floorWeight
        RankOptions opts = new RankOptions(7.0, 0.05);
        FileAccessRecord r = new FileAccessRecord("/a", 0, 0, 0, 0);
        double w = FileHeatRanking.decayWeight(r, NOW, opts);
        assertEquals(0.05, w, 1e-9, "无时间戳 → floorWeight");
    }

    @Test
    void decayWeight_matchMemoryDirFormula() {
        // 与 MemoryDir.java:657 的 Math.exp(-ageDays * Math.log(2) / 7.0) 同构验证
        // 0.5^(age/7) = exp((age/7) * ln(0.5)) = exp(-(age/7) * ln(2))
        RankOptions opts = new RankOptions(7.0, 0.0);  // floor=0 避免下限干扰
        long ageMs = 3 * DAY_MS;  // age=3 days
        FileAccessRecord r = rec("/a", NOW - ageMs, 1, 0);
        double jcodeForm = FileHeatRanking.decayWeight(r, NOW, opts);
        double memoryDirForm = Math.exp(-(ageMs / 86_400_000.0) * Math.log(2) / 7.0);
        assertEquals(memoryDirForm, jcodeForm, 1e-12, "jcode 0.5^(age/7) == MemoryDir exp(-age*ln2/7)");
    }

    @Test
    void rankFiles_emptyReturnsEmpty() {
        assertTrue(FileHeatRanking.rankFiles(List.of(), NOW, RankOptions.DEFAULT).isEmpty());
        assertTrue(FileHeatRanking.rankFiles(null, NOW, RankOptions.DEFAULT).isEmpty());
    }

    @Test
    void rankFiles_scoreDescending() {
        // 两个文件：a 最近访问，b 旧访问 → a 排前
        FileAccessRecord a = rec("/a", NOW, 1, 0);          // 最近
        FileAccessRecord b = rec("/b", NOW - 30 * DAY_MS, 1, 0);  // 旧
        List<RankedFile> ranked = FileHeatRanking.rankFiles(List.of(b, a), NOW, RankOptions.DEFAULT);
        assertEquals("/a", ranked.get(0).path(), "score 降序 → a 在前");
        assertEquals("/b", ranked.get(1).path());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    @Test
    void rankFiles_tieBreakByLastAccess() {
        // 同 score 信号下（这里用相同 age 但不同 lastAccess 验证 tie-break）
        // 实际 score 会因 access count 略不同，但验证 lastAccess 降序优先级
        FileAccessRecord recent = rec("/z", NOW - 1 * DAY_MS, 5, 0);
        FileAccessRecord older = rec("/a", NOW - 10 * DAY_MS, 5, 0);
        List<RankedFile> ranked = FileHeatRanking.rankFiles(List.of(older, recent), NOW, RankOptions.DEFAULT);
        // recent 的 decay 更高 → score 更高 → 排前
        assertEquals("/z", ranked.get(0).path(), "近期访问优先");
    }

    @Test
    void rankFiles_tieBreakByPath() {
        // 完全相同的时间戳和计数 → path 升序 tie-break（确定性）
        FileAccessRecord a = rec("/b/path", NOW - 5 * DAY_MS, 2, 0);
        FileAccessRecord b = rec("/a/path", NOW - 5 * DAY_MS, 2, 0);
        List<RankedFile> ranked = FileHeatRanking.rankFiles(List.of(a, b), NOW, RankOptions.DEFAULT);
        assertEquals("/a/path", ranked.get(0).path(), "path 升序 tie-break");
        assertEquals("/b/path", ranked.get(1).path());
    }

    @Test
    void rankFiles_editWeightsDoubleReadCount() {
        // 编辑权重 ×2：1 编辑 vs 2 读取应产生相同 weightedAccessCount
        FileAccessRecord editOne = rec("/a", NOW, 0, 1);  // weighted = 2
        FileAccessRecord readTwo = rec("/b", NOW, 2, 0);  // weighted = 2
        List<RankedFile> ranked = FileHeatRanking.rankFiles(List.of(editOne, readTwo), NOW, RankOptions.DEFAULT);
        // 同 weighted count + 同 lastAccess → score 相同 → path 升序
        assertEquals("/a", ranked.get(0).path());
        assertEquals("/b", ranked.get(1).path());
        assertEquals(ranked.get(0).score(), ranked.get(1).score(), 1e-9, "edit×2 == read×2 → score 相同");
    }

    @Test
    void rankFiles_deterministicOrdering() {
        FileAccessRecord a = rec("/a", NOW - 1 * DAY_MS, 3, 1);
        FileAccessRecord b = rec("/b", NOW - 5 * DAY_MS, 1, 0);
        FileAccessRecord c = rec("/c", NOW - 10 * DAY_MS, 5, 2);
        List<RankedFile> r1 = FileHeatRanking.rankFiles(List.of(a, b, c), NOW, RankOptions.DEFAULT);
        List<RankedFile> r2 = FileHeatRanking.rankFiles(List.of(c, a, b), NOW, RankOptions.DEFAULT);  // 不同输入顺序
        // 排序结果应一致（确定性）
        assertEquals(r1.size(), r2.size());
        for (int i = 0; i < r1.size(); i++) {
            assertEquals(r1.get(i).path(), r2.get(i).path(), "确定性排序: index " + i);
        }
    }

    @Test
    void rankFiles_jcodeSampleTest() {
        // 镜像 jcode repo_ranking.rs 测试精神：3 个文件，1 旧 1 新 1 高频
        FileAccessRecord oldFile = rec("/old", NOW - 60 * DAY_MS, 1, 0);   // 旧，floor 接近 0.05
        FileAccessRecord newFile = rec("/new", NOW - 1 * DAY_MS, 1, 0);    // 新，decay 接近 1
        FileAccessRecord hotFile = rec("/hot", NOW - 2 * DAY_MS, 10, 5);   // 高频，decay 高 + log(20) 加成
        List<RankedFile> ranked = FileHeatRanking.rankFiles(
                List.of(oldFile, newFile, hotFile), NOW, RankOptions.DEFAULT);
        // 期望排序：hot > new > old
        assertEquals("/hot", ranked.get(0).path(), "高频 + 近期 → 最高分");
        assertEquals("/new", ranked.get(1).path(), "近期 → 次高");
        assertEquals("/old", ranked.get(2).path(), "旧 + 低频 → 最低");
    }
}
