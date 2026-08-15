package com.ouisani.aios.core.evolution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, versioned rule produced from a diagnosed failure.
 *
 * <p>An EvoAsset is a candidate until all gates have passed.  The action DSL
 * is data only: consumers must obtain rules through
 * {@link EvoAssetPipeline#activeAssets(String)}, which returns promoted rules
 * whose effective split is later than the source split.</p>
 */
public record EvoAsset(
        String id,
        String failureClass,
        String target,
        Map<String, Object> match,
        Map<String, Object> action,
        List<String> createdFromRuns,
        String evaluationSplit,
        GateResults gateResults,
        List<String> compatibleAssets,
        Status status,
        int version,
        int sourceSplitOrdinal,
        int effectiveFromSplitOrdinal,
        long createdAtEpochMs,
        String rollbackReason
) {

    public enum Status {
        CANDIDATE,
        TARGETED_PASSED,
        GLOBAL_PASSED,
        STACK_CONFIRMED,
        SHADOW,
        CANARY,
        PROMOTED,
        ROLLED_BACK,
        REJECTED;

        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Status fromJson(String value) {
            if (value == null || value.isBlank()) return CANDIDATE;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("unknown evo asset status: " + value, error);
            }
        }
    }

    public EvoAsset {
        id = normalizeId(id);
        failureClass = normalizeToken(failureClass, "failureClass");
        target = normalizeTarget(target);
        match = immutableMap(match);
        action = immutableMap(action);
        createdFromRuns = immutableStrings(createdFromRuns);
        evaluationSplit = normalizeSplit(evaluationSplit);
        gateResults = gateResults == null ? GateResults.empty() : gateResults;
        compatibleAssets = immutableIds(compatibleAssets, id);
        status = status == null ? Status.CANDIDATE : status;
        if (version <= 0) throw new IllegalArgumentException("asset version must be > 0");
        if (sourceSplitOrdinal < 0) {
            throw new IllegalArgumentException("source split ordinal must be >= 0");
        }
        if (effectiveFromSplitOrdinal <= sourceSplitOrdinal) {
            throw new IllegalArgumentException(
                    "effective split must be strictly later than source split");
        }
        if (createdAtEpochMs <= 0) createdAtEpochMs = Instant.now().toEpochMilli();
        rollbackReason = rollbackReason == null ? "" : rollbackReason.trim();
    }

    /** Convenience constructor for a hand-authored candidate DSL. */
    public EvoAsset(String id, String failureClass, String target,
                    Map<String, Object> match, Map<String, Object> action,
                    List<String> createdFromRuns, String evaluationSplit,
                    GateResults gateResults, List<String> compatibleAssets,
                    Status status) {
        this(id, failureClass, target, match, action, createdFromRuns, evaluationSplit,
                gateResults, compatibleAssets, status, 1, 0, 1,
                Instant.now().toEpochMilli(), "");
    }

    public static EvoAsset candidate(String id, FailureDiagnoser.Diagnosis diagnosis,
                                     EvaluationSplit sourceSplit) {
        Objects.requireNonNull(diagnosis, "diagnosis must not be null");
        Objects.requireNonNull(sourceSplit, "source split must not be null");
        return new EvoAsset(id, diagnosis.failureClass(), diagnosis.target(),
                diagnosis.match(), diagnosis.action(), diagnosis.runIds(), sourceSplit.id(),
                GateResults.empty(), diagnosis.compatibleAssets(), Status.CANDIDATE,
                1, sourceSplit.ordinal(), sourceSplit.ordinal() + 1,
                Instant.now().toEpochMilli(), "");
    }

    public static EvoAsset candidate(String id, String failureClass, String target,
                                     Map<String, Object> match,
                                     Map<String, Object> action,
                                     List<String> createdFromRuns,
                                     EvaluationSplit sourceSplit) {
        return new EvoAsset(id, failureClass, target, match, action, createdFromRuns,
                sourceSplit.id(), GateResults.empty(), List.of(), Status.CANDIDATE,
                1, sourceSplit.ordinal(), sourceSplit.ordinal() + 1,
                Instant.now().toEpochMilli(), "");
    }

    public EvoAsset withGate(EvoGateOutcome outcome) {
        Objects.requireNonNull(outcome, "gate outcome must not be null");
        Status next = statusAfterGate(outcome);
        return copy(next, gateResults.with(outcome), "");
    }

    public EvoAsset withStatus(Status nextStatus) {
        return copy(nextStatus, gateResults, nextStatus == Status.ROLLED_BACK
                ? rollbackReason : "");
    }

    public EvoAsset rollback(String reason) {
        String message = reason == null || reason.isBlank() ? "rolled_back" : reason.trim();
        return copy(Status.ROLLED_BACK, gateResults, message);
    }

    public boolean isApplicable(EvaluationSplit split) {
        return split != null && status == Status.PROMOTED
                && split.ordinal() >= effectiveFromSplitOrdinal;
    }

    public String memoryAssetId() {
        return "evo:" + id;
    }

    public String toDsl() {
        return EvoAssetDsl.toJson(this);
    }

    private EvoAsset copy(Status nextStatus, GateResults nextGates, String reason) {
        return new EvoAsset(id, failureClass, target, match, action, createdFromRuns,
                evaluationSplit, nextGates, compatibleAssets, nextStatus,
                version + 1, sourceSplitOrdinal, effectiveFromSplitOrdinal,
                createdAtEpochMs, reason);
    }

    private Status statusAfterGate(EvoGateOutcome outcome) {
        if (!outcome.passed()) {
            return outcome.gate() == EvoGate.TARGETED_REGRESSION
                    || outcome.gate() == EvoGate.GLOBAL_REGRESSION
                    || outcome.gate() == EvoGate.STACK_CONFIRMATION
                    ? Status.REJECTED : Status.ROLLED_BACK;
        }
        return switch (outcome.gate()) {
            case TARGETED_REGRESSION -> Status.TARGETED_PASSED;
            case GLOBAL_REGRESSION -> Status.GLOBAL_PASSED;
            case STACK_CONFIRMATION -> Status.STACK_CONFIRMED;
            case SHADOW -> Status.CANARY;
            case CANARY -> Status.PROMOTED;
        };
    }

    private static String normalizeId(String value) {
        Objects.requireNonNull(value, "evo asset id must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("evo-")) {
            throw new IllegalArgumentException("evo asset id must start with evo-");
        }
        if (normalized.length() > 256) throw new IllegalArgumentException("evo asset id is too long");
        return normalized;
    }

    private static String normalizeToken(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String normalizeTarget(String value) {
        Objects.requireNonNull(value, "target must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        if (normalized.isEmpty()) throw new IllegalArgumentException("target must not be blank");
        return normalized;
    }

    private static String normalizeSplit(String value) {
        Objects.requireNonNull(value, "evaluationSplit must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("evaluationSplit must not be blank");
        return normalized;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return List.copyOf(values.stream().filter(Objects::nonNull)
                .map(String::trim).filter(value -> !value.isEmpty()).toList());
    }

    private static List<String> immutableIds(List<String> values, String self) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String id = normalizeId(value);
            if (!id.equals(self)) normalized.add(id);
        }
        return List.copyOf(normalized);
    }

    /** Per-gate booleans plus evidence/details retained for audit and rollback. */
    public record GateResults(
            boolean targetedRegressionPassed,
            boolean globalRegressionPassed,
            boolean stackConfirmationPassed,
            boolean shadowPassed,
            boolean canaryPassed,
            Map<String, Object> details,
            List<String> failures
    ) {
        public GateResults {
            details = immutableMap(details);
            failures = immutableStrings(failures);
        }

        public static GateResults empty() {
            return new GateResults(false, false, false, false, false, Map.of(), List.of());
        }

        public boolean hasPassed(EvoGate gate) {
            if (gate == null) return false;
            return switch (gate) {
                case TARGETED_REGRESSION -> targetedRegressionPassed;
                case GLOBAL_REGRESSION -> globalRegressionPassed;
                case STACK_CONFIRMATION -> stackConfirmationPassed;
                case SHADOW -> shadowPassed;
                case CANARY -> canaryPassed;
            };
        }

        public GateResults with(EvoGateOutcome outcome) {
            LinkedHashMap<String, Object> nextDetails = new LinkedHashMap<>(details);
            nextDetails.put(outcome.gate().name().toLowerCase(Locale.ROOT),
                    Map.of("passed", outcome.passed(), "score", outcome.score(),
                            "evidence", outcome.evidence(), "reason", outcome.reason(),
                            "details", outcome.details()));
            ArrayList<String> nextFailures = new ArrayList<>(failures);
            if (!outcome.passed()) {
                nextFailures.add(outcome.gate().name().toLowerCase(Locale.ROOT)
                        + ":" + (outcome.reason().isBlank() ? "failed" : outcome.reason()));
            }
            return switch (outcome.gate()) {
                case TARGETED_REGRESSION -> new GateResults(outcome.passed(),
                        globalRegressionPassed, stackConfirmationPassed, shadowPassed,
                        canaryPassed, nextDetails, nextFailures);
                case GLOBAL_REGRESSION -> new GateResults(targetedRegressionPassed,
                        outcome.passed(), stackConfirmationPassed, shadowPassed,
                        canaryPassed, nextDetails, nextFailures);
                case STACK_CONFIRMATION -> new GateResults(targetedRegressionPassed,
                        globalRegressionPassed, outcome.passed(), shadowPassed,
                        canaryPassed, nextDetails, nextFailures);
                case SHADOW -> new GateResults(targetedRegressionPassed,
                        globalRegressionPassed, stackConfirmationPassed, outcome.passed(),
                        canaryPassed, nextDetails, nextFailures);
                case CANARY -> new GateResults(targetedRegressionPassed,
                        globalRegressionPassed, stackConfirmationPassed, shadowPassed,
                        outcome.passed(), nextDetails, nextFailures);
            };
        }

        private static Map<String, Object> immutableMap(Map<String, Object> values) {
            if (values == null || values.isEmpty()) return Map.of();
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        private static List<String> immutableStrings(List<String> values) {
            if (values == null || values.isEmpty()) return List.of();
            return List.copyOf(values.stream().filter(Objects::nonNull)
                    .map(String::trim).filter(value -> !value.isEmpty()).toList());
        }
    }
}
