package com.ouisani.aios.core.learning.instinct;

import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Instinct 观察者 — 工具调用模式检测器。
 * <p>
 * 借鉴 ECC 的持续学习系统设计：
 * <ol>
 *   <li>观察层: 捕获 100% 的工具调用到 observations</li>
 *   <li>模式检测层: 分析观察，检测四类模式</li>
 *   <li>Instinct 生成: 检测到模式后生成 instinct</li>
 * </ol>
 *
 * <h3>四类检测模式</h3>
 * <ul>
 *   <li><b>用户纠正</b> — "No, use X instead of Y"，检测连续调用中工具切换</li>
 *   <li><b>错误解决</b> — 错误后跟修复操作</li>
 *   <li><b>重复工作流</b> — 相同工具序列反复出现</li>
 *   <li><b>工具偏好</b> — 总是 Grep before Edit 等固定顺序</li>
 * </ul>
 *
 * <h3>OS 类比: Linux Kernel perf record + ftrace</h3>
 * 类似 Linux 内核的性能分析工具：
 * perf record 捕获所有事件，ftrace 分析模式。
 * 本观察者捕获所有工具调用，分析行为模式。
 *
 * @see Instinct
 * @see InstinctStore
 */
public final class InstinctObserver {

    private static final Logger log = LoggerFactory.getLogger(InstinctObserver.class);

    private static final InstinctObserver INSTANCE = new InstinctObserver();

    /** 最大观察记录数（环形缓冲） */
    private static final int MAX_OBSERVATIONS = 10_000;

    /** 重复工作流检测的最小重复次数 */
    private static final int WORKFLOW_MIN_REPEATS = 3;

    /** 工作流序列的最大长度 */
    private static final int WORKFLOW_MAX_LENGTH = 5;

    /** 观察记录: agentId → 工具调用序列 */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<ToolObservation>> observations
            = new ConcurrentHashMap<>();

    /** 已检测到的工作流模式: agentId → (序列哈希 → 出现次数) */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> workflowPatterns
            = new ConcurrentHashMap<>();

    /** 统计 */
    private final AtomicLong totalObservations = new AtomicLong(0);
    private final AtomicLong totalPatternsDetected = new AtomicLong(0);
    private final AtomicLong totalInstinctsGenerated = new AtomicLong(0);

    private InstinctObserver() {}

    public static InstinctObserver instance() { return INSTANCE; }

    /**
     * 记录一次工具调用观察。
     * <p>
     * 应该在工具执行后（PostToolUse 时机）调用。
     *
     * @param agentId      Agent ID
     * @param toolName     工具名称
     * @param success      是否成功
     * @param projectHash  项目哈希
     */
    public void observe(String agentId, String toolName, boolean success, String projectHash) {
        ToolObservation obs = new ToolObservation(
                System.currentTimeMillis(), agentId, toolName, success, projectHash);

        ConcurrentLinkedQueue<ToolObservation> queue =
                observations.computeIfAbsent(agentId, k -> new ConcurrentLinkedQueue<>());
        queue.add(obs);

        // 环形缓冲：超过上限移除最旧的
        while (queue.size() > MAX_OBSERVATIONS) {
            queue.poll();
        }

        totalObservations.incrementAndGet();

        // 实时模式检测
        detectPatterns(agentId, projectHash);
    }

    /**
     * 记录用户纠正事件。
     * <p>
     * 当用户说 "No, use X instead of Y" 时调用。
     *
     * @param agentId       Agent ID
     * @param fromTool      被纠正的工具
     * @param toTool        纠正后的工具
     * @param projectHash   项目哈希
     */
    public void recordUserCorrection(String agentId, String fromTool, String toTool, String projectHash) {
        String pattern = "user_correction:" + fromTool + "_to_" + toTool;
        String action = "prefer_" + toTool + "_over_" + fromTool;
        String description = "User corrected: use " + toTool + " instead of " + fromTool;

        Instinct instinct = InstinctStore.instance().record(
                pattern, action, description, 0.6, projectHash);

        totalInstinctsGenerated.incrementAndGet();

        SemanticEtw.getInstance().logEvent("LEARNING", "USER_CORRECTION_DETECTED",
                "agent=" + agentId + " from=" + fromTool + " to=" + toTool
                        + " instinct=" + instinct.id());

        log.info("[InstinctObserver] 用户纠正: {} → {}, instinct={}", fromTool, toTool, instinct.id());
    }

