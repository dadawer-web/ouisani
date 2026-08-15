package com.ouisani.aios.core.memory;

/** Immutable decision/audit data for one governed plane crossing. */
public record MemoryBridgeDecision(
        boolean allowed,
        MemoryPlane sourcePlane,
        MemoryPlane targetPlane,
        String operation,
        String reason,
        String agentId,
        String tenantId,
        String workflowId,
        String traceId,
        int recordCount,
        long latencyMs) {

    public MemoryBridgeDecision {
        sourcePlane = sourcePlane == null ? MemoryPlane.EXECUTION : sourcePlane;
        targetPlane = targetPlane == null ? MemoryPlane.EXPERIENCE_SIDECAR : targetPlane;
        operation = operation == null || operation.isBlank() ? "memory_bridge" : operation;
        reason = reason == null ? "" : reason;
        recordCount = Math.max(0, recordCount);
        latencyMs = Math.max(0L, latencyMs);
    }

    public static MemoryBridgeDecision denied(MemoryPlane sourcePlane,
                                               MemoryPlane targetPlane,
                                               String operation,
                                               String reason,
                                               String agentId,
                                               String tenantId,
                                               String workflowId,
                                               String traceId,
                                               long latencyMs) {
        return new MemoryBridgeDecision(false, sourcePlane, targetPlane, operation, reason,
                agentId, tenantId, workflowId, traceId, 0, latencyMs);
    }
}
