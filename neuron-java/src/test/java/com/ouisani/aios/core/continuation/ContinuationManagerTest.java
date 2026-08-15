package com.ouisani.aios.core.continuation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContinuationManagerTest {
    private final ContinuationManager manager = ContinuationManager.instance();
    private Path store;

    @BeforeEach
    void setUp() throws Exception {
        store = Files.createTempFile("continuation-test", ".json");
        manager.setStoreFileForTest(store);
        manager.clearForTest();
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.clearForTest();
        Files.deleteIfExists(store);
    }

    @Test
    void retainsReadResultsButRequiresApprovalForSideEffects() {
        manager.registerPlan("run-1", "workflow-1", null, "trace-1", List.of(
                new ContinuationManager.PlanStep("inspect", "Inspect files", "PENDING", null, List.of(), null, null, null, false),
                new ContinuationManager.PlanStep("write", "Write report", "PENDING", null, List.of("inspect"), null, "execute", null, true)));
        manager.recordToolResult("run-1", "file_read", "{\"path\":\"a.txt\"}", "a.txt", "read", "sha256:read", "ok", true, true, 1);
        manager.recordToolResult("run-1", "file_write", "{\"path\":\"out.txt\"}", "out.txt", "execute", "sha256:write", "written", true, false, 2);
        manager.markStepCompleted("run-1", "inspect", false);
        manager.captureInterruption("run-1", "user_interrupt");

        ContinuationManager.ContinuationPlan plan = manager.prepare("run-1", "改为只检查配置，不要写文件");
        assertEquals(1, plan.reusableResults().size());
        assertEquals("file_read", plan.reusableResults().get(0).toolName());
        assertTrue(plan.invalidatedSteps().stream().anyMatch(step -> step.stepId().equals("write")));
        assertTrue(plan.requiresApproval().stream().noneMatch(step -> step.toolName().equals("file_write")));
        assertEquals(ContinuationManager.STATE_READY, plan.state());
    }

    @Test
    void persistsCheckpointAcrossManagerReload() {
        manager.registerPlan("run-2", "workflow-2", null, "trace-2", List.of());
        manager.recordToolResult("run-2", "grep", "{}", "", "read", "digest", "found", true, true, 1);
        manager.captureInterruption("run-2", "stop");

        manager.setStoreFileForTest(store);
        var plan = manager.get("run-2").orElseThrow();
        assertEquals(1, plan.retainedTools().size());
        assertEquals("found", plan.retainedTools().get(0).result());
    }
}
