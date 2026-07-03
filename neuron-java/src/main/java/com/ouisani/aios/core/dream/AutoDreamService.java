package com.ouisani.aios.core.dream;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

/**
 * 自动梦境整合 — 对标 Claude Code 的 autoDream。
 * <p>
 * 跨会话记忆整合：在空闲时自动反思、整合知识：
 * - 三重门控（时间门 → 会话门 → 锁门）
 * - 4 阶段 Prompt（Orient → Gather → Consolidate → Prune）
 * - 文件锁防并发
 * <p>
 * <b>失败学习集成（借鉴 Headroom learn）：</b>
 * 新增 {@link #learnFromFailures} 方法 — 从失败会话中提取修正规则，
 * 检测循环（canonical signature），LLM 分析后写入持久化规则文件。
 * 番茄钟死循环跑了几十轮，系统现在能自动生成"下次别这么干"的规则。
 * <p>
 * OS 类比：相当于 Linux 的 kswapd 后台回收 — 空闲时整理内存。
 * learnFromFailures = panic 日志分析器 — 从崩溃中提取修复规则。
 */
public class AutoDreamService {

    private static final Logger log = LoggerFactory.getLogger(AutoDreamService.class);

    /** 配置 */
    public record AutoDreamConfig(
            double minHours,
            int minSessions
    ) {
        public AutoDreamConfig() { this(24, 5); }
    }

    private static final String LOCK_FILE = ".consolidate-lock";
    private static final long LOCK_EXPIRY_MS = 3600_000; // 1 hour

    private static AutoDreamConfig config = new AutoDreamConfig();
    private static volatile long lastConsolidatedAt = 0;
    private static volatile int sessionsSinceLastConsolidation = 0;

    /**
     * 检查是否应该触发梦境整合 — 三重门控。
     */
    public static boolean shouldDream() {
        // 门1：时间门
        long hoursSinceLast = (System.currentTimeMillis() - lastConsolidatedAt) / 3600_000;
        if (hoursSinceLast < config.minHours()) return false;

        // 门2：会话门
        if (sessionsSinceLastConsolidation < config.minSessions()) return false;

        // 门3：锁门
        if (isLocked()) return false;

        return true;
    }

    /**
     * 执行梦境整合 — 4 阶段 Prompt。
     */
    public static String consolidate(AiosSdk sdk, String agentId, String memoryDir) {
        log.info("[AutoDream] Starting consolidation for agent: {}", agentId);
        System.out.println("[AutoDream] 正在启动整合...");

        // 获取文件锁
        if (!acquireLock(memoryDir)) {
            log.warn("[AutoDream] Another process is consolidating");
            return "Consolidation skipped: another process is running";
        }

        try {
            // Phase 1: Orient — 查看现有记忆
            String existingMemory = readMemoryFiles(memoryDir);
            String orientPrompt = "Review the following existing memory and identify areas that need updating:\n\n" + existingMemory;

            // Phase 2: Gather — 收集新信号
            String gatherPrompt = "Based on the existing memory, what new information should be gathered from recent sessions?";

            // Phase 3: Consolidate — 合并更新
            String consolidatePrompt = buildConsolidationPrompt(existingMemory);
            String result = sdk.think(agentId, consolidatePrompt);

            // Phase 4: Prune — 修剪索引
            String prunedResult = pruneResult(result);

            // 写入记忆文件
            writeMemoryFile(memoryDir, prunedResult);

            lastConsolidatedAt = System.currentTimeMillis();
            sessionsSinceLastConsolidation = 0;

            log.info("[AutoDream] Consolidation complete for agent: {}", agentId);
            System.out.println("[AutoDream] Consolidation complete");
            return prunedResult;

        } catch (Exception e) {
            rollbackLock(memoryDir);
            log.error("[AutoDream] Consolidation failed: {}", e.getMessage());
            return "Consolidation failed: " + e.getMessage();
        } finally {
            releaseLock(memoryDir);
        }
    }

    /**
     * 记录新会话。
     */
    public static void recordNewSession() {
        sessionsSinceLastConsolidation++;
    }

    // ════════════════════════════════════════════════════════════════
    //  失败学习 — 借鉴 Headroom learn
    //
    //  从失败会话中提取修正规则：
    //  1. LoopDetector 检测循环（canonical signature + 分页参数剥离）
    //  2. SessionAnalyzer LLM 分析失败原因
    //  3. LearnRuleWriter 写入持久化规则文件
    //
    //  不破坏现有 consolidate() 逻辑 — 独立的新方法，可按需调用。
    // ════════════════════════════════════════════════════════════════

