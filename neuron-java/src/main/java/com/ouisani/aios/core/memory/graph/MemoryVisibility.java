package com.ouisani.aios.core.memory.graph;

/**
 * Visibility boundary for a graph node.
 *
 * <p>PRIVATE is owner-scope only, TENANT is tenant-wide, SHARED is an
 * explicitly shared cross-agent fact (still tenant-bound when a tenant is
 * present), and PUBLIC has no tenant restriction.  The graph store uses
 * fail-closed checks for missing tenant information.</p>
 */
public enum MemoryVisibility {
    PRIVATE,
    TENANT,
    SHARED,
    PUBLIC
}
