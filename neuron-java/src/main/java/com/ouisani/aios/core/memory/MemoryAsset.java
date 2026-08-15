package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A governed memory-related asset.
 *
 * <p>Neuron stores memory records separately from the things that may be
 * assembled into an Agent context.  A chat-memory namespace, Skill, Wiki
 * projection, or CodeGraph therefore becomes an explicit asset with an
 * owner, tenant boundary, ACL and delegation bit.  The asset id is carried in
 * record provenance as {@code asset=&lt;id&gt;} and in signed delegation tokens.
 * It is never a prompt instruction.</p>
 */
public final class MemoryAsset {

    public enum Type {
        CHAT_MEMORY,
        SKILL,
        WIKI,
        CODE_GRAPH,
        OTHER
    }

    public enum OwnerScope {
        USER,
        TEAM,
        AGENT
    }

    private final String assetId;
    private final Type type;
    private final OwnerScope ownerScope;
    private final String ownerId;
    private final String tenantId;
    private final String reference;
    private final Set<String> readers;
    private final Set<String> delegators;
    private final boolean delegable;
    private final MemoryAssetAcl acl;
    private final boolean legacyAcl;
    private final Map<String, String> metadata;

    public MemoryAsset(String assetId, Type type, OwnerScope ownerScope, String ownerId,
                       String tenantId, String reference, Set<String> readers,
                       Set<String> delegators, boolean delegable,
                       Map<String, String> metadata) {
        this(assetId, type, ownerScope, ownerId, tenantId, reference, readers,
                delegators, delegable, metadata,
                MemoryAssetAcl.fromLegacy(ownerScope, ownerId, readers, delegators, delegable),
                true);
    }

