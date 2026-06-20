package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.VectorMath;
import com.ouisani.aios.core.vfs.VfsJournal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 向量节点 — AIOS 的残差金字塔向量索引引擎。
 * <p>
 * 不再将所有高维向量平铺在一个 List 里，而是构建多级树状结构（金字塔）：
 * <ul>
 *   <li><b>L0 (顶层)</b>：粗粒度的聚类中心点（Cluster Centroids），通过 K-Means 构建</li>
 *   <li><b>L1 (底层)</b>：存储相对于 L0 中心点的"残差向量 (Residual Vectors)"，
 *       而非完整原始向量</li>
 * </ul>
 *
 * <h3>残差金字塔结构</h3>
 * <pre>
 *   L0: [Centroid_0] [Centroid_1] ... [Centroid_K]     ← K-Means 聚类中心
 *        │              │                    │
 *   L1: [R₀₀,R₀₁...] [R₁₀,R₁₁,...]  [Rₖ₀,Rₖ₁,...]   ← 残差向量 R = V - C
 * </pre>
 *
 * <h3>Wave 召回算法</h3>
 * <pre>
 *   Query ──→ L0: 严格阈值锁定高相关簇
 *                │
 *                ▼
 *          L1: 波动阈值搜索
 *          ┌──────────────────────────────────┐
 *          │  Wave 1: θ = θ₀ (严格)           │
 *          │  ↓ 密度梯度检测                   │
 *          │  Wave 2: θ = θ₀ ± Δθ (自适应)    │
 *          │  ↓ 捕获长尾 / 排除噪音            │
 *          │  Wave 3: θ = θ_final (收敛)      │
 *          └──────────────────────────────────┘
 *                │
 *                ▼
 *          合并 + Re-rank → 最终结果
 * </pre>
 */
