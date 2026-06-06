package com.ouisani.aios.core.llm;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.cache.SemanticCacheManager;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义分支预测器 — AIOS 的推测执行引擎。
 * <p>
 * 类比现代 CPU 的分支预测器（Branch Predictor）和推测执行（Speculative Execution）：
 * <ul>
 *   <li>当系统处于等待状态时（如等待用户输入、等待 LLM 响应），
 *       SpeculativePredictor 利用 E_CORE 极速模型预测 3 个最可能的
 *       "下一步意图"（Branch Targets）</li>
 *   <li>将预测的意图封装为 {@link AgentTask}（标记为 SPECULATIVE），
 *       在后台静默执行，结果暂存到预测缓冲区</li>
 *   <li>当真实意图到达时，计算向量相似度：
 *       命中（>95%）→ 瞬间返回缓存结果（0 延迟）；
 *       未命中 → Pipeline Flush，清空预测缓冲区</li>
 * </ul>
 *
 * <h3>预测管线</h3>
 * <pre>
 *   用户输入 ──→ [等待中] ──→ SpeculativePredictor
 *                                │
 *                    ┌───────────┼───────────┐
 *                    ▼           ▼           ▼
 *              Branch #1   Branch #2   Branch #3
 *              (E_CORE)    (E_CORE)    (E_CORE)
 *                    │           │           │
 *                    ▼           ▼           ▼
 *              PredictionBuffer (SemanticCacheManager)
 *                                │
 *   真实输入到达 ──→ Cosine Similarity Match
 *                    │
 *           ┌──── Hit ────┐  ┌── Miss ──┐
 *           ▼              ▼            ▼
 *     瞬间返回结果    Pipeline Flush   常规计算
 * </pre>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>CPU 微架构</th><th>AIOS SpeculativePredictor</th><th>说明</th></tr>
 *   <tr><td>Branch Predictor</td><td>predictBranches()</td><td>预测分支方向</td></tr>
 *   <tr><td>Branch Target Buffer</td><td>predictionBuffer</td><td>预测目标缓存</td></tr>
 *   <tr><td>Speculative Execution</td><td>executeSpeculative()</td><td>推测执行</td></tr>
 *   <tr><td>Branch Hit</td><td>checkPredictionHit()</td><td>分支命中</td></tr>
 *   <tr><td>Pipeline Flush</td><td>pipelineFlush()</td><td>流水线冲刷</td></tr>
 *   <tr><td>Reorder Buffer</td><td>PredictionRecord</td><td>重排序缓冲</td></tr>
 * </table>
 *
 * @see LlmRouter
 * @see SemanticCacheManager
 */
public final class SpeculativePredictor {

    private static final Logger log = LoggerFactory.getLogger(SpeculativePredictor.class);

    // ── 配置 ──

    /** 预测分支数量（类比 CPU 的 3-way 分支预测） */
    private static final int BRANCH_COUNT = 3;

    /** 命中阈值 — 余弦相似度 > 0.95 视为命中 */
    private static final float HIT_THRESHOLD = 0.95f;

    /** 预测超时（毫秒） — 预测任务的最大执行时间 */
    private static final long PREDICTION_TIMEOUT_MS = 30_000L;

    // ── Singleton ──

    private static final class Holder {
        static final SpeculativePredictor INSTANCE = new SpeculativePredictor();
    }

    public static SpeculativePredictor instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    /** LLM Router — 用于 E_CORE 预测和 Embedding */
    private LlmRouter llmRouter;

    /** 预测缓冲区 — 存储推测执行的结果 */
    private final ConcurrentHashMap<String, PredictionRecord> predictionBuffer = new ConcurrentHashMap<>();

    /** 活跃的推测任务 — predictionId → Future */
    private final ConcurrentHashMap<String, Future<?>> activeSpeculations = new ConcurrentHashMap<>();

    /** 推测执行线程池 */
    private ExecutorService speculationExecutor;

    /** 是否启用推测执行 */
    private volatile boolean enabled = false;

    // ── 统计 ──

