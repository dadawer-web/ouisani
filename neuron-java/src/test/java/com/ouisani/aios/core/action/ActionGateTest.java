package com.ouisani.aios.core.action;

import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.recovery.RecoveryAuthorizationManager;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionGateTest {

    @org.junit.jupiter.api.AfterEach
    void clearRecoveryBoundary() {
        RecoveryAuthorizationManager.instance().clear();
    }

    private static final ToolInput INPUT = new ToolInput() {
        @Override public String toJson() { return "{\"path\":\"/tmp/a\",\"content\":\"x\"}"; }
    };

    private static final Tool<ToolInput> READ_TOOL = new Tool<>() {
        @Override public String name() { return "file_read"; }
        @Override public String description() { return "read"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolOutput call(ToolInput input, ToolContext context) { return ToolOutput.ok("ok"); }
        @Override public boolean readOnly() { return true; }
    };

    private static final Tool<ToolInput> WRITE_TOOL = new Tool<>() {
        @Override public String name() { return "file_write"; }
        @Override public String description() { return "write"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolOutput call(ToolInput input, ToolContext context) { return ToolOutput.ok("ok"); }
    };

    @Test
    void envelopeDigestIsStableAndDetectsParameterMutation() {
        ActionEnvelope envelope = ActionEnvelope.forTool(
                "tenant-a", "wf-1", "run-1", "trace-1", "agent-1",
                "parent-1", "delegation-1", "node-1", 1,
                "file_write", "execute", "/tmp/a", INPUT.toJson());

        assertTrue(envelope.actionDigest().startsWith("sha256:"));
        assertTrue(ActionGate.instance().validateDigest(envelope, INPUT.toJson()));
        assertFalse(ActionGate.instance().validateDigest(envelope,
                "{\"path\":\"/tmp/a\",\"content\":\"changed\"}"));
    }

    @Test
    void readActionCanBeAllowedWithoutWorkflowIdentity() {
        ActionEnvelope envelope = ActionEnvelope.forTool(
                null, null, null, "trace-1", "agent-1", null, null, null, 0,
                "file_read", "read", "/tmp/a", INPUT.toJson());
        ActionGate.GateResult result = ActionGate.instance().authorize(
                envelope, READ_TOOL, INPUT, new ToolContext("agent-1", null, "/tmp"),
                new PermissionChecker());

        assertEquals(ActionGate.Decision.ALLOW, result.decision());
    }

    @Test
    void sideEffectWithoutWorkflowIdentityIsDeniedBeforePermissionPrompt() {
        ActionEnvelope envelope = ActionEnvelope.forTool(
                null, null, "run-1", "trace-1", "agent-1", null, null, null, 0,
                "file_write", "execute", "/tmp/a", INPUT.toJson());
        ActionGate.GateResult result = ActionGate.instance().authorize(
                envelope, WRITE_TOOL, INPUT, new ToolContext("agent-1", null, "/tmp"),
                new PermissionChecker());

        assertEquals(ActionGate.Decision.DENY, result.decision());
        assertEquals("missing_workflow_identity_for_side_effect", result.reason());
    }

    @Test
    void restoredWorkflowBlocksSideEffectBeforePermissionCheck() {
        RecoveryAuthorizationManager.instance().markRestored("wf-restored");
        ActionEnvelope envelope = ActionEnvelope.forTool(
                "tenant-a", "wf-restored", "run-1", "trace-1", "agent-1",
                null, null, null, 0, "file_write", "execute", "/tmp/a", INPUT.toJson());

        ActionGate.GateResult result = ActionGate.instance().authorize(
                envelope, WRITE_TOOL, INPUT, new ToolContext("agent-1", null, "/tmp"),
                new PermissionChecker());

        assertEquals(ActionGate.Decision.DENY, result.decision());
        assertTrue(result.reason().startsWith("recovery_reauthorization_required:"));
    }
}
