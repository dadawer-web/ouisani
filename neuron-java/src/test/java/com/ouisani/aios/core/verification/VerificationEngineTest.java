package com.ouisani.aios.core.verification;

import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationEngineTest {

    private final VerificationEngine engine = new VerificationEngine();

    @Test
    void businessPredicatesAndOutputSchemaGateToolSuccess() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "committed");
        output.put("count", 2);
        Observation observation = new Observation(
                "wf", "node", VerificationStage.SKILL_END,
                output, Map.of("status", "pending", "count", 1),
                Map.of("prepare", "SUCCESS"), Set.of("prepare"), Set.of("prepare", "node"),
                List.of(), "", true, true, Map.of());

        VerificationContract contract = VerificationContract.builder()
                .predicate(GoalPredicate.outputEquals("status", "committed"))
                .predicate(GoalPredicate.stateChanged("count"))
                .predicate(GoalPredicate.requiredStepCompleted("prepare"))
                .require(EvidenceRequirement.outputSchema(Map.of(
                        "status", String.class, "count", Number.class)))
                .onFail(CorrectiveAction.REPLAN)
                .build();

        VerificationResult result = engine.verify(contract, observation);

        assertTrue(result.configured());
        assertTrue(result.isPass());
        assertNull(result.correctiveAction());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void missingPermissionIsInconclusiveAndRequestsObservation() {
        Observation observation = new Observation(
                "wf", "node", VerificationStage.SKILL_END,
                Map.of("result", "ok"), Map.of(), Map.of(), Set.of("node"), Set.of("node"),
                List.of(), "", null, true, Map.of());
        VerificationContract contract = VerificationContract.builder()
                .require(EvidenceRequirement.permissionStillValid())
                .onInconclusive(CorrectiveAction.OBSERVE)
                .build();

        VerificationResult result = engine.verify(contract, observation);

        assertEquals(Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(CorrectiveAction.OBSERVE, result.correctiveAction());
        assertFalse(result.isPass());
    }

    @Test
    void failedBusinessPredicateCarriesTheConfiguredRecoveryAction() {
        Observation observation = new Observation(
                "wf", "node", VerificationStage.SKILL_END,
                Map.of("status", "draft"), Map.of(), Map.of(), Set.of("node"), Set.of("node"),
                List.of(), "", true, true, Map.of());
        VerificationResult result = engine.verify(
                VerificationContract.builder()
                        .predicate(GoalPredicate.outputEquals("status", "committed"))
                        .onFail(CorrectiveAction.RETRY)
                        .build(), observation);

        assertTrue(result.isFail());
        assertEquals(CorrectiveAction.RETRY, result.correctiveAction());
        assertTrue(result.failures().stream().anyMatch(text -> text.contains("status")));
    }

    @Test
    void finalResponseMustBeCoveredByEvidence() {
        Observation observation = new Observation(
                "wf", "node", VerificationStage.FINAL,
                Map.of("final_response", "Result: artifact-42"), Map.of(), Map.of(),
                Set.of("node"), Set.of("node"),
                List.of(new Observation.Evidence("artifact-42", "/factory/report.md", "artifact-42", true)),
                "Result: artifact-42", true, true, Map.of());
        VerificationContract contract = VerificationContract.builder()
                .require(EvidenceRequirement.finalResponseCoveredBy("artifact-42"))
                .finalStage()
                .build();

        VerificationResult result = engine.verify(contract, observation);

        assertTrue(result.isPass());
    }

    @Test
    void workflowNodeCapturesBaselineForStateChangeChecks() {
        WorkflowNode node = new WorkflowNode("node", "worker", "sub");
        node.putOutput("state", "before");
        node.captureVerificationBaseline();
        node.putOutput("state", "after");

        Observation observation = new Observation(
                "wf", node.instanceId(), VerificationStage.SKILL_END,
                node.getOutputData(), node.verificationBaseline(), Map.of(),
                Set.of(node.instanceId()), Set.of(node.instanceId()), List.of(), "", true, true, Map.of());

        VerificationResult result = engine.verify(
                VerificationContract.builder().predicate(GoalPredicate.stateChanged("state")).build(),
                observation);

        assertTrue(result.isPass());
    }

    @Test
    void baselineDeepCopiesNestedState() {
        WorkflowNode node = new WorkflowNode("node", "worker", "sub");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("state", "before");
        node.putOutput("payload", nested);
        node.captureVerificationBaseline();
        nested.put("state", "after");

        Observation observation = new Observation(
                "wf", node.instanceId(), VerificationStage.SKILL_END,
                node.getOutputData(), node.verificationBaseline(), Map.of(),
                Set.of(node.instanceId()), Set.of(node.instanceId()), List.of(), "", true, true, Map.of());

        assertTrue(engine.verify(
                VerificationContract.builder().predicate(GoalPredicate.stateChanged("payload.state")).build(),
                observation).isPass());
    }
}
