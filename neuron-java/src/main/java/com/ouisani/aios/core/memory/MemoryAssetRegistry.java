package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.tool.DelegationToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local registry for memory assets and their ACL/delegation boundary.
 *
 * <p>The registry is deliberately independent from a particular memory
 * provider.  It can register a Chat Memory namespace, Skill, Wiki or
 * CodeGraph reference and later assemble only the assets a parent is allowed
 * to delegate.  A missing asset is denied when a loadout was explicitly
 * requested; there is no implicit "all assets" fallback at that boundary.</p>
 */
public final class MemoryAssetRegistry {

    private static final MemoryAssetRegistry GLOBAL = new MemoryAssetRegistry();
    private final ConcurrentHashMap<String, MemoryAsset> assets = new ConcurrentHashMap<>();

    public static MemoryAssetRegistry global() { return GLOBAL; }

    public MemoryAsset register(MemoryAsset asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        assets.put(asset.assetId(), asset);
        return asset;
    }

    public MemoryAsset registerAsset(MemoryAsset asset) { return register(asset); }

    public Optional<MemoryAsset> get(String assetId) {
        if (assetId == null || assetId.isBlank()) return Optional.empty();
        return Optional.ofNullable(assets.get(normalize(assetId)));
    }

    public MemoryAsset require(String assetId) {
        return get(assetId).orElseThrow(() ->
                new IllegalArgumentException("memory asset is not registered: " + assetId));
    }

    public boolean unregister(String assetId) {
        if (assetId == null || assetId.isBlank()) return false;
        return assets.remove(normalize(assetId)) != null;
    }

    public List<MemoryAsset> list() {
        return assets.values().stream()
                .sorted(Comparator.comparing(MemoryAsset::assetId))
                .toList();
    }

    public void clearForTest() { assets.clear(); }

    /** Register (or refresh) the per-Agent chat-memory asset used by lifecycle records. */
    public MemoryAsset ensureChatMemory(String agentId, String tenantId) {
        MemoryAsset asset = MemoryAsset.chatMemory(agentId, tenantId);
        assets.putIfAbsent(asset.assetId(), asset);
        return assets.get(asset.assetId());
    }

    public MemoryAsset registerSkill(String assetId, String ownerId, String tenantId,
                                     String reference, Set<String> readers,
                                     Set<String> delegators) {
        return register(MemoryAsset.skill(assetId, ownerId, tenantId, reference,
                readers, delegators));
    }

    public MemoryAsset registerSkill(String assetId, String ownerId, String tenantId,
                                     String reference, MemoryAssetAcl acl) {
        return register(MemoryAsset.skill(assetId, ownerId, tenantId, reference, acl));
    }

    public MemoryAsset registerWiki(String assetId, MemoryAsset.OwnerScope ownerScope,
                                    String ownerId, String tenantId, String reference,
                                    Set<String> readers, Set<String> delegators) {
        return register(MemoryAsset.wiki(assetId, ownerScope, ownerId, tenantId, reference,
                readers, delegators));
    }

    public MemoryAsset registerWiki(String assetId, MemoryAsset.OwnerScope ownerScope,
                                    String ownerId, String tenantId, String reference,
                                    MemoryAssetAcl acl) {
        return register(MemoryAsset.wiki(assetId, ownerScope, ownerId, tenantId, reference,
                acl));
    }

    public MemoryAsset registerCodeGraph(String assetId, MemoryAsset.OwnerScope ownerScope,
                                         String ownerId, String tenantId, String reference,
                                         Set<String> readers, Set<String> delegators) {
        return register(MemoryAsset.codeGraph(assetId, ownerScope, ownerId, tenantId,
                reference, readers, delegators));
    }

    public MemoryAsset registerCodeGraph(String assetId, MemoryAsset.OwnerScope ownerScope,
                                         String ownerId, String tenantId, String reference,
                                         MemoryAssetAcl acl) {
        return register(MemoryAsset.codeGraph(assetId, ownerScope, ownerId, tenantId,
                reference, acl));
    }

