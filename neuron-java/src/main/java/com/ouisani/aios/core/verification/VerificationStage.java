package com.ouisani.aios.core.verification;

/**
 * When a verification contract is evaluated.
 *
 * <p>{@link #DURING} is intended for tool/skill observations, {@link #SKILL_END}
 * is the hard gate before a workflow node is marked successful, and
 * {@link #FINAL} validates the final answer or artifact after the graph has
 * finished.</p>
 */
public enum VerificationStage {
    DURING,
    SKILL_END,
    FINAL
}