    private MemoryAsset(String assetId, Type type, OwnerScope ownerScope, String ownerId,
                        String tenantId, String reference, Set<String> readers,
                        Set<String> delegators, boolean delegable,
                        Map<String, String> metadata, MemoryAssetAcl acl,
                        boolean legacyAcl) {
        this.assetId = normalizeAssetId(assetId);
        this.type = type == null ? Type.OTHER : type;
        this.ownerScope = ownerScope == null ? OwnerScope.AGENT : ownerScope;
        this.ownerId = clean(ownerId);
        if (this.ownerId == null) throw new IllegalArgumentException("ownerId must not be blank");
        this.tenantId = clean(tenantId);
        this.reference = reference == null ? "" : reference.trim();
        this.readers = normalizePrincipals(readers);
        this.delegators = normalizePrincipals(delegators);
        this.delegable = delegable;
        this.acl = acl == null ? MemoryAssetAcl.fromLegacy(this.ownerScope, this.ownerId,
                this.readers, this.delegators, delegable) : acl;
        this.legacyAcl = legacyAcl;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Construct an asset with an explicit durable owner/team/restricted ACL. */
    public MemoryAsset(String assetId, Type type, OwnerScope ownerScope, String ownerId,
                       String tenantId, String reference, MemoryAssetAcl acl,
                       boolean delegable, Map<String, String> metadata) {
        this(assetId, type, ownerScope, ownerId, tenantId, reference, Set.of(), Set.of(),
                delegable, metadata, acl, false);
    }

    public MemoryAsset(String assetId, Type type, OwnerScope ownerScope, String ownerId,
                       String tenantId, String reference, MemoryAssetAcl acl,
                       Map<String, String> metadata) {
        this(assetId, type, ownerScope, ownerId, tenantId, reference, acl,
                acl == null || acl.bindable(), metadata);
    }

    public MemoryAsset(String assetId, Type type, OwnerScope ownerScope, String ownerId,
                       String tenantId, MemoryAssetAcl acl, boolean delegable) {
        this(assetId, type, ownerScope, ownerId, tenantId, "", acl, delegable, Map.of());
    }

    public MemoryAsset(String assetId, Type type, OwnerScope ownerScope, String ownerId,
                       String tenantId, MemoryAssetAcl acl) {
        this(assetId, type, ownerScope, ownerId, tenantId, "", acl,
                acl == null || acl.bindable(), Map.of());
    }

    /** Convenience constructor for assets without an external reference. */
    public MemoryAsset(String assetId, Type type, OwnerScope ownerScope, String ownerId,
                       String tenantId, Set<String> readers, Set<String> delegators,
                       boolean delegable) {
        this(assetId, type, ownerScope, ownerId, tenantId, "", readers, delegators,
                delegable, Map.of());
    }

    /** Minimal asset owned by an Agent; the owner can read and delegate it. */
    public MemoryAsset(String assetId, Type type, String ownerAgentId, String tenantId,
                       boolean delegable) {
        this(assetId, type, OwnerScope.AGENT, ownerAgentId, tenantId, "",
                Set.of(), Set.of(), delegable, Map.of());
    }

    public static MemoryAsset chatMemory(String agentId, String tenantId) {
        return new MemoryAsset(chatAssetId(agentId), Type.CHAT_MEMORY, OwnerScope.AGENT,
                agentId, tenantId, "", Set.of(), Set.of(), true, Map.of());
    }

    public static MemoryAsset chatMemory(String agentId, String tenantId,
                                         MemoryAssetAcl acl) {
        return new MemoryAsset(chatAssetId(agentId), Type.CHAT_MEMORY, OwnerScope.AGENT,
                agentId, tenantId, "", acl, true, Map.of());
    }

    public static MemoryAsset skill(String assetId, String ownerId, String tenantId,
                                    String reference, Set<String> readers,
                                    Set<String> delegators) {
        return new MemoryAsset(assetId, Type.SKILL, OwnerScope.USER, ownerId, tenantId,
                reference, readers, delegators, true, Map.of());
    }

    public static MemoryAsset skill(String assetId, String ownerId, String tenantId,
                                    String reference, MemoryAssetAcl acl) {
        return new MemoryAsset(assetId, Type.SKILL, OwnerScope.USER, ownerId, tenantId,
                reference, acl, true, Map.of());
    }

    public static MemoryAsset wiki(String assetId, OwnerScope scope, String ownerId,
                                   String tenantId, String reference, Set<String> readers,
                                   Set<String> delegators) {
        return new MemoryAsset(assetId, Type.WIKI, scope, ownerId, tenantId, reference,
                readers, delegators, true, Map.of());
    }

    public static MemoryAsset wiki(String assetId, OwnerScope scope, String ownerId,
                                   String tenantId, String reference, MemoryAssetAcl acl) {
        return new MemoryAsset(assetId, Type.WIKI, scope, ownerId, tenantId,
                reference, acl, true, Map.of());
    }

    public static MemoryAsset codeGraph(String assetId, OwnerScope scope, String ownerId,
                                        String tenantId, String reference, Set<String> readers,
                                        Set<String> delegators) {
        return new MemoryAsset(assetId, Type.CODE_GRAPH, scope, ownerId, tenantId,
                reference, readers, delegators, true, Map.of());
    }

    public static MemoryAsset codeGraph(String assetId, OwnerScope scope, String ownerId,
                                        String tenantId, String reference, MemoryAssetAcl acl) {
        return new MemoryAsset(assetId, Type.CODE_GRAPH, scope, ownerId, tenantId,
                reference, acl, true, Map.of());
    }

    public String assetId() { return assetId; }

    public Type type() { return type; }

    public OwnerScope ownerScope() { return ownerScope; }

    public String ownerId() { return ownerId; }

    public String tenantId() { return tenantId; }

    public String reference() { return reference; }

    public Set<String> readers() { return readers; }

    public Set<String> delegators() { return delegators; }

    public boolean delegable() { return delegable; }

    /** Durable owner/team/restricted policy attached to this asset. */
    public MemoryAssetAcl acl() { return acl; }

    /** Whether this asset may be mounted into a child loadout at all. */
    public boolean bindable() { return delegable && acl.bindable(); }

    /** Alias used by adapters that distinguish the ACL bit from delegation tokens. */
    public boolean isBindable() { return bindable(); }

    public Map<String, String> metadata() { return metadata; }

    public boolean isOwner(MemoryAccessContext context) { return isOwnerPrincipal(context); }

    public boolean isTeamMember(MemoryAccessContext context) {
        return acl.accessMode() == MemoryAssetAcl.AccessMode.TEAM
                && acl.matchesTeamMember(context);
    }

    /** Whether the caller may read this asset without a delegated token. */
    public boolean canRead(MemoryAccessContext context) {
        return authorizeRead(context).allowed();
    }

    /** Whether the caller may put this asset into a child loadout. */
    public boolean canDelegate(MemoryAccessContext context) {
        return authorizeBinding(context, null).allowed();
    }

    /** Structured durable ACL decision for a direct asset read. */
    public MemoryAssetPermissionDecision authorizeRead(MemoryAccessContext context) {
        if (!sameTenant(context)) return MemoryAssetPermissionDecision.deny("tenant_mismatch");
        if (isOwnerPrincipal(context)) return MemoryAssetPermissionDecision.allow("owner");

        return switch (acl.accessMode()) {
            case PRIVATE -> MemoryAssetPermissionDecision.deny("private_owner_only");
            case TEAM -> acl.matchesTeamMember(context)
                    ? MemoryAssetPermissionDecision.allow("team_member")
                    : MemoryAssetPermissionDecision.deny("team_member_required");
            case RESTRICTED -> acl.matchesReader(context) || matchesPrincipal(readers, context)
                    ? MemoryAssetPermissionDecision.allow("restricted_acl")
                    : MemoryAssetPermissionDecision.deny("restricted_acl_denied");
        };
    }

    /** Structured durable ACL decision for binding this asset to a child Agent. */
    public MemoryAssetPermissionDecision authorizeBinding(MemoryAccessContext context,
                                                           String childAgentId) {
        if (!delegable || !acl.bindable()) {
            return MemoryAssetPermissionDecision.deny("asset_not_bindable");
        }
        if (!sameTenant(context)) {
            return MemoryAssetPermissionDecision.deny("tenant_mismatch");
        }
        if (isOwnerPrincipal(context)) {
            if (childAgentId != null && !acl.allowsTargetAgent(childAgentId)) {
                return MemoryAssetPermissionDecision.deny("child_agent_acl_denied");
            }
            return MemoryAssetPermissionDecision.allow("owner_binding");
        }

        boolean principalAllowed = switch (acl.accessMode()) {
            case PRIVATE -> false;
            case TEAM -> acl.matchesTeamMember(context);
            case RESTRICTED -> {
                // Old readers/delegators constructors were intentionally
                // fail-closed: only the explicit delegators could bind.
                if (legacyAcl) {
                    yield !delegators.isEmpty() && matchesPrincipal(delegators, context);
                }
                yield acl.binders().isEmpty()
                        ? acl.matchesReader(context)
                        : acl.matchesBinder(context);
            }
        };
        if (!principalAllowed) {
            return MemoryAssetPermissionDecision.deny("asset_binding_acl_denied");
        }
        if (childAgentId != null && !acl.allowsTargetAgent(childAgentId)) {
            return MemoryAssetPermissionDecision.deny("child_agent_acl_denied");
        }
        return MemoryAssetPermissionDecision.allow(
                acl.accessMode() == MemoryAssetAcl.AccessMode.TEAM
                        ? "team_member_binding" : "restricted_acl_binding");
    }

    /** Check both the parent binder and the child-agent target ACL. */
    public boolean canBindToAgent(MemoryAccessContext context, String childAgentId) {
        return authorizeBinding(context, childAgentId).allowed();
    }

    public MemoryAssetPermissionDecision authorizeBindToAgent(MemoryAccessContext context,
                                                              String childAgentId) {
        return authorizeBinding(context, childAgentId);
    }

    /** Stable id for the L0-L3 records produced by an Agent turn. */
    public static String chatAssetId(String agentId) {
        String value = clean(agentId);
        String safe = value == null ? "unknown" : value.toLowerCase()
                .replaceAll("[^a-z0-9._:/-]", "_");
        return "chat:" + safe;
    }

    private boolean sameTenant(MemoryAccessContext context) {
        if (tenantId == null) return true;
        return context != null && tenantId.equals(context.effectiveTenantId());
    }

    private boolean isOwnerPrincipal(MemoryAccessContext context) {
        if (context == null) return false;
        return switch (ownerScope) {
            case TEAM -> ownerId.equals(clean(context.teamId()));
            case USER -> ownerId.equals(clean(context.userId()))
                    || ownerId.equals(clean(context.agentId()));
            case AGENT -> ownerId.equals(clean(context.agentId()));
        };
    }

    private static boolean matchesPrincipal(Set<String> acl, MemoryAccessContext context) {
        if (context == null) return false;
        return acl.contains("*")
                || containsPrincipal(acl, context.agentId(), "agent")
                || containsPrincipal(acl, context.userId(), "user")
                || containsPrincipal(acl, context.teamId(), "team");
    }

    private static boolean containsPrincipal(Set<String> acl, String id, String kind) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.trim().toLowerCase();
        return acl.contains(normalized) || acl.contains(kind + ":" + normalized);
    }

    private static Set<String> normalizePrincipals(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) result.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(result);
    }

    public static String normalizeAssetId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("assetId must not be blank");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 256) throw new IllegalArgumentException("assetId is too long");
        return normalized;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MemoryAsset that && assetId.equals(that.assetId);
    }

    @Override
    public int hashCode() { return Objects.hash(assetId); }

    @Override
    public String toString() {
        return "MemoryAsset[id=" + assetId + ", type=" + type + ", owner=" + ownerId
                + ", delegable=" + delegable + "]";
    }
}
