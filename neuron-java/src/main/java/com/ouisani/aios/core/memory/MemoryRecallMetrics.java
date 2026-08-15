package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.core.trace.TraceSpan;
import com.ouisani.aios.core.trace.TracingManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Best-effort recall metrics reporter.
 *
 * <p>The reporter keeps the recall metrics used by the lifecycle: hit count,
 * top score, latency, strategy,
 * injected token estimate, citation/unused rates, isolation filtering, and
 * untrusted-source rejection. Metrics are sent to the existing Telemetry and
 * Trace channels and are never allowed to affect recall or an Agent turn.</p>
 *
 * <p>Task-success improvement is an observational lift: completed turns with
 * at least one recalled memory are compared with completed turns without a
 * hit for the same agent. It is deliberately labelled as correlation rather
 * than causal attribution.</p>
 */
public final class MemoryRecallMetrics {

    public static final String RECALL_EVENT = "memory.recall.metrics";
    public static final String OUTCOME_EVENT = "memory.recall.outcome";

    private static final Pattern MARKUP = Pattern.compile("<[^>]{0,256}>");
    private static final Pattern TOKEN_SPLIT = Pattern.compile(
            "[\\p{Z}\\p{Punct}\\d]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MAX_TRACKED_AGENTS = 4_096;
    private static final ConcurrentHashMap<String, OutcomeStats> OUTCOMES =
            new ConcurrentHashMap<>();

    private MemoryRecallMetrics() {
    }

    /** Immutable view of one recall operation's metrics. */
    public record RecallSnapshot(
            int hitCount,
            double topScore,
            long latencyMs,
            String strategy,
            long injectedTokenCount,
            double citationRate,
            double uselessRecallRate,
            int permissionFilteredCount,
            int untrustedRejectedCount,
            boolean partial,
            boolean timedOut,
            String errorCode) {

        public RecallSnapshot {
            hitCount = Math.max(0, hitCount);
            topScore = Double.isFinite(topScore) ? Math.max(0.0, Math.min(1.0, topScore)) : 0.0;
            latencyMs = Math.max(0L, latencyMs);
            strategy = strategy == null ? "" : strategy;
            injectedTokenCount = Math.max(0L, injectedTokenCount);
            citationRate = clamp01(citationRate);
            uselessRecallRate = clamp01(uselessRecallRate);
            permissionFilteredCount = Math.max(0, permissionFilteredCount);
            untrustedRejectedCount = Math.max(0, untrustedRejectedCount);
            errorCode = errorCode == null ? "" : errorCode;
        }

        public Map<String, Object> asMetadata() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("recall_hit_count", hitCount);
            if (hitCount > 0) metadata.put("recall_top_score", topScore);
            metadata.put("recall_latency_ms", latencyMs);
            metadata.put("recall_strategy", strategy);
            metadata.put("recall_strategy_code", strategyCode(strategy));
            metadata.put("recall_injected_token_count", injectedTokenCount);
            metadata.put("recall_citation_rate", citationRate);
            metadata.put("recall_useless_rate", uselessRecallRate);
            metadata.put("recall_permission_filtered_count", permissionFilteredCount);
            metadata.put("recall_untrusted_rejected_count", untrustedRejectedCount);
            metadata.put("recall_partial", partial);
            metadata.put("recall_timed_out", timedOut);
            if (!errorCode.isBlank()) metadata.put("recall_error_code", errorCode);
            return metadata;
        }
    }

