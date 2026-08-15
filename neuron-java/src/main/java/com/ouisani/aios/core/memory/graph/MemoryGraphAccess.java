package com.ouisani.aios.core.memory.graph;

/**
 * Read context for a graph query.
 *
 * <p>{@code scopeId} is normally an agent or workflow namespace.  It is kept
 * separate from {@code tenantId}: a PRIVATE node is owner-scope data, while a
 * TENANT/SHARED node may be read by another agent in the same tenant.</p>
 */
public record MemoryGraphAccess(
        String scopeId,
        String tenantId,
        boolean includeShared) {

    public MemoryGraphAccess {
        scopeId = clean(scopeId);
        tenantId = clean(tenantId);
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
    }

    public MemoryGraphAccess(String scopeId, String tenantId) {
        this(scopeId, tenantId, true);
    }

    public static MemoryGraphAccess owner(String scopeId, String tenantId) {
        return new MemoryGraphAccess(scopeId, tenantId, true);
    }

    public static MemoryGraphAccess privateOnly(String scopeId, String tenantId) {
        return new MemoryGraphAccess(scopeId, tenantId, false);
    }

    /**
     * Fail-closed visibility check.  The owner scope is supplied by the graph
     * store because it is not a field on the node wire schema.
     */
    public boolean canRead(String ownerScope, MemoryNode node) {
        if (node == null || ownerScope == null) return false;
        return switch (node.visibility()) {
            case PRIVATE -> scopeId.equals(ownerScope);
            case TENANT -> tenantId != null && tenantId.equals(node.tenant());
            case SHARED -> includeShared
                    && (node.tenant() == null || tenantId != null && tenantId.equals(node.tenant()));
            case PUBLIC -> includeShared;
        };
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
