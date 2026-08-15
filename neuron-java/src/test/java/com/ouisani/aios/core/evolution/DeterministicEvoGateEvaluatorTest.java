package com.ouisani.aios.core.evolution;

import com.ouisani.aios.core.recovery.RecoveryResult;
import com.ouisani.aios.core.verification.GoalPredicate;
import com.ouisani.aios.core.verification.Observation;
import com.ouisani.aios.core.verification.VerificationContract;
import com.ouisani.aios.core.verification.VerificationStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicEvoGateEvaluatorTest {

    @Test
    void everyDeterministicCaseMustPass() {
        VerificationContract contract = VerificationContract.builder()
                .predicate(GoalPredicate.outputPresent("result"))
                .build();
        Observation passingObservation = new Observation("workflow", "node",
                VerificationStage.SKILL_END, Map.of("result", "ok"), Map.of(), Map.of(),
                Set.of("step"), Set.of("step"), List.of(), "", true, true, Map.of());
        DeterministicEvoGateEvaluator evaluator = new DeterministicEvoGateEvaluator(Map.of(
                EvoGate.TARGETED_REGRESSION,
                List.of(new DeterministicEvoGateEvaluator.VerificationCase(
                        "case-pass", contract, passingObservation))));

        EvoGateOutcome pass = evaluator.evaluate(EvoGate.TARGETED_REGRESSION, null, null);
        assertTrue(pass.passed());
        assertTrue(pass.score() > 0.99);

        Observation missingObservation = new Observation("workflow", "node",
                VerificationStage.SKILL_END, Map.of(), Map.of(), Map.of(), Set.of(), Set.of(),
                List.of(), "", true, true, Map.of());
        DeterministicEvoGateEvaluator failing = new DeterministicEvoGateEvaluator(Map.of(
                EvoGate.TARGETED_REGRESSION,
                List.of(new DeterministicEvoGateEvaluator.VerificationCase(
                        "case-fail", contract, missingObservation))));
        assertFalse(failing.evaluate(EvoGate.TARGETED_REGRESSION, null, null).passed());
    }

    @Test
    void recoveryAdapterOnlyTurnsResultIntoEvidence() {
        EvoGateEvaluator evaluator = DeterministicEvoGateEvaluator.fromRecoveryResults(
                gate -> RecoveryResult.ok("recovery evaluated", "must not be applied"));
        EvoGateOutcome outcome = evaluator.evaluate(EvoGate.GLOBAL_REGRESSION, null, null);
        assertTrue(outcome.passed());
        assertTrue(outcome.evidence().contains("recovery_evaluation_passed"));
    }
}
