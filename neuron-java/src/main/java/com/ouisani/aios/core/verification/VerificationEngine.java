package com.ouisani.aios.core.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Deterministic evaluator for verification-aware workflow contracts. */
public final class VerificationEngine {

    private static final Logger log = LoggerFactory.getLogger(VerificationEngine.class);

    public VerificationResult verify(VerificationContract contract, Observation observation) {
        VerificationStage stage = observation == null ? VerificationStage.SKILL_END : observation.stage();
        if (contract == null || !contract.appliesTo(stage)) return VerificationResult.notConfigured(stage);

        List<String> evidence = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        boolean requiredInconclusive = false;

        for (GoalPredicate predicate : contract.predicates()) {
            GoalPredicate.Evaluation evaluation = evaluate(predicate, observation);
            add(evidence, predicate.id() + ": " + evaluation.evidence());
            if (evaluation.verdict() == Verdict.FAIL) add(failures, predicate.id() + ": " + evaluation.evidence());
            if (evaluation.verdict() == Verdict.INCONCLUSIVE) requiredInconclusive = true;
        }

        for (EvidenceRequirement requirement : contract.evidenceRequirements()) {
            EvidenceRequirement.Evaluation evaluation = evaluate(requirement, observation);
            add(evidence, requirement.id() + ": " + evaluation.evidence());
            if (evaluation.verdict() == Verdict.FAIL && requirement.required()) {
                add(failures, requirement.id() + ": " + evaluation.evidence());
            }
            if (evaluation.verdict() == Verdict.INCONCLUSIVE && requirement.required()) {
                requiredInconclusive = true;
            }
        }

        Verdict verdict = failures.isEmpty()
                ? (requiredInconclusive ? Verdict.INCONCLUSIVE : Verdict.PASS)
                : Verdict.FAIL;
        CorrectiveAction action = verdict == Verdict.FAIL ? contract.onFail()
                : verdict == Verdict.INCONCLUSIVE ? contract.onInconclusive() : null;
        VerificationResult result = new VerificationResult(stage, verdict, action, evidence, failures, true);
        log.debug("[VerificationEngine] stage={} verdict={} action={} evidenceCount={} failureCount={}",
                stage, verdict, action, evidence.size(), failures.size());
        return result;
    }

    private static GoalPredicate.Evaluation evaluate(GoalPredicate predicate, Observation observation) {
        if (predicate == null) return GoalPredicate.Evaluation.inconclusive("null goal predicate");
        try {
            GoalPredicate.Evaluation result = predicate.evaluate(observation);
            return result == null ? GoalPredicate.Evaluation.inconclusive("predicate returned null") : result;
        } catch (RuntimeException e) {
            return GoalPredicate.Evaluation.inconclusive("predicate threw " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private static EvidenceRequirement.Evaluation evaluate(EvidenceRequirement requirement,
                                                            Observation observation) {
        if (requirement == null) return EvidenceRequirement.Evaluation.inconclusive("null evidence requirement");
        return requirement.evaluate(observation);
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }
}
