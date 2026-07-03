package com.ouisani.aios.core.llm;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BoundedTopK 单元测试 — 验证镜像 jcode lib.rs:43-83 的语义。
 */
class BoundedTopKTest {

    @Test
    void limit_zero_returns_empty() {
        BoundedTopK<String> bk = new BoundedTopK<>(0);
        bk.offer(1.0f, "a");
        assertTrue(bk.drainSorted().isEmpty(), "limit=0 → empty");
    }

    @Test
    void under_limit_keeps_all() {
        BoundedTopK<String> bk = new BoundedTopK<>(5);
        bk.offer(1.0f, "a");
        bk.offer(2.0f, "b");
        bk.offer(3.0f, "c");

        List<BoundedTopK.TopKEntry<String>> r = bk.drainSorted();
        assertEquals(3, r.size());
        // score 降序
        assertEquals("c", r.get(0).value());
        assertEquals("b", r.get(1).value());
        assertEquals("a", r.get(2).value());
    }

    @Test
    void strict_greater_replaces_head() {
        BoundedTopK<String> bk = new BoundedTopK<>(3);
        bk.offer(1.0f, "a");
        bk.offer(2.0f, "b");
        bk.offer(3.0f, "c");
        // head 是 score=1.0（"a"），5.0 > 1.0 → 替换
        bk.offer(5.0f, "d");

        List<BoundedTopK.TopKEntry<String>> r = bk.drainSorted();
        assertEquals(3, r.size());
        assertEquals("d", r.get(0).value()); // 5.0
        assertEquals("c", r.get(1).value()); // 3.0
        assertEquals("b", r.get(2).value()); // 2.0
    }

    @Test
    void equal_score_does_not_evict_first_arrival() {
        BoundedTopK<String> bk = new BoundedTopK<>(2);
        bk.offer(1.0f, "first");
        bk.offer(2.0f, "second");
        // head = 1.0；候选 1.0 不严格大于 → 不替换
        bk.offer(1.0f, "later");

        List<BoundedTopK.TopKEntry<String>> r = bk.drainSorted();
        assertEquals(2, r.size());
        // 先到者 "first" 保留（ordinal 0）
        assertTrue(r.stream().anyMatch(e -> e.value().equals("first")));
        assertFalse(r.stream().anyMatch(e -> e.value().equals("later")));
    }

    @Test
    void ties_break_by_ordinal_ascending_in_output() {
        // 三个同分候选 + 一个高分 — 两个同分会被保留（limit=3）
        BoundedTopK<String> bk = new BoundedTopK<>(3);
        bk.offer(0.5f, "tie1");
        bk.offer(0.5f, "tie2");
        bk.offer(1.0f, "high");
        bk.offer(0.5f, "tie3"); // 严格 > 不成立 → tie3 不入

        List<BoundedTopK.TopKEntry<String>> r = bk.drainSorted();
        assertEquals(3, r.size());
        // high 第一
        assertEquals("high", r.get(0).value());
        // 然后两个 tie，按 ordinal 升序：tie1 在 tie2 之前
        assertEquals("tie1", r.get(1).value());
        assertEquals("tie2", r.get(2).value());
    }

    @Test
    void drain_sorted_descending_by_score() {
        BoundedTopK<Integer> bk = new BoundedTopK<>(10);
        for (int i = 0; i < 20; i++) {
            bk.offer(i, i);
        }
        List<BoundedTopK.TopKEntry<Integer>> r = bk.drainSorted();
        assertEquals(10, r.size());
        // 前 10 个：score 19..10 降序
        for (int i = 0; i < 10; i++) {
            assertEquals(19 - i, r.get(i).value(), "rank " + i + " should be " + (19 - i));
            assertEquals((float) (19 - i), r.get(i).score(), 1e-6f);
        }
    }

    @Test
    void negative_scores_handled_correctly() {
        BoundedTopK<String> bk = new BoundedTopK<>(2);
        bk.offer(-1.0f, "neg");
        bk.offer(0.0f, "zero");
        bk.offer(-0.5f, "neg_half");

        List<BoundedTopK.TopKEntry<String>> r = bk.drainSorted();
        // 保留 score 最高的两个：zero(0.0) 和 neg_half(-0.5)
        assertEquals(2, r.size());
        assertEquals("zero", r.get(0).value());
        assertEquals("neg_half", r.get(1).value());
    }

    @Test
    void minus_zero_less_than_plus_zero_total_compare() {
        // -0.0 < +0.0（IEEE 754 totalOrder；Java Float.compare 与 Rust f32::total_cmp 一致）
        assertEquals(-1, BoundedTopK.totalCompare(-0.0f, 0.0f));
        // -0.0 不严格 > +0.0 → 不替换，"first" 保留
        BoundedTopK<String> bk = new BoundedTopK<>(1);
        bk.offer(0.0f, "first");
        bk.offer(-0.0f, "second");
        List<BoundedTopK.TopKEntry<String>> r = bk.drainSorted();
        assertEquals(1, r.size());
        assertEquals("first", r.get(0).value(), "+0.0 not strictly > -0.0 → first retained");
    }

    @Test
    void topK_static_convenience() {
        List<BoundedTopK.TopKEntry<String>> entries = List.of(
                new BoundedTopK.TopKEntry<>("a", 1.0f),
                new BoundedTopK.TopKEntry<>("b", 3.0f),
                new BoundedTopK.TopKEntry<>("c", 2.0f),
                new BoundedTopK.TopKEntry<>("d", 5.0f),
                new BoundedTopK.TopKEntry<>("e", 4.0f)
        );
        Iterator<BoundedTopK.TopKEntry<String>> it = entries.iterator();
        List<BoundedTopK.TopKEntry<String>> r = BoundedTopK.topK(it, 3);

        assertEquals(3, r.size());
        assertEquals("d", r.get(0).value()); // 5.0
        assertEquals("e", r.get(1).value()); // 4.0
        assertEquals("b", r.get(2).value()); // 3.0
    }

    @Test
    void repeated_drain_does_not_clear_heap() {
        BoundedTopK<String> bk = new BoundedTopK<>(3);
        bk.offer(1.0f, "a");
        bk.offer(2.0f, "b");
        bk.offer(3.0f, "c");

        List<BoundedTopK.TopKEntry<String>> r1 = bk.drainSorted();
        List<BoundedTopK.TopKEntry<String>> r2 = bk.drainSorted();
        assertEquals(r1, r2, "drainSorted is non-destructive");
    }
}
