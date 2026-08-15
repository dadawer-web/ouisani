package com.ouisani.aios.core.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAssetDelegationGuardTest {

    @AfterEach
    void clear() {
        DelegationGuard.clear();
        DelegationToken.clearRevocationForTest();
    }

    @Test
    void childTokenCarriesOnlyTheRequestedMemoryLoadout() {
        DelegationGuard.DelegationContext child = DelegationGuard.enter(
                "lead", "researcher", Set.of("memory:*"),
                Set.of("wiki:project", "graph:api"), 60_000, 3);

        assertEquals(Set.of("wiki:project", "graph:api"),
                child.token().delegableMemoryAssets());
        assertTrue(child.token().allowsMemoryAsset("wiki:project"));

        DelegationGuard.activate(child);
        assertThrows(DelegationGuard.DelegationException.class, () ->
                DelegationGuard.enter("researcher", "writer", Set.of("memory:*"),
                        Set.of("wiki:project", "skill:secret"), 60_000, 3));
    }

    @Test
    void syntheticRootPreservesTenantForAssetRecall() {
        DelegationGuard.DelegationContext child = DelegationGuard.enter(
                "lead", "researcher", Set.of("memory:read:memory"),
                Set.of("wiki:project"), 60_000, 3, null, "tenant-a");

        assertEquals("tenant-a", child.token().tenantId());
        assertTrue(child.token().allowsMemoryAsset("wiki:project"));
    }
}