    /**
     * 记录错误解决事件。
     * <p>
     * 当错误发生后跟修复操作时调用。
     *
     * @param agentId       Agent ID
     * @param failedTool    失败的工具
     * @param recoveryTool  修复工具
     * @param projectHash   项目哈希
     */
    public void recordErrorRecovery(String agentId, String failedTool, String recoveryTool, String projectHash) {
        String pattern = "error_recovery:" + failedTool + "_then_" + recoveryTool;
        String action = "after_" + failedTool + "_failure_use_" + recoveryTool;
        String description = "When " + failedTool + " fails, use " + recoveryTool + " for recovery";

        Instinct instinct = InstinctStore.instance().record(
                pattern, action, description, 0.5, projectHash);

        totalInstinctsGenerated.incrementAndGet();

        SemanticEtw.getInstance().logEvent("LEARNING", "ERROR_RECOVERY_DETECTED",
                "agent=" + agentId + " failed=" + failedTool + " recovery=" + recoveryTool
                        + " instinct=" + instinct.id());

        log.info("[InstinctObserver] 错误恢复: {} → {}, instinct={}", failedTool, recoveryTool, instinct.id());
    }

    /**
     * 模式检测 — 分析最近的工具调用序列。
     * <p>
     * 检测两类模式：
     * 1. 重复工作流 — 相同工具序列反复出现
     * 2. 工具偏好 — 固定的工具调用顺序
     */
    private void detectPatterns(String agentId, String projectHash) {
        ConcurrentLinkedQueue<ToolObservation> queue = observations.get(agentId);
        if (queue == null || queue.size() < WORKFLOW_MIN_REPEATS) return;

        // 取最近的观察记录
        List<ToolObservation> recent = new ArrayList<>(queue);
        int size = recent.size();

        // 检测重复工作流：长度 2-5 的子序列
        for (int len = 2; len <= Math.min(WORKFLOW_MAX_LENGTH, size / 2); len++) {
            // 取最近的 len 个工具名
            List<String> currentSeq = new ArrayList<>();
            for (int i = size - len; i < size; i++) {
                currentSeq.add(recent.get(i).toolName());
            }

            String seqHash = computeSequenceHash(currentSeq);
            String seqKey = String.join("→", currentSeq);

            ConcurrentHashMap<String, AtomicLong> patterns =
                    workflowPatterns.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>());
            long count = patterns.computeIfAbsent(seqHash, k -> new AtomicLong(0)).incrementAndGet();

            // 重复达到阈值，生成 instinct
            if (count == WORKFLOW_MIN_REPEATS) {
                String pattern = "repeated_workflow:" + seqKey;
                String action = "auto_chain:" + seqKey;
                String description = "Repeated workflow detected: " + seqKey + " (seen " + count + " times)";

                Instinct instinct = InstinctStore.instance().record(
                        pattern, action, description, 0.4, projectHash);

                totalInstinctsGenerated.incrementAndGet();
                totalPatternsDetected.incrementAndGet();

                SemanticEtw.getInstance().logEvent("LEARNING", "WORKFLOW_PATTERN_DETECTED",
                        "agent=" + agentId + " sequence=" + seqKey + " count=" + count
                                + " instinct=" + instinct.id());

                log.info("[InstinctObserver] 重复工作流检测: {} ({}次), instinct={}",
                        seqKey, count, instinct.id());
            }
        }
    }

    /**
     * 获取 Agent 的观察统计。
     */
    public Map<String, Object> getAgentStats(String agentId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        ConcurrentLinkedQueue<ToolObservation> queue = observations.get(agentId);
        stats.put("observation_count", queue != null ? queue.size() : 0);

        ConcurrentHashMap<String, AtomicLong> patterns = workflowPatterns.get(agentId);
        stats.put("pattern_count", patterns != null ? patterns.size() : 0);

        return stats;
    }

    /**
     * 获取全局统计。
     */
    public Map<String, Object> getGlobalStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_observations", totalObservations.get());
        stats.put("total_patterns_detected", totalPatternsDetected.get());
        stats.put("total_instincts_generated", totalInstinctsGenerated.get());
        stats.put("tracked_agents", observations.size());
        return stats;
    }

    /**
     * 清理 Agent 的观察数据（Agent 终止时调用）。
     */
    public void cleanupAgent(String agentId) {
        observations.remove(agentId);
        workflowPatterns.remove(agentId);
        log.debug("[InstinctObserver] 清理 Agent 观察数据: {}", agentId);
    }

    private static String computeSequenceHash(List<String> sequence) {
        String joined = String.join("|", sequence);
        return Integer.toHexString(joined.hashCode());
    }

    /**
     * 工具调用观察记录。
     */
    private record ToolObservation(
            long timestamp,
            String agentId,
            String toolName,
            boolean success,
            String projectHash
    ) {}
}