public non-sealed class VectorNode implements VfsNode {

    private final String path;
    private final LlmProvider llmProvider;
    final List<VectorRecord> records;
    private int ownerUid;
    private int permissions;

    // ════════════════════════════════════════════════════════════════
    //  残差金字塔索引 (Residual Pyramid Index)
    // ════════════════════════════════════════════════════════════════

    /** L0 层：聚类中心 */
    private volatile float[][] centroids = null;

    /** L1 层：每个簇的残差向量记录 — clusterId → List<ResidualRecord> */
    private final Map<Integer, List<ResidualRecord>> residualIndex = new ConcurrentHashMap<>();

    /** 金字塔是否已构建 */
    private volatile boolean pyramidBuilt = false;

    /** 聚类数 K — 默认 sqrt(N) */
    private volatile int numClusters = 0;

    /** 触发金字塔重建的阈值（新增记录数） */
    private static final int REBUILD_THRESHOLD = 50;

    /** 上次构建金字塔时的记录数 */
    private volatile int lastBuildRecordCount = 0;

    // ── Wave 算法配置 ──

    /** L0 层搜索的候选簇数量（nprobe） */
    private static final int NPROBE = 3;

    /** Wave 初始阈值 */
    private static final float WAVE_INITIAL_THRESHOLD = 0.7f;

    /** Wave 自适应步长 */
    private static final float WAVE_STEP = 0.05f;

    /** Wave 最大波数 */
    private static final int WAVE_MAX_ROUNDS = 3;

    /** Wave 最小阈值 */
    private static final float WAVE_MIN_THRESHOLD = 0.3f;

    /** Wave 最大阈值 */
    private static final float WAVE_MAX_THRESHOLD = 0.95f;

    public VectorNode(String path, LlmProvider llmProvider) {
        this(path, llmProvider, 0, 0666);
    }

    public VectorNode(String path, LlmProvider llmProvider, int ownerUid, int permissions) {
        this.path = path;
        this.llmProvider = llmProvider;
        this.records = new CopyOnWriteArrayList<>();
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() { return VfsNodeType.VECTOR; }

    @Override
    public String path() { return path; }

    @Override
    public int ownerUid() { return ownerUid; }

    @Override
    public void setOwnerUid(int uid) { this.ownerUid = uid; }

    @Override
    public int permissions() { return permissions; }

    @Override
    public void setPermissions(int perm) { this.permissions = perm; }

    // ════════════════════════════════════════════════════════════════
    //  写操作 (sys_write) — 双写：原始记录 + 残差索引
    // ════════════════════════════════════════════════════════════════

    @Override
    public String read() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\":\"").append(path).append("\",");
        sb.append("\"recordCount\":").append(records.size()).append(",");
        sb.append("\"pyramidBuilt\":").append(pyramidBuilt).append(",");
        sb.append("\"numClusters\":").append(numClusters).append(",");
        sb.append("\"records\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append(",");
            VectorRecord r = records.get(i);
            sb.append("{\"id\":").append(i).append(",")
              .append("\"text\":\"").append(escape(r.text)).append("\",")
              .append("\"clusterId\":").append(r.clusterId).append(",")
              .append("\"dimensions\":").append(r.vector.length).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public boolean write(String payload) {
        if (payload == null || payload.isBlank()) return false;

        // WAL: journal the write before applying it
        VfsJournal.getInstance().appendLog(path, "WRITE", payload);

        float[] vector = llmProvider.embed(payload);

        // 分配到最近的簇（如果金字塔已构建）
        int clusterId = -1;
        if (pyramidBuilt && centroids != null) {
            clusterId = VectorMath.nearestCentroid(vector, centroids);

            // 计算残差并存入 L1 层
            float[] residual = VectorMath.residual(vector, centroids[clusterId]);
            residualIndex.computeIfAbsent(clusterId, k -> new ArrayList<>())
                    .add(new ResidualRecord(records.size(), payload, residual, clusterId));
        }

        records.add(new VectorRecord(payload, vector, clusterId));

        // 检查是否需要重建金字塔
        if (records.size() - lastBuildRecordCount >= REBUILD_THRESHOLD) {
            rebuildPyramidAsync();
        }

        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  残差金字塔构建 (Pyramid Construction)
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建残差金字塔 — K-Means 聚类 + 残差编码。
     * <p>
     * 构建流程：
     * <ol>
     *   <li>确定聚类数 K = max(1, sqrt(N))</li>
     *   <li>对所有向量执行 K-Means，得到 L0 层中心</li>
     *   <li>将每个向量编码为残差 R = V - C[cluster(V)]，存入 L1 层</li>
     * </ol>
     */
    public void buildPyramid() {
        if (records.size() < 2) {
            return;
        }

        // ── Step 1: 确定聚类数 K ──
        numClusters = Math.max(1, (int) Math.sqrt(records.size()));
        numClusters = Math.min(numClusters, records.size());

        // ── Step 2: K-Means 聚类 ──
        List<float[]> vectors = records.stream().map(r -> r.vector).toList();
        centroids = VectorMath.kMeans(vectors, numClusters, 20);

        // ── Step 3: 构建残差索引 ──
        residualIndex.clear();
        for (int i = 0; i < records.size(); i++) {
            VectorRecord record = records.get(i);
            int clusterId = VectorMath.nearestCentroid(record.vector, centroids);
            float[] residual = VectorMath.residual(record.vector, centroids[clusterId]);

            residualIndex.computeIfAbsent(clusterId, k -> new ArrayList<>())
                    .add(new ResidualRecord(i, record.text, residual, clusterId));

            // 更新记录的簇 ID
            records.set(i, new VectorRecord(record.text, record.vector, clusterId));
        }

        pyramidBuilt = true;
        lastBuildRecordCount = records.size();

        System.out.printf("  [VectorNode] %s: Pyramid built: K=%d, records=%d, L0 centroids=%d%n",
                path, numClusters, records.size(), centroids.length);
    }

    /**
     * 异步重建金字塔。
     */
    private void rebuildPyramidAsync() {
        Thread.ofVirtual().name("pyramid-rebuild-" + path).start(() -> {
            try {
                buildPyramid();
            } catch (Exception e) {
                // 静默失败，不影响写入
            }
        });
    }

    // ════════════════════════════════════════════════════════════════
    //  Wave 召回算法 (Wave Retrieval Algorithm)
    // ════════════════════════════════════════════════════════════════

    /**
     * Wave 召回搜索 — 波动阈值的多轮检索。
     * <p>
     * 不再使用僵化的 Top-K 或固定阈值，而是像雷达波一样扩散：
     * <ol>
     *   <li><b>Wave 1</b>：在 L0 层用严格阈值锁定高相关簇（nprobe 个）</li>
     *   <li><b>Wave 2</b>：在 L1 层提取残差进行比对，根据匹配密度梯度
     *       动态放大或缩小相似度阈值</li>
     *   <li><b>Wave 3</b>：收敛阈值，捕获长尾或排除噪音</li>
     * </ol>
     *
     * @param query 查询文本
     * @param topK  返回结果数
     * @return JSON 格式的搜索结果
     */
    public String search(String query, int topK) {
        if (query == null || query.isBlank()) return "[]";

        float[] queryVec = llmProvider.embed(query);

        // 如果金字塔已构建，使用 Wave 召回
        if (pyramidBuilt && centroids != null) {
            return waveSearch(query, queryVec, topK);
        }

        // 降级到暴力搜索
        return bruteForceSearch(query, queryVec, topK);
    }

    /**
     * Wave 召回核心算法。
     */
    private String waveSearch(String query, float[] queryVec, int topK) {
        // ── Phase 1: L0 层 — 锁定候选簇 ──
        int[] candidateClusters = VectorMath.nearestCentroids(queryVec, centroids, NPROBE);

        // ── Phase 2: L1 层 — Wave 波动搜索 ──
        List<WaveSearchResult> allResults = new ArrayList<>();
        float currentThreshold = WAVE_INITIAL_THRESHOLD;

        for (int wave = 1; wave <= WAVE_MAX_ROUNDS; wave++) {
            List<WaveSearchResult> waveResults = new ArrayList<>();

            for (int clusterId : candidateClusters) {
                List<ResidualRecord> clusterRecords = residualIndex.get(clusterId);
                if (clusterRecords == null) continue;

                // 从残差重建原始向量，计算精确相似度
                for (ResidualRecord rr : clusterRecords) {
                    float[] reconstructed = VectorMath.reconstruct(rr.residual, centroids[clusterId]);
                    float similarity = VectorMath.cosineSimilarity(queryVec, reconstructed);

                    if (similarity >= currentThreshold) {
                        waveResults.add(new WaveSearchResult(
                                rr.id, rr.text, similarity, clusterId, wave, currentThreshold
                        ));
                    }
                }
            }

            // ── 密度梯度检测 ──
            if (!waveResults.isEmpty()) {
                float density = (float) waveResults.size() / candidateClusters.length;
                float avgSimilarity = (float) waveResults.stream()
                        .mapToDouble(r -> r.similarity).average().orElse(0);

                // 根据密度梯度调整阈值
                if (density > 2.0 && avgSimilarity > 0.85) {
                    // 密度过高且平均分高 → 收紧阈值（排除噪音）
                    currentThreshold = Math.min(WAVE_MAX_THRESHOLD, currentThreshold + WAVE_STEP);
                } else if (density < 0.5 && wave < WAVE_MAX_ROUNDS) {
                    // 密度过低 → 放松阈值（捕获长尾）
                    currentThreshold = Math.max(WAVE_MIN_THRESHOLD, currentThreshold - WAVE_STEP);
                }
                // 否则保持当前阈值

                allResults.addAll(waveResults);
            } else if (wave < WAVE_MAX_ROUNDS) {
                // 本轮无结果 → 放松阈值
                currentThreshold = Math.max(WAVE_MIN_THRESHOLD, currentThreshold - WAVE_STEP * 2);
            }
        }

        // ── Phase 3: 合并 + Re-rank ──
        // 去重（同一记录可能被多个 Wave 命中）
        Map<Integer, WaveSearchResult> deduped = new LinkedHashMap<>();
        for (WaveSearchResult r : allResults) {
            deduped.merge(r.id, r, (existing, incoming) ->
                    incoming.similarity > existing.similarity ? incoming : existing);
        }

        List<WaveSearchResult> finalResults = new ArrayList<>(deduped.values());
        finalResults.sort(Comparator.comparingDouble(WaveSearchResult::similarity).reversed());

        int count = Math.min(topK, finalResults.size());

        // ── 构造 JSON 输出 ──
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":\"").append(escape(query)).append("\",");
        sb.append("\"algorithm\":\"WAVE\",");
        sb.append("\"pyramid\":{\"K\":").append(numClusters)
          .append(",\"nprobe\":").append(NPROBE).append("},");
        sb.append("\"totalHits\":").append(deduped.size()).append(",");
        sb.append("\"results\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            WaveSearchResult r = finalResults.get(i);
            sb.append("{\"rank\":").append(i + 1).append(",")
              .append("\"id\":").append(r.id).append(",")
              .append("\"similarity\":").append(String.format("%.6f", r.similarity)).append(",")
              .append("\"clusterId\":").append(r.clusterId).append(",")
              .append("\"wave\":").append(r.wave).append(",")
              .append("\"threshold\":").append(String.format("%.2f", r.threshold)).append(",")
              .append("\"text\":\"").append(escape(r.text)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 暴力搜索（降级方案）。
     */
    private String bruteForceSearch(String query, float[] queryVec, int topK) {
        List<WaveSearchResult> results = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            VectorRecord record = records.get(i);
            float similarity = VectorMath.cosineSimilarity(queryVec, record.vector);
            results.add(new WaveSearchResult(i, record.text, similarity, record.clusterId, 0, 0));
        }

        results.sort(Comparator.comparingDouble(WaveSearchResult::similarity).reversed());
        int count = Math.min(topK, results.size());

        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":\"").append(escape(query)).append("\",");
        sb.append("\"algorithm\":\"BRUTE_FORCE\",");
        sb.append("\"results\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            WaveSearchResult r = results.get(i);
            sb.append("{\"rank\":").append(i + 1).append(",")
              .append("\"id\":").append(r.id).append(",")
              .append("\"similarity\":").append(String.format("%.6f", r.similarity)).append(",")
              .append("\"text\":\"").append(escape(r.text)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  公共 API
    // ════════════════════════════════════════════════════════════════

    public int recordCount() { return records.size(); }

    public List<VectorRecord> getRecords() { return List.copyOf(records); }

    public boolean isPyramidBuilt() { return pyramidBuilt; }

    public int numClusters() { return numClusters; }

    /**
     * 获取金字塔统计报告。
     */
    public String getPyramidStats() {
        if (!pyramidBuilt) {
            return "Pyramid: NOT BUILT (records=" + records.size() + ")";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Pyramid: K=").append(numClusters)
          .append(", records=").append(records.size())
          .append(", L0 centroids=").append(centroids != null ? centroids.length : 0)
          .append(", L1 clusters=");
        sb.append("{");
        boolean first = true;
        for (Map.Entry<Integer, List<ResidualRecord>> e : residualIndex.entrySet()) {
            if (!first) sb.append(", ");
            sb.append("C").append(e.getKey()).append(":").append(e.getValue().size());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  Shadow Copy
    // ════════════════════════════════════════════════════════════════

    @Override
    public VfsNode createShadowCopy() {
        List<VectorRecord> frozenRecords = List.copyOf(records);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\":\"").append(path).append("\",");
        sb.append("\"snapshot\":true,");
        sb.append("\"recordCount\":").append(frozenRecords.size()).append(",");
        sb.append("\"pyramidBuilt\":").append(pyramidBuilt).append(",");
        sb.append("\"records\":[");
        for (int i = 0; i < frozenRecords.size(); i++) {
            if (i > 0) sb.append(",");
            VectorRecord r = frozenRecords.get(i);
            sb.append("{\"id\":").append(i).append(",")
              .append("\"text\":\"").append(escape(r.text)).append("\",")
              .append("\"clusterId\":").append(r.clusterId).append(",")
              .append("\"dimensions\":").append(r.vector.length).append("}");
        }
        sb.append("]}");

        return new ShadowCopyNode(path + " [SHADOW]", VfsNodeType.VECTOR, sb.toString(), ownerUid);
    }

    // ── 内部辅助 ──

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    // ════════════════════════════════════════════════════════════════
    //  数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 向量记录 — 升级版，支持金字塔层级。
     */
    public record VectorRecord(
            /** 文本内容 */
            String text,
            /** 原始向量 */
            float[] vector,
            /** 所属簇 ID（-1 表示未分配） */
            int clusterId
    ) {
        /** 兼容旧构造 */
        public VectorRecord(String text, float[] vector) {
            this(text, vector, -1);
        }
    }

    /**
     * 残差记录 — L1 层的存储单元。
     */
    public record ResidualRecord(
            /** 原始记录 ID */
            int id,
            /** 文本内容 */
            String text,
            /** 残差向量 R = V - C */
            float[] residual,
            /** 所属簇 ID */
            int clusterId
    ) {}

    /**
     * Wave 搜索结果 — 记录波峰/波谷评分。
     */
    public record WaveSearchResult(
            /** 记录 ID */
            int id,
            /** 文本内容 */
            String text,
            /** 相似度分数 */
            float similarity,
            /** 所属簇 ID */
            int clusterId,
            /** 命中的 Wave 轮次 */
            int wave,
            /** 命中时的阈值 */
            float threshold
    ) {}
}
