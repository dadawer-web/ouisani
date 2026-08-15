package com.ouisani.aios.core.evolution;

import java.util.List;

/**
 * Adapter for deterministic targeted/global regression and shadow/canary
 * evaluators.  It never mutates a prompt or a live strategy by itself.
 */
@FunctionalInterface
public interface EvoGateEvaluator {

    EvoGateOutcome evaluate(EvoGate gate, EvoAsset asset, EvaluationContext context);

    /** A useful test/bootstrap evaluator; production callers should supply real evaluations. */
    static EvoGateEvaluator alwaysPass() {
        return (gate, asset, context) -> EvoGateOutcome.pass(gate, "deterministic_pass");
    }

    /** Context supplied to a gate, including the currently active promoted stack. */
    record EvaluationContext(
            EvaluationSplit sourceSplit,
            EvaluationSplit currentSplit,
            List<EvoAsset> activeStack
    ) {
        public EvaluationContext {
            activeStack = activeStack == null ? List.of() : List.copyOf(activeStack);
        }
    }
}
