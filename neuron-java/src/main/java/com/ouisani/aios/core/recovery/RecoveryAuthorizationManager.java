package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.action.ActionEnvelope;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.tool.DelegationGuard;
import com.ouisani.aios.core.tool.DelegationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the authorization boundary introduced by restoring a workspace.
 *
 * <p>A restored snapshot contains state that was authorized before the
 * process stopped. That authorization is not carried forward automatically:
 * every mutating action is blocked until an explicit reauthorization is
 * recorded. Read-only actions remain available so an operator can inspect the
 * restored state and decide what to approve.</p>
 */
public final class RecoveryAuthorizationManager {

    private static final Logger log = LoggerFactory.getLogger(RecoveryAuthorizationManager.class);
    private static final RecoveryAuthorizationManager INSTANCE = new RecoveryAuthorizationManager();

    private final ConcurrentHashMap<String, RestoreBoundary> boundaries = new ConcurrentHashMap<>();

    private RecoveryAuthorizationManager() {}

    public static RecoveryAuthorizationManager instance() {
        return INSTANCE;
    }

    /** A restore boundary and its current authorization state. */
    public record RestoreBoundary(String boundaryId, String workspaceId, long restoredAtMs,
                                  boolean reauthorized, String reauthorizedBy,
                                  String approvalRef, long reauthorizedAtMs) {}

    /** Register a successful restore and invalidate all pre-restore delegation tokens. */
    public RestoreBoundary markRestored(String workspaceId) {
        String scope = required(workspaceId, "workspaceId");
        // A token issued before a restore must not survive the restore boundary.
        DelegationToken.revokeAll();
        DelegationGuard.clear();

        RestoreBoundary boundary = new RestoreBoundary(
                "restore_" + UUID.randomUUID(), scope, System.currentTimeMillis(),
                false, null, null, 0L);
        boundaries.put(scope, boundary);
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_PERMISSION, "RECOVERY", "RESTORE_REAUTH_REQUIRED",
                null, scope, "boundaryId=" + boundary.boundaryId(),
                new UnifiedAuditLog.AuditContext(null, scope, null, null, null, null, null, null, -1)));
        try {
            EventBus.instance().broadcast("sys.recovery.reauthorization_required",
                    "{\"workspaceId\":\"" + json(scope) + "\",\"boundaryId\":\""
                            + json(boundary.boundaryId()) + "\"}");
        } catch (Throwable t) {
            log.debug("[RecoveryAuth] unable to publish reauthorization event: {}", t.getMessage());
        }
        return boundary;
    }

    /**
     * Returns a denial reason for a mutating action, or {@code null} when it
     * may continue. Pending boundaries are intentionally fail-closed globally
     * when the action cannot be unambiguously mapped to a restored workspace.
     */
    public String denialReason(ActionEnvelope envelope) {
        if (envelope == null || envelope.isReadOnlyAction() || boundaries.isEmpty()) return null;
        RestoreBoundary matching = matchingBoundary(envelope);
        if (matching == null) {
            // A restore boundary is security-sensitive. Do not let a caller
            // evade it by omitting or changing workflow/run identity.
            for (RestoreBoundary boundary : boundaries.values()) {
                if (!boundary.reauthorized()) {
                    return "recovery_reauthorization_required:" + boundary.workspaceId();
                }
            }
            return null;
        }
        if (!matching.reauthorized()) {
            return "recovery_reauthorization_required:" + matching.workspaceId();
        }
        return null;
    }

    /** Explicitly reauthorize a restored workspace after human/policy approval. */
    public boolean reauthorize(String workspaceId, String agentId, String approvalRef) {
        String scope = required(workspaceId, "workspaceId");
        String actor = required(agentId, "agentId");
        String approval = required(approvalRef, "approvalRef");
        final RestoreBoundary[] updated = new RestoreBoundary[1];
        boundaries.computeIfPresent(scope, (ignored, current) -> {
            RestoreBoundary next = new RestoreBoundary(current.boundaryId(), current.workspaceId(),
                    current.restoredAtMs(), true, actor, approval, System.currentTimeMillis());
            updated[0] = next;
            return next;
        });
        if (updated[0] == null) return false;
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_PERMISSION, "RECOVERY", "RESTORE_REAUTHORIZED",
                actor, scope, "boundaryId=" + updated[0].boundaryId() + ",approvalRef=" + approval,
                new UnifiedAuditLog.AuditContext(null, scope, null, null, actor, null, null, null, -1)));
        return true;
    }

    /** Convenience overload for callers that use a generated approval reference. */
    public boolean reauthorize(String workspaceId, String agentId) {
        return reauthorize(workspaceId, agentId, "manual-approval:" + UUID.randomUUID());
    }

    public boolean isPending(String workspaceId) {
        RestoreBoundary boundary = workspaceId == null ? null : boundaries.get(workspaceId.trim());
        return boundary != null && !boundary.reauthorized();
    }

    public boolean isReauthorized(String workspaceId) {
        RestoreBoundary boundary = workspaceId == null ? null : boundaries.get(workspaceId.trim());
        return boundary != null && boundary.reauthorized();
    }

    public List<RestoreBoundary> boundaries() {
        return Collections.unmodifiableList(new ArrayList<>(boundaries.values()));
    }

    /** Test/administrative reset; does not restore any authorization. */
    public void clear() {
        boundaries.clear();
    }

    private RestoreBoundary matchingBoundary(ActionEnvelope envelope) {
        for (String candidate : new String[]{envelope.workflowId(), envelope.runId()}) {
            if (candidate == null || candidate.isBlank()) continue;
            RestoreBoundary boundary = boundaries.get(candidate);
            if (boundary != null) return boundary;
        }
        return null;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
