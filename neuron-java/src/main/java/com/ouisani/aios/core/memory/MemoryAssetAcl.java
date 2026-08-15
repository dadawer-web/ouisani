package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Durable access policy for a registered Memory Asset.
 *
 * <p>{@link AccessMode#PRIVATE}, {@link AccessMode#TEAM}, and
 * {@link AccessMode#RESTRICTED} describe the asset lifecycle/registry
 * boundary.  They deliberately do not replace the execution-time
 * {@code MemoryScope.PRIVATE/TASK/TEAM} check on individual records.  A
 * signed {@code DelegationToken} can narrow execution after this policy has
 * allowed an asset to be mounted.</p>
 *
 * <p>The ACL has separate target-agent and binder sets.  This prevents an
 * asset that a role may read from being silently mounted into every child
 * Agent.  An empty target set means any child Agent in the same tenant; an
 * empty binder set in a restricted policy means the allowed readers may bind
 * it, while legacy reader/delegator constructors retain their old fail-closed
 * behaviour in {@link MemoryAsset}.</p>
 */
public record MemoryAssetAcl(
        AccessMode accessMode,
        String teamId,
        Set<String> users,
        Set<String> roles,
        Set<String> agents,
        Set<String> teams,
        Set<String> bindableAgents,
        Set<String> binders,
        boolean bindable) {

    public enum AccessMode {
        PRIVATE,
        TEAM,
        RESTRICTED
    }

    public MemoryAssetAcl {
        accessMode = accessMode == null ? AccessMode.PRIVATE : accessMode;
        teamId = clean(teamId);
        users = normalize(users);
        roles = normalize(roles);
        agents = normalize(agents);
        teams = normalize(teams);
        bindableAgents = normalize(bindableAgents);
        binders = normalize(binders);
    }

    /**
     * Translate the pre-ACL readers/delegators constructor into a durable
     * policy.  Unprefixed legacy principals are copied to all compatible
     * principal sets because the old matcher accepted agent/user/team ids
     * without a type prefix.
     */
    static MemoryAssetAcl fromLegacy(MemoryAsset.OwnerScope ownerScope, String ownerId,
                                     Set<String> readers, Set<String> delegators,
                                     boolean delegable) {
        if (ownerScope == MemoryAsset.OwnerScope.TEAM) {
            return team(ownerId);
        }
        Set<String> legacyReaders = normalize(readers);
        Set<String> legacyDelegators = normalize(delegators);
        if (legacyReaders.isEmpty() && legacyDelegators.isEmpty()) {
            return privateAsset();
        }
        Set<String> users = legacyByKind(legacyReaders, "user");
        Set<String> roles = legacyByKind(legacyReaders, "role");
        Set<String> agents = legacyByKind(legacyReaders, "agent");
        Set<String> teams = legacyByKind(legacyReaders, "team");
        for (String principal : legacyReaders) {
            if (!principal.contains(":")) {
                users = add(users, principal);
                agents = add(agents, principal);
                teams = add(teams, principal);
            }
        }
        return new MemoryAssetAcl(AccessMode.RESTRICTED, null,
                users, roles, agents, teams,
                Set.of(), legacyDelegators, delegable);
    }

    /** Owner-only ACL. */
    public static MemoryAssetAcl privateAsset() {
        return privateAsset(true);
    }

    public static MemoryAssetAcl privateAsset(boolean bindable) {
        return new MemoryAssetAcl(AccessMode.PRIVATE, null, Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), bindable);
    }

    /** Team ACL; {@code teamId} is the durable team boundary. */
    public static MemoryAssetAcl team(String teamId) {
        return team(teamId, true);
    }

    public static MemoryAssetAcl team(String teamId, boolean bindable) {
        return new MemoryAssetAcl(AccessMode.TEAM, teamId, Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), bindable);
    }

    public static MemoryAssetAcl teamAsset(String teamId) {
        return team(teamId);
    }

    /** Restricted ACL with user/role/agent principals and no child target restriction. */
    public static MemoryAssetAcl restricted(Set<String> users, Set<String> roles,
                                            Set<String> agents) {
        return restricted(users, roles, agents, Set.of(), Set.of(), Set.of(), true);
    }

    public static MemoryAssetAcl restricted(Set<String> users, Set<String> roles,
                                            Set<String> agents, boolean bindable) {
        return restricted(users, roles, agents, Set.of(), Set.of(), Set.of(), bindable);
    }

    /** Full restricted ACL factory. */
    public static MemoryAssetAcl restricted(Set<String> users, Set<String> roles,
                                            Set<String> agents, Set<String> teams,
                                            Set<String> bindableAgents,
                                            Set<String> binders, boolean bindable) {
        return new MemoryAssetAcl(AccessMode.RESTRICTED, null, users, roles, agents,
                teams, bindableAgents, binders, bindable);
    }

    public Set<String> allowedUsers() { return users; }

    public Set<String> allowedRoles() { return roles; }

    public Set<String> allowedAgents() { return agents; }

    public Set<String> allowedTeams() { return teams; }

    public boolean isPrivate() { return accessMode == AccessMode.PRIVATE; }

    public boolean isTeam() { return accessMode == AccessMode.TEAM; }

    public boolean isRestricted() { return accessMode == AccessMode.RESTRICTED; }

    boolean matchesTeamMember(MemoryAccessContext context) {
        if (context == null || context.teamId() == null) return false;
        String team = context.teamId().toLowerCase(Locale.ROOT);
        return "*".equals(teamId) || (teamId != null && teamId.equals(team))
                || matchesPrincipal(teams, context.teamId(), "team");
    }

    boolean matchesReader(MemoryAccessContext context) {
        if (context == null) return false;
        return matchesPrincipal(users, context.userId(), "user")
                || matchesPrincipal(agents, context.agentId(), "agent")
                || matchesPrincipal(teams, context.teamId(), "team")
                || context.roles().stream().anyMatch(role -> matchesPrincipal(roles, role, "role"));
    }

    boolean matchesBinder(MemoryAccessContext context) {
        if (context == null) return false;
        return binders.contains("*")
                || matchesPrincipal(binders, context.userId(), "user")
                || matchesPrincipal(binders, context.agentId(), "agent")
                || matchesPrincipal(binders, context.teamId(), "team")
                || context.roles().stream().anyMatch(role -> matchesPrincipal(binders, role, "role"));
    }

    boolean allowsTargetAgent(String childAgentId) {
        if (bindableAgents.isEmpty() || bindableAgents.contains("*")) return true;
        return matchesPrincipal(bindableAgents, childAgentId, "agent");
    }

    private static boolean matchesPrincipal(Set<String> acl, String value, String kind) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return acl.contains("*") || acl.contains(normalized)
                || acl.contains(kind + ":" + normalized);
    }

    private static Set<String> normalize(Set<String> values) {
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

    private static Set<String> legacyByKind(Set<String> values, String kind) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String prefix = kind + ":";
        for (String value : values) {
            if (value.startsWith(prefix)) result.add(value.substring(prefix.length()));
        }
        return Set.copyOf(result);
    }

    private static Set<String> add(Set<String> values, String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>(values);
        result.add(value);
        return Set.copyOf(result);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
