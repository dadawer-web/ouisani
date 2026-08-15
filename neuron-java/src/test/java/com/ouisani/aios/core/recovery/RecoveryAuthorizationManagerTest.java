package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.action.ActionEnvelope;
import com.ouisani.aios.core.tool.DelegationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryAuthorizationManagerTest {

    private final RecoveryAuthorizationManager manager = RecoveryAuthorizationManager.instance();

    @AfterEach
    void reset() {
        manager.clear();
        DelegationToken.clearRevocationForTest();
    }

    @Test
    void restoredWorkflowBlocksMutationUntilExplicitReauthorization() {
        manager.markRestored("wf-restored");

        ActionEnvelope write = ActionEnvelope.forTool(
                "tenant-a", "wf-restored", "run-1", "trace-1", "agent-1",
                null, null, null, 0, "file_write", "execute", "/tmp/a", "{\"x\":1}");
        ActionEnvelope read = ActionEnvelope.forTool(
                "tenant-a", "wf-restored", "run-1", "trace-1", "agent-1",
                null, null, null, 0, "file_read", "read", "/tmp/a", "{}");

        assertEquals("recovery_reauthorization_required:wf-restored", manager.denialReason(write));
        assertNull(manager.denialReason(read), "只读检查仍应可用");
        assertTrue(manager.reauthorize("wf-restored", "operator-1", "approval-123"));
        assertNull(manager.denialReason(write));
    }

    @Test
    void preRestoreDelegationTokenIsInvalidAfterRestore() {
        DelegationToken token = DelegationToken.rootWithCapabilities(
                "agent-1", "tenant-a", "wf-restored", "trace-1", Set.of("tool:file_write"));
        assertTrue(token.isValid());

        manager.markRestored("wf-restored");

        assertFalse(token.isValid(), "恢复前签发的 token 不得跨越恢复边界");
        DelegationToken fresh = DelegationToken.root("agent-1", "tenant-a", "wf-restored", "trace-2");
        assertTrue(fresh.isValid(), "恢复后新签发的 token 可以使用");
    }

    @Test
    void unknownActionCannotEvadePendingBoundaryByOmittingWorkflow() {
        manager.markRestored("wf-restored");
        ActionEnvelope missingWorkflow = ActionEnvelope.forTool(
                "tenant-a", null, "run-unknown", "trace-1", "agent-1",
                null, null, null, 0, "shell", "execute", "cmd", "{}");
        assertTrue(manager.denialReason(missingWorkflow).startsWith("recovery_reauthorization_required:"));
    }
}
