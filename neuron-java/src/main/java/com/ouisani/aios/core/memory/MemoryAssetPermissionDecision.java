package com.ouisani.aios.core.memory;

/**
 * Structured result for a long-lived Memory Asset permission check.
 *
 * <p>This is intentionally separate from the boolean execution checks on a
 * {@code MemoryScope}.  Callers can expose {@link #reason()} in audit events
 * without turning an ACL decision into prompt content.</p>
 */
public record MemoryAssetPermissionDecision(boolean allowed, String reason) {

    public MemoryAssetPermissionDecision {
        reason = reason == null || reason.isBlank() ? "unspecified" : reason.trim();
    }

    public static MemoryAssetPermissionDecision allow(String reason) {
        return new MemoryAssetPermissionDecision(true, reason == null ? "allowed" : reason);
    }

    public static MemoryAssetPermissionDecision deny(String reason) {
        return new MemoryAssetPermissionDecision(false, reason == null ? "denied" : reason);
    }
}
