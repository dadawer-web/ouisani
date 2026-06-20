package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.cache.SemanticCacheManager;
import com.ouisani.aios.core.cache.eviction.BionicCognitiveStrategy;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.vfs.VectorNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 认知梦境守护进程 — AIOS 内核的潜意识。
 * <p>
 * 在传统 OS 中，空闲任务（PID 0）只是让 CPU 休眠以省电。
 * 在 AIOS 中，空闲任务会<b>做梦</b>。
 *
 * <h3>生物学灵感：睡眠与记忆巩固</h3>
 * 人类睡眠时，海马体会重播当天的经历，将重要记忆转移到
 * 新皮层进行长期存储。这个过程称为<b>记忆巩固</b>，它将
 * 脆弱的短期记忆转化为稳定的长期知识。
 *
 * <h3>AIOS 实现</h3>
 * CognitiveDreamDaemon 实现了完全相同的机制：
 * <ol>
 *   <li><b>扫描</b>：识别语义缓存中正在衰减的短期记忆（艾宾浩斯曲线）</li>
 *   <li><b>巩固</b>：使用低成本 LLM 调用总结和提取因果关系</li>
 *   <li><b>持久化</b>：将巩固后的经验以向量形式写入 VFS 的 VectorNode</li>
 *   <li><b>驱逐</b>：释放原始缓存条目，为新体验腾出认知空间</li>
 * </ol>
 *
 * <h3>自发灵感</h3>
 * 巩固过程中，如果发现矛盾、未解决问题或新颖洞见，
 * 通过 EventBus 触发 {@code spontaneous_idea} 事件 —
 * 类比睡眠中的"灵光一现"。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>OS/生物学</th><th>AIOS</th><th>说明</th></tr>
 *   <tr><td>空闲任务 (PID 0)</td><td>CognitiveDreamDaemon</td><td>系统空闲时运行</td></tr>
 *   <tr><td>海马体重播</td><td>consolidateMemories()</td><td>短期→长期记忆</td></tr>
 *   <tr><td>新皮层存储</td><td>/var/db/memory (VectorNode)</td><td>持久化向量存储</td></tr>
 *   <tr><td>灵光一现</td><td>spontaneous_idea 事件</td><td>潜意识发现</td></tr>
 * </table>
 *
 * @see SemanticCacheManager
 * @see BionicCognitiveStrategy
 * @see VectorNode
 * @see EventBus
 */
public final class CognitiveDreamDaemon {

    private static final Logger log = LoggerFactory.getLogger(CognitiveDreamDaemon.class);

    // ── Singleton ──

    private static final class Holder {
        static final CognitiveDreamDaemon INSTANCE = new CognitiveDreamDaemon();
    }

    public static CognitiveDreamDaemon instance() {
        return Holder.INSTANCE;
    }

    // ── Configuration ──

    /** 梦境周期检查间隔（秒） */
    private static final long DREAM_CYCLE_INTERVAL_SEC = 30;

    /** 触发巩固的最少衰减条目数 */
    private static final int MIN_DECAYING_ENTRIES = 2;

    /** 保留分数阈值 — 低于此值的缓存条目被视为"正在衰减" */
    private static final double RETENTION_THRESHOLD = 0.3;

    /** 持久化记忆的 VFS 路径 */
    private static final String MEMORY_DB_PATH = "/var/db/memory";

    /** 记忆巩固的 LLM 提示词模板 */
    private static final String CONSOLIDATION_PROMPT =
            "你是一个认知科学家的潜意识。以下是一些零散的短期记忆碎片。"
            + "请提炼出1-3条核心经验或因果关系，每条不超过50字。"
            + "只输出提炼结果，用编号列表格式，不要任何解释。\n\n"
            + "记忆碎片：\n%s";

    // ── State ──

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean dreaming = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private LlmProvider llmProvider;
    private BionicCognitiveStrategy bionicStrategy;

    private final AtomicLong totalDreamCycles = new AtomicLong(0);
    private final AtomicLong totalMemoriesConsolidated = new AtomicLong(0);
    private final AtomicLong totalSpontaneousIdeas = new AtomicLong(0);

