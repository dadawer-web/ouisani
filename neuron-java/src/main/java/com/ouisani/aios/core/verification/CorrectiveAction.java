package com.ouisani.aios.core.verification;

/** Action the workflow should take when evidence is not sufficient. */
public enum CorrectiveAction {
    RETRY,
    REPLAN,
    OBSERVE,
    ASK_USER,
    ABORT
}
