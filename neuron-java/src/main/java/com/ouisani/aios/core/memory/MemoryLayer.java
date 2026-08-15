package com.ouisani.aios.core.memory;

/**
 * Lifecycle layer of a memory record.
 *
 * <p>The layers describe how far a piece of evidence has been distilled. They
 * are deliberately orthogonal to access scope ({@code PRIVATE}/{@code TASK}/
 * {@code TEAM}) and to provenance domain ({@code USER}/{@code AGENT}): a raw
 * conversation can be private, while a reviewed scenario can be shared with
 * a team.</p>
 *
 * <ul>
 *   <li>{@link #L0}: raw conversation, tool output, or other source evidence</li>
 *   <li>{@link #L1}: atomic fact, preference, constraint, or event</li>
 *   <li>{@link #L2}: project or scenario-level synthesis</li>
 *   <li>{@link #L3}: durable persona, policy, or stable rule</li>
 * </ul>
 */
public enum MemoryLayer {
    /** Raw evidence retained for provenance and later extraction. */
    L0,
    /** Atomic, actionable memory extracted from evidence. */
    L1,
    /** Scenario or project-level synthesis. */
    L2,
    /** Long-lived persona, policy, or stable rule. */
    L3;

    /** Parse a user/API value without making callers repeat enum boilerplate. */
    public static MemoryLayer parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return value.trim().toUpperCase(java.util.Locale.ROOT).startsWith("L")
                    ? valueOf(value.trim().toUpperCase(java.util.Locale.ROOT))
                    : valueOf("L" + value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("layer must be L0, L1, L2, or L3", ex);
        }
    }
}
