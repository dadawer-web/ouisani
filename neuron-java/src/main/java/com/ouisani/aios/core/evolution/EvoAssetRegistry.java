package com.ouisani.aios.core.evolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned in-process registry for EvoAssets.  Every replacement is retained
 * in history, so gate decisions remain auditable and rollback does not erase
 * the candidate that caused it.
 */
public final class EvoAssetRegistry {
    private final ConcurrentHashMap<String, EvoAsset> current = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<EvoAsset>> histories = new ConcurrentHashMap<>();

    public synchronized EvoAsset register(EvoAsset asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        EvoAsset previous = current.get(asset.id());
        if (previous != null && asset.version() <= previous.version()) {
            throw new IllegalArgumentException("EvoAsset version must increase: " + asset.id());
        }
        current.put(asset.id(), asset);
        histories.compute(asset.id(), (ignored, old) -> {
            ArrayList<EvoAsset> next = new ArrayList<>(old == null ? List.of() : old);
            next.add(asset);
            return List.copyOf(next);
        });
        return asset;
    }

    public EvoAsset registerCandidate(EvoAsset asset) {
        if (asset == null || asset.status() != EvoAsset.Status.CANDIDATE) {
            throw new IllegalArgumentException("only candidate EvoAssets may be registered as candidates");
        }
        return register(asset);
    }

    public Optional<EvoAsset> get(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.ofNullable(current.get(id.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    public EvoAsset require(String id) {
        return get(id).orElseThrow(() ->
                new IllegalArgumentException("EvoAsset is not registered: " + id));
    }

    public List<EvoAsset> list() {
        return current.values().stream()
                .sorted(Comparator.comparing(EvoAsset::id))
                .toList();
    }

    public List<EvoAsset> history(String id) {
        if (id == null || id.isBlank()) return List.of();
        return histories.getOrDefault(id.trim().toLowerCase(java.util.Locale.ROOT), List.of());
    }

    public synchronized void importJson(String json) {
        if (json == null || json.isBlank()) return;
        var parsed = com.google.gson.JsonParser.parseString(json);
        if (!parsed.isJsonArray()) throw new IllegalArgumentException("EvoAsset registry JSON must be an array");
        for (var element : parsed.getAsJsonArray()) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("EvoAsset entry must be an object");
            register(EvoAssetDsl.fromJson(element.getAsJsonObject()));
        }
    }

    public String exportJson() {
        return EvoAssetDsl.toJson(list());
    }

    /** Export every immutable version, not only the current version per id. */
    public String exportHistoryJson() {
        ArrayList<EvoAsset> all = new ArrayList<>();
        histories.values().stream().sorted((left, right) -> left.getFirst().id()
                .compareTo(right.getFirst().id())).forEach(all::addAll);
        return EvoAssetDsl.toJson(all);
    }

    public synchronized void clearForTest() {
        current.clear();
        histories.clear();
    }
}
