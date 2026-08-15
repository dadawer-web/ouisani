package com.ouisani.aios.core.evolution;

import com.ouisani.aios.core.recovery.RecoveryResult;
import com.ouisani.aios.core.verification.Observation;
import com.ouisani.aios.core.verification.VerificationContract;
import com.ouisani.aios.core.verification.VerificationEngine;
import com.ouisani.aios.core.verification.VerificationResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Deterministic gate adapter for the existing workflow verifier.  A gate is
 * considered safe only when every supplied case is PASS; FAIL and
 * INCONCLUSIVE both block promotion.  This lets recovery/regression suites
 * reuse the same business predicates instead of asking an LLM to grade a
 * candidate rule.
 */
public final class DeterministicEvoGateEvaluator implements EvoGateEvaluator {
    private final VerificationEngine verificationEngine;
    private final Map<EvoGate, List<VerificationCase>> cases;

    public DeterministicEvoGateEvaluator(Map<EvoGate, List<VerificationCase>> cases) {
        this(new VerificationEngine(), cases);
    }

    public DeterministicEvoGateEvaluator(VerificationEngine verificationEngine,
                                         Map<EvoGate, List<VerificationCase>> cases) {
        this.verificationEngine = Objects.requireNonNull(verificationEngine,
                "verification engine must not be null");
        EnumMap<EvoGate, List<VerificationCase>> copy = new EnumMap<>(EvoGate.class);
        if (cases != null) {
            for (Map.Entry<EvoGate, List<VerificationCase>> entry : cases.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(entry.getKey(), entry.getValue() == null
                            ? List.of() : List.copyOf(entry.getValue()));
                }
            }
        }
        this.cases = Map.copyOf(copy);
    }

    @Override
    public EvoGateOutcome evaluate(EvoGate gate, EvoAsset asset,
                                   EvaluationContext context) {
        List<VerificationCase> gateCases = cases.getOrDefault(gate, List.of());
        if (gateCases.isEmpty()) {
            return EvoGateOutcome.fail(gate, "no_deterministic_cases");
        }
        List<String> evidence = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int passed = 0;
        for (VerificationCase testCase : gateCases) {
            VerificationResult result = verificationEngine.verify(
                    testCase.contract(), testCase.observation());
            evidence.addAll(result.evidence().stream()
                    .map(value -> testCase.id() + ": " + value).toList());
            if (result.isPass()) {
                passed++;
            } else {
                failures.add(testCase.id() + ": " + result.verdict()
                        + (result.failures().isEmpty() ? "" : " " + result.failures()));
            }
        }
        double score = (double) passed / gateCases.size();
        if (!failures.isEmpty()) {
            return EvoGateOutcome.fail(gate,
                    "deterministic_verification_failed:" + String.join(";", failures),
                    evidence, Map.of("passed", passed, "total", gateCases.size(),
                            "failures", failures));
        }
        return EvoGateOutcome.pass(gate, score, evidence,
                Map.of("passed", passed, "total", gateCases.size()));
    }

    /** Adapt a recovery evaluation result without ever applying its prompt. */
    public static EvoGateEvaluator fromRecoveryResults(
            Function<EvoGate, RecoveryResult> recoveryEvaluator) {
        Objects.requireNonNull(recoveryEvaluator, "recovery evaluator must not be null");
        return (gate, asset, context) -> {
            RecoveryResult result = recoveryEvaluator.apply(gate);
            if (result == null) return EvoGateOutcome.fail(gate, "recovery_result_missing");
            if (!result.success()) return EvoGateOutcome.fail(gate,
                    "recovery_evaluation_failed:" + result.message());
            return EvoGateOutcome.pass(gate, "recovery_evaluation_passed");
        };
    }

    /** One deterministic verification case from a targeted/global suite. */
    public record VerificationCase(
            String id,
            VerificationContract contract,
            Observation observation
    ) {
        public VerificationCase {
            id = id == null || id.isBlank() ? "case" : id.trim();
            Objects.requireNonNull(contract, "verification contract must not be null");
            Objects.requireNonNull(observation, "verification observation must not be null");
        }
    }
}
