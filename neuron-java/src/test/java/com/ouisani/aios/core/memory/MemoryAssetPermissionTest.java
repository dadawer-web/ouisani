package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import com.ouisani.aios.core.tool.DelegationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAssetPermissionTest {

    private final MemoryAssetRegistry registry = MemoryAssetRegistry.global();

    @AfterEach
    void clear() {
        registry.clearForTest();
        DelegationToken.clearRevocationForTest();
    }

    @Test
    void privateAssetIsOwnerOnlyEvenWhenTheCallerSharesTheTenant() {
        MemoryAsset asset = new MemoryAsset("wiki:private", MemoryAsset.Type.WIKI,
                MemoryAsset.OwnerScope.USER, "user-owner", "tenant-a", "wiki/private",
                MemoryAssetAcl.privateAsset(), true, Map.of());
        MemoryAccessContext owner = MemoryAccessContext.of("agent-owner", "tenant-a",
                "wf", "team-a", "user-owner", Set.of("writer"));
        MemoryAccessContext peer = MemoryAccessContext.of("agent-peer", "tenant-a",
                "wf", "team-a", "user-peer", Set.of("writer"));

        assertTrue(asset.canRead(owner));
        assertTrue(asset.canBindToAgent(owner, "child"));
        assertFalse(asset.canRead(peer));
        assertEquals("private_owner_only", asset.authorizeRead(peer).reason());
    }

    @Test
    void teamAssetAllowsTeamMembersButNotAParallelTeam() {
        MemoryAsset asset = new MemoryAsset("wiki:team", MemoryAsset.Type.WIKI,
                MemoryAsset.OwnerScope.TEAM, "team-red", "tenant-a", "wiki/team",
                MemoryAssetAcl.team("team-red"), true, Map.of());
        MemoryAccessContext member = MemoryAccessContext.of("agent-member", "tenant-a",
                "wf", "team-red");
        MemoryAccessContext outsider = MemoryAccessContext.of("agent-outsider", "tenant-a",
                "wf", "team-blue");

        assertTrue(asset.canRead(member));
        assertTrue(asset.canBindToAgent(member, "child"));
        assertFalse(asset.canRead(outsider));
        assertFalse(asset.canBindToAgent(outsider, "child"));
        assertEquals("team_member_required", asset.authorizeRead(outsider).reason());
    }

    @Test
    void restrictedAclSeparatesReaderRoleAndChildAgentTarget() {
        MemoryAssetAcl acl = MemoryAssetAcl.restricted(
                Set.of("user-alice"), Set.of("researcher"), Set.of("agent-researcher"),
                Set.of(), Set.of("agent-researcher"), Set.of(), true);
        MemoryAsset asset = new MemoryAsset("wiki:restricted", MemoryAsset.Type.WIKI,
                MemoryAsset.OwnerScope.USER, "user-owner", "tenant-a", "wiki/restricted",
                acl, true, Map.of());
        MemoryAccessContext roleReader = MemoryAccessContext.of("agent-parent", "tenant-a",
                "wf", "team-a", "user-alice", Set.of("researcher"));
        MemoryAccessContext unrelated = MemoryAccessContext.of("agent-other", "tenant-a",
                "wf", "team-a", "user-bob", Set.of("reviewer"));

        assertTrue(asset.canRead(roleReader));
        assertTrue(asset.canBindToAgent(roleReader, "agent-researcher"));
        assertFalse(asset.canBindToAgent(roleReader, "agent-writer"));
        assertFalse(asset.canRead(unrelated));
        assertEquals("restricted_acl_denied", asset.authorizeRead(unrelated).reason());
    }

    @Test
    void loadoutReturnsBindingReasonAndExactTokenIsRuntimeProof() {
        MemoryAssetAcl acl = MemoryAssetAcl.restricted(
                Set.of("user-alice"), Set.of("researcher"), Set.of(),
                Set.of(), Set.of("agent-child"), Set.of(), true);
        registry.register(new MemoryAsset("wiki:restricted", MemoryAsset.Type.WIKI,
                MemoryAsset.OwnerScope.USER, "user-owner", "tenant-a", "wiki/restricted",
                acl, true, Map.of()));
        MemoryAccessContext parent = MemoryAccessContext.of("agent-parent", "tenant-a",
                "wf", null, "user-alice", Set.of("researcher"));

        MemoryAssetLoadout denied = registry.createLoadout("agent-parent", "agent-writer",
                Set.of("wiki:restricted"), parent);
        assertTrue(denied.hasDeniedAssets());
        assertEquals("child_agent_acl_denied", denied.denialReasons().get("wiki:restricted"));

        MemoryAssetLoadout allowed = registry.createLoadout("agent-parent", "agent-child",
                Set.of("wiki:restricted"), parent);
        assertTrue(allowed.assetIds().contains("wiki:restricted"));
        DelegationToken token = DelegationToken.rootWithMemoryAssets(
                "agent-parent", "tenant-a", "wf", "trace", Set.of("memory:*"),
                allowed.assetIds());
        DelegationToken child = DelegationToken.issueChildWithMemoryAssets(
                token, "agent-child", Set.of("memory:*"), allowed.assetIds(), 60_000, 3);
        MemoryAccessContext childContext = MemoryAccessContext.of("agent-child", "tenant-a",
                "wf", null, child);
        assertTrue(registry.isRecallAllowed("wiki:restricted", childContext));
    }

    @Test
    void recallDoesNotTreatADelegationWildcardAsAnAclBypass() {
        registry.register(MemoryAsset.chatMemory("owner", "tenant-a"));
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        store.store("outsider", MemoryRecord.core("rule", "owner-only rule",
                "lifecycle:l3-promotion;asset=chat:owner;tenant=tenant-a",
                System.currentTimeMillis(), 0.9, MemoryDomain.USER));
        DelegationToken wildcard = DelegationToken.root("outsider", "tenant-a", "wf", "trace");
        MemoryAccessContext outsider = MemoryAccessContext.of("outsider", "tenant-a", "wf",
                null, wildcard);

        MemoryRecallHook.RecallResult result = new MemoryRecallHook(store, null, registry).recall(
                MemoryRecallHook.RecallRequest.of("outsider", "rule"), outsider);
        assertTrue(result.records().isEmpty());
        assertTrue(result.permissionFilteredCount() >= 1);
    }

    private static final class FakeProvider implements MemoryProvider {
        private final Map<String, List<MemoryRecord>> stored = new ConcurrentHashMap<>();

        @Override
        public boolean store(String agentId, MemoryRecord record) {
            stored.computeIfAbsent(agentId, ignored -> new ArrayList<>()).add(record);
            return true;
        }

        @Override
        public String retrieve(String agentId, String query) { return ""; }

        @Override public void clear(String agentId) { stored.remove(agentId); }

        @Override public String providerName() { return "permission-test"; }
    }
}
