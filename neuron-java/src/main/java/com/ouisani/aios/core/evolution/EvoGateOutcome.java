package com.ouisani.aios.core.evolution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Deterministic, auditable result returned by one EvoAsset gate. */
public record EvoGateOutcome(
        EvoGate gate,
        boolean passed,
        double score,
        List<String> evidence,
        Map<String, Object> details,
        String reason
) {
    public EvoGateOutcome {
        if (gate == null) throw new IllegalArgumentException("gate must not be null");
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            throw new IllegalArgumentException("gate score must be finite");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        Map<String, Object> copied = new LinkedHashMap<>();
        if (details != null) copied.putAll(details);
        details = Collections.unmodifiableMap(copied);
        reason = reason == null ? "" : reason.trim();
    }

    public static EvoGateOutcome pass(EvoGate gate, String... evidence) {
        return new EvoGateOutcome(gate, true, 1.0,
                evidence == null ? List.of() : List.of(evidence), Map.of(), "");
    }

    public static EvoGateOutcome pass(EvoGate gate, double score,
                                      List<String> evidence,
                                      Map<String, Object> details) {
        return new EvoGateOutcome(gate, true, score, evidence, details, "");
    }

    public static EvoGateOutcome fail(EvoGate gate, String reason) {
        return new EvoGateOutcome(gate, false, 0.0, List.of(), Map.of(),
                reason == null ? "gate_failed" : reason);
    }

    public static EvoGateOutcome fail(EvoGate gate, String reason,
                                      List<String> evidence,
                                      Map<String, Object> details) {
        return new EvoGateOutcome(gate, false, 0.0, evidence, details, reason);
    }
}
