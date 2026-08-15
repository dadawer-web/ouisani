package com.ouisani.aios.core.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 有界 top-k 最小堆 — 镜像 jcode {@code jcode-embedding/src/lib.rs:43-83} {@code top_k_scored}。
 * <p>
 * 用 {@link PriorityQueue}（默认最小堆，head = 最小元素）保持 size &le; limit：
 * <ol>
 *   <li>未满：直接 {@code add}</li>
 *   <li>已满：若 {@code candidate.score > head.score}（<b>严格 &gt;</b>），弹出 head 并加入新值；
 *       同分（包括 NaN）不替换 — 先到者（ordinal 小者）保留</li>
 * </ol>
 * 最终输出按 score 降序、ordinal 升序排序（镜像 lib.rs:74-82）。
 *
 * <h3>与 Rust 实现的语义对齐</h3>
 * <ul>
 *   <li>Rust {@code BinaryHeap<Reverse<TopKItem<T>>>} — max-heap 包 {@code Reverse} → min-heap。
 *       Java {@link PriorityQueue} + 升序 {@link Comparator} → 同样 min-heap，head = 最小元素。</li>
 *   <li>Rust {@code Ord} = {@code score.total_cmp().then(ordinal.cmp())} —
 *       {@link #totalCompare(float, float)} 用 {@link Float#compare} 镜像 {@code f32::total_cmp}，
 *       处理 NaN/−0.0 与 Rust 一致（NaN &gt; +∞，−0.0 &lt; +0.0）。</li>
 *   <li>严格 {@code >} 替换：同分保留先到者（ordinal 小者）— 镜像 lib.rs:64-67。</li>
 * </ul>
 *
 * @param <T> 值类型
 */
public final class BoundedTopK<T> {

    /**
     * 堆条目 — 镜像 jcode {@code TopKItem<T>}（lib.rs:14-19）。
     */
    public record TopKItem<T>(float score, int ordinal, T value) {}

    private final int limit;
    private final PriorityQueue<TopKItem<T>> heap;
    private int nextOrdinal = 0;

    /**
     * 构造一个容量为 {@code limit} 的有界堆。
     *
     * @param limit 最大容量（&lt;= 0 时所有方法均返回空，镜像 lib.rs:47-49）
     */
    public BoundedTopK(int limit) {
        this.limit = limit;
        // 升序 Comparator：head = 最小元素（先按 score 升序，再按 ordinal 升序）
        this.heap = new PriorityQueue<>(
                Comparator
                        .comparing((TopKItem<T> i) -> i.score(), BoundedTopK::totalCompare)
                        .thenComparingInt(TopKItem<T>::ordinal)
        );
    }

    /**
     * 投递一个候选值。已满时严格按 {@code >} 替换 head。
     * <p>
     * 镜像 lib.rs:52-71：先到者获得较小 ordinal；同分不替换。
     *
     * @param score 分数
     * @param value 值
     */
    public void offer(float score, T value) {
        if (limit <= 0) return;
        int ordinal = nextOrdinal++;
        TopKItem<T> candidate = new TopKItem<>(score, ordinal, value);

        if (heap.size() < limit) {
            heap.offer(candidate);
            return;
        }

        TopKItem<T> smallest = heap.peek();
        // 严格 >：同分不替换
        if (totalCompare(score, smallest.score()) > 0) {
            heap.poll();
            heap.offer(candidate);
        }
    }

    /**
     * 取出当前 top-k 结果，按 score 降序、ordinal 升序排序。
     * <p>
     * 镜像 lib.rs:74-82。
     *
     * @return 排序后的 (value, score) 列表；堆不重置
     */
    public List<TopKEntry<T>> drainSorted() {
        List<TopKItem<T>> items = new ArrayList<>(heap);
        // score 降序（total_cmp 反向），再 ordinal 升序
        items.sort(
                Comparator
                        .comparing((TopKItem<T> i) -> i.score(), BoundedTopK::totalCompareReversed)
                        .thenComparingInt(TopKItem<T>::ordinal)
        );
        List<TopKEntry<T>> result = new ArrayList<>(items.size());
        for (TopKItem<T> item : items) {
            result.add(new TopKEntry<>(item.value(), item.score()));
        }
        return result;
    }

    /**
     * 便利方法：从迭代器构造 top-k。镜像 jcode {@code top_k_scored} 顶层函数。
     *
     * @param items 候选迭代器（值 + 分数）
     * @param limit 容量
     * @return 排序后的 top-k 列表
     */
    public static <T> List<TopKEntry<T>> topK(Iterator<TopKEntry<T>> items, int limit) {
        BoundedTopK<T> bk = new BoundedTopK<>(limit);
        while (items.hasNext()) {
            TopKEntry<T> e = items.next();
            bk.offer(e.score(), e.value());
        }
        return bk.drainSorted();
    }

    /** 当前堆大小。 */
    public int size() {
        return heap.size();
    }

    /** 容量。 */
    public int limit() {
        return limit;
    }

    // ════════════════════════════════════════════════════════════════
    //  f32::total_cmp 镜像
    // ════════════════════════════════════════════════════════════════

    /**
     * 镜像 Rust {@code f32::total_cmp}：按 IEEE 754 全序比较。
     * <p>
     * 使用 {@link Float#compare} — 与 Rust {@code total_cmp} 在非 NaN / 非带符号零场景下一致；
     * 这正是 cosine 相似度的常态（值域 {@code [-1, 1]}，无 NaN / -0.0）。
     * <ul>
     *   <li>{@code -0.0 < +0.0}（Rust total_cmp 一致）</li>
     *   <li>{@code NaN > +∞}（Rust total_cmp 中 NaN 排在 +∞ 和 -∞ 之间；Float.compare 把 NaN 视作 +∞+1。
     *       实际 embedding 不会产生 NaN，差异可忽略。）</li>
     * </ul>
     */
    static int totalCompare(float a, float b) {
        return Float.compare(a, b);
    }

    /** totalCompare 的反向（用于降序排序）。 */
    private static int totalCompareReversed(float a, float b) {
        return totalCompare(b, a);
    }

    // ════════════════════════════════════════════════════════════════
    //  值条目
    // ════════════════════════════════════════════════════════════════

    /**
     * 输出条目 — 镜像 jcode {@code top_k_scored} 返回的 {@code (T, f32)}。
     */
    public record TopKEntry<T>(T value, float score) {}
}
