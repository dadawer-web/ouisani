package com.ouisani.aios.core.verification;

import java.util.List;

/** Auditable result of evaluating a {@link VerificationContract}. */
public record VerificationResult(
        VerificationStage stage,
        Verdict verdict,
        CorrectiveAction correctiveAction,
        List<String> evidence,
        List<String> failures,
        boolean configured
) {

    public VerificationResult {
        stage = stage == null ? VerificationStage.SKILL_END : stage;
        verdict = verdict == null ? Verdict.INCONCLUSIVE : verdict;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public boolean isPass() { return verdict == Verdict.PASS; }
    public boolean isFail() { return verdict == Verdict.FAIL; }
    public boolean isInconclusive() { return verdict == Verdict.INCONCLUSIVE; }

    public static VerificationResult notConfigured(VerificationStage stage) {
        return new VerificationResult(stage, Verdict.INCONCLUSIVE, CorrectiveAction.OBSERVE,
                List.of("verification contract not configured for stage " + stage), List.of(), false);
    }
}