    /**
     * 从失败会话中学习 — 借鉴 Headroom headroom learn。
     * <p>
     * 分析 AgentTask 的会话历史，检测循环，LLM 分析后写入持久化规则文件。
     * <p>
     * <b>不破坏现有逻辑：</b>此方法独立于 {@link #consolidate}，可按需调用。
     * 建议在 {@link #consolidate} 之前或之后调用，作为梦境整合的补充。
     *
     * @param task      Agent 任务（包含 contextHistory）
     * @param sdk       AIOS SDK（用于 LLM 分析）
     * @param agentId   Agent ID
     * @param memoryDir 记忆目录（规则文件写入位置）
     * @return 学习结果摘要
     */
    public static String learnFromFailures(AgentTask task, AiosSdk sdk,
                                            String agentId, String memoryDir) {
        log.info("[AutoDream] 启动失败学习: agent={}", agentId);
        System.out.println("[AutoDream] 正在从失败会话中学习...");

        if (task == null) {
            return "learnFromFailures skipped: task is null";
        }

        List<String> contextHistory = task.contextHistory();
        if (contextHistory == null || contextHistory.isEmpty()) {
            log.info("[AutoDream] 无会话历史，跳过失败学习");
            return "learnFromFailures skipped: no session history";
        }

        // 1. SessionAnalyzer 分析失败会话
        List<LearnRecommendation> recommendations = SessionAnalyzer.analyze(
                contextHistory, sdk, agentId);

        if (recommendations.isEmpty()) {
            log.info("[AutoDream] 未生成推荐规则（无失败或无循环）");
            return "learnFromFailures: no recommendations generated (no failures or loops detected)";
        }

        // 2. LearnRuleWriter 写入持久化规则文件
        LearnRuleWriter.WriteResult result = LearnRuleWriter.writeToDefault(
                memoryDir, recommendations, false);

        if (!result.success()) {
            log.error("[AutoDream] 规则写入失败: {}", result.error);
            return "learnFromFailures failed: " + result.error;
        }

        int loopCount = (int) recommendations.stream()
                .filter(LearnRecommendation::isLoopGuardrail).count();

        String summary = String.format(
                "learnFromFailures: generated %d rules (%d loop guardrails), written to %s",
                result.recommendationCount, loopCount, result.fileWritten);

        log.info("[AutoDream] {}", summary);
        System.out.println("[AutoDream] 失败学习完成: " + result.recommendationCount
                + " 条规则 (" + loopCount + " 个循环护栏)");

        return summary;
    }

    /**
     * 便捷方法：只检测循环不调用 LLM（快速诊断）。
     *
     * @param task Agent 任务
     * @return 检测到的循环列表（可能为空）
     */
    public static List<LoopDetector.LoopPattern> detectLoops(AgentTask task) {
        if (task == null) return List.of();
        List<LoopDetector.ToolCallRecord> toolCalls =
                SessionAnalyzer.extractToolCalls(task.contextHistory());
        return LoopDetector.detectLoops(toolCalls);
    }

    private static String buildConsolidationPrompt(String existingMemory) {
        return """
                You are performing a memory consolidation task. Review the existing memory and update it:
                
                1. Remove outdated information
                2. Add any new patterns or learnings
                3. Reorganize for clarity
                4. Keep the total size under 25KB
                
                Existing Memory:
                ---
                """ + existingMemory + """
                
                ---
                
                Output the updated memory in the same format:
                """;
    }

    private static String pruneResult(String result) {
        int maxLines = 500; // MAX_ENTRYPOINT_LINES equivalent
        String[] lines = result.split("\n");
        if (lines.length <= maxLines) return result;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("... [pruned to ").append(maxLines).append(" lines]");
        return sb.toString();
    }

    // ── 文件锁机制 ──

    private static boolean isLocked() {
        Path lockPath = Path.of(LOCK_FILE);
        if (!Files.exists(lockPath)) return false;

        try {
            long mtime = Files.getLastModifiedTime(lockPath).toMillis();
            if (System.currentTimeMillis() - mtime > LOCK_EXPIRY_MS) return false;

            String content = Files.readString(lockPath, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return false;

            // 检查 PID 是否存活
            long pid = Long.parseLong(content);
            return ProcessHandle.of(pid).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean acquireLock(String memoryDir) {
        try {
            Path lockPath = Path.of(memoryDir, LOCK_FILE);
            Files.writeString(lockPath, String.valueOf(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void releaseLock(String memoryDir) {
        try {
            Files.deleteIfExists(Path.of(memoryDir, LOCK_FILE));
        } catch (IOException ignored) {}
    }

    private static void rollbackLock(String memoryDir) {
        try {
            Path lockPath = Path.of(memoryDir, LOCK_FILE);
            if (lastConsolidatedAt > 0) {
                Files.setLastModifiedTime(lockPath, FileTime.fromMillis(lastConsolidatedAt));
            }
        } catch (IOException ignored) {}
    }

    private static String readMemoryFiles(String memoryDir) {
        try {
            StringBuilder sb = new StringBuilder();
            try (var stream = Files.list(Path.of(memoryDir))) {
                stream.filter(p -> p.toString().endsWith(".md"))
                      .forEach(p -> {
                          try {
                              sb.append("--- ").append(p.getFileName()).append(" ---\n");
                              sb.append(Files.readString(p, StandardCharsets.UTF_8)).append("\n\n");
                          } catch (IOException ignored) {}
                      });
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeMemoryFile(String memoryDir, String content) throws IOException {
        Path dir = Path.of(memoryDir);
        if (!Files.exists(dir)) Files.createDirectories(dir);
        Files.writeString(dir.resolve("consolidated_memory.md"), content, StandardCharsets.UTF_8);
    }

    public static void setConfig(AutoDreamConfig cfg) { config = cfg; }
}
