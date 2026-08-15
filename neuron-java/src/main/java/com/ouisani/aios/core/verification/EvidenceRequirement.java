package com.ouisani.aios.core.verification;

import com.ouisani.aios.core.VfsManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A piece of concrete evidence required by a verification contract.
 *
 * @param expectedValue type-dependent value; OUTPUT_SCHEMA expects
 *                      {@code Map<String, Class<?>>}
 */
public record EvidenceRequirement(
        String id,
        String description,
        EvidenceType type,
        String reference,
        Object expectedValue,
        boolean required
) {

    public EvidenceRequirement {
        id = id == null || id.isBlank() ? typeName(type) + ":" + reference : id;
        description = description == null || description.isBlank() ? id : description;
        type = type == null ? EvidenceType.OUTPUT_KEY : type;
        reference = reference == null ? "" : reference;
    }

    public static EvidenceRequirement outputKey(String key) {
        return new EvidenceRequirement("output:" + key, "output contains " + key,
                EvidenceType.OUTPUT_KEY, key, null, true);
    }

    public static EvidenceRequirement outputSchema(Map<String, Class<?>> schema) {
        Map<String, Class<?>> copy = schema == null ? Map.of() : new LinkedHashMap<>(schema);
        return new EvidenceRequirement("output_schema", "output matches declared schema",
                EvidenceType.OUTPUT_SCHEMA, "output", copy, true);
    }

    public static EvidenceRequirement stateChanged(String key) {
        return new EvidenceRequirement("state_changed:" + key, "state[" + key + "] changed",
                EvidenceType.STATE_CHANGE, key, null, true);
    }

    public static EvidenceRequirement requiredStep(String stepId) {
        return new EvidenceRequirement("step:" + stepId, "step " + stepId + " completed",
                EvidenceType.REQUIRED_STEP, stepId, null, true);
    }

    public static EvidenceRequirement artifactExists(String vfsPath) {
        return new EvidenceRequirement("artifact:" + vfsPath, "artifact exists: " + vfsPath,
                EvidenceType.ARTIFACT_EXISTS, vfsPath, null, true);
    }

    public static EvidenceRequirement upstreamSucceeded(String stepId) {
        return new EvidenceRequirement("upstream:" + stepId, "upstream step succeeded: " + stepId,
                EvidenceType.UPSTREAM_SUCCESS, stepId, null, true);
    }

    public static EvidenceRequirement permissionStillValid() {
        return new EvidenceRequirement("permission_still_valid", "permission approval remains valid",
                EvidenceType.PERMISSION_APPROVAL, "permission", null, true);
    }

    public static EvidenceRequirement finalResponseCoveredBy(String evidenceId) {
        return new EvidenceRequirement("response_covered:" + evidenceId,
                "final response is covered by evidence " + evidenceId,
                EvidenceType.FINAL_RESPONSE_COVERAGE, evidenceId, null, true);
    }

    public Evaluation evaluate(Observation observation) {
        if (observation == null) return Evaluation.inconclusive("observation is null");
        try {
            return switch (type) {
                case OUTPUT_KEY -> observation.hasOutput(reference)
                        ? Evaluation.pass("output[" + reference + "] is present")
                        : Evaluation.fail("output[" + reference + "] is missing");
                case OUTPUT_SCHEMA -> evaluateSchema(observation);
                case STATE_CHANGE -> evaluateStateChange(observation);
                case REQUIRED_STEP -> observation.completedSteps().contains(reference)
                        ? Evaluation.pass("step " + reference + " completed")
                        : Evaluation.fail("step " + reference + " is not completed");
                case ARTIFACT_EXISTS -> {
                    boolean exists = !reference.isBlank() && VfsManager.instance().exists(reference);
                    yield exists
                            ? Evaluation.pass("artifact exists: " + reference)
                            : Evaluation.fail("artifact missing: " + reference);
                }
                case UPSTREAM_SUCCESS -> {
                    String status = observation.upstreamStatuses().get(reference);
                    if (status == null) yield Evaluation.inconclusive("status unavailable for " + reference);
                    yield "SUCCESS".equalsIgnoreCase(status)
                            ? Evaluation.pass("upstream " + reference + " succeeded")
                            : Evaluation.fail("upstream " + reference + " status=" + status);
                }
                case PERMISSION_APPROVAL -> {
                    if (observation.permissionStillValid() == null) {
                        yield Evaluation.inconclusive("permission validity was not supplied");
                    }
                    yield observation.permissionStillValid()
                            ? Evaluation.pass("permission approval remains valid")
                            : Evaluation.fail("permission approval is no longer valid");
                }
                case FINAL_RESPONSE_COVERAGE -> evaluateFinalResponseCoverage(observation);
            };
        } catch (RuntimeException e) {
            return Evaluation.inconclusive("evidence check failed: " + e.getMessage());
        }
    }

    private Evaluation evaluateSchema(Observation observation) {
        if (!(expectedValue instanceof Map<?, ?> schema) || schema.isEmpty()) {
            return Evaluation.inconclusive("output schema is empty or invalid");
        }
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!observation.hasOutput(key)) return Evaluation.fail("schema field missing: " + key);
            Object actual = observation.outputValue(key);
            if (actual == null) return Evaluation.fail("schema field is null: " + key);
            if (entry.getValue() instanceof Class<?> expected && !expected.isInstance(actual)) {
                return Evaluation.fail("schema field " + key + " expected " + expected.getSimpleName()
                        + " but was " + actual.getClass().getSimpleName());
            }
        }
        return Evaluation.pass("output matches declared schema");
    }

    private Evaluation evaluateStateChange(Observation observation) {
        if (!observation.hasOutput(reference)) {
            return Evaluation.inconclusive("current state[" + reference + "] is unavailable");
        }
        if (!observation.hasBaseline(reference)) {
            return Evaluation.pass("state[" + reference + "] appeared during execution");
        }
        return java.util.Objects.deepEquals(observation.baselineValue(reference), observation.outputValue(reference))
                ? Evaluation.fail("state[" + reference + "] did not change")
                : Evaluation.pass("state[" + reference + "] changed");
    }

    private Evaluation evaluateFinalResponseCoverage(Observation observation) {
        if (observation.finalResponse().isBlank()) return Evaluation.inconclusive("final response is unavailable");
        Observation.Evidence item = observation.evidence(reference);
        if (item == null) return Evaluation.inconclusive("evidence not found: " + reference);
        if (!item.authoritative()) return Evaluation.fail("evidence is not authoritative: " + reference);
        boolean covered = observation.finalResponse().contains(item.id())
                || (!item.value().isBlank() && observation.finalResponse().contains(item.value()));
        return covered
                ? Evaluation.pass("final response cites evidence " + reference)
                : Evaluation.fail("final response is not covered by evidence " + reference);
    }

    public record Evaluation(Verdict verdict, String evidence) {
        public Evaluation {
            verdict = verdict == null ? Verdict.INCONCLUSIVE : verdict;
            evidence = evidence == null ? "" : evidence;
        }

        public static Evaluation pass(String evidence) { return new Evaluation(Verdict.PASS, evidence); }
        public static Evaluation fail(String evidence) { return new Evaluation(Verdict.FAIL, evidence); }
        public static Evaluation inconclusive(String evidence) { return new Evaluation(Verdict.INCONCLUSIVE, evidence); }
    }

    private static String typeName(EvidenceType type) {
        return type == null ? "evidence" : type.name().toLowerCase(java.util.Locale.ROOT);
    }
}
