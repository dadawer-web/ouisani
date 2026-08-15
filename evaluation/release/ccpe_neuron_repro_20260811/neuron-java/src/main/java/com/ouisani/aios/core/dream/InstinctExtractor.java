package com.ouisani.aios.core.dream;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.learning.instinct.Instinct;
import com.ouisani.aios.core.learning.instinct.InstinctStore;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本能提取器 — 从 RecoveryOrchestrator 的纠错历史中提取本能(Instincts)。
 * <p>
 * 借鉴 ECC (Everything Claude Code) 的持续学习 v2 设计：
 * 当 DAG 引擎成功跑完一个任务，但中间 AutoMedicAgent 曾介入修复过多次，
 * 在夜间休眠或会话结束时，本提取器读取纠错流水账，调用本地小模型进行"归纳反思"，
 * 提取出可复用的规则，生成 instinct 文件到 VFS。
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li>收集阶段: RecoveryOrchestrator 每次执行恢复时，调用 recordRecoveryEvent 记录纠错事件</li>
 *   <li>提取阶段: AutoDreamService 启动时，调用 extractInstincts 进行归纳反思</li>
 *   <li>持久化阶段: 生成的 instinct 写入 VFS /vfs/aios_skills/instincts/ 目录</li>
 *   <li>注入阶段: ContextInjector 在 Agent 发起请求时，自动挂载相关 instinct 到上下文</li>
 * </ol>
 *
 * <h3>OS 类比: Linux Kernel kworker + compaction</h3>
 * 类似 Linux 内核的 kworker 线程在后台执行内存规整：
 * 碎片化的纠错记录(碎片页)被整理成连续的本能规则(规整后的大页)，
 * 供后续 Agent 直接使用(减少缺页中断)。
 *
 * @see InstinctStore
 * @see com.ouisani.aios.core.recovery.RecoveryOrchestrator
 * @see AutoDreamService
 */
public final class InstinctExtractor {

    private static final Logger log = LoggerFactory.getLogger(InstinctExtractor.class);

    private static final InstinctExtractor INSTANCE = new InstinctExtractor();

    /** VFS 中 instinct 文件的存放目录 */
    public static final String INSTINCT_VFS_DIR = "/vfs/aios_skills/instincts";

    /** 最大保留的纠错事件数(环形缓冲) */
    private static final int MAX_RECOVERY_EVENTS = 500;

    /** 提取本能的最小纠错事件数 */
    private static final int MIN_EVENTS_FOR_EXTRACTION = 3;

    /** 纠错事件队列: agentId → 事件列表 */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<RecoveryEvent>> recoveryEvents
            = new ConcurrentHashMap<>();

    /** 统计 */
    private final AtomicLong totalEventsRecorded = new AtomicLong(0);
    private final AtomicLong totalInstinctsExtracted = new AtomicLong(0);
    private final AtomicLong totalExtractionRuns = new AtomicLong(0);

    private InstinctExtractor() {}

    public static InstinctExtractor instance() { return INSTANCE; }

    /**
     * 纠错事件记录 — 当 RecoveryOrchestrator 执行恢复时调用。
     * <p>
     * 这是本能提取的输入源。每次 Agent 崩溃后被成功恢复，都记录一条事件。
     *
     * @param agentId      Agent ID
     * @param errorCategory 错误类别(如 TOOL_ERROR, EDIT_ERROR)
     * @param errorMessage  错误消息
     * @param recoveryStrategy 使用的恢复策略
     * @param success      恢复是否成功
     * @param projectHash  项目哈希
     */
    public void recordRecoveryEvent(String agentId, String errorCategory, String errorMessage,
                                    String recoveryStrategy, boolean success, String projectHash) {
        RecoveryEvent event = new RecoveryEvent(
                System.currentTimeMillis(), agentId, errorCategory,
                errorMessage, recoveryStrategy, success, projectHash);

        ConcurrentLinkedQueue<RecoveryEvent> queue =
                recoveryEvents.computeIfAbsent(agentId, k -> new ConcurrentLinkedQueue<>());
        queue.add(event);

        // 环形缓冲: 超过上限移除最旧的
        while (queue.size() > MAX_RECOVERY_EVENTS) {
            queue.poll();
        }

        totalEventsRecorded.incrementAndGet();

        SemanticEtw.getInstance().logEvent("LEARNING", "RECOVERY_EVENT_RECORDED",
                "agent=" + agentId + " category=" + errorCategory
                        + " strategy=" + recoveryStrategy + " success=" + success);
    }

    /**
     * 提取本能 — 从纠错历史中归纳出可复用的规则。
     * <p>
     * 应该由 AutoDreamService 在夜间休眠或会话结束时调用。
     *
     * @param projectHash 项目哈希
     * @return 提取出的本能数量
     */
    public int extractInstincts(String projectHash) {
        totalExtractionRuns.incrementAndGet();
        log.info("[InstinctExtractor] 开始提取本能, project={}", projectHash);

        int totalExtracted = 0;

        // 遍历所有 Agent 的纠错历史
        for (Map.Entry<String, ConcurrentLinkedQueue<RecoveryEvent>> entry : recoveryEvents.entrySet()) {
            String agentId = entry.getKey();
            ConcurrentLinkedQueue<RecoveryEvent> queue = entry.getValue();

            if (queue.size() < MIN_EVENTS_FOR_EXTRACTION) continue;

            // 按错误模式分组
            Map<String, List<RecoveryEvent>> patternGroups = groupByPattern(queue);

            for (Map.Entry<String, List<RecoveryEvent>> group : patternGroups.entrySet()) {
                String pattern = group.getKey();
                List<RecoveryEvent> events = group.getValue();

                // 同一模式出现 3+ 次才提取
                if (events.size() < MIN_EVENTS_FOR_EXTRACTION) continue;

                // 提取本能
                Instinct instinct = extractFromPattern(pattern, events, projectHash);
                if (instinct != null) {
                    // 持久化到 VFS
                    persistInstinctToVfs(instinct);
                    totalExtracted++;
                    totalInstinctsExtracted.incrementAndGet();

                    log.info("[InstinctExtractor] 提取本能: {} (从 {} 次纠错中)",
                            instinct.id(), events.size());
                }
            }
        }

        log.info("[InstinctExtractor] 提取完成, 共 {} 个本能", totalExtracted);
        return totalExtracted;
    }

