package com.ouisani.aios.core.cache.eviction;

import com.ouisani.aios.core.cache.SemanticCacheManager.CacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bionic Cognitive Strategy — memory eviction modeled on the human brain.
 * <p>
 * This strategy replaces the cold utilitarianism of LRU/LFU with a
 * biologically-inspired memory model:
 * <p>
 * <h3>1. Ebbinghaus Forgetting Curve (时间衰减因子)</h3>
 * Memory strength decays exponentially over time:
 * <pre>
 *   R(t) = e^(-t / S)
 * </pre>
 * Where {@code R} is retention, {@code t} is elapsed time since last access,
 * and {@code S} is the memory stability (how "strongly" the memory was encoded).
 * Entries with low retention are candidates for forgetting (eviction).
 * <p>
 * <h3>2. Activation Weight (激活权重)</h3>
 * Each time a cache entry is retrieved (cache hit), its activation weight
 * increases — modeling the neuroscience principle that "neurons that fire
 * together wire together." Frequently accessed entries develop strong
 * synaptic weights and resist forgetting.
 * <pre>
 *   activation = baseActivation * (1 + accessCount * reinforcementFactor)
 * </pre>
 * <p>
 * <h3>3. Emotional Valence (情绪标签)</h3>
 * Entries may carry an "emotion" tag in their metadata (e.g., "critical",
 * "positive", "neutral"). Entries tagged as "critical" receive a stability
 * boost, modeling how emotionally charged memories are more resistant to
 * forgetting in human cognition.
 * <p>
 * <h3>Composite Score</h3>
 * The final eviction priority is computed as:
 * <pre>
 *   evictionScore = retention * activation * emotionMultiplier
 * </pre>
 * Entries with the <b>lowest</b> evictionScore are evicted first — they are
 * the "forgotten memories" of the AIOS cognitive substrate.
 */
public final class BionicCognitiveStrategy implements MemoryEvictionStrategy {

    private static final Logger log = LoggerFactory.getLogger(BionicCognitiveStrategy.class);

    // ── Ebbinghaus Curve Parameters ──

    /** Memory stability constant (milliseconds). Higher = slower forgetting. */
    private final long stabilityConstantMs;

    /** Base activation for newly encoded memories. */
    private final double baseActivation;

    /** Reinforcement factor per access — models synaptic potentiation. */
    private final double reinforcementFactor;

    /** Emotion multiplier for "critical" tagged entries. */
    private final double criticalEmotionMultiplier;

    /** Emotion multiplier for "positive" tagged entries. */
    private final double positiveEmotionMultiplier;

    /** Default emotion multiplier (neutral / no tag). */
    private final double neutralEmotionMultiplier;

    /** Fraction of entries to evict when capacity is exceeded. */
    private final double evictionRatio;

    public BionicCognitiveStrategy() {
        this(3600_000L,  // 1 hour stability
             1.0,        // base activation
             0.5,        // reinforcement per access
             3.0,        // critical emotion boost
             1.5,        // positive emotion boost
             1.0,        // neutral (no boost)
             0.20);      // evict 20%
    }

    public BionicCognitiveStrategy(long stabilityConstantMs,
                                   double baseActivation,
                                   double reinforcementFactor,
                                   double criticalEmotionMultiplier,
                                   double positiveEmotionMultiplier,
                                   double neutralEmotionMultiplier,
                                   double evictionRatio) {
        this.stabilityConstantMs = stabilityConstantMs;
        this.baseActivation = baseActivation;
        this.reinforcementFactor = reinforcementFactor;
        this.criticalEmotionMultiplier = criticalEmotionMultiplier;
        this.positiveEmotionMultiplier = positiveEmotionMultiplier;
        this.neutralEmotionMultiplier = neutralEmotionMultiplier;
        this.evictionRatio = evictionRatio;
    }

    @Override
    public List<CacheEntry> selectForEviction(List<CacheEntry> entries, int capacity) {
        if (entries.size() <= capacity) {
            return List.of();
        }

        int overflow = entries.size() - capacity;
        int toEvict = Math.max(overflow, (int) Math.ceil(entries.size() * evictionRatio));
        long now = System.currentTimeMillis();

        // Sort by evictionScore ASC — lowest scores are "forgotten" first
        List<CacheEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingDouble(e -> computeEvictionScore(e, now)));

        List<CacheEntry> victims = sorted.subList(0, Math.min(toEvict, sorted.size()));

        if (log.isDebugEnabled()) {
            for (CacheEntry v : victims) {
                double score = computeEvictionScore(v, now);
                log.debug("[Bionic Eviction] Victim: retention={:.4f}, activation={:.4f}, emotion={:.2f}, score={:.6f}",
                        computeRetention(v, now), computeActivation(v), computeEmotionMultiplier(v), score);
            }
        }

        log.info("[Bionic Eviction] capacity={}, entries={}, toEvict={}, victims={}",
                capacity, entries.size(), toEvict, victims.size());

        return new ArrayList<>(victims);
    }

    @Override
    public void onAccess(CacheEntry entry) {
        // Activation weight is computed from accessCount at eviction time,
        // so no separate bookkeeping is needed. The CacheEntry's accessCount
        // and lastAccessTime are updated by the cache manager.
        log.trace("[Bionic Eviction] Memory reactivated: activation={:.4f}, retention={:.4f}",
                computeActivation(entry), computeRetention(entry, System.currentTimeMillis()));
    }

    @Override
    public String strategyName() {
        return "BionicCognitive(Ebbinghaus+Activation)";
    }

    // ════════════════════════════════════════════════════════════════
    //  Cognitive Scoring Engine
    // ════════════════════════════════════════════════════════════════

    /**
     * Compute the composite eviction score.
     * <p>
     * Lower score = higher eviction priority (the memory is "forgotten").
     */
    double computeEvictionScore(CacheEntry entry, long now) {
        double retention = computeRetention(entry, now);
        double activation = computeActivation(entry);
        double emotion = computeEmotionMultiplier(entry);
        return retention * activation * emotion;
    }

    /**
     * Ebbinghaus forgetting curve: R(t) = e^(-t/S)
     *
     * @return retention value in [0, 1]. 1 = perfectly retained, 0 = fully forgotten
     */
    public double computeRetention(CacheEntry entry, long now) {
        long elapsedMs = Math.max(0, now - entry.lastAccessTime());
        double t = elapsedMs / (double) stabilityConstantMs;
        return Math.exp(-t);
    }

    /**
     * Activation weight: baseActivation * (1 + accessCount * reinforcementFactor)
     * <p>
     * Models synaptic potentiation — each retrieval strengthens the memory trace.
     */
    double computeActivation(CacheEntry entry) {
        return baseActivation * (1.0 + entry.accessCount() * reinforcementFactor);
    }

    /**
     * Emotion multiplier based on the "emotion" metadata tag.
     * <p>
     * Emotionally charged memories resist forgetting — this is a direct
     * analog of the amygdala's modulation of hippocampal memory consolidation.
     */
    double computeEmotionMultiplier(CacheEntry entry) {
        if (entry.metadata() == null) {
            return neutralEmotionMultiplier;
        }
        Object emotion = entry.metadata().get("emotion");
        if (emotion == null) {
            return neutralEmotionMultiplier;
        }
        String tag = emotion.toString().toLowerCase();
        return switch (tag) {
            case "critical", "urgent", "important" -> criticalEmotionMultiplier;
            case "positive", "reward", "success" -> positiveEmotionMultiplier;
            default -> neutralEmotionMultiplier;
        };
    }
}