    /** Metrics emitted when a completed/failed turn supplies an answer. */
    public record OutcomeSnapshot(
            double citationRate,
            double uselessRecallRate,
            boolean taskSucceeded,
            boolean improvedTaskSuccess,
            boolean baselineAvailable,
            double taskSuccessLift,
            long recalledTurnCount,
            long recalledSuccessCount,
            long unrecalledTurnCount,
            long unrecalledSuccessCount) {

        public Map<String, Object> asMetadata() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("recall_citation_rate", citationRate);
            metadata.put("recall_useless_rate", uselessRecallRate);
            metadata.put("recall_task_succeeded", taskSucceeded);
            metadata.put("recall_improved_task_success", improvedTaskSuccess);
            metadata.put("recall_success_baseline_available", baselineAvailable);
            metadata.put("recall_task_success_lift", taskSuccessLift);
            metadata.put("recall_recalled_turn_count", recalledTurnCount);
            metadata.put("recall_recalled_success_count", recalledSuccessCount);
            metadata.put("recall_unrecalled_turn_count", unrecalledTurnCount);
            metadata.put("recall_unrecalled_success_count", unrecalledSuccessCount);
            metadata.put("recall_task_success_rate_with_memory", recalledTurnCount == 0
                    ? 0.0 : (double) recalledSuccessCount / recalledTurnCount);
            metadata.put("recall_task_success_rate_without_memory", unrecalledTurnCount == 0
                    ? 0.0 : (double) unrecalledSuccessCount / unrecalledTurnCount);
            return metadata;
        }
    }

    /** Report one recall operation to Telemetry, Trace, and the result's caller. */
    public static RecallSnapshot recordRecall(MemoryRecallHook.RecallRequest request,
                                              MemoryRecallHook.RecallResult result,
                                              long latencyMs) {
        try {
            MemoryRecallHook.RecallResult safeResult = result == null
                    ? MemoryRecallHook.RecallResult.unavailable(
                    "missing_recall_result", "metrics", "recall result was null") : result;
            RecallSnapshot snapshot = new RecallSnapshot(
                    safeResult.records().size(),
                    safeResult.topScore(),
                    latencyMs,
                    safeResult.effectiveStrategy(),
                    estimateTokens(safeResult.context()),
                    0.0,
                    0.0,
                    safeResult.permissionFilteredCount(),
                    safeResult.untrustedRejectedCount(),
                    safeResult.partial(),
                    safeResult.timedOut(),
                    safeResult.error() == null ? "" : safeResult.error().code());
            Map<String, Object> metadata = new HashMap<>(snapshot.asMetadata());
            if (request != null) {
                metadata.put("agentId", request.agentId());
                if (request.tenantId() != null) metadata.put("tenantId", request.tenantId());
                if (request.workflowId() != null) metadata.put("workflowId", request.workflowId());
                if (request.sessionId() != null) metadata.put("sessionId", request.sessionId());
            }
            emitTelemetry(RECALL_EVENT, metadata);
            attachTrace("memory.recall", snapshot.asMetadata());
            return snapshot;
        } catch (RuntimeException ignored) {
            // Observability is strictly best-effort.
            return new RecallSnapshot(0, 0.0, latencyMs, "error", 0,
                    0.0, 0.0, 0, 0, true, false, "metrics_failed");
        }
    }

    /**
     * Correlate the recalled records with the assistant answer and task
     * outcome. Citation is intentionally conservative and deterministic: an
     * exact normalized phrase or at least two meaningful terms is required.
     */
    public static OutcomeSnapshot recordOutcome(String agentId, String turnId,
                                                MemoryRecallHook.RecallResult result,
                                                String assistantResponse,
                                                boolean taskSucceeded) {
        try {
            MemoryRecallHook.RecallResult safeResult = result == null
                    ? MemoryRecallHook.RecallResult.unavailable(
                    "missing_recall_result", "outcome", "recall result was not associated with the turn") : result;
            double citationRate = citationRate(safeResult, assistantResponse);
            double uselessRate = safeResult.records().isEmpty() ? 0.0 : 1.0 - citationRate;
            String key = agentId == null || agentId.isBlank() ? "unknown" : agentId;
            OutcomeStats stats = outcomeStats(key);
            boolean recalled = !safeResult.records().isEmpty();
            if (recalled) {
                stats.recalledTurns.incrementAndGet();
                if (taskSucceeded) stats.recalledSuccesses.incrementAndGet();
            } else {
                stats.unrecalledTurns.incrementAndGet();
                if (taskSucceeded) stats.unrecalledSuccesses.incrementAndGet();
            }
            long recalledTurns = stats.recalledTurns.get();
            long recalledSuccesses = stats.recalledSuccesses.get();
            long unrecalledTurns = stats.unrecalledTurns.get();
            long unrecalledSuccesses = stats.unrecalledSuccesses.get();
            boolean baseline = unrecalledTurns > 0;
            double recalledRate = recalledTurns == 0 ? 0.0
                    : (double) recalledSuccesses / recalledTurns;
            double unrecalledRate = unrecalledTurns == 0 ? 0.0
                    : (double) unrecalledSuccesses / unrecalledTurns;
            double lift = recalledRate - unrecalledRate;
            boolean improved = baseline && recalledTurns > 0 && lift > 0.0;
            OutcomeSnapshot snapshot = new OutcomeSnapshot(citationRate, uselessRate,
                    taskSucceeded, improved, baseline, lift, recalledTurns,
                    recalledSuccesses, unrecalledTurns, unrecalledSuccesses);
            Map<String, Object> metadata = new HashMap<>(snapshot.asMetadata());
            if (agentId != null) metadata.put("agentId", agentId);
            if (turnId != null) metadata.put("turnId", turnId);
            emitTelemetry(OUTCOME_EVENT, metadata);
            attachTrace("memory.recall.outcome", snapshot.asMetadata());
            return snapshot;
        } catch (RuntimeException ignored) {
            return new OutcomeSnapshot(0.0, 0.0, taskSucceeded,
                    false, false, 0.0, 0, 0, 0, 0);
        }
    }

    /** Citation ratio used by dashboards and tests. */
    public static double citationRate(MemoryRecallHook.RecallResult result,
                                      String assistantResponse) {
        if (result == null || result.hits().isEmpty()
                || assistantResponse == null || assistantResponse.isBlank()) return 0.0;
        String response = normalize(assistantResponse);
        int cited = 0;
        for (MemoryRecallHook.RecallHit hit : result.hits()) {
            String content = normalize(hit.record().content());
            if (content.isBlank()) continue;
            if (response.contains(content) || meaningfulOverlap(content, response)) cited++;
        }
        return (double) cited / result.hits().size();
    }

    /** Estimate injected model tokens without coupling memory to an LLM SDK. */
    public static long estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0L;
        int codePoints = text.codePointCount(0, text.length());
        return Math.max(1L, (codePoints + 3L) / 4L);
    }

    /** Clear rolling success baselines in isolated tests or process reset. */
    public static void resetOutcomeBaselines() {
        OUTCOMES.clear();
    }

    private static OutcomeStats outcomeStats(String agentId) {
        if (OUTCOMES.size() >= MAX_TRACKED_AGENTS && !OUTCOMES.containsKey(agentId)) {
            // Avoid unbounded cardinality from attacker-controlled agent IDs.
            return new OutcomeStats();
        }
        return OUTCOMES.computeIfAbsent(agentId, ignored -> new OutcomeStats());
    }

    private static boolean meaningfulOverlap(String content, String response) {
        List<String> terms = terms(content);
        if (terms.isEmpty()) return false;
        int matches = 0;
        for (String term : terms) {
            if (term.length() >= 2 && response.contains(term)) matches++;
        }
        return matches >= Math.min(2, terms.size());
    }

    private static List<String> terms(String value) {
        List<String> terms = new ArrayList<>();
        for (String token : TOKEN_SPLIT.split(value)) {
            if (token.length() >= 2 && !terms.contains(token)) terms.add(token);
        }
        return terms;
    }

    private static String normalize(String value) {
        return value == null ? "" : MARKUP.matcher(value)
                .replaceAll(" ").replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int strategyCode(String strategy) {
        if (strategy == null) return -1;
        return switch (strategy) {
            case "skipped" -> 0;
            case "keyword", "keyword-fallback" -> 1;
            case "embedding" -> 2;
            case "hybrid" -> 3;
            default -> -1;
        };
    }

    private static void emitTelemetry(String eventName, Map<String, Object> metadata) {
        try {
            TelemetryService.instance().logEvent(eventName, metadata);
        } catch (RuntimeException ignored) {
            // Metrics must never turn a successful turn into a failed one.
        }
    }

    private static void attachTrace(String eventName, Map<String, Object> metrics) {
        try {
            TracingManager tracing = TracingManager.instance();
            TraceSpan current = tracing.currentSpan();
            if (current == null) return;
            for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                current.setAttribute(entry.getKey(), entry.getValue());
            }
            current.addEvent(eventName, metrics);
        } catch (RuntimeException ignored) {
            // Metrics/tracing are best-effort.
        }
    }

    private static final class OutcomeStats {
        private final AtomicLong recalledTurns = new AtomicLong();
        private final AtomicLong recalledSuccesses = new AtomicLong();
        private final AtomicLong unrecalledTurns = new AtomicLong();
        private final AtomicLong unrecalledSuccesses = new AtomicLong();
    }
}
