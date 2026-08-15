package com.ouisani.aios.core.ipc;

/**
 * Visibility boundary for a shared-memory record.
 *
 * <p>The scope is deliberately small: private agent state, workflow/task
 * state, or state shared by an explicitly identified agent team.  It is an
 * execution-time boundary for a single governed record.  Long-lived assets
 * use {@link com.ouisani.aios.core.memory.MemoryAssetAcl} for owner/team/
 * restricted ACLs and binding policy; the two checks are intentionally
 * composed rather than collapsed.</p>
 */
public enum MemoryScope {
    /** Only the record owner may read or write the record. */
    PRIVATE,
    /** Agents in the same workflow may access the record when authorized. */
    TASK,
    /** Agents in the same team may access the record when authorized. */
    TEAM
}