    /**
     * 按错误模式分组。
     * <p>
     * 相同 errorCategory + 相同 recoveryStrategy 的事件归为一组，
     * 因为它们代表同一类问题的同一类解决方案。
     */
    private Map<String, List<RecoveryEvent>> groupByPattern(ConcurrentLinkedQueue<RecoveryEvent> queue) {
        Map<String, List<RecoveryEvent>> groups = new ConcurrentHashMap<>();
        for (RecoveryEvent event : queue) {
            String pattern = event.errorCategory + ":" + event.recoveryStrategy;
            groups.computeIfAbsent(pattern, k -> new java.util.ArrayList<>()).add(event);
        }
        return groups;
    }

    /**
     * 从错误模式中提取本能。
     * <p>
     * 分析同一模式的多次纠错事件，提取出通用规则。
     */
    private Instinct extractFromPattern(String pattern, List<RecoveryEvent> events, String projectHash) {
        // 取最近的事件作为代表
        RecoveryEvent latest = events.get(events.size() - 1);

        // 构建本能描述
        String instinctPattern = "recovery_pattern:" + pattern;
        String action = "apply_" + latest.recoveryStrategy + "_for_" + latest.errorCategory;
        String description = buildDescription(pattern, events);

        // 置信度: 基于出现次数，越多越确信
        double confidence = Math.min(0.9, 0.3 + events.size() * 0.15);

        // 记录到 InstinctStore
        return InstinctStore.instance().record(
                instinctPattern, action, description, confidence, projectHash);
    }

    /**
     * 构建本能描述。
     * <p>
     * 从多次纠错事件中提取共同特征，生成人类可读的规则描述。
     */
    private String buildDescription(String pattern, List<RecoveryEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("Recovery pattern: ").append(pattern).append("\n");
        sb.append("Observed ").append(events.size()).append(" times.\n");

        // 列出最近的错误消息(最多3条)
        sb.append("Recent errors:\n");
        int count = 0;
        for (int i = events.size() - 1; i >= 0 && count < 3; i--, count++) {
            String msg = events.get(i).errorMessage;
            if (msg != null && msg.length() > 100) msg = msg.substring(0, 100) + "...";
            sb.append("  - ").append(msg).append("\n");
        }

        // 建议规则
        sb.append("Suggested rule: When encountering ").append(events.get(0).errorCategory);
        sb.append(", apply ").append(events.get(0).recoveryStrategy).append(" strategy.");

        return sb.toString();
    }

    /**
     * 将本能持久化到 VFS。
     * <p>
     * 写入 /vfs/aios_skills/instincts/{instinctId}.md
     * 第二天 Agent 发起请求时，ContextInjector 会自动挂载相关本能。
     */
    private void persistInstinctToVfs(Instinct instinct) {
        String vfsPath = INSTINCT_VFS_DIR + "/" + instinct.id() + ".md";

        String content = instinct.toYaml();

        try {
            VfsManager vfs = VfsManager.instance();
            boolean ok = vfs.writeText(vfsPath, content);
            if (ok) {
                SemanticEtw.getInstance().logEvent("LEARNING", "INSTINCT_PERSISTED",
                        "id=" + instinct.id() + " path=" + vfsPath + " confidence=" + instinct.confidence());
                log.debug("[InstinctExtractor] 本能已持久化: {}", vfsPath);
            } else {
                log.warn("[InstinctExtractor] 本能持久化失败: {}", vfsPath);
            }
        } catch (Exception e) {
            log.warn("[InstinctExtractor] 本能持久化异常: {}", e.getMessage());
        }
    }

    /**
     * 获取 Agent 的纠错统计。
     */
    public Map<String, Object> getAgentStats(String agentId) {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        ConcurrentLinkedQueue<RecoveryEvent> queue = recoveryEvents.get(agentId);
        stats.put("recovery_event_count", queue != null ? queue.size() : 0);
        return stats;
    }

    /**
     * 获取全局统计。
     */
    public Map<String, Object> getGlobalStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("total_events_recorded", totalEventsRecorded.get());
        stats.put("total_instincts_extracted", totalInstinctsExtracted.get());
        stats.put("total_extraction_runs", totalExtractionRuns.get());
        stats.put("tracked_agents", recoveryEvents.size());
        return stats;
    }

    /**
     * 清理 Agent 的纠错历史(Agent 终止时调用)。
     */
    public void cleanupAgent(String agentId) {
        recoveryEvents.remove(agentId);
    }

    /**
     * 纠错事件记录。
     */
    private record RecoveryEvent(
            long timestamp,
            String agentId,
            String errorCategory,
            String errorMessage,
            String recoveryStrategy,
            boolean success,
            String projectHash
    ) {}
}
