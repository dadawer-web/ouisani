package com.ouisani.aios.core.ipc;

import com.ouisani.aios.core.tool.DelegationToken;
import com.ouisani.aios.core.tool.DelegationGuard;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Identity and delegation context used by scoped shared memory.
 *
 * <p>Callers can pass an explicit context at API boundaries. Internal agent
 * code can use {@link #current()}, which combines {@link CallerContext} with
 * the signed token installed by {@link DelegationGuard}.</p>
 */
public record MemoryAccessContext(
        String agentId,
        String tenantId,
        String workflowId,
        String teamId,
        DelegationToken delegationToken,
        String userId,
        Set<String> roles) {

    public MemoryAccessContext {
        agentId = clean(agentId);
        tenantId = clean(tenantId);
        workflowId = clean(workflowId);
        teamId = clean(teamId);
        userId = clean(userId);
        roles = normalizeRoles(roles);
    }

    public MemoryAccessContext(String agentId, String tenantId,
                               String workflowId, String teamId) {
        this(agentId, tenantId, workflowId, teamId, null, null, Set.of());
    }

    /** Source-compatible constructor with a signed token but no user/roles. */
    public MemoryAccessContext(String agentId, String tenantId,
                               String workflowId, String teamId,
                               DelegationToken delegationToken) {
        this(agentId, tenantId, workflowId, teamId, delegationToken, null, Set.of());
    }

    public MemoryAccessContext(String agentId, String tenantId,
                               String workflowId, String teamId,
                               String userId, Set<String> roles) {
        this(agentId, tenantId, workflowId, teamId, null, userId, roles);
    }

    public static MemoryAccessContext of(String agentId, String tenantId,
                                         String workflowId, String teamId) {
        return new MemoryAccessContext(agentId, tenantId, workflowId, teamId, null, null, Set.of());
    }

    public static MemoryAccessContext of(String agentId, String tenantId,
                                         String workflowId, String teamId,
                                         DelegationToken token) {
        return new MemoryAccessContext(agentId, tenantId, workflowId, teamId, token, null, Set.of());
    }

    /** Identity-aware overload for durable user/role ACL checks. */
    public static MemoryAccessContext of(String agentId, String tenantId,
                                         String workflowId, String teamId,
                                         String userId, Set<String> roles) {
        return new MemoryAccessContext(agentId, tenantId, workflowId, teamId,
                null, userId, roles);
    }

    /** Identity-aware overload with a signed execution token. */
    public static MemoryAccessContext of(String agentId, String tenantId,
                                         String workflowId, String teamId,
                                         DelegationToken token, String userId,
                                         Set<String> roles) {
        return new MemoryAccessContext(agentId, tenantId, workflowId, teamId,
                token, userId, roles);
    }

    /** Alternate argument order useful to HTTP adapters. */
    public static MemoryAccessContext of(String agentId, String tenantId,
                                         String workflowId, String teamId,
                                         String userId, Set<String> roles,
                                         DelegationToken token) {
        return of(agentId, tenantId, workflowId, teamId, token, userId, roles);
    }

    /** Build a context from the current agent and signed delegation scope. */
    public static MemoryAccessContext current() {
        CallerContext caller = CallerContext.current();
        DelegationToken token = DelegationGuard.currentToken();
        // The signed child token is the authoritative identity after a
        // virtual-thread hand-off.  CallerContext is inheritable and may
        // still contain the parent's id, which must not make a child recall
        // look like a cross-agent access.
        String agent = token != null && token.childAgentId() != null
                ? token.childAgentId() : caller != null ? caller.agentId() : null;
        String tenant = token != null && token.tenantId() != null
                ? token.tenantId() : caller != null ? caller.tenantId() : null;
        String workflow = null;
        if (token != null) {
            if (agent == null) agent = token.childAgentId();
            if (tenant == null) tenant = token.tenantId();
            workflow = token.workflowId();
        }
        return new MemoryAccessContext(agent, tenant, workflow, null, token,
                null, Set.of());
    }

    public boolean hasIdentity() {
        return agentId != null && !agentId.isBlank();
    }

    /** Effective values prefer the signed token when the caller omitted them. */
    public String effectiveTenantId() {
        return delegationToken != null && delegationToken.tenantId() != null
                ? delegationToken.tenantId() : tenantId;
    }

    public String effectiveWorkflowId() {
        return delegationToken != null && delegationToken.workflowId() != null
                ? delegationToken.workflowId() : workflowId;
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role.trim().toLowerCase(Locale.ROOT));
    }

    public boolean allowsNamespace(String namespace, String operation) {
        if (delegationToken == null) return true;
        if (!delegationToken.isValid()) return false;
        String ns = namespace == null ? "" : namespace.trim().toLowerCase(Locale.ROOT);
        String op = operation == null ? "read" : operation.trim().toLowerCase(Locale.ROOT);
        return delegationToken.allowsCapability("memory:*")
                || delegationToken.allowsCapability("memory:" + ns)
                || delegationToken.allowsCapability("memory:" + op + ":*")
                || delegationToken.allowsCapability("memory:" + op + ":" + ns);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Set<String> normalizeRoles(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(normalized);
    }
}
