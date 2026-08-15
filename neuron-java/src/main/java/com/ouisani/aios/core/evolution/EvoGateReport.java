package com.ouisani.aios.core.evolution;

import java.util.List;

/** Complete audit record for one ordered gate evaluation. */
public record EvoGateReport(
        String assetId,
        EvoAsset asset,
        List<EvoGateOutcome> outcomes,
        boolean passed,
        String message
) {
    public EvoGateReport {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        message = message == null ? "" : message;
    }

    public boolean hasOutcome(EvoGate gate) {
        return outcomes.stream().anyMatch(outcome -> outcome.gate() == gate);
    }

    public EvoGateOutcome outcome(EvoGate gate) {
        return outcomes.stream()
                .filter(outcome -> outcome.gate() == gate)
                .reduce((first, second) -> second)
                .orElse(null);
    }
}
