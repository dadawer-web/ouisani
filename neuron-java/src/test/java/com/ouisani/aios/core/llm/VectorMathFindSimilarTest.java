package com.ouisani.aios.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VectorMath.findSimilar 单元测试 — 验证镜像 jcode lib.rs:402-418 的语义。
 * <p>
 * 覆盖：阈值预筛、top-k 截断、维度不匹配守护、同分 ordinal、limit=0、空候选、降序输出。
 */
class VectorMathFindSimilarTest {

    private static float[] v(float... xs) { return xs; }

    @Test
    void threshold_filters_below_threshold() {
        float[] query = v(1.0f, 0.0f);
        List<float[]> candidates = List.of(
                v(1.0f, 0.0f),   // cos = 1.0
                v(0.0f, 1.0f),   // cos = 0.0
                v(-1.0f, 0.0f)   // cos = -1.0
        );
        List<VectorMath.ScoredIndex> r = VectorMath.findSimilar(query, candidates, 0.5f, 3);
        assertEquals(1, r.size());
        assertEquals(0, r.get(0).index());
        assertEquals(1.0f, r.get(0).score(), 1e-6f);
    }

    @Test
    void topk_truncation() {
        float[] query = v(1.0f, 0.0f);
        List<float[]> candidates = List.of(
                v(0.9f, 0.1f),   // ~0.99
                v(0.8f, 0.2f),   // ~0.97
                v(0.7f, 0.3f),   // ~0.93
                v(0.6f, 0.4f),   // ~0.87
                v(0.5f, 0.5f)    // ~0.71
        );
        List<VectorMath.ScoredIndex> r = VectorMath.findSimilar(query, candidates, -1.0f, 3);
        assertEquals(3, r.size());
        // 降序：最相似在前
        assertTrue(r.get(0).score() >= r.get(1).score());
        assertTrue(r.get(1).score() >= r.get(2).score());
        // 最高分 = 索引 0
        assertEquals(0, r.get(0).index());
    }

    @Test
    void dim_mismatch_skipped() {
        // query 384 维，候选含 1536 维（mockEmbed 维度）与 384 维混合 — 不抛异常，跳过 1536 维
        float[] query = new float[384];
        query[0] = 1.0f;
        float[] match384 = new float[384];
        match384[0] = 1.0f;        // cos = 1.0
        float[] wrong1536 = new float[1536];  // 维度不匹配
        wrong1536[0] = 1.0f;
        List<float[]> candidates = List.of(wrong1536, match384);

        List<VectorMath.ScoredIndex> r = VectorMath.findSimilar(query, candidates, -1.0f, 5);
        // 仅 384 维候选保留
        assertEquals(1, r.size());
        assertEquals(1, r.get(0).index());
        assertEquals(1.0f, r.get(0).score(), 1e-6f);
    }

    @Test
    void ties_break_by_index_order() {
        // 两个完全相同的向量同分 — 先到者（索引小）保留并排在前面
        float[] query = v(1.0f, 0.0f);
        List<float[]> candidates = List.of(
                v(1.0f, 0.0f),   // index 0, cos=1.0
                v(0.0f, 1.0f),   // index 1, cos=0.0
                v(1.0f, 0.0f)    // index 2, cos=1.0 (tie with index 0)
        );
        List<VectorMath.ScoredIndex> r = VectorMath.findSimilar(query, candidates, -1.0f, 3);
        // 两个同分 1.0（index 0 和 2）+ 一个 0.0（index 1）
        assertEquals(3, r.size());
        assertEquals(1.0f, r.get(0).score(), 1e-6f);
        assertEquals(1.0f, r.get(1).score(), 1e-6f);
        // 同分按 ordinal 升序：index 0 在 index 2 之前
        assertEquals(0, r.get(0).index());
        assertEquals(2, r.get(1).index());
        assertEquals(1, r.get(2).index());
    }

    @Test
    void empty_candidates_returns_empty() {
        float[] query = v(1.0f, 0.0f);
        List<VectorMath.ScoredIndex> r = VectorMath.findSimilar(query, List.of(), -1.0f, 5);
        assertTrue(r.isEmpty());
    }

    @Test
    void negative_threshold_includes_all() {
        float[] query = v(1.0f, 0.0f);
        List<float[]> candidates = List.of(
                v(1.0f, 0.0f),   // 1.0
                v(-1.0f, 0.0f)   // -1.0
        );
        // threshold = -1.0f → 全保留（cosine >= -1.0 恒成立）
        List<VectorMath.ScoredIndex> r = VectorMath.findSimilar(query, candidates, -1.0f, 5);
        assertEquals(2, r.size());
        assertEquals(0, r.get(0).index()); // 1.0 在前
        assertEquals(1, r.get(1).index()); // -1.0 在后
    }

    @Test
    void limit_zero_returns_empty() {
        float[] query = v(1.0f, 0.0f);
        List<float[]> candidates = List.of(v(1.0f, 0.0f));
        List<VectorMath.ScoredIndex> r = VectorMath.findSimilar(query, candidates, -1.0f, 0);
        assertTrue(r.isEmpty());
    }

    @Test
    void descending_score_order() {
        float[] query = v(1.0f, 0.0f);
        List<float[]> candidates = List.of(
                v(0.5f, 0.5f),   // ~0.71
                v(1.0f, 0.0f),   // 1.0
                v(0.0f, 1.0f)    // 0.0
        );
        List<VectorMath.ScoredIndex> r = VectorMath.findSimilar(query, candidates, -1.0f, 3);
        assertEquals(3, r.size());
        // 严格降序（这里分数各不相同）
        assertTrue(r.get(0).score() > r.get(1).score());
        assertTrue(r.get(1).score() > r.get(2).score());
        assertEquals(1, r.get(0).index()); // 1.0
        assertEquals(0, r.get(1).index()); // 0.71
        assertEquals(2, r.get(2).index()); // 0.0
    }
}
