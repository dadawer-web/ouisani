package com.ouisani.aios.core.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;
import com.ouisani.aios.core.permission.PermissionBehavior;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.permission.PermissionRule;

import static org.junit.jupiter.api.Assertions.*;

class DelegationTokenTest {

    @AfterEach
    void clearContext() {
        DelegationGuard.clear();
        DelegationToken.clearRevocationForTest();
    }

    @Test
    void childCapabilitiesCannotExceedParent() {
        DelegationToken root = DelegationToken.root("root", "tenant-a", "workflow-a", "trace-a");
        DelegationToken readOnly = DelegationToken.issueChild(
                root, "reviewer", Set.of("tool:file_read"), 60_000, 3);

        assertTrue(readOnly.isValid());
        assertTrue(readOnly.allowsTool("file_read"));
        assertFalse(readOnly.allowsTool("file_write"));
        assertThrows(IllegalArgumentException.class,
                () -> DelegationToken.issueChild(readOnly, "writer", Set.of("tool:file_write"), 60_000, 1));
    }

    @Test
    void signatureAndExpiryAreChecked() throws Exception {
        DelegationToken root = DelegationToken.root("root");
        DelegationToken shortLived = DelegationToken.issueChild(root, "child", Set.of("tool:file_read"), 1, 1);
        assertTrue(shortLived.isValid());
        Thread.sleep(8);
        assertFalse(shortLived.isValid());
        assertFalse(shortLived.consumeCall());
    }

    @Test
    void callBudgetIsAtomicAndExpiresAfterUse() {
        DelegationToken root = DelegationToken.root("root");
        DelegationToken oneCall = DelegationToken.issueChild(root, "child", Set.of("tool:file_read"), 60_000, 1);
        assertTrue(oneCall.consumeCall());
        assertFalse(oneCall.consumeCall());
        assertEquals(0, oneCall.remainingCalls());
    }

    @Test
    void guardPropagatesTokenAndEnforcesCapability() {
        DelegationGuard.DelegationContext ctx = DelegationGuard.enter("root", "child",
                Set.of("tool:file_read"), 60_000, 2);
        DelegationGuard.activate(ctx);

        assertEquals(ctx.token(), DelegationGuard.currentToken());
        assertNull(DelegationGuard.checkToolAllowed("file_read"));
        assertTrue(DelegationGuard.checkToolAllowed("file_write")
                .startsWith("delegation_capability_denied"));
        assertNull(DelegationGuard.checkToolAllowed("file_read"));
        assertEquals("delegation_call_budget_exhausted",
                DelegationGuard.checkToolAllowed("file_read"));
    }

    @Test
    void profileBoundRootCannotDelegateWriteCapability() {
        PermissionProfile readOnly = new PermissionProfile(
                PermissionMode.DEFAULT,
                List.of(new PermissionRule(PermissionRule.RuleSource.POLICY_SETTINGS,
                        PermissionBehavior.DENY, "file_write", null)),
                List.of(), List.of(new PermissionRule(PermissionRule.RuleSource.POLICY_SETTINGS,
                        PermissionBehavior.ALLOW, "file_read", null)));
        // The production AgentTool maps this profile to a read-only root capability set;
        // the token primitive itself must still reject the attempted write attenuation.
        DelegationToken root = DelegationToken.rootWithCapabilities("root", null, null, null,
                Set.of("tool:file_read"));
        assertThrows(IllegalArgumentException.class,
                () -> DelegationToken.issueChild(root, "child", Set.of("tool:file_write"), 1000, 1));
        assertTrue(readOnly.allowRules().stream().anyMatch(r -> "file_read".equals(r.toolName())));
    }
}
