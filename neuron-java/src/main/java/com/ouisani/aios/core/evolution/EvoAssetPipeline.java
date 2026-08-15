package com.ouisani.aios.core.evolution;

import com.ouisani.aios.core.memory.MemoryAsset;
import com.ouisani.aios.core.memory.MemoryAssetAcl;
import com.ouisani.aios.core.memory.MemoryAssetRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gated, split-aware EvoAsset lifecycle.
 *
 * <pre>
 * failure -&gt; diagnose -&gt; candidate -&gt; targeted -&gt; global -&gt; stack
 *         -&gt; shadow -&gt; canary -&gt; promoted (or rollback)
 * </pre>
 *
 * <p>The current split is never eligible for a rule created in that split.
 * A rule becomes observable through {@link #activeAssets(String)} only after
 * promotion and only at its next effective split ordinal.</p>
 */
public final class EvoAssetPipeline {
    private final EvoAssetRegistry registry;
    private final MemoryAssetRegistry memoryAssets;
    private final FailureDiagnoser diagnoser;
    private final String ownerAgentId;
    private final String tenantId;
    private final Map<String, EvaluationSplit> splits = new LinkedHashMap<>();
    private EvaluationSplit currentSplit;

    public EvoAssetPipeline() {
        this(new EvoAssetRegistry(), MemoryAssetRegistry.global(), new FailureDiagnoser(),
                "evo-system", null);
    }

    public EvoAssetPipeline(MemoryAssetRegistry memoryAssets) {
        this(new EvoAssetRegistry(), memoryAssets, new FailureDiagnoser(), "evo-system", null);
    }

    public EvoAssetPipeline(EvoAssetRegistry registry, MemoryAssetRegistry memoryAssets,
                            FailureDiagnoser diagnoser, String ownerAgentId, String tenantId) {
        this.registry = Objects.requireNonNull(registry, "asset registry must not be null");
        this.memoryAssets = Objects.requireNonNull(memoryAssets, "memory asset registry must not be null");
        this.diagnoser = Objects.requireNonNull(diagnoser, "failure diagnoser must not be null");
        this.ownerAgentId = clean(ownerAgentId, "evo-system");
        this.tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    public EvoAssetPipeline(EvoAssetRegistry registry, MemoryAssetRegistry memoryAssets) {
        this(registry, memoryAssets, new FailureDiagnoser(), "evo-system", null);
    }

    public synchronized EvaluationSplit beginSplit(String splitId) {
        int nextOrdinal = splits.values().stream()
                .mapToInt(EvaluationSplit::ordinal).max().orElse(-1) + 1;
        return beginSplit(splitId, nextOrdinal);
    }

    public synchronized EvaluationSplit beginSplit(String splitId, int ordinal) {
        if (currentSplit != null && !currentSplit.closed()) {
            throw new IllegalStateException("close the current evaluation split first: " + currentSplit.id());
        }
        if (splits.containsKey(splitId)) {
            throw new IllegalArgumentException("evaluation split already exists: " + splitId);
        }
        int lastOrdinal = splits.values().stream()
                .mapToInt(EvaluationSplit::ordinal).max().orElse(-1);
        if (ordinal <= lastOrdinal) {
            throw new IllegalArgumentException("evaluation split ordinals must increase");
        }
        EvaluationSplit split = new EvaluationSplit(splitId, ordinal, false);
        splits.put(split.id(), split);
        currentSplit = split;
        return split;
    }

    public synchronized EvaluationSplit endSplit() {
        if (currentSplit == null) throw new IllegalStateException("no evaluation split is open");
        if (currentSplit.closed()) return currentSplit;
        currentSplit = currentSplit.close();
        splits.put(currentSplit.id(), currentSplit);
        return currentSplit;
    }

    public synchronized Optional<EvaluationSplit> currentSplit() {
        return Optional.ofNullable(currentSplit);
    }

    public EvoAssetRegistry registry() { return registry; }

    public MemoryAssetRegistry memoryAssetRegistry() { return memoryAssets; }

    /** Compile a failed run into a candidate, never into an active rule. */
    public synchronized EvoAsset propose(FailureDiagnoser.FailureTrajectory trajectory) {
        Objects.requireNonNull(trajectory, "failure trajectory must not be null");
        EvaluationSplit split = requireOpenSplit();
        if (!trajectory.splitId().isBlank() && !split.id().equals(trajectory.splitId())) {
            throw new IllegalArgumentException("failure trajectory belongs to another split");
        }
        FailureDiagnoser.Diagnosis diagnosis = diagnoser.diagnose(trajectory);
        return registerCandidate(EvoAsset.candidate(newId(), diagnosis, split));
    }

    public synchronized EvoAsset propose(FailureDiagnoser.Diagnosis diagnosis) {
        Objects.requireNonNull(diagnosis, "diagnosis must not be null");
        return registerCandidate(EvoAsset.candidate(newId(), diagnosis, requireOpenSplit()));
    }

    public synchronized EvoAsset registerCandidate(EvoAsset candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (candidate.status() != EvoAsset.Status.CANDIDATE) {
            throw new IllegalArgumentException("only CANDIDATE assets may enter the pipeline");
        }
        EvaluationSplit split = requireSplit(candidate.evaluationSplit());
        if (!split.equals(currentSplit) || split.closed()
                || split.ordinal() != candidate.sourceSplitOrdinal()) {
            throw new IllegalArgumentException(
                    "candidate must be created in the currently open source split");
        }
        EvoAsset registered = registry.registerCandidate(candidate);
        syncMemoryAsset(registered);
        return registered;
    }

    /** Run targeted, global and stack confirmation in order. */
    public synchronized EvoGateReport evaluate(String assetId, EvoGateEvaluator evaluator) {
        Objects.requireNonNull(evaluator, "gate evaluator must not be null");
        EvoAsset asset = registry.require(assetId);
        ensureStaticEvaluationAllowed(asset);
        if (!isStaticStatus(asset.status())) {
            throw new IllegalStateException("asset is not awaiting static gates: " + asset.status());
        }

        EvaluationSplit source = requireSplit(asset.evaluationSplit());
        List<EvoGateOutcome> outcomes = new ArrayList<>();
        for (EvoGate gate : List.of(EvoGate.TARGETED_REGRESSION,
                EvoGate.GLOBAL_REGRESSION, EvoGate.STACK_CONFIRMATION)) {
            if (asset.gateResults().hasPassed(gate)) continue;
            EvoGateOutcome outcome = evaluateGate(gate, asset, source, evaluator);
            outcomes.add(outcome);
            asset = update(asset, asset.withGate(outcome));
            if (!outcome.passed()) {
                return report(asset, outcomes, false, gate.name() + " failed");
            }
        }
        if (asset.status() != EvoAsset.Status.SHADOW) {
            asset = update(asset, asset.withStatus(EvoAsset.Status.SHADOW));
        }
        return report(asset, outcomes, true, "static gates passed; awaiting shadow");
    }

    /** Shadow runs are non-effective and must occur in a later split. */
    public synchronized EvoGateReport runShadow(String assetId, EvoGateEvaluator evaluator) {
        return runRuntimeGate(assetId, EvoGate.SHADOW, EvoAsset.Status.SHADOW,
                EvoAsset.Status.CANARY, evaluator);
    }

    /** Canary runs are the final gate; only a passing canary is promoted. */
    public synchronized EvoGateReport runCanary(String assetId, EvoGateEvaluator evaluator) {
        return runRuntimeGate(assetId, EvoGate.CANARY, EvoAsset.Status.CANARY,
                EvoAsset.Status.PROMOTED, evaluator);
    }

    /** Explicit promotion hook for an externally recorded passing canary. */
    public synchronized EvoAsset promote(String assetId) {
        EvoAsset asset = registry.require(assetId);
        ensureLaterSplit(asset);
        if (asset.status() != EvoAsset.Status.CANARY || !asset.gateResults().canaryPassed()) {
            throw new IllegalStateException("asset has no passing canary: " + assetId);
        }
        return update(asset, asset.withStatus(EvoAsset.Status.PROMOTED));
    }

    /** Roll back a shadow, canary, or promoted rule while retaining its history. */
    public synchronized EvoAsset rollback(String assetId, String reason) {
        EvoAsset asset = registry.require(assetId);
        if (asset.status() == EvoAsset.Status.ROLLED_BACK
                || asset.status() == EvoAsset.Status.REJECTED) return asset;
        return update(asset, asset.rollback(reason));
    }

    /** Only promoted assets effective for the current split are returned. */
    public synchronized List<EvoAsset> activeAssets(String target) {
        if (currentSplit == null) return List.of();
        String normalized = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        return registry.list().stream()
                .filter(asset -> normalized.isBlank() || asset.target().equals(normalized))
                .filter(asset -> asset.isApplicable(currentSplit))
                .sorted(Comparator.comparing(EvoAsset::id))
                .toList();
    }

    public synchronized List<EvoAsset> activeAssets() {
        return activeAssets("");
    }

    public synchronized boolean isApplicable(String assetId) {
        EvoAsset asset = registry.get(assetId).orElse(null);
        return asset != null && currentSplit != null && asset.isApplicable(currentSplit);
    }

    public synchronized List<EvaluationSplit> splits() {
        return splits.values().stream().toList();
    }

    private EvoGateReport runRuntimeGate(String assetId, EvoGate gate,
                                         EvoAsset.Status requiredStatus,
                                         EvoAsset.Status passStatus,
                                         EvoGateEvaluator evaluator) {
        Objects.requireNonNull(evaluator, "gate evaluator must not be null");
        EvoAsset asset = registry.require(assetId);
        ensureLaterSplit(asset);
        if (asset.status() != requiredStatus) {
            throw new IllegalStateException("asset is not ready for " + gate + ": " + asset.status());
        }
        EvaluationSplit source = requireSplit(asset.evaluationSplit());
        EvoGateOutcome outcome = evaluateGate(gate, asset, source, evaluator);
        EvoAsset next = update(asset, asset.withGate(outcome));
        // withGate() already selects CANARY/PROMOTED or ROLLED_BACK.  Keep the
        // explicit status argument as a defensive assertion for custom assets.
        if (outcome.passed() && next.status() != passStatus) {
            next = update(next, next.withStatus(passStatus));
        }
        return report(next, List.of(outcome), outcome.passed(),
                outcome.passed() ? gate + " passed" : gate + " failed; rolled back");
    }

    private EvoGateOutcome evaluateGate(EvoGate gate, EvoAsset asset,
                                        EvaluationSplit source, EvoGateEvaluator evaluator) {
        if (gate == EvoGate.STACK_CONFIRMATION) {
            for (String compatible : asset.compatibleAssets()) {
                EvoAsset required = registry.get(compatible).orElse(null);
                if (required == null || !isApplicable(compatible)) {
                    return EvoGateOutcome.fail(gate, "compatible_asset_not_active:" + compatible);
                }
            }
        }
        EvoGateOutcome result;
        try {
            result = evaluator.evaluate(gate, asset,
                    new EvoGateEvaluator.EvaluationContext(source, currentSplit,
                            activeAssets(asset.target())));
        } catch (RuntimeException error) {
            result = EvoGateOutcome.fail(gate, "evaluator_error:" + safeMessage(error));
        }
        if (result == null || result.gate() != gate) {
            return EvoGateOutcome.fail(gate, "invalid_gate_outcome");
        }
        return result;
    }

    private EvoAsset update(EvoAsset old, EvoAsset next) {
        EvoAsset registered = registry.register(next);
        syncMemoryAsset(registered);
        return registered;
    }

    private EvoGateReport report(EvoAsset asset, List<EvoGateOutcome> outcomes,
                                 boolean passed, String message) {
        return new EvoGateReport(asset.id(), asset, outcomes, passed, message);
    }

    private void ensureStaticEvaluationAllowed(EvoAsset asset) {
        if (currentSplit == null) throw new IllegalStateException("no evaluation split is open");
        EvaluationSplit source = requireSplit(asset.evaluationSplit());
        if (currentSplit.ordinal() < source.ordinal()
                || (currentSplit.ordinal() == source.ordinal() && !currentSplit.closed())) {
            throw new IllegalStateException(
                    "static gates can run only after the source split is closed");
        }
    }

    private void ensureLaterSplit(EvoAsset asset) {
        if (currentSplit == null) throw new IllegalStateException("no evaluation split is open");
        if (currentSplit.ordinal() < asset.effectiveFromSplitOrdinal()) {
            throw new IllegalStateException("asset is not eligible until a later split");
        }
    }

    private boolean isStaticStatus(EvoAsset.Status status) {
        return status == EvoAsset.Status.CANDIDATE
                || status == EvoAsset.Status.TARGETED_PASSED
                || status == EvoAsset.Status.GLOBAL_PASSED
                || status == EvoAsset.Status.STACK_CONFIRMED;
    }

    private EvaluationSplit requireOpenSplit() {
        if (currentSplit == null || currentSplit.closed()) {
            throw new IllegalStateException("an open evaluation split is required");
        }
        return currentSplit;
    }

    private EvaluationSplit requireSplit(String id) {
        EvaluationSplit split = splits.get(id);
        if (split == null) throw new IllegalStateException("unknown evaluation split: " + id);
        return split;
    }

    private void syncMemoryAsset(EvoAsset asset) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("kind", "evo_asset");
        metadata.put("evo_id", asset.id());
        metadata.put("version", Integer.toString(asset.version()));
        metadata.put("status", asset.status().jsonName());
        metadata.put("failure_class", asset.failureClass());
        metadata.put("target", asset.target());
        metadata.put("evaluation_split", asset.evaluationSplit());
        metadata.put("effective_from_split_ordinal", Integer.toString(asset.effectiveFromSplitOrdinal()));
        metadata.put("gate_results", EvoAssetDsl.toJson(asset).contains("gate_results")
                ? "recorded" : "missing");
        memoryAssets.register(new MemoryAsset(asset.memoryAssetId(), MemoryAsset.Type.OTHER,
                MemoryAsset.OwnerScope.AGENT, ownerAgentId, tenantId,
                "evo-dsl:" + asset.id(), MemoryAssetAcl.privateAsset(true), true, metadata));
    }

    private static String newId() { return "evo-" + UUID.randomUUID(); }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String safeMessage(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
