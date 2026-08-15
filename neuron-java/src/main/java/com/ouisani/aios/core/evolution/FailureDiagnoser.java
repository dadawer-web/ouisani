package com.ouisani.aios.core.evolution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/**
 * Deterministic failure-to-DSL compiler.  It creates a candidate diagnosis;
 * it never promotes or applies a rule.
 */
public final class FailureDiagnoser {

    public Diagnosis diagnose(FailureTrajectory trajectory) {
        Objects.requireNonNull(trajectory, "failure trajectory must not be null");
        String haystack = String.join(" ", trajectory.failureClass(), trajectory.message(),
                trajectory.target(), String.valueOf(trajectory.observed()),
                String.valueOf(trajectory.expected())).toLowerCase(Locale.ROOT);
        String failureClass = normalizeClass(trajectory.failureClass());
        if (failureClass.isBlank()) {
            failureClass = inferClass(haystack);
        }
        String target = trajectory.target() == null || trajectory.target().isBlank()
                ? inferTarget(failureClass) : trajectory.target().trim().toLowerCase(Locale.ROOT);

        LinkedHashMap<String, Object> match = new LinkedHashMap<>();
        match.put("failure_class", failureClass);
        if (!trajectory.observed().isEmpty()) match.put("observed", trajectory.observed());
        if (!trajectory.expected().isEmpty()) match.put("expected", trajectory.expected());
        if (!trajectory.metadata().isEmpty()) match.put("metadata", trajectory.metadata());

        LinkedHashMap<String, Object> action = new LinkedHashMap<>();
        action.put("kind", "evidence_guard");
        action.put("target", target);
        action.put("requires_gate", true);
        action.put("apply_from_next_split", true);

        return new Diagnosis(failureClass, target, match, action,
                trajectory.runId().isBlank() ? List.of() : List.of(trajectory.runId()),
                trajectory.message(), List.of());
    }

    public EvoAsset draft(FailureTrajectory trajectory, EvaluationSplit split) {
        Diagnosis diagnosis = diagnose(trajectory);
        return EvoAsset.candidate(newId(), diagnosis, split);
    }

    private static String newId() {
        return "evo-" + java.util.UUID.randomUUID();
    }

    private static String normalizeClass(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String inferClass(String haystack) {
        if (containsAny(haystack, "temporal", "timestamp", "stale", "before", "after", "time")) {
            return "TEMPORAL_GROUNDING";
        }
        if (containsAny(haystack, "retriev", "memory", "evidence", "grounding", "conflict")) {
            return "RETRIEVAL_GROUNDING";
        }
        if (containsAny(haystack, "verif", "predicate", "schema", "artifact")) {
            return "VERIFICATION";
        }
        if (containsAny(haystack, "tool", "execution", "timeout", "permission")) {
            return "TOOL_EXECUTION";
        }
        return "UNKNOWN";
    }

    private static String inferTarget(String failureClass) {
        return switch (failureClass) {
            case "TEMPORAL_GROUNDING", "RETRIEVAL_GROUNDING" -> "retriever";
            case "VERIFICATION" -> "verifier";
            case "TOOL_EXECUTION" -> "tool_runner";
            default -> "workflow";
        };
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    /** Input captured from one failed run; it is intentionally data-only. */
    public record FailureTrajectory(
            String runId,
            String splitId,
            String failureClass,
            String target,
            String message,
            Map<String, Object> observed,
            Map<String, Object> expected,
            Map<String, Object> metadata
    ) {
        public FailureTrajectory {
            runId = runId == null ? "" : runId.trim();
            splitId = splitId == null ? "" : splitId.trim();
            failureClass = failureClass == null ? "" : failureClass.trim();
            target = target == null ? "" : target.trim();
            message = message == null ? "" : message.trim();
            observed = copy(observed);
            expected = copy(expected);
            metadata = copy(metadata);
        }

        public static FailureTrajectory of(String runId, String splitId,
                                           String failureClass, String target,
                                           String message) {
            return new FailureTrajectory(runId, splitId, failureClass, target, message,
                    Map.of(), Map.of(), Map.of());
        }

        private static Map<String, Object> copy(Map<String, Object> value) {
            return value == null || value.isEmpty() ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(value));
        }
    }

    /** Output of diagnosis, before it becomes a candidate EvoAsset. */
    public record Diagnosis(
            String failureClass,
            String target,
            Map<String, Object> match,
            Map<String, Object> action,
            List<String> runIds,
            String reason,
            List<String> compatibleAssets
    ) {
        public Diagnosis {
            failureClass = failureClass == null || failureClass.isBlank()
                    ? "UNKNOWN" : failureClass.trim().toUpperCase(Locale.ROOT);
            target = target == null || target.isBlank() ? "workflow" : target.trim().toLowerCase(Locale.ROOT);
            match = match == null || match.isEmpty() ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(match));
            action = action == null || action.isEmpty() ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(action));
            runIds = runIds == null ? List.of() : List.copyOf(runIds);
            reason = reason == null ? "" : reason.trim();
            compatibleAssets = compatibleAssets == null ? List.of() : List.copyOf(compatibleAssets);
        }
    }
}
