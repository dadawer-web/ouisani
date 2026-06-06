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
 * Cognitive Dream Daemon — the AIOS kernel's subconscious mind.
 * <p>
 * In a traditional OS, the idle task (PID 0) simply halts the CPU
 * to save power. In AIOS, the idle task <b>dreams</b>.
 * <p>
 * <h3>Biological Inspiration: Sleep & Memory Consolidation</h3>
 * During human sleep, the hippocampus replays the day's experiences
 * and transfers important memories to the neocortex for long-term
 * storage. This process — called <b>memory consolidation</b> — is
 * what transforms fragile short-term memories into stable long-term
 * knowledge.
 * <p>
 * The CognitiveDreamDaemon implements this exact mechanism:
 * <ol>
 *   <li><b>Scan</b>: Identifies short-term memories in the SemanticCache
 *       that are decaying (low retention score per the Ebbinghaus curve)</li>
 *   <li><b>Consolidate</b>: Uses a low-cost LLM call to summarize and
 *       extract causal relationships from these fragments</li>
 *   <li><b>Persist</b>: Writes the consolidated experience as an embedding
 *       into the VFS VectorNode at {@code /var/db/memory}</li>
 *   <li><b>Evict</b>: Releases the original cache entries, freeing
 *       cognitive capacity for new experiences</li>
 * </ol>
 * <p>
 * <h3>Spontaneous Ideas</h3>
 * During consolidation, if the daemon discovers a contradiction,
 * unresolved problem, or novel insight, it fires a
 * {@code spontaneous_idea} event via the EventBus — an asynchronous
 * interrupt that can wake a sleeping Agent to act on the insight.
 * This is the AIOS equivalent of a "eureka moment" during sleep.
 * <p>
 * <h3>Scheduling</h3>
 * The daemon runs at {@code ProcessPriority.IDLE} — it only executes
 * when no HIGH or NORMAL priority agents are active. The
 * {@link com.ouisani.aios.core.TaskScheduler TaskScheduler} triggers
 * this daemon when it detects system idle state.
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

    /** How often to check for idle state and trigger a dream cycle (seconds). */
    private static final long DREAM_CYCLE_INTERVAL_SEC = 30;

    /** Minimum number of decaying entries to trigger consolidation. */
    private static final int MIN_DECAYING_ENTRIES = 2;

    /** Retention threshold below which a cache entry is considered "decaying". */
    private static final double RETENTION_THRESHOLD = 0.3;

    /** VFS path for persistent memory storage. */
    private static final String MEMORY_DB_PATH = "/var/db/memory";

    /** LLM prompt for memory consolidation. */
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

    /**
     * Configure the daemon with an LLM provider for consolidation.
     */
    public void configure(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
        log.info("[DreamDaemon] Configured with LlmProvider: {}",
                llmProvider != null ? llmProvider.name() : "null");
    }

    /**
     * Start the dream daemon's periodic cycle.
     */
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

        log.info("[DreamDaemon] Started. Dream cycle interval: {}s", DREAM_CYCLE_INTERVAL_SEC);
        System.out.println("  \u001B[35m[DreamDaemon] Cognitive dream daemon started. The system now dreams.\u001B[0m");
    }

    /**
     * Stop the dream daemon.
     */
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

        log.info("[DreamDaemon] Stopped. Stats: cycles={}, consolidated={}, ideas={}",
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
     * Execute one dream cycle: scan → consolidate → persist → evict.
     * <p>
     * This is the AIOS equivalent of a single REM sleep cycle.
     * The daemon scans the SemanticCache for decaying memories,
     * consolidates them via LLM, persists the result, and releases
     * the original entries.
     */
    private void dreamCycle() {
        if (!running.get() || dreaming.get()) return;

        // ── Idle Check: only dream when the system is idle ──
        TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
        if (scheduler != null && !scheduler.isSystemIdle()) {
            log.debug("[DreamDaemon] System not idle (active: {}), skipping dream cycle",
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
                log.debug("[DreamDaemon] Not enough decaying memories ({}/{}), skipping dream cycle",
                        decaying.size(), MIN_DECAYING_ENTRIES);
                return;
            }

            log.info("[DreamDaemon] Dream cycle #{}: found {} decaying memories",
                    totalDreamCycles.get(), decaying.size());
            System.out.printf("  \u001B[35m[DreamDaemon] Dream cycle #%d: scanning %d decaying memories...\u001B[0m%n",
                    totalDreamCycles.get(), decaying.size());

            // ── Phase 2: Consolidate via LLM ──
            String consolidated = consolidateMemories(decaying);

            if (consolidated == null || consolidated.isBlank()) {
                log.warn("[DreamDaemon] Consolidation produced no output, aborting dream cycle");
                return;
            }

            // ── Phase 3: Persist to /var/db/memory (VectorNode) ──
            persistConsolidatedMemory(consolidated);

            // ── Phase 4: Evict consolidated entries from cache ──
            evictConsolidatedEntries(cacheMgr, decaying);

            totalMemoriesConsolidated.addAndGet(decaying.size());

            log.info("[DreamDaemon] Dream cycle #{} complete: {} memories consolidated, {} chars persisted",
                    totalDreamCycles.get(), decaying.size(), consolidated.length());

            // ── Phase 5: Check for spontaneous ideas ──
            checkForSpontaneousIdeas(consolidated);

        } catch (Exception e) {
            log.error("[DreamDaemon] Dream cycle error: {}", e.getMessage(), e);
        } finally {
            dreaming.set(false);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 1: Scan for Decaying Memories
    // ════════════════════════════════════════════════════════════════

    /**
     * Scan the SemanticCache for entries whose retention score
     * (per the Ebbinghaus forgetting curve) has fallen below the
     * threshold — these are the "fading memories" that need
     * consolidation before they're lost forever.
     */
    private List<SemanticCacheManager.CacheEntry> scanDecayingMemories(SemanticCacheManager cacheMgr) {
        List<SemanticCacheManager.CacheEntry> decaying = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (SemanticCacheManager.CacheEntry entry : cacheMgr.getCacheEntries()) {
            double retention = bionicStrategy.computeRetention(entry, now);
            if (retention < RETENTION_THRESHOLD) {
                decaying.add(entry);
                log.debug("[DreamDaemon] Decaying memory: retention={:.4f}, age={}ms, accessCount={}",
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
     * Consolidate decaying memories by asking the LLM to summarize
     * and extract causal relationships.
     * <p>
     * This is the neural equivalent of hippocampal replay during
     * slow-wave sleep: the brain reactivates recent experiences,
     * extracts their gist, and transfers them to the neocortex.
     * <p>
     * We use a low-cost LLM call (or the fast_model if available)
     * to keep the cognitive overhead minimal.
     */
    private String consolidateMemories(List<SemanticCacheManager.CacheEntry> entries) {
        if (llmProvider == null) {
            log.warn("[DreamDaemon] No LlmProvider configured, cannot consolidate");
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
            log.info("[DreamDaemon] LLM consolidation complete: {} chars", result != null ? result.length() : 0);
            System.out.printf("  \u001B[35m[DreamDaemon] Memory consolidation: %d fragments → %d chars of distilled experience\u001B[0m%n",
                    entries.size(), result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("[DreamDaemon] LLM consolidation failed: {}", e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 3: Persist to /var/db/memory (Neocortical Storage)
    // ════════════════════════════════════════════════════════════════

    /**
     * Persist the consolidated experience into the VFS VectorNode
     * at {@code /var/db/memory}.
     * <p>
     * This is the neural equivalent of transferring a memory from
     * the hippocampus (short-term) to the neocortex (long-term).
     * The VectorNode stores both the text and its embedding, enabling
     * future semantic retrieval.
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
                log.info("[DreamDaemon] Persisted consolidated memory to {} ({} chars)",
                        MEMORY_DB_PATH, consolidated.length());
                System.out.printf("  \u001B[35m[DreamDaemon] Memory persisted to %s (%d chars) → long-term storage\u001B[0m%n",
                        MEMORY_DB_PATH, consolidated.length());
            } else {
                log.warn("[DreamDaemon] VectorNode not found at {}, falling back to VFS file write", MEMORY_DB_PATH);
                // Fallback: write as a plain text file
                String fallbackPath = MEMORY_DB_PATH + "/dream_log_" + System.currentTimeMillis();
                vfs.mount(fallbackPath, "dream_log",
                        new com.ouisani.aios.vfs.MutableFileNode(fallbackPath));
                vfs.resolve(fallbackPath).ifPresent(node -> node.write(consolidated));
            }
        } catch (Exception e) {
            log.error("[DreamDaemon] Failed to persist memory: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 4: Evict Consolidated Entries
    // ════════════════════════════════════════════════════════════════

    /**
     * Remove the consolidated entries from the SemanticCache.
     * <p>
     * After consolidation, the original short-term memories are no
     * longer needed — their essence has been captured in the
     * long-term VectorNode. Freeing them makes room for new experiences.
     */
    private void evictConsolidatedEntries(SemanticCacheManager cacheMgr,
                                          List<SemanticCacheManager.CacheEntry> entries) {
        // The SemanticCacheManager doesn't have a direct remove method,
        // so we mark them as very low priority for the next eviction cycle
        for (SemanticCacheManager.CacheEntry entry : entries) {
            entry.meta("consolidated", true);
            entry.meta("consolidation_time", System.currentTimeMillis());
        }

        log.info("[DreamDaemon] Marked {} entries as consolidated (will be evicted on next cycle)",
                entries.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 5: Spontaneous Idea Detection
    // ════════════════════════════════════════════════════════════════

    /**
     * Analyze the consolidated memory for contradictions, unresolved
     * problems, or novel insights. If found, fire a
     * {@code spontaneous_idea} event via the EventBus.
     * <p>
     * This is the AIOS equivalent of a "eureka moment" during sleep —
     * the subconscious mind discovers something important and wakes
     * the conscious mind to act on it.
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

            log.info("[DreamDaemon] SPONTANEOUS IDEA detected! Trigger: '{}', insight: {} chars",
                    triggerWord, consolidated.length());
            System.out.printf("  \u001B[33m[DreamDaemon] ⚡ SPONTANEOUS IDEA! Trigger='%s' — event broadcast to all subscribers\u001B[0m%n",
                    triggerWord);

            SemanticEtw.getInstance().logEvent("DREAM", "SPONTANEOUS_IDEA",
                    "trigger=" + triggerWord + " cycle=" + totalDreamCycles.get());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Public API: Manual Trigger & Diagnostics
    // ════════════════════════════════════════════════════════════════

    /**
     * Manually trigger a dream cycle (for testing or admin use).
     */
    public void triggerDreamCycle() {
        log.info("[DreamDaemon] Manual dream cycle triggered");
        dreamCycle();
    }

    /**
     * Get daemon statistics.
     */
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