    private CognitiveDreamDaemon() {
        this.bionicStrategy = new BionicCognitiveStrategy();
    }

    // ════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════

    /** 配置 LLM Provider — 用于记忆巩固时的摘要生成。 */
    public void configure(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
        log.info("[DreamDaemon] 已配置 LlmProvider: {}",
                llmProvider != null ? llmProvider.name() : "null");
    }

    /** 启动梦境守护进程的周期性循环。 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return; // already running
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aios-dream-daemon");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                this::dreamCycle,
                DREAM_CYCLE_INTERVAL_SEC,
                DREAM_CYCLE_INTERVAL_SEC,
                TimeUnit.SECONDS
        );

        log.info("[DreamDaemon] 已启动。梦境周期间隔: {}s", DREAM_CYCLE_INTERVAL_SEC);
        System.out.println("  \u001B[35m[DreamDaemon] 认知梦境守护进程已启动。系统现在可以做梦了。\u001B[0m");
    }

    /** 停止梦境守护进程。 */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("[DreamDaemon] 已停止。统计: cycles={}, consolidated={}, ideas={}",
                totalDreamCycles.get(), totalMemoriesConsolidated.get(), totalSpontaneousIdeas.get());
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isDreaming() {
        return dreaming.get();
    }

    // ════════════════════════════════════════════════════════════════
    //  Dream Cycle — the core subconscious process
    // ════════════════════════════════════════════════════════════════

