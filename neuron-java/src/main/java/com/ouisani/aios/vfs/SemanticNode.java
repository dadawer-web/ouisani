package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.cache.SemanticCacheManager;
import com.ouisani.aios.core.llm.ComputeCore;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.VectorMath;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.core.tool.DataTypes;
import com.ouisani.aios.core.tool.Port;
import com.ouisani.aios.core.vfs.VfsJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 混合存储卷管理器 — AIOS 的 TagMemo 双重索引节点。
 * <p>
 * 借鉴 VCPToolBox 的 TagMemo 双重索引理念和文件系统 RAID 的思想，
 * SemanticNode 被重构为一个透明的"混合存储卷管理器"，底层同时
 * 挂载 {@link VectorNode}（向量索引）和 {@link GraphNode}（图索引）。
 *
 * <h3>双写机制 (Dual-Write / RAID-1-like)</h3>
 * 当 Agent 将一段重要记忆写入 SemanticNode 时：
 * <ol>
 *   <li>文本 → Embedding → 存入 VectorNode（模糊语义召回）</li>
 *   <li>文本 → E_CORE LLM → 实体关系三元组 → 存入 GraphNode（精确逻辑链路）</li>
 * </ol>
 *
 * <h3>相控阵读取 (Phased-Array Read)</h3>
 * 当 Agent 发起检索时，不再仅进行 Vector KNN 搜索：
 * <ol>
 *   <li>Phase 1: GraphNode BFS — 找到关联实体子图（精确逻辑链路）</li>
 *   <li>Phase 2: VectorNode KNN — 语义相似度召回</li>
 *   <li>Phase 3: Re-ranking — 利用图谱实体作为 Tag 权重重排序</li>
 * </ol>
 *
 * <h3>冷热数据分层</h3>
 * SemanticCacheManager 驱逐的热数据优雅地流入此双重索引节点，
 * 实现从 L1 缓存到 L2 持久存储的降级。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>存储系统</th><th>AIOS SemanticNode</th><th>说明</th></tr>
 *   <tr><td>RAID-1 Mirror</td><td>Dual-Write</td><td>数据双写到两个存储卷</td></tr>
 *   <tr><td>L1 Cache → L2 SSD</td><td>CacheManager → SemanticNode</td><td>冷热分层</td></tr>
 *   <tr><td>B-Tree + Bloom Filter</td><td>VectorNode + GraphNode</td><td>双重索引</td></tr>
 *   <tr><td>相控阵雷达</td><td>Phased-Array Read</td><td>多波束联合定位</td></tr>
 * </table>
 *
 * @see VectorNode
 * @see GraphNode
 * @see SemanticCacheManager
 */