    private final AtomicLong totalPredictions = new AtomicLong(0);
    private final AtomicLong totalHits = new AtomicLong(0);
    private final AtomicLong totalMisses = new AtomicLong(0);
    private final AtomicLong totalFlushes = new AtomicLong(0);

    private SpeculativePredictor() {
    }

    /**
     * 配置 LLM Router。
     */
    public void configure(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
        this.speculationExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.enabled = true;

        log.info("[SpeculativePredictor] Configured with LlmRouter, enabled=true");
        System.out.println("  ✓ [SpecExec] Semantic Branch Predictor active (branches="
                + BRANCH_COUNT + ", hitThreshold=" + HIT_THRESHOLD + ")");
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 1: 预测管线 (Prediction Pipeline)
    // ════════════════════════════════════════════════════════════════

    /**
     * 预测分支 — 从当前上下文预测最可能的下一步意图。
     * <p>
     * 类比 CPU 的 Branch Target Buffer (BTB)：根据历史模式
     * 预测分支方向和目标地址。
     * <p>
     * 使用 E_CORE 极速模型生成 {@code BRANCH_COUNT} 个预测意图，
     * 每个预测包含意图描述和置信度。
     *
     * @param context 当前上下文（对话历史、任务状态等）
     * @return 预测分支列表，按置信度降序排列
     */
    public List<PredictedBranch> predictBranches(String context) {
        if (!enabled || llmRouter == null || context == null || context.isBlank()) {
            return List.of();
        }

        totalPredictions.incrementAndGet();

        log.debug("[SpeculativePredictor] Predicting branches for context (len={})", context.length());

        try {
            // 构造预测提示词
            String predictionPrompt = buildPredictionPrompt(context);

            // 使用 E_CORE 生成预测
            String predictionText;
            try {
                predictionText = llmRouter.think(predictionPrompt,
                        "你是 AIOS 的语义分支预测器。根据上下文预测用户最可能的下一步意图。"
                        + "严格按格式输出，每行一个意图，格式：意图描述|置信度(0-1)");
            } catch (Exception e) {
                log.warn("[SpeculativePredictor] E_CORE prediction failed: {}", e.getMessage());
                return generateFallbackBranches(context);
            }

            // 解析预测结果
            List<PredictedBranch> branches = parsePredictions(predictionText);

            // 补充到 BRANCH_COUNT 个
            while (branches.size() < BRANCH_COUNT) {
                branches.add(new PredictedBranch(
                        "fallback_branch_" + branches.size(),
                        0.3f - branches.size() * 0.1f,
                        null
                ));
            }

            // 截断到 BRANCH_COUNT
            if (branches.size() > BRANCH_COUNT) {
                branches = branches.subList(0, BRANCH_COUNT);
            }

            log.info("[SpeculativePredictor] Predicted {} branches: {}", branches.size(),
                    branches.stream().map(b -> b.intent + "(" + String.format("%.2f", b.confidence) + ")")
                            .toList());

            SemanticEtw.getInstance().logEvent("SPEC_EXEC", "PREDICT",
                    "branches=" + branches.size() + " topConfidence="
                            + String.format("%.2f", branches.get(0).confidence));

            return branches;

        } catch (Exception e) {
            log.error("[SpeculativePredictor] Prediction error: {}", e.getMessage());
            return List.of();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 2: 推测执行引擎 (Speculative Execution Engine)
    // ════════════════════════════════════════════════════════════════

    /**
     * 推测执行 — 在后台静默执行预测的分支。
     * <p>
     * 类比 CPU 的推测执行：在分支结果确定之前，提前执行预测路径
     * 上的指令。如果预测正确，结果直接提交（0 延迟）；如果预测
     * 错误，结果被丢弃（Pipeline Flush）。
     *
     * @param context 当前上下文
     * @param branches 预测的分支列表
     * @return 预测 ID，用于后续命中检查
     */
    public String executeSpeculative(String context, List<PredictedBranch> branches) {
        if (!enabled || branches.isEmpty()) return null;

        String predictionId = "pred-" + System.nanoTime();

        for (int i = 0; i < branches.size(); i++) {
            PredictedBranch branch = branches.get(i);
            String branchId = predictionId + "-b" + i;

            // 在虚拟线程中异步执行推测任务
            Future<?> future = speculationExecutor.submit(() -> {
                try {
                    long startTime = System.currentTimeMillis();

                    // 使用 E_CORE 执行推测性 LLM 调用
                    String speculativeResult;
                    try {
                        speculativeResult = llmRouter.think(
                                branch.intent,
                                "你是一个 AIOS 推测执行引擎。基于预测的意图生成可能的回复。");
                    } catch (Exception e) {
                        log.debug("[SpeculativePredictor] Branch {} execution failed: {}", branchId, e.getMessage());
                        return;
                    }

                    long elapsed = System.currentTimeMillis() - startTime;

                    // 计算意图的 Embedding
                    float[] intentVector = null;
                    try {
                        intentVector = llmRouter.embed(branch.intent);
                    } catch (Exception e) {
                        log.debug("[SpeculativePredictor] Embedding failed for branch {}", branchId);
                    }

                    // 存入预测缓冲区
                    PredictionRecord record = new PredictionRecord(
                            predictionId, branchId, branch.intent,
                            branch.confidence, speculativeResult,
                            intentVector, System.currentTimeMillis(), elapsed
                    );

                    predictionBuffer.put(branchId, record);

                    log.debug("[SpeculativePredictor] Branch {} completed: intent={}, elapsed={}ms",
                            branchId, branch.intent, elapsed);

                } catch (Exception e) {
                    log.warn("[SpeculativePredictor] Speculative execution error: {}", e.getMessage());
                }
            });

            activeSpeculations.put(branchId, future);
        }

        log.info("[SpeculativePredictor] Launched {} speculative branches: predictionId={}",
                branches.size(), predictionId);

        return predictionId;
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 3: 命中与回退 (Cache Hit & Pipeline Flush)
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查预测命中 — 当真实意图到达时，检查是否命中预测缓冲区。
     * <p>
     * 类比 CPU 的分支预测命中检查：
     * <ul>
     *   <li>命中（相似度 > 95%）→ 瞬间返回缓存结果（0 延迟大模型交互）</li>
     *   <li>未命中 → Pipeline Flush，清空预测缓冲区</li>
     * </ul>
     *
     * @param realIntent 真实的用户意图
     * @param predictionId 之前的预测 ID
     * @return 命中结果，如果未命中返回 null
     */
    public PredictionHitResult checkPredictionHit(String realIntent, String predictionId) {
        if (!enabled || realIntent == null || predictionId == null) {
            return null;
        }

        // 计算真实意图的 Embedding
        float[] realVector;
        try {
            realVector = llmRouter.embed(realIntent);
        } catch (Exception e) {
            log.warn("[SpeculativePredictor] Embedding failed for real intent, flushing");
            pipelineFlush(predictionId);
            return null;
        }

        // 在预测缓冲区中查找最佳匹配
        float bestSimilarity = -1.0f;
        PredictionRecord bestRecord = null;

        for (Map.Entry<String, PredictionRecord> entry : predictionBuffer.entrySet()) {
            PredictionRecord record = entry.getValue();

            // 只检查属于当前预测 ID 的记录
            if (!record.predictionId.equals(predictionId)) continue;

            if (record.intentVector == null) continue;

            float similarity = cosineSimilarity(realVector, record.intentVector);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestRecord = record;
            }
        }

        if (bestSimilarity >= HIT_THRESHOLD && bestRecord != null) {
            // ── 分支命中！──
            totalHits.incrementAndGet();

            log.info("[SpeculativePredictor] ╔══════════════════════════════════════════════════╗");
            log.info("[SpeculativePredictor] ║  BRANCH PREDICTION HIT! similarity={}",
                    String.format("%.4f", bestSimilarity));
            log.info("[SpeculativePredictor] ║  Predicted: {}", bestRecord.intent);
            log.info("[SpeculativePredictor] ║  Result available instantly (0 latency)");
            log.info("[SpeculativePredictor] ╚══════════════════════════════════════════════════╝");

            SemanticEtw.getInstance().logEvent("SPEC_EXEC", "HIT",
                    "similarity=" + String.format("%.4f", bestSimilarity)
                    + " intent=" + bestRecord.intent);

            // 清空其他未命中的预测
            pipelineFlush(predictionId);

            return new PredictionHitResult(
                    true, bestRecord.intent, bestRecord.result,
                    bestSimilarity, bestRecord.elapsedMs
            );
        }

        // ── 分支未命中 — Pipeline Flush ──
        totalMisses.incrementAndGet();

        log.debug("[SpeculativePredictor] Branch MISS: bestSimilarity={}, threshold={}",
                String.format("%.4f", bestSimilarity), HIT_THRESHOLD);

        pipelineFlush(predictionId);

        return new PredictionHitResult(
                false, null, null, bestSimilarity, 0
        );
    }

    /**
     * Pipeline Flush — 清空预测缓冲区中指定预测的所有记录。
     * <p>
     * 类比 CPU 的流水线冲刷：当分支预测失败时，必须丢弃所有
     * 推测执行的结果，恢复到分支点的状态。
     */
    public void pipelineFlush(String predictionId) {
        if (predictionId == null) return;

        int flushed = 0;
        var iterator = predictionBuffer.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().predictionId.equals(predictionId)) {
                iterator.remove();
                flushed++;
            }
        }

        // 取消仍在执行的推测任务
        var specIterator = activeSpeculations.entrySet().iterator();
        while (specIterator.hasNext()) {
            var entry = specIterator.next();
            if (entry.getKey().startsWith(predictionId)) {
                entry.getValue().cancel(true);
                specIterator.remove();
            }
        }

        totalFlushes.incrementAndGet();

        log.debug("[SpeculativePredictor] Pipeline flush: predictionId={}, flushedRecords={}",
                predictionId, flushed);
    }

    /**
     * 全量 Pipeline Flush — 清空所有预测缓冲区。
     */
    public void pipelineFlushAll() {
        predictionBuffer.clear();
        for (Future<?> future : activeSpeculations.values()) {
            future.cancel(true);
        }
        activeSpeculations.clear();
        log.info("[SpeculativePredictor] Full pipeline flush");
    }

    // ════════════════════════════════════════════════════════════════
    //  便捷 API — 一站式预测+执行+命中检查
    // ════════════════════════════════════════════════════════════════

    /**
     * 触发推测执行 — 从上下文预测分支并后台执行。
     * <p>
     * 在系统等待时调用（如等待用户输入、等待 LLM 响应）。
     *
     * @param context 当前上下文
     * @return 预测 ID，用于后续 {@link #checkPredictionHit} 检查
     */
    public String speculate(String context) {
        List<PredictedBranch> branches = predictBranches(context);
        if (branches.isEmpty()) return null;
        return executeSpeculative(context, branches);
    }

    /**
     * 尝试获取预测结果 — 如果命中则返回结果，否则返回 null。
     *
     * @param realIntent 真实意图
     * @param predictionId 预测 ID
     * @return 命中结果，未命中返回 null
     */
    public String tryGetSpeculativeResult(String realIntent, String predictionId) {
        PredictionHitResult hit = checkPredictionHit(realIntent, predictionId);
        if (hit != null && hit.hit()) {
            return hit.result();
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  统计与报告
    // ════════════════════════════════════════════════════════════════

    public boolean isEnabled() { return enabled; }

    public long totalPredictions() { return totalPredictions.get(); }
    public long totalHits() { return totalHits.get(); }
    public long totalMisses() { return totalMisses.get(); }
    public long totalFlushes() { return totalFlushes.get(); }

    public float hitRate() {
        long total = totalHits.get() + totalMisses.get();
        return total > 0 ? (float) totalHits.get() / total : 0;
    }

    public int bufferSize() { return predictionBuffer.size(); }

    public String getStatsReport() {
        return """
                ┌─ SpeculativePredictor Stats ────────────────────────
                │  Enabled             : %s
                │  Total Predictions   : %d
                │  Branch Hits         : %d
                │  Branch Misses       : %d
                │  Pipeline Flushes    : %d
                │  Hit Rate            : %.1f%%
                │  Buffer Size         : %d
                │  Hit Threshold       : %.0f%%
                │  Branch Count        : %d
                └─────────────────────────────────────────────────"""
                .formatted(enabled, totalPredictions.get(), totalHits.get(),
                        totalMisses.get(), totalFlushes.get(), hitRate() * 100,
                        predictionBuffer.size(), HIT_THRESHOLD * 100, BRANCH_COUNT);
    }

    // ── 内部辅助 ──

    private String buildPredictionPrompt(String context) {
        // 截取上下文，避免过长
        String truncatedContext = context.length() > 2000
                ? context.substring(0, 2000) + "..." : context;

        return """
                基于以下上下文，预测用户最可能的3个下一步意图。
                每个意图一行，格式：意图描述|置信度
                
                上下文：
                %s
                
                请预测3个最可能的下一步意图：""".formatted(truncatedContext);
    }

    private List<PredictedBranch> parsePredictions(String predictionText) {
        List<PredictedBranch> branches = new ArrayList<>();
        if (predictionText == null || predictionText.isBlank()) return branches;

        for (String line : predictionText.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;

            String intent;
            float confidence = 0.5f;

            if (line.contains("|")) {
                String[] parts = line.split("\\|", 2);
                intent = parts[0].strip();
                try {
                    confidence = Float.parseFloat(parts[1].strip());
                } catch (NumberFormatException ignored) {}
            } else {
                // 去除序号前缀
                intent = line.replaceFirst("^\\d+[.、)\\s]+", "").strip();
            }

            if (!intent.isEmpty()) {
                branches.add(new PredictedBranch(intent, confidence, null));
            }
        }

        // 按置信度降序排列
        branches.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        return branches;
    }

    private List<PredictedBranch> generateFallbackBranches(String context) {
        // 当 E_CORE 不可用时，基于关键词的简单启发式预测
        List<PredictedBranch> fallback = new ArrayList<>();
        fallback.add(new PredictedBranch("继续当前对话", 0.5f, null));
        fallback.add(new PredictedBranch("请求更多信息", 0.3f, null));
        fallback.add(new PredictedBranch("结束当前任务", 0.2f, null));
        return fallback;
    }

    /**
     * 余弦相似度计算。
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        float dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ════════════════════════════════════════════════════════════════
    //  数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 预测分支 — 类比 CPU 的 Branch Target。
     */
    public record PredictedBranch(
            /** 预测的意图描述 */
            String intent,
            /** 置信度 (0.0 ~ 1.0) */
            float confidence,
            /** 意图的 Embedding 向量（延迟计算） */
            float[] intentVector
    ) {}

    /**
     * 预测记录 — 类比 CPU 的 Reorder Buffer (ROB) 条目。
     */
    public record PredictionRecord(
            /** 预测 ID */
            String predictionId,
            /** 分支 ID */
            String branchId,
            /** 预测的意图 */
            String intent,
            /** 置信度 */
            float confidence,
            /** 推测执行的结果 */
            String result,
            /** 意图的 Embedding 向量 */
            float[] intentVector,
            /** 完成时间戳 */
            long completedAt,
            /** 执行耗时（毫秒） */
            long elapsedMs
    ) {}

    /**
     * 命中结果 — 描述一次分支预测命中检查的结果。
     */
    public record PredictionHitResult(
            /** 是否命中 */
            boolean hit,
            /** 命中的预测意图 */
            String matchedIntent,
            /** 命中的推测结果 */
            String result,
            /** 最高相似度 */
            float similarity,
            /** 推测执行的耗时（命中时有效） */
            long speculativeElapsedMs
    ) {}
}