    /**
     * 执行一次梦境周期：扫描 → 巩固 → 持久化 → 驱逐。
     * <p>
     * 类比一次 REM 睡眠周期：扫描语义缓存中的衰减记忆，
     * 通过 LLM 巩固，持久化结果，释放原始条目。
     */
    private void dreamCycle() {
        if (!running.get() || dreaming.get()) return;

        // ── Idle Check: only dream when the system is idle ──
        TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
        if (scheduler != null && !scheduler.isSystemIdle()) {
            log.debug("[DreamDaemon] 系统非空闲 (active: {})，跳过梦境周期",
                    scheduler.activeCount());
            return;
        }

        dreaming.set(true);
        totalDreamCycles.incrementAndGet();

        try {
            SemanticCacheManager cacheMgr = SemanticCacheManager.instance();

            // ── Phase 1: Scan for decaying memories ──
            List<SemanticCacheManager.CacheEntry> decaying = scanDecayingMemories(cacheMgr);

            if (decaying.size() < MIN_DECAYING_ENTRIES) {
                log.debug("[DreamDaemon] 衰减记忆不足 ({}/{})，跳过梦境周期",
                        decaying.size(), MIN_DECAYING_ENTRIES);
                return;
            }

            log.info("[DreamDaemon] 梦境周期 #{}：发现 {} 个衰减记忆",
                    totalDreamCycles.get(), decaying.size());
            System.out.printf("  \u001B[35m[DreamDaemon] 梦境周期 #%d：扫描 %d 个衰减记忆...\u001B[0m%n",
                    totalDreamCycles.get(), decaying.size());

            // ── Phase 2: Consolidate via LLM ──
            String consolidated = consolidateMemories(decaying);

            if (consolidated == null || consolidated.isBlank()) {
                log.warn("[DreamDaemon] 整合未产生输出，中止梦境周期");
                return;
            }

            // ── Phase 3: Persist to /var/db/memory (VectorNode) ──
            persistConsolidatedMemory(consolidated);

            // ── Phase 4: Evict consolidated entries from cache ──
            evictConsolidatedEntries(cacheMgr, decaying);

            totalMemoriesConsolidated.addAndGet(decaying.size());

            log.info("[DreamDaemon] 梦境周期 #{} 完成：{} 个记忆已整合，{} 字符已持久化",
                    totalDreamCycles.get(), decaying.size(), consolidated.length());

            // ── Phase 5: Check for spontaneous ideas ──
            checkForSpontaneousIdeas(consolidated);

        } catch (Exception e) {
            log.error("[DreamDaemon] 梦境周期错误: {}", e.getMessage(), e);
        } finally {
            dreaming.set(false);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 1: Scan for Decaying Memories
    // ════════════════════════════════════════════════════════════════

    /**
     * 扫描语义缓存中保留分数低于阈值的条目 — 这些是"正在消退的记忆"。
     */
    private List<SemanticCacheManager.CacheEntry> scanDecayingMemories(SemanticCacheManager cacheMgr) {
        List<SemanticCacheManager.CacheEntry> decaying = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (SemanticCacheManager.CacheEntry entry : cacheMgr.getCacheEntries()) {
            double retention = bionicStrategy.computeRetention(entry, now);
            if (retention < RETENTION_THRESHOLD) {
                decaying.add(entry);
                log.debug("[DreamDaemon] 衰减记忆: retention={:.4f}, age={}ms, accessCount={}",
                        retention, now - entry.createdAt(), entry.accessCount());
            }
        }

        // Sort by retention ascending — most decayed first
        decaying.sort(Comparator.comparingDouble(e -> bionicStrategy.computeRetention(e, now)));

        return decaying;
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 2: Consolidate via LLM (Hippocampal Replay)
    // ════════════════════════════════════════════════════════════════

    /**
     * 通过 LLM 巩固衰减记忆 — 类比海马体在慢波睡眠中的重播。
     * <p>
     * 将零散的短期记忆碎片交给 LLM 提炼核心经验和因果关系，
     * 使用低成本 LLM 调用以保持认知开销最小。
     */
    private String consolidateMemories(List<SemanticCacheManager.CacheEntry> entries) {
        if (llmProvider == null) {
            log.warn("[DreamDaemon] LlmProvider 未配置，无法整合");
            return null;
        }

        // Assemble the memory fragments
        StringBuilder fragments = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            SemanticCacheManager.CacheEntry entry = entries.get(i);
            fragments.append(String.format("%d. [access=%d, age=%dms] %s%n",
                    i + 1,
                    entry.accessCount(),
                    System.currentTimeMillis() - entry.createdAt(),
                    entry.responseText().length() > 300
                            ? entry.responseText().substring(0, 300) + "..."
                            : entry.responseText()));
        }

        String prompt = String.format(CONSOLIDATION_PROMPT, fragments.toString());

        try {
            String result = llmProvider.think(prompt);
            log.info("[DreamDaemon] LLM 整合完成: {} chars", result != null ? result.length() : 0);
            System.out.printf("  \u001B[35m[DreamDaemon] 记忆整合：%d 个碎片 → %d 字符的提炼经验\u001B[0m%n",
                    entries.size(), result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("[DreamDaemon] LLM 整合失败: {}", e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 3: Persist to /var/db/memory (Neocortical Storage)
    // ════════════════════════════════════════════════════════════════

    /**
     * 将巩固后的经验持久化到 VFS VectorNode ({@code /var/db/memory})。
     * <p>
     * 类比将记忆从海马体（短期）转移到新皮层（长期）。
     */
    private void persistConsolidatedMemory(String consolidated) {
        VfsManager vfs = VfsManager.instance();

        try {
            // Resolve the VectorNode at /var/db/memory
            Optional<com.ouisani.aios.core.VfsNode> nodeOpt = vfs.resolve(MEMORY_DB_PATH);

            if (nodeOpt.isPresent() && nodeOpt.get() instanceof VectorNode vecNode) {
                // Write the consolidated memory — VectorNode.write() will
                // automatically embed it and store the vector
                vecNode.write(consolidated);
                log.info("[DreamDaemon] 已持久化整合记忆至 {} ({} chars)",
                        MEMORY_DB_PATH, consolidated.length());
                System.out.printf("  \u001B[35m[DreamDaemon] 记忆已持久化至 %s（%d 字符）→ 长期存储\u001B[0m%n",
                        MEMORY_DB_PATH, consolidated.length());
            } else {
                log.warn("[DreamDaemon] VectorNode 未找到 {}，回退至 VFS 文件写入", MEMORY_DB_PATH);
                // Fallback: write as a plain text file
                String fallbackPath = MEMORY_DB_PATH + "/dream_log_" + System.currentTimeMillis();
                vfs.mount(fallbackPath, "dream_log",
                        new com.ouisani.aios.vfs.MutableFileNode(fallbackPath));
                vfs.resolve(fallbackPath).ifPresent(node -> node.write(consolidated));
            }
        } catch (Exception e) {
            log.error("[DreamDaemon] 持久化记忆失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 4: Evict Consolidated Entries
    // ════════════════════════════════════════════════════════════════

    /**
     * 驱逐已巩固的缓存条目 — 释放认知空间。
     */
    private void evictConsolidatedEntries(SemanticCacheManager cacheMgr,
                                          List<SemanticCacheManager.CacheEntry> entries) {
        // The SemanticCacheManager doesn't have a direct remove method,
        // so we mark them as very low priority for the next eviction cycle
        for (SemanticCacheManager.CacheEntry entry : entries) {
            entry.meta("consolidated", true);
            entry.meta("consolidation_time", System.currentTimeMillis());
        }

        log.info("[DreamDaemon] 已标记 {} 条目为已整合（将在下一周期被驱逐）",
                entries.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 5: Spontaneous Idea Detection
    // ════════════════════════════════════════════════════════════════

    /**
     * 分析巩固后的记忆，检测矛盾、未解决问题或新颖洞见。
     * 如果发现，通过 EventBus 触发 {@code spontaneous_idea} 事件 —
     * 类比睡眠中的"灵光一现"。
     */
    private void checkForSpontaneousIdeas(String consolidated) {
        if (consolidated == null || consolidated.isBlank()) return;

        // Simple heuristic: check for keywords that indicate
        // contradictions, problems, or insights
        String[] ideaTriggers = {
                "矛盾", "冲突", "不一致", "问题", "未解决", "异常", "错误",
                "contradiction", "conflict", "inconsistency", "problem",
                "unresolved", "anomaly", "error", "insight", "发现",
                "关键", "重要", "critical", "important", "breakthrough"
        };

        boolean isSpontaneous = false;
        String triggerWord = null;
        String lowerConsolidated = consolidated.toLowerCase();

        for (String trigger : ideaTriggers) {
            if (lowerConsolidated.contains(trigger.toLowerCase())) {
                isSpontaneous = true;
                triggerWord = trigger;
                break;
            }
        }

        if (isSpontaneous) {
            totalSpontaneousIdeas.incrementAndGet();

            String eventPayload = String.format(
                    "{\"type\":\"spontaneous_idea\",\"trigger\":\"%s\","
                    + "\"insight\":\"%s\",\"timestamp\":%d,"
                    + "\"dreamCycle\":%d}",
                    triggerWord,
                    escapeJson(consolidated.length() > 500
                            ? consolidated.substring(0, 500) + "..."
                            : consolidated),
                    System.currentTimeMillis(),
                    totalDreamCycles.get()
            );

            EventBus.instance().broadcast("spontaneous_idea", eventPayload);

            log.info("[DreamDaemon] 检测到自发灵感！触发词: '{}'，洞见: {} chars",
                    triggerWord, consolidated.length());
            System.out.printf("  \u001B[33m[DreamDaemon] ⚡ 自发灵感！触发词='%s' — 事件已广播至所有订阅者\u001B[0m%n",
                    triggerWord);

            SemanticEtw.getInstance().logEvent("DREAM", "SPONTANEOUS_IDEA",
                    "trigger=" + triggerWord + " cycle=" + totalDreamCycles.get());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Public API: Manual Trigger & Diagnostics
    // ════════════════════════════════════════════════════════════════

    /** 手动触发一次梦境周期（用于测试或管理操作）。 */
    public void triggerDreamCycle() {
        log.info("[DreamDaemon] 手动梦境周期已触发");
        dreamCycle();
    }

    /** 获取守护进程统计数据。 */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("running", running.get());
        stats.put("dreaming", dreaming.get());
        stats.put("totalDreamCycles", totalDreamCycles.get());
        stats.put("totalMemoriesConsolidated", totalMemoriesConsolidated.get());
        stats.put("totalSpontaneousIdeas", totalSpontaneousIdeas.get());
        stats.put("retentionThreshold", RETENTION_THRESHOLD);
        stats.put("dreamCycleIntervalSec", DREAM_CYCLE_INTERVAL_SEC);
        return stats;
    }

    // ── Utility ──

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