    /**
     * Assemble a loadout from a parent's ACL and signed delegation scope.
     * Requested ids are fail-closed: unknown, non-delegable, non-readable, or
     * token-out-of-scope ids are returned in {@link MemoryAssetLoadout#deniedAssetIds()}.
     * An empty request means "all currently registered assets this parent may
     * delegate" and is still materialized as an exact set.
     */
    public MemoryAssetLoadout createLoadout(String parentAgentId, String childAgentId,
                                            Set<String> requestedAssetIds,
                                            MemoryAccessContext accessContext) {
        String parent = clean(parentAgentId);
        String child = clean(childAgentId);
        if (parent == null || child == null) {
            throw new IllegalArgumentException("parentAgentId and childAgentId are required");
        }
        MemoryAccessContext context = accessContext == null
                ? MemoryAccessContext.current() : accessContext;
        validateParentIdentity(parent, context);

        DelegationToken token = context == null ? null : context.delegationToken();
        Set<String> parentScope = token == null
                ? Set.of("*") : normalizeSet(token.delegableMemoryAssets());
        List<String> requested = normalizeRequested(requestedAssetIds);
        if (requested.isEmpty()) {
            requested = assets.keySet().stream().sorted().toList();
        }

        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        LinkedHashSet<String> denied = new LinkedHashSet<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String id : requested) {
            MemoryAsset asset = assets.get(id);
            String reason = null;
            if (asset == null) reason = "asset_not_registered";
            else if (!matchesScope(parentScope, id)) reason = "parent_token_scope_denied";
            else {
                MemoryAssetPermissionDecision decision = asset.authorizeBinding(context, child);
                if (!decision.allowed()) reason = decision.reason();
            }
            if (reason == null) allowed.add(id);
            else {
                denied.add(id);
                reasons.put(id, reason);
            }
        }
        return new MemoryAssetLoadout(parent, child, "", allowed, denied, reasons);
    }

    public MemoryAssetLoadout loadoutForChild(String parentAgentId, String childAgentId,
                                              Set<String> requestedAssetIds,
                                              MemoryAccessContext accessContext) {
        return createLoadout(parentAgentId, childAgentId, requestedAssetIds, accessContext);
    }

    /** Variant that uses an already-issued token as the parent boundary. */
    public MemoryAssetLoadout createLoadout(String parentAgentId, String childAgentId,
                                            Set<String> requestedAssetIds,
                                            DelegationToken token) {
        MemoryAccessContext context = MemoryAccessContext.of(parentAgentId,
                token == null ? null : token.tenantId(),
                token == null ? null : token.workflowId(), null, token);
        return createLoadout(parentAgentId, childAgentId, requestedAssetIds, context);
    }

    /** Role-oriented assembly for common Researcher/Writer/Reviewer agents. */
    public MemoryAssetLoadout createRoleLoadout(String parentAgentId, String childAgentId,
                                                String role, MemoryAccessContext context) {
        String normalized = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        Set<MemoryAsset.Type> types = roleTypes(normalized);
        Set<String> requested = new LinkedHashSet<>();
        for (MemoryAsset asset : list()) {
            if (types.contains(asset.type())) requested.add(asset.assetId());
        }
        if (requested.isEmpty()) {
            return new MemoryAssetLoadout(parentAgentId, childAgentId, normalized,
                    Set.of(), Set.of(), Map.of());
        }
        MemoryAssetLoadout base = createLoadout(parentAgentId, childAgentId, requested, context);
        return new MemoryAssetLoadout(base.parentAgentId(), base.childAgentId(), normalized,
                base.assetIds(), base.deniedAssetIds(), base.denialReasons());
    }

    /**
     * Check a provenance asset at recall time.  A signed token is the proof of
     * delegated authority; the registry still requires the asset to be known
     * and delegable when a non-wildcard token names it.
     */
    public boolean isRecallAllowed(String assetId, MemoryAccessContext context) {
        if (assetId == null || assetId.isBlank()) return false;
        String id = normalize(assetId);
        DelegationToken token = context == null ? null : context.delegationToken();
        MemoryAsset asset = assets.get(id);
        if (asset == null) return false;
        if (token != null && (!token.isValid() || !context.allowsNamespace("memory", "read"))) {
            return false;
        }
        if (token != null && token.isValid() && token.allowsMemoryAsset(id)
                && !token.delegableMemoryAssets().contains("*")) {
            // An exact signed asset id is the proof that a parent already
            // passed the durable bind check.  It still must remain bindable
            // and in the same tenant; the token is the execution-time scope.
            if (!asset.bindable() || context == null
                    || !context.allowsNamespace("memory", "read")) return false;
            String assetTenant = asset.tenantId();
            String callerTenant = context == null ? null : context.effectiveTenantId();
            return assetTenant == null || (callerTenant != null && assetTenant.equals(callerTenant));
        }
        return asset != null && asset.canRead(context);
    }

    /** Audit-friendly binding decision for an Agent loadout adapter. */
    public MemoryAssetPermissionDecision authorizeBinding(String assetId,
                                                          MemoryAccessContext parentContext,
                                                          String childAgentId) {
        if (assetId == null || assetId.isBlank()) {
            return MemoryAssetPermissionDecision.deny("asset_not_registered");
        }
        MemoryAsset asset = assets.get(normalize(assetId));
        return asset == null
                ? MemoryAssetPermissionDecision.deny("asset_not_registered")
                : asset.authorizeBinding(parentContext, childAgentId);
    }

    public boolean canBindToAgent(String assetId, MemoryAccessContext parentContext,
                                  String childAgentId) {
        return authorizeBinding(assetId, parentContext, childAgentId).allowed();
    }

    /** Return the asset id embedded in a record source, if any. */
    public static String assetIdFromSource(String source) {
        if (source == null || source.isBlank()) return null;
        String marker = "asset=";
        int start = source.indexOf(marker);
        while (start >= 0 && start > 0 && source.charAt(start - 1) != ';') {
            start = source.indexOf(marker, start + marker.length());
        }
        if (start < 0) return null;
        int valueStart = start + marker.length();
        int end = source.indexOf(';', valueStart);
        String value = end < 0 ? source.substring(valueStart) : source.substring(valueStart, end);
        return value.isBlank() ? null : normalize(value);
    }

    private static Set<MemoryAsset.Type> roleTypes(String role) {
        return switch (role) {
            case "researcher", "research" -> EnumSet.of(
                    MemoryAsset.Type.WIKI, MemoryAsset.Type.CODE_GRAPH,
                    MemoryAsset.Type.SKILL);
            case "writer", "write" -> EnumSet.of(
                    MemoryAsset.Type.CHAT_MEMORY, MemoryAsset.Type.WIKI,
                    MemoryAsset.Type.SKILL);
            case "reviewer", "review" -> EnumSet.of(
                    MemoryAsset.Type.WIKI, MemoryAsset.Type.CODE_GRAPH,
                    MemoryAsset.Type.SKILL);
            default -> EnumSet.allOf(MemoryAsset.Type.class);
        };
    }

    private static boolean matchesScope(Set<String> scope, String assetId) {
        return scope.contains("*") || scope.contains(assetId);
    }

    private static void validateParentIdentity(String parentAgentId,
                                               MemoryAccessContext context) {
        if (context == null) return;
        if (context.delegationToken() != null && !context.delegationToken().isValid()) {
            throw new SecurityException("memory loadout delegation token is invalid");
        }
        if (context.agentId() != null && !parentAgentId.equals(context.agentId())) {
            throw new SecurityException("memory loadout parent identity mismatch");
        }
    }

    private static List<String> normalizeRequested(Set<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return normalizeSet(values).stream().sorted().toList();
    }

    private static Set<String> normalizeSet(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) result.add(normalize(value));
            }
        }
        return Set.copyOf(result);
    }

    private static String normalize(String value) {
        return MemoryAsset.normalizeAssetId(value);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
