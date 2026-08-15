package com.ouisani.aios.core.verification;

/** Deterministic evidence checks supplied by the workflow runtime. */
public enum EvidenceType {
    OUTPUT_KEY,
    OUTPUT_SCHEMA,
    STATE_CHANGE,
    REQUIRED_STEP,
    ARTIFACT_EXISTS,
    UPSTREAM_SUCCESS,
    PERMISSION_APPROVAL,
    FINAL_RESPONSE_COVERAGE
}