public non-sealed class SemanticNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(SemanticNode.class);

    // ── 实体关系抽取 Prompt ──

    private static final String EXTRACTION_SYSTEM_PROMPT =
            "你是一个知识图谱构建器。从给定文本中提取核心实体和关系三元组。"
            + "严格按格式输出，每行一个：[实体A|关系|实体B]。"
            + "只输出三元组，不要任何解释。";

    // ── 节点基础属性 ──

    private final String path;
    private final LlmProvider llmProvider;
    private int ownerUid;
    private int permissions;

    // ── 双重索引卷 ──

    /** 向量索引卷 — 用于模糊语义召回 (KNN) */
    private final VectorNode vectorVolume;

    /** 图索引卷 — 用于精确逻辑链路 (BFS) */
    private final GraphNode graphVolume;

    // ── 双写记录索引 ──

    /**
     * 双写记录 — 记录每条写入数据的向量索引和图索引的关联。
     * key: 记录 ID（自增），value: 该记录关联的图谱实体列表。
     * 用于 Re-ranking 时将图谱实体与向量记录对应。
     */
    private final ConcurrentHashMap<Long, DualWriteRecord> dualWriteIndex = new ConcurrentHashMap<>();

    /** 记录 ID 自增序列 */
    private final AtomicLong recordIdSeq = new AtomicLong(0);

    // ── 统计 ──

    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong totalDualWrites = new AtomicLong(0);
    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong totalPhasedArrayReads = new AtomicLong(0);
    private final AtomicLong totalInfluxFromCache = new AtomicLong(0);

    // ════════════════════════════════════════════════════════════════
    //  构造
    // ════════════════════════════════════════════════════════════════

    public SemanticNode(String path, LlmProvider llmProvider) {
        this(path, llmProvider, 0, 0644);
    }

    public SemanticNode(String path, LlmProvider llmProvider, int ownerUid, int permissions) {
        this.path = path;
        this.llmProvider = llmProvider;
        this.ownerUid = ownerUid;
        this.permissions = permissions;

        // 创建底层双卷 — 共享同一个 LlmProvider
        this.vectorVolume = new VectorNode(path + ":vec", llmProvider);
        this.graphVolume = new GraphNode(path + ":graph", llmProvider);

        log.info("[SemanticNode] Hybrid Volume Manager created: path={}, vecVolume={}, graphVolume={}",
                path, vectorVolume.path(), graphVolume.path());
    }

    // ════════════════════════════════════════════════════════════════
    //  VfsNode 接口实现
    // ════════════════════════════════════════════════════════════════

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.SEMANTIC;
    }

    // ── 强类型 I/O 契约 ──
    @Override
    public List<Port> inputPorts() {
        return List.of(new Port("memory", DataTypes.PLAIN_TEXT,
                "自然语言记忆片段（write 入口，双写到向量卷+图谱卷）", true));
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(new Port("stats", DataTypes.JSON_DATA,
                "混合存储统计摘要 JSON（read 出口）", true));
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    // ════════════════════════════════════════════════════════════════
    //  sys_write — 双写机制 (RAID-1-like Dual-Write)
    // ════════════════════════════════════════════════════════════════

    /**
     * 双写 — 将数据同时写入 VectorNode 和 GraphNode。
     * <p>
     * 类比 RAID-1 的镜像写入：数据被同时写入两个独立的存储卷，
     * 任何一个卷的故障不影响另一个卷的数据完整性。
     * <p>
     * 写入流程：
     * <ol>
     *   <li><b>WAL 日志</b>：先写日志，保证崩溃恢复</li>
     *   <li><b>向量卷写入</b>：文本 → Embedding → VectorNode（模糊语义召回）</li>
     *   <li><b>图卷写入</b>：文本 → E_CORE LLM 抽取三元组 → GraphNode（精确逻辑链路）</li>
     *   <li><b>双写索引</b>：记录向量记录与图谱实体的关联</li>
     * </ol>
     */
    @Override
    public boolean write(String data) {
        if (data == null || data.isBlank()) return false;

        long recordId = recordIdSeq.incrementAndGet();
        totalWrites.incrementAndGet();

        // ── WAL: 先写日志 ──
        VfsJournal.getInstance().appendLog(path, "DUAL_WRITE", data);

        // ── Phase 1: 向量卷写入 — 文本 → Embedding → VectorNode ──
        boolean vecSuccess = writeToVectorVolume(recordId, data);

        // ── Phase 2: 图卷写入 — 文本 → E_CORE 抽取三元组 → GraphNode ──
        List<String> extractedEntities = writeToGraphVolume(recordId, data);

        // ── Phase 3: 更新双写索引 ──
        DualWriteRecord record = new DualWriteRecord(
                recordId, data,
                vecSuccess,
                extractedEntities,
                System.currentTimeMillis()
        );
        dualWriteIndex.put(recordId, record);

        if (vecSuccess && !extractedEntities.isEmpty()) {
            totalDualWrites.incrementAndGet();
        }

        log.info("[SemanticNode] Dual-write: recordId={}, vecOk={}, entities={}, textLen={}",
                recordId, vecSuccess, extractedEntities.size(), data.length());

        SemanticEtw.getInstance().logEvent("VFS", "SEMANTIC_DUAL_WRITE",
                "path=" + path + " recordId=" + recordId
                + " vecOk=" + vecSuccess + " entities=" + extractedEntities.size());

        return vecSuccess;
    }

    /**
     * 向量卷写入 — 文本 → Embedding → VectorNode。
     */
    private boolean writeToVectorVolume(long recordId, String data) {
        try {
            boolean ok = vectorVolume.write(data);
            if (ok) {
                log.debug("[SemanticNode] Vector volume write OK: recordId={}", recordId);
            } else {
                log.warn("[SemanticNode] Vector volume write FAILED: recordId={}", recordId);
            }
            return ok;
        } catch (Exception e) {
            log.error("[SemanticNode] Vector volume write error: recordId={}, error={}", recordId, e.getMessage());
            return false;
        }
    }

    /**
     * 图卷写入 — 文本 → E_CORE LLM 抽取三元组 → GraphNode。
     * <p>
     * 使用 E_CORE（能效核）进行实体关系抽取，因为这是相对简单的
     * 结构化提取任务，不需要旗舰模型的推理能力。
     *
     * @return 抽取出的实体列表
     */
    private List<String> writeToGraphVolume(long recordId, String data) {
        List<String> entities = new ArrayList<>();

        try {
            // 使用 E_CORE 进行三元组抽取（如果 LlmRouter 可用则自动路由）
            String extraction = llmProvider.think(data, EXTRACTION_SYSTEM_PROMPT);

            // 解析三元组并写入 GraphNode
            int tripletCount = graphVolume.parseAndStoreTriplets(extraction);

            // 收集本次抽取的实体
            if (tripletCount > 0) {
                // 从抽取结果中提取实体名称
                entities = extractEntitiesFromTriplets(extraction);
            }

            log.debug("[SemanticNode] Graph volume write OK: recordId={}, triplets={}, entities={}",
                    recordId, tripletCount, entities.size());

        } catch (Exception e) {
            log.warn("[SemanticNode] Graph volume write error (non-fatal): recordId={}, error={}",
                    recordId, e.getMessage());
            // 图卷写入失败不影响整体写入 — 降级为纯向量模式
        }

        return entities;
    }

    /**
     * 从三元组文本中提取实体名称。
     */
    private List<String> extractEntitiesFromTriplets(String tripletText) {
        List<String> entities = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("\\[([^|\\]]+)\\s*\\|\\s*([^|\\]]+)\\s*\\|\\s*([^\\]]+)]")
                .matcher(tripletText);
        while (matcher.find()) {
            String entityA = matcher.group(1).strip();
            String entityB = matcher.group(3).strip();
            if (!entityA.isEmpty()) entities.add(entityA);
            if (!entityB.isEmpty()) entities.add(entityB);
        }
        return entities.stream().distinct().toList();
    }

    // ════════════════════════════════════════════════════════════════
    //  sys_read — 相控阵读取 (Phased-Array Read)
    // ════════════════════════════════════════════════════════════════

    /**
     * 读取 — 返回混合存储的统计摘要。
     * <p>
     * 对于语义检索，请使用 {@link #search(String, int)} 方法。
     */
    @Override
    public String read() {
        totalReads.incrementAndGet();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\":\"").append(path).append("\",");
        sb.append("\"type\":\"HybridVolumeManager\",");
        sb.append("\"vectorVolume\":{");
        sb.append("\"path\":\"").append(vectorVolume.path()).append("\",");
        sb.append("\"records\":").append(vectorVolume.recordCount()).append("},");
        sb.append("\"graphVolume\":{");
        sb.append("\"path\":\"").append(graphVolume.path()).append("\",");
        sb.append("\"entities\":").append(graphVolume.entityCount()).append(",");
        sb.append("\"edges\":").append(graphVolume.edgeCount()).append("},");
        sb.append("\"dualWriteIndex\":{");
        sb.append("\"totalRecords\":").append(dualWriteIndex.size()).append(",");
        sb.append("\"dualWriteComplete\":").append(totalDualWrites.get()).append("},");
        sb.append("\"stats\":{");
        sb.append("\"totalWrites\":").append(totalWrites.get()).append(",");
        sb.append("\"totalReads\":").append(totalReads.get()).append(",");
        sb.append("\"phasedArrayReads\":").append(totalPhasedArrayReads.get()).append(",");
        sb.append("\"influxFromCache\":").append(totalInfluxFromCache.get()).append("}");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 相控阵读取 — 多波束联合检索。
     * <p>
     * 类比相控阵雷达的多波束联合定位：单一波束（向量搜索）
     * 只能提供模糊的方位信息，但多个波束（向量 + 图谱）的
     * 交叉定位可以极大地提高精度。
     * <p>
     * 检索流程：
     * <ol>
     *   <li><b>Phase 1: Graph BFS</b> — 在 GraphNode 中 BFS 查找关联实体子图</li>
     *   <li><b>Phase 2: Vector KNN</b> — 在 VectorNode 中进行语义相似度召回</li>
     *   <li><b>Phase 3: Re-ranking</b> — 利用图谱实体作为 Tag 权重重排序</li>
     * </ol>
     *
     * @param query 查询文本
     * @param topK  返回的最大结果数
     * @return JSON 格式的检索结果（带图谱增强的排序）
     */
    public String search(String query, int topK) {
        totalPhasedArrayReads.incrementAndGet();

        log.info("[SemanticNode] Phased-Array Read: query='{}', topK={}",
                query.substring(0, Math.min(60, query.length())), topK);

        // ── Phase 1: Graph BFS — 查找关联实体子图 ──
        Set<String> graphEntities = findRelatedEntities(query);
        Map<String, Double> entityWeights = computeEntityWeights(graphEntities, query);

        // ── Phase 2: Vector KNN — 语义相似度召回 ──
        List<RankedResult> vectorResults = vectorKnnSearch(query, topK * 3); // 召回 3x，用于重排序

        // ── Phase 3: Re-ranking — 图谱实体权重重排序 ──
        List<RankedResult> reranked = rerankWithGraphTags(vectorResults, entityWeights);

        // 截取 topK
        List<RankedResult> finalResults = reranked.subList(0, Math.min(topK, reranked.size()));

        // 格式化输出
        return formatSearchResults(query, finalResults, graphEntities, entityWeights);
    }

    /**
     * Phase 1: 在 GraphNode 中查找与查询相关的实体。
     * <p>
     * 策略：从查询文本中提取关键词，在图中查找匹配的实体，
     * 然后进行 BFS 扩展找到关联子图。
     */
    private Set<String> findRelatedEntities(String query) {
        Set<String> relatedEntities = new LinkedHashSet<>();

        // 尝试在图中查找查询中提到的实体
        // 简单策略：按常见分隔符拆分查询，在图中匹配
        String[] tokens = query.split("[，,。.！!？?\\s：:；;、]+");
        for (String token : tokens) {
            token = token.strip();
            if (token.isEmpty() || token.length() < 2) continue;

            // 在图的所有实体中查找包含此 token 的实体
            for (String entity : graphVolume.allEntities) {
                if (entity.contains(token) || token.contains(entity)) {
                    relatedEntities.add(entity);
                }
            }
        }

        // BFS 扩展 — 从匹配的实体出发，扩展 1 层关联
        Set<String> expandedEntities = new LinkedHashSet<>(relatedEntities);
        for (String entity : relatedEntities) {
            String subgraph = graphVolume.querySubgraph(entity, 1);
            // 从子图 JSON 中提取实体名称（简单解析）
            extractEntitiesFromJson(subgraph, expandedEntities);
        }

        log.debug("[SemanticNode] Graph BFS: query tokens={}, matched={}, expanded={}",
                tokens.length, relatedEntities.size(), expandedEntities.size());

        return expandedEntities;
    }

    /**
     * 从子图 JSON 中提取实体名称。
     */
    private void extractEntitiesFromJson(String json, Set<String> target) {
        // 简单解析：查找 "traversal" 字段中的实体
        var matcher = java.util.regex.Pattern.compile("\"traversal\":\"([^\"]+)\"")
                .matcher(json);
        while (matcher.find()) {
            String traversal = matcher.group(1);
            // 格式: "EntityA → Relation → EntityB"
            String[] parts = traversal.split("→");
            for (String part : parts) {
                String cleaned = part.strip();
                // 跳过关系词（通常较短且不含中文/大写字母）
                if (cleaned.length() >= 2) {
                    target.add(cleaned);
                }
            }
        }
    }

    /**
     * 计算图谱实体的权重 — 用于 Re-ranking。
     * <p>
     * 权重策略：
     * - 直接匹配查询的实体：权重 1.0
     * - BFS 扩展 1 层的实体：权重 0.5
     * - 图中度数（连接数）越高的实体权重越高
     */
    private Map<String, Double> computeEntityWeights(Set<String> entities, String query) {
        Map<String, Double> weights = new HashMap<>();

        for (String entity : entities) {
            double weight = 0.5; // 基础权重

            // 直接匹配查询的实体权重更高
            if (query.contains(entity)) {
                weight = 1.0;
            }

            // 图中度数加成 — 连接数越多，说明越核心
            Set<GraphNode.Edge> edges = graphVolume.adjacencyList.getOrDefault(entity, Set.of());
            weight += Math.min(0.3, edges.size() * 0.1); // 每条边 +0.1，上限 +0.3

            weights.put(entity, weight);
        }

        return weights;
    }

    /**
     * Phase 2: 在 VectorNode 中进行 KNN 语义搜索。
     */
    private List<RankedResult> vectorKnnSearch(String query, int topK) {
        List<RankedResult> results = new ArrayList<>();

        try {
            float[] queryVec = llmProvider.embed(query);
            List<VectorNode.VectorRecord> records = vectorVolume.getRecords();

            List<float[]> vectors = new ArrayList<>(records.size());
            for (VectorNode.VectorRecord r : records) vectors.add(r.vector());
            // O(N log K) 有界堆 top-k — 镜像 jcode find_similar；维度不匹配的候选被跳过
            List<VectorMath.ScoredIndex> top = VectorMath.findSimilar(queryVec, vectors, -1.0f, topK);
            for (VectorMath.ScoredIndex si : top) {
                VectorNode.VectorRecord record = records.get(si.index());
                results.add(new RankedResult(si.index(), record.text(), si.score(), 0.0));
            }
        } catch (Exception e) {
            log.error("[SemanticNode] Vector KNN search error: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Phase 3: 利用图谱实体权重对向量结果进行重排序。
     * <p>
     * Re-ranking 公式：
     * <pre>
     *   finalScore = α * vectorSimilarity + (1-α) * tagBoost
     * </pre>
     * 其中 tagBoost 是向量记录文本中包含的图谱实体权重之和。
     * 包含更多高权重图谱实体的记录会被提升排名。
     */
    private List<RankedResult> rerankWithGraphTags(List<RankedResult> vectorResults,
                                                    Map<String, Double> entityWeights) {
        if (entityWeights.isEmpty()) {
            // 无图谱信息，直接返回向量排序结果
            return vectorResults;
        }

        double alpha = 0.6; // 向量相似度权重
        double beta = 0.4;  // 图谱标签权重

        List<RankedResult> reranked = new ArrayList<>();

        for (RankedResult result : vectorResults) {
            double tagBoost = 0.0;
            String text = result.text().toLowerCase();

            // 计算文本中包含的图谱实体权重之和
            for (Map.Entry<String, Double> entry : entityWeights.entrySet()) {
                if (text.contains(entry.getKey().toLowerCase())) {
                    tagBoost += entry.getValue();
                }
            }

            // 归一化 tagBoost（避免无限增长）
            tagBoost = Math.min(1.0, tagBoost / entityWeights.size());

            // 计算最终分数
            double finalScore = alpha * result.vectorSimilarity() + beta * tagBoost;

            reranked.add(new RankedResult(result.id(), result.text(),
                    result.vectorSimilarity(), finalScore));
        }

        // 按最终分数降序重排序
        reranked.sort(Comparator.comparingDouble(RankedResult::finalScore).reversed());

        return reranked;
    }

    /**
     * 格式化搜索结果为 JSON。
     */
    private String formatSearchResults(String query, List<RankedResult> results,
                                       Set<String> graphEntities,
                                       Map<String, Double> entityWeights) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":\"").append(escape(query)).append("\",");
        sb.append("\"phasedArrayRead\":true,");
        sb.append("\"graphPhase\":{");
        sb.append("\"matchedEntities\":").append(graphEntities.size()).append(",");
        sb.append("\"entities\":[");
        int i = 0;
        for (String entity : graphEntities) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(escape(entity)).append("\",")
              .append("\"weight\":").append(String.format("%.2f", entityWeights.getOrDefault(entity, 0.0))).append("}");
            i++;
        }
        sb.append("]},");
        sb.append("\"results\":[");
        for (int j = 0; j < results.size(); j++) {
            if (j > 0) sb.append(",");
            RankedResult r = results.get(j);
            sb.append("{\"rank\":").append(j + 1).append(",")
              .append("\"id\":").append(r.id()).append(",")
              .append("\"vectorSimilarity\":").append(String.format("%.6f", r.vectorSimilarity())).append(",")
              .append("\"finalScore\":").append(String.format("%.6f", r.finalScore())).append(",")
              .append("\"text\":\"").append(escape(r.text().length() > 300
                    ? r.text().substring(0, 300) + "..." : r.text())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  冷热数据分层 — Cache Eviction → SemanticNode Influx
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 SemanticCacheManager 接收驱逐的热数据。
     * <p>
     * 当 SemanticCacheManager 驱逐一个 CacheEntry 时（无论是容量
     * 驱逐还是 Tick 衰减遗忘），调用此方法将数据流入冷端双重索引。
     * <p>
     * 类比 Linux 的页面回收：当物理内存不足时，kswapd 将活跃页面
     * 移到不活跃 LRU，最终换出到交换分区。这里 SemanticCacheManager
     * 是 L1 缓存，SemanticNode 是 L2 持久存储。
     *
     * @param text      被驱逐的缓存文本
     * @param vector    被驱逐的缓存向量（复用，避免重新 Embedding）
     * @param metadata  被驱逐的缓存元数据
     */
    public void influxFromCache(String text, float[] vector, Map<String, Object> metadata) {
        totalInfluxFromCache.incrementAndGet();

        long recordId = recordIdSeq.incrementAndGet();

        // ── 向量卷：复用已有的 Embedding，无需重新计算 ──
        VfsJournal.getInstance().appendLog(path, "CACHE_INFLUX", text);
        vectorVolume.records.add(new VectorNode.VectorRecord(text, vector));

        // ── 图卷：异步抽取三元组（使用 E_CORE） ──
        List<String> entities = writeToGraphVolume(recordId, text);

        // ── 更新双写索引 ──
        DualWriteRecord record = new DualWriteRecord(
                recordId, text, true, entities, System.currentTimeMillis()
        );
        record.meta("source", "cache_eviction");
        if (metadata != null) {
            if (metadata.containsKey("emotion")) {
                record.meta("emotion", metadata.get("emotion"));
            }
            if (metadata.containsKey("synapticWeight")) {
                record.meta("originalSynapticWeight", metadata.get("synapticWeight"));
            }
        }
        dualWriteIndex.put(recordId, record);

        log.info("[SemanticNode] Cache influx: recordId={}, textLen={}, entities={}, totalInflux={}",
                recordId, text.length(), entities.size(), totalInfluxFromCache.get());

        SemanticEtw.getInstance().logEvent("VFS", "CACHE_INFLUX",
                "path=" + path + " recordId=" + recordId + " entities=" + entities.size());
    }

    /**
     * 批量接收 SemanticCacheManager 的驱逐数据。
     */
    public void influxBatchFromCache(List<SemanticCacheManager.CacheEntry> evicted) {
        for (SemanticCacheManager.CacheEntry entry : evicted) {
            influxFromCache(entry.responseText(), entry.queryVector(), entry.metadata());
        }
        log.info("[SemanticNode] Batch cache influx: {} entries received", evicted.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  公共 API
    // ════════════════════════════════════════════════════════════════

    /** 获取底层向量卷引用 */
    public VectorNode vectorVolume() {
        return vectorVolume;
    }

    /** 获取底层图卷引用 */
    public GraphNode graphVolume() {
        return graphVolume;
    }

    /** 双写记录总数 */
    public int dualWriteCount() {
        return dualWriteIndex.size();
    }

    /** 总写入次数 */
    public long totalWrites() {
        return totalWrites.get();
    }

    /** 总相控阵读取次数 */
    public long totalPhasedArrayReads() {
        return totalPhasedArrayReads.get();
    }

    /** 从缓存流入的总条目数 */
    public long totalInfluxFromCache() {
        return totalInfluxFromCache.get();
    }

    /**
     * 打印混合卷统计报告。
     */
    public String getStatsReport() {
        return """
                ┌─ SemanticNode Hybrid Volume Stats ──────────────────
                │  Path               : %s
                │  Vector Records     : %d
                │  Graph Entities     : %d
                │  Graph Edges        : %d
                │  Dual Write Records : %d
                │  Total Writes       : %d
                │  Phased-Array Reads : %d
                │  Cache Influx       : %d
                └─────────────────────────────────────────────────"""
                .formatted(
                        path,
                        vectorVolume.recordCount(),
                        graphVolume.entityCount(),
                        graphVolume.edgeCount(),
                        dualWriteIndex.size(),
                        totalWrites.get(),
                        totalPhasedArrayReads.get(),
                        totalInfluxFromCache.get());
    }

    // ── 内部辅助 ──

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 双写记录 — 记录一次写入操作的向量卷和图卷状态。
     */
    private static final class DualWriteRecord {
        final long id;
        final String text;
        final boolean vectorWritten;
        final List<String> graphEntities;
        final long timestamp;
        final Map<String, Object> metadata;

        DualWriteRecord(long id, String text, boolean vectorWritten,
                        List<String> graphEntities, long timestamp) {
            this.id = id;
            this.text = text;
            this.vectorWritten = vectorWritten;
            this.graphEntities = graphEntities;
            this.timestamp = timestamp;
            this.metadata = new HashMap<>();
        }

        void meta(String key, Object value) {
            metadata.put(key, value);
        }
    }

    /**
     * 排序结果 — 相控阵读取的输出单元。
     *
     * @param id                向量记录 ID
     * @param text              记录文本
     * @param vectorSimilarity  原始向量相似度
     * @param finalScore        图谱增强后的最终分数
     */
    record RankedResult(int id, String text, double vectorSimilarity, double finalScore) {}
}
