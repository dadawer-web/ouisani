package com.ouisani.aios.core.evolution;

import com.ouisani.aios.core.memory.MemoryAssetRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvoAssetPipelineTest {

    @Test
    void candidateCannotAffectItsSourceSplitAndPromotesOnlyAfterLaterGates() {
        MemoryAssetRegistry memory = new MemoryAssetRegistry();
        EvoAssetRegistry assets = new EvoAssetRegistry();
        EvoAssetPipeline pipeline = new EvoAssetPipeline(assets, memory,
                new FailureDiagnoser(), "agent-a", "tenant-a");

        pipeline.beginSplit("split-1", 1);
        EvoAsset candidate = pipeline.propose(FailureDiagnoser.FailureTrajectory.of(
                "run-1", "split-1", "TEMPORAL_GROUNDING", "retriever",
                "stale timestamp evidence"));

        assertEquals(EvoAsset.Status.CANDIDATE, candidate.status());
        assertFalse(pipeline.isApplicable(candidate.id()));
        assertTrue(memory.get(candidate.memoryAssetId()).isPresent());
        assertThrows(IllegalStateException.class,
                () -> pipeline.evaluate(candidate.id(), EvoGateEvaluator.alwaysPass()));

        pipeline.endSplit();
        EvoGateReport staticReport = pipeline.evaluate(candidate.id(), EvoGateEvaluator.alwaysPass());
        assertTrue(staticReport.passed());
        assertEquals(EvoAsset.Status.SHADOW, assets.require(candidate.id()).status());
        assertFalse(pipeline.isApplicable(candidate.id()));

        pipeline.beginSplit("split-2", 2);
        EvoGateReport shadow = pipeline.runShadow(candidate.id(), EvoGateEvaluator.alwaysPass());
        assertTrue(shadow.passed());
        assertEquals(EvoAsset.Status.CANARY, assets.require(candidate.id()).status());
        EvoGateReport canary = pipeline.runCanary(candidate.id(), EvoGateEvaluator.alwaysPass());
        assertTrue(canary.passed());
        assertEquals(EvoAsset.Status.PROMOTED, assets.require(candidate.id()).status());
        assertTrue(pipeline.isApplicable(candidate.id()));
        assertEquals(List.of(candidate.id()), pipeline.activeAssets("retriever")
                .stream().map(EvoAsset::id).toList());
        assertTrue(assets.history(candidate.id()).size() >= 7);
    }

    @Test
    void failedStaticGateRejectsAndDoesNotEnterActiveStack() {
        EvoAssetPipeline pipeline = new EvoAssetPipeline(new MemoryAssetRegistry());
        pipeline.beginSplit("split-1", 1);
        EvoAsset candidate = pipeline.propose(FailureDiagnoser.FailureTrajectory.of(
                "run-2", "split-1", "VERIFICATION", "verifier", "missing evidence"));
        pipeline.endSplit();

        EvoGateReport report = pipeline.evaluate(candidate.id(), (gate, asset, context) ->
                gate == EvoGate.TARGETED_REGRESSION
                        ? EvoGateOutcome.fail(gate, "targeted_regression_failed")
                        : EvoGateOutcome.pass(gate));
        assertFalse(report.passed());
        assertEquals(EvoAsset.Status.REJECTED, report.asset().status());
        pipeline.beginSplit("split-2", 2);
        assertTrue(pipeline.activeAssets().isEmpty());
    }

    @Test
    void stackConfirmationRequiresCompatibleAssetsToBeActive() {
        EvoAssetPipeline pipeline = new EvoAssetPipeline(new MemoryAssetRegistry());
        pipeline.beginSplit("split-1", 1);
        FailureDiagnoser.Diagnosis diagnosis = new FailureDiagnoser.Diagnosis(
                "RETRIEVAL_GROUNDING", "retriever", Map.of(), Map.of(),
                List.of("run-3"), "conflicting evidence", List.of("evo-missing"));
        EvoAsset candidate = pipeline.propose(diagnosis);
        pipeline.endSplit();

        EvoGateReport report = pipeline.evaluate(candidate.id(), EvoGateEvaluator.alwaysPass());
        assertFalse(report.passed());
        assertEquals(EvoGate.STACK_CONFIRMATION, report.outcomes().getLast().gate());
        assertEquals(EvoAsset.Status.REJECTED, report.asset().status());
        assertTrue(report.asset().gateResults().failures().stream()
                .anyMatch(value -> value.contains("compatible_asset_not_active")));
    }

    @Test
    void shadowOrCanaryFailureRollsBackWithoutPromotion() {
        EvoAssetPipeline pipeline = new EvoAssetPipeline(new MemoryAssetRegistry());
        pipeline.beginSplit("split-1", 1);
        EvoAsset candidate = pipeline.propose(FailureDiagnoser.FailureTrajectory.of(
                "run-4", "split-1", "TEMPORAL_GROUNDING", "retriever", "bad temporal edge"));
        pipeline.endSplit();
        pipeline.evaluate(candidate.id(), EvoGateEvaluator.alwaysPass());
        pipeline.beginSplit("split-2", 2);

        EvoGateReport shadow = pipeline.runShadow(candidate.id(), (gate, asset, context) ->
                EvoGateOutcome.fail(gate, "shadow_regression_failed"));
        assertFalse(shadow.passed());
        assertEquals(EvoAsset.Status.ROLLED_BACK, shadow.asset().status());
        assertTrue(pipeline.activeAssets().isEmpty());
    }

    @Test
    void dslContainsRequiredFieldsAndRoundTripsVersionedState() {
        EvoAsset asset = new EvoAsset("evo-json-1", "TEMPORAL_GROUNDING", "retriever",
                Map.of("window", "recent"), Map.of("kind", "evidence_guard"),
                List.of("run-5"), "split-4", EvoAsset.GateResults.empty(), List.of(),
                EvoAsset.Status.CANDIDATE);
        String json = EvoAssetDsl.toJson(asset);
        assertTrue(json.contains("\"failure_class\""));
        assertTrue(json.contains("\"created_from_runs\""));
        assertTrue(json.contains("\"gate_results\""));
        assertTrue(json.contains("\"status\":\"candidate\""));
        EvoAsset restored = EvoAssetDsl.fromJson(json);
        assertEquals(asset.id(), restored.id());
        assertEquals(asset.failureClass(), restored.failureClass());
        assertEquals(asset.evaluationSplit(), restored.evaluationSplit());
        assertEquals(asset.status(), restored.status());
        assertNotNull(restored.gateResults());
    }
}
