package com.ouisani.aios.core.memory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable memory assets assembled for one child Agent.
 *
 * <p>The loadout is a capability-like object: it records the exact asset ids
 * that may be mounted, not a prompt fragment.  A child loadout can therefore
 * be compared with the parent's delegable set before a signed child token is
 * issued.</p>
 */
public record MemoryAssetLoadout(
        String parentAgentId,
        String childAgentId,
        String role,
        Set<String> assetIds,
        Set<String> deniedAssetIds,
        Map<String, String> denialReasons) {

    public MemoryAssetLoadout {
        parentAgentId = clean(parentAgentId);
        childAgentId = clean(childAgentId);
        role = role == null || role.isBlank() ? "" : role.trim().toLowerCase(Locale.ROOT);
        assetIds = normalize(assetIds);
        deniedAssetIds = normalize(deniedAssetIds);
        denialReasons = denialReasons == null ? Map.of() : Map.copyOf(denialReasons);
    }

    public MemoryAssetLoadout(String parentAgentId, String childAgentId,
                              Set<String> assetIds) {
        this(parentAgentId, childAgentId, "", assetIds, Set.of(), Map.of());
    }

    public static MemoryAssetLoadout empty(String parentAgentId, String childAgentId) {
        return new MemoryAssetLoadout(parentAgentId, childAgentId, "", Set.of(), Set.of(), Map.of());
    }

    /** Alias used by integrations that call the set a memory loadout. */
    public Set<String> memoryAssetIds() { return assetIds; }

    public boolean contains(String assetId) {
        if (assetId == null || assetId.isBlank()) return false;
        return assetIds.contains(MemoryAsset.normalizeAssetId(assetId));
    }

    /** Exact subset check; a parent wildcard delegates every registered id. */
    public boolean isSubsetOf(Set<String> parentDelegableAssetIds) {
        Set<String> parent = normalize(parentDelegableAssetIds);
        return parent.contains("*") || parent.containsAll(assetIds);
    }

    public boolean isSubsetOf(MemoryAssetLoadout parent) {
        return parent != null && isSubsetOf(parent.assetIds());
    }

    public boolean hasDeniedAssets() { return !deniedAssetIds.isEmpty(); }

    private static Set<String> normalize(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(MemoryAsset.normalizeAssetId(value));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
