package com.ouisani.aios.core.verification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evidence snapshot supplied to a verification contract.
 *
 * <p>The snapshot deliberately keeps the verifier independent from an LLM or
 * a particular tool implementation. A successful tool call is only one field
 * in this object; predicates and evidence requirements decide whether the
 * task actually reached its goal.</p>
 */
public record Observation(
        String workflowId,
        String nodeId,
        VerificationStage stage,
        Map<String, Object> output,
        Map<String, Object> baselineOutput,
        Map<String, String> upstreamStatuses,
        Set<String> completedSteps,
        Set<String> attemptedSteps,
        List<Evidence> evidence,
        String finalResponse,
        Boolean permissionStillValid,
        boolean toolReportedSuccess,
        Map<String, Object> metadata
) {

    public Observation {
        output = immutableMap(output);
        baselineOutput = immutableMap(baselineOutput);
        upstreamStatuses = immutableMap(upstreamStatuses);
        completedSteps = immutableSet(completedSteps);
        attemptedSteps = immutableSet(attemptedSteps);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        metadata = immutableMap(metadata);
        finalResponse = finalResponse == null ? "" : finalResponse;
    }

    public Object outputValue(String key) {
        return valueAt(output, key);
    }

    public Object baselineValue(String key) {
        return valueAt(baselineOutput, key);
    }

    public boolean hasOutput(String key) {
        return containsValue(output, key);
    }

    public boolean hasBaseline(String key) {
        return containsValue(baselineOutput, key);
    }

    public boolean hasEvidence(String reference) {
        return evidence.stream().anyMatch(item -> item.matches(reference));
    }

    public Evidence evidence(String reference) {
        return evidence.stream().filter(item -> item.matches(reference)).findFirst().orElse(null);
    }

    /** A compact, source-grounded evidence item used by final-answer checks. */
    public record Evidence(String id, String source, String value, boolean authoritative) {
        public Evidence {
            id = id == null ? "" : id;
            source = source == null ? "" : source;
            value = value == null ? "" : value;
        }

        public boolean matches(String reference) {
            return reference != null && (reference.equals(id) || reference.equals(source));
        }
    }

    private static Object valueAt(Map<String, Object> values, String path) {
        if (values == null || path == null || path.isBlank()) return null;
        if (values.containsKey(path)) return values.get(path);
        Object current = values;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) return null;
            current = map.get(part);
        }
        return current;
    }

    private static boolean containsValue(Map<String, Object> values, String path) {
        if (values == null || path == null || path.isBlank()) return false;
        if (values.containsKey(path)) return true;
        Object current = values;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(parts[i])) return false;
            current = map.get(parts[i]);
        }
        return true;
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        if (source == null || source.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static <T> Set<T> immutableSet(Set<T> source) {
        if (source == null || source.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
