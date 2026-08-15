package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.verification.Observation;
import com.ouisani.aios.core.verification.VerificationEngine;
import com.ouisani.aios.core.verification.VerificationStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyVerificationContractTest {

    @Test
    void parsesDeclarativeVerificationContract() {
        String json = """
                {
                  "instanceId": "publish",
                  "role": "publisher",
                  "verification": {
                    "stages": ["DURING", "SKILL_END"],
                    "onFail": "REPLAN",
                    "onInconclusive": "OBSERVE",
                    "predicates": [
                      {"type": "output_equals", "key": "status", "value": "committed"},
                      {"type": "state_changed", "key": "version"}
                    ],
                    "evidence": [
                      {"type": "output_key", "reference": "result"},
                      {"type": "permission_approval"}
                    ]
                  }
                }
                """;

        WorkflowNode node = TopologyJsonParser.parseSingleNode(json);

        assertNotNull(node);
        assertNotNull(node.verificationContract());
        assertTrue(node.verificationContract().stages().contains(VerificationStage.DURING));
        assertTrue(node.verificationContract().stages().contains(VerificationStage.SKILL_END));
        assertEquals(2, node.verificationContract().predicates().size());
        assertEquals(2, node.verificationContract().evidenceRequirements().size());

        Observation observation = new Observation(
                "wf", "publish", VerificationStage.SKILL_END,
                Map.of("status", "committed", "version", 2, "result", "ok"),
                Map.of("version", 1), Map.of(), Set.of("publish"), Set.of("publish"),
                List.of(), "", true, true, Map.of());

        assertTrue(new VerificationEngine().verify(node.verificationContract(), observation).isPass());
    }
}
