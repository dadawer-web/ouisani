package com.ouisani.aios.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 向量数学工具库 — AIOS 的底层向量运算引擎。
 * <p>
 * 在原有余弦相似度、欧氏距离、归一化的基础上，新增：
 * <ul>
 *   <li>向量残差计算 (Residual): R = V - C</li>
 *   <li>向量重建 (Reconstruct): V = C + R</li>
 *   <li>向量加减法</li>
 *   <li>标量乘法</li>
 *   <li>K-Means 聚类（用于残差金字塔 L0 层构建）</li>
 *   <li>内积 (Dot Product)</li>
 *   <li>L2 范数 (Norm)</li>
 * </ul>
 */
public final class VectorMath {

    private VectorMath() {}

    // ════════════════════════════════════════════════════════════════
    //  基础向量运算
    // ════════════════════════════════════════════════════════════════

    public static float cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length || v1.length == 0) {
            throw new IllegalArgumentException("Vectors must be non-null, same length, and non-empty");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }

        if (norm1 == 0.0f || norm2 == 0.0f) {
            return 0.0f;
        }

        return (float) (dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2)));
    }

    public static float euclideanDistance(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vectors must be non-null and same length");
        }

        float sum = 0.0f;
        for (int i = 0; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            sum += diff * diff;
        }

        return (float) Math.sqrt(sum);
    }

    public static float[] normalize(float[] v) {
        if (v == null || v.length == 0) {
            throw new IllegalArgumentException("Vector must be non-null and non-empty");
        }

        float norm = l2Norm(v);
        if (norm == 0.0f) {
            return new float[v.length];
        }

        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            result[i] = v[i] / norm;
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  残差计算 (Residual Computation)
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算残差向量 — R = V - C。
     * <p>
     * 残差金字塔的核心运算：将原始向量 V 减去其所属簇的中心 C，
     * 得到残差 R。残差通常比原始向量更稀疏、更紧凑，
     * 在存储和检索时具有更高的效率。
     *
     * @param vector  原始向量 V
     * @param centroid 簇中心 C
     * @return 残差向量 R = V - C
     */
    public static float[] residual(float[] vector, float[] centroid) {
        if (vector == null || centroid == null || vector.length != centroid.length) {
            throw new IllegalArgumentException("Vector and centroid must be non-null and same length");
        }

        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i] - centroid[i];
        }
        return result;
    }

    /**
     * 从残差重建原始向量 — V = C + R。
     * <p>
     * 检索时，将残差向量 R 与其簇中心 C 相加，
     * 重建出原始向量 V，用于精确相似度计算。
     *
     * @param residual  残差向量 R
     * @param centroid  簇中心 C
     * @return 重建的原始向量 V = C + R
     */
    public static float[] reconstruct(float[] residual, float[] centroid) {
        if (residual == null || centroid == null || residual.length != centroid.length) {
            throw new IllegalArgumentException("Residual and centroid must be non-null and same length");
        }

        float[] result = new float[residual.length];
        for (int i = 0; i < residual.length; i++) {
            result[i] = residual[i] + centroid[i];
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  向量算术
    // ════════════════════════════════════════════════════════════════

    /** 向量加法: result = a + b */
    public static float[] add(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("Vectors must be non-null and same length");
        }
        float[] result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] + b[i];
        }
        return result;
    }

    /** 向量减法: result = a - b */
    public static float[] subtract(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("Vectors must be non-null and same length");
        }
        float[] result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] - b[i];
        }
        return result;
    }

    /** 标量乘法: result = v * scalar */
    public static float[] scale(float[] v, float scalar) {
        if (v == null) throw new IllegalArgumentException("Vector must be non-null");
        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            result[i] = v[i] * scalar;
        }
        return result;
    }

    /** 内积 (Dot Product) */
    public static float dotProduct(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("Vectors must be non-null and same length");
        }
        float sum = 0.0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /** L2 范数 (Euclidean Norm) */
    public static float l2Norm(float[] v) {
        if (v == null) throw new IllegalArgumentException("Vector must be non-null");
        float sum = 0.0f;
        for (float val : v) {
            sum += val * val;
        }
        return (float) Math.sqrt(sum);
    }

    /** 零向量 */
    public static float[] zeros(int dim) {
        return new float[dim];
    }

    // ════════════════════════════════════════════════════════════════
    //  K-Means 聚类 (用于残差金字塔 L0 层构建)
    // ════════════════════════════════════════════════════════════════

    /**
     * K-Means 聚类 — 将向量集合划分为 K 个簇，返回簇中心。
     * <p>
     * 用于构建残差金字塔的 L0 层（粗粒度聚类中心）。
     * 算法：
     * <ol>
     *   <li>随机初始化 K 个中心（从数据中采样）</li>
     *   <li>迭代：分配每个向量到最近中心 → 重新计算中心</li>
     *   <li>收敛或达到最大迭代次数后返回</li>
     * </ol>
     *
     * @param vectors  输入向量集合
     * @param k        簇数量
     * @param maxIter  最大迭代次数
     * @return K 个簇中心
     */
    public static float[][] kMeans(List<float[]> vectors, int k, int maxIter) {
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalArgumentException("Vectors must be non-null and non-empty");
        }
        if (k <= 0 || k > vectors.size()) {
            k = Math.max(1, vectors.size());
        }

        int dim = vectors.get(0).length;
        Random rng = new Random(42);

        // ── 初始化：从数据中随机采样 K 个中心 ──
        float[][] centroids = new float[k][dim];
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) indices.add(i);
        java.util.Collections.shuffle(indices, rng);

        for (int c = 0; c < k; c++) {
            System.arraycopy(vectors.get(indices.get(c % indices.size())), 0, centroids[c], 0, dim);
        }

        // ── 迭代 ──
        int[] assignments = new int[vectors.size()];

        for (int iter = 0; iter < maxIter; iter++) {
            boolean changed = false;

            // 分配每个向量到最近中心
            for (int i = 0; i < vectors.size(); i++) {
                float[] v = vectors.get(i);
                int bestCluster = 0;
                float bestDist = Float.MAX_VALUE;

                for (int c = 0; c < k; c++) {
                    float dist = euclideanDistance(v, centroids[c]);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestCluster = c;
                    }
                }

                if (assignments[i] != bestCluster) {
                    assignments[i] = bestCluster;
                    changed = true;
                }
            }

            if (!changed) break;

            // 重新计算中心
            float[][] newCentroids = new float[k][dim];
            int[] counts = new int[k];

            for (int i = 0; i < vectors.size(); i++) {
                int c = assignments[i];
                for (int d = 0; d < dim; d++) {
                    newCentroids[c][d] += vectors.get(i)[d];
                }
                counts[c]++;
            }

            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    for (int d = 0; d < dim; d++) {
                        newCentroids[c][d] /= counts[c];
                    }
                    System.arraycopy(newCentroids[c], 0, centroids[c], 0, dim);
                }
            }
        }

        return centroids;
    }

    /**
     * 找到向量最近的簇中心。
     *
     * @param vector    查询向量
     * @param centroids 簇中心数组
     * @return 最近簇的索引
     */
    public static int nearestCentroid(float[] vector, float[][] centroids) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int c = 0; c < centroids.length; c++) {
            float dist = euclideanDistance(vector, centroids[c]);
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    /**
     * 找到向量最近的 N 个簇中心（按距离升序）。
     *
     * @param vector    查询向量
     * @param centroids 簇中心数组
     * @param n         返回数量
     * @return 最近 N 个簇的索引数组
     */
    public static int[] nearestCentroids(float[] vector, float[][] centroids, int n) {
        n = Math.min(n, centroids.length);
        record IndexDist(int index, float dist) {}
        List<IndexDist> list = new ArrayList<>();
        for (int c = 0; c < centroids.length; c++) {
            list.add(new IndexDist(c, euclideanDistance(vector, centroids[c])));
        }
        list.sort(java.util.Comparator.comparingDouble(IndexDist::dist));
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            result[i] = list.get(i).index;
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  有界 top-k 语义检索 (镜像 jcode find_similar, lib.rs:402-418)
    // ════════════════════════════════════════════════════════════════

    /**
     * findSimilar 的结果项：候选在原列表中的索引 + 相似度分数。
     */
    public record ScoredIndex(int index, float score) {}

    /**
     * 有界 top-k 语义检索 — 镜像 jcode {@code find_similar}（lib.rs:402-418）。
     * <p>
     * 用 {@link BoundedTopK} 最小堆保持 top-k，O(N log K)，避免全量排序的 O(N log N)。
     * <ul>
     *   <li>对每个候选计算 cosine 相似度，过滤 {@code score >= threshold}</li>
     *   <li>维度不匹配的候选被跳过（不抛异常）— 保证中途切换 embedding provider
     *       （如 mockEmbed 1536 维 → ONNX 384 维）时既有索引查询不崩溃</li>
     *   <li>输出按 score 降序、ordinal（索引）升序排列</li>
     * </ul>
     *
     * @param query      查询向量
     * @param candidates 候选向量列表
     * @param threshold  最低相似度阈值（cosine ∈ [-1,1]；传 -1.0f 表示不过滤）
     * @param topK       返回数量上限（&lt;= 0 返回空）
     * @return 排序后的 (index, score) 列表
     */
    public static List<ScoredIndex> findSimilar(float[] query, List<float[]> candidates,
                                                float threshold, int topK) {
        BoundedTopK<Integer> heap = new BoundedTopK<>(topK);
        for (int i = 0; i < candidates.size(); i++) {
            float score;
            try {
                score = cosineSimilarity(query, candidates.get(i));
            } catch (IllegalArgumentException e) {
                continue; // 维度不匹配 — skip
            }
            if (score >= threshold) {
                heap.offer(score, i);
            }
        }
        return heap.drainSorted().stream()
                .map(e -> new ScoredIndex(e.value(), e.score()))
                .toList();
    }
}
