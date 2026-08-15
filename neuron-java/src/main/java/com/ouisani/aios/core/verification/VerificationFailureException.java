package com.ouisani.aios.core.verification;

/** Raised by the workflow bridge when a configured contract blocks success. */
public final class VerificationFailureException extends RuntimeException {

    private final VerificationResult result;

    public VerificationFailureException(VerificationResult result) {
        super(message(result));
        this.result = result;
    }

    public VerificationResult result() { return result; }

    private static String message(VerificationResult result) {
        if (result == null) return "verification failed";
        String details = result.failures().isEmpty()
                ? String.join(" | ", result.evidence())
                : String.join(" | ", result.failures());
        return "verification " + result.verdict() + " at " + result.stage()
                + "; correctiveAction=" + result.correctiveAction()
                + (details.isBlank() ? "" : "; evidence=" + details);
    }
}
