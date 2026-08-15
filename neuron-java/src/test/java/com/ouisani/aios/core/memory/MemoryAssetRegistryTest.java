package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import com.ouisani.aios.core.tool.DelegationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAssetRegistryTest {

    private final MemoryAssetRegistry registry = MemoryAssetRegistry.global();

    @AfterEach
    void clearAssets() {
        registry.clearForTest();
        DelegationToken.clearRevocationForTest();
    }

    @Test
    void childLoadoutIsAnAclAndSignedTokenSubset() {
        registry.register(MemoryAsset.wiki("wiki:project", MemoryAsset.OwnerScope.AGENT,
                "lead", "tenant-a", "wiki/project", Set.of(), Set.of()));
        registry.register(MemoryAsset.skill("skill:secret", "lead", "tenant-a",
                "skills/secret", Set.of(), Set.of()));

        DelegationToken parent = DelegationToken.rootWithMemoryAssets(
                "lead", "tenant-a", "workflow-a", "trace-a", Set.of("memory:*"),
                Set.of("wiki:project"));
        MemoryAccessContext access = MemoryAccessContext.of(
                "lead", "tenant-a", "workflow-a", null, parent);
        MemoryAssetLoadout loadout = registry.createLoadout("lead", "researcher",
                Set.of("wiki:project", "skill:secret"), access);

        assertEquals(Set.of("wiki:project"), loadout.assetIds());
        assertEquals(Set.of("skill:secret"), loadout.deniedAssetIds());
        assertTrue(loadout.isSubsetOf(parent.delegableMemoryAssets()));

        DelegationToken child = DelegationToken.issueChildWithMemoryAssets(
                parent, "researcher", Set.of("memory:*"), loadout.assetIds(), 60_000, 3);
        assertTrue(child.allowsMemoryAsset("wiki:project"));
        assertFalse(child.allowsMemoryAsset("skill:secret"));
        assertThrows(IllegalArgumentException.class, () ->
                DelegationToken.issueChildWithMemoryAssets(parent, "writer",
                        Set.of("memory:*"), Set.of("skill:secret"), 60_000, 3));
    }

    @Test
    void rolePresetsSeparateResearcherWriterAndReviewerMemory() {
        registry.register(MemoryAsset.chatMemory("lead", "tenant-a"));
        registry.register(MemoryAsset.skill("skill:style", "lead", "tenant-a",
                "skills/style", Set.of(), Set.of()));
        registry.register(MemoryAsset.wiki("wiki:project", MemoryAsset.OwnerScope.AGENT,
                "lead", "tenant-a", "wiki/project", Set.of(), Set.of()));
        registry.register(MemoryAsset.codeGraph("graph:api", MemoryAsset.OwnerScope.AGENT,
                "lead", "tenant-a", "graph/api", Set.of(), Set.of()));

        DelegationToken root = DelegationToken.root("lead", "tenant-a", "workflow-a", "trace-a");
        MemoryAccessContext access = MemoryAccessContext.of("lead", "tenant-a", "workflow-a", null, root);
        MemoryAssetLoadout researcher = registry.createRoleLoadout("lead", "researcher",
                "researcher", access);
        MemoryAssetLoadout writer = registry.createRoleLoadout("lead", "writer", "writer", access);
        MemoryAssetLoadout reviewer = registry.createRoleLoadout("lead", "reviewer", "reviewer", access);

        assertTrue(researcher.assetIds().containsAll(Set.of("wiki:project", "graph:api", "skill:style")));
        assertTrue(writer.assetIds().containsAll(Set.of("chat:lead", "wiki:project", "skill:style")));
        assertTrue(reviewer.assetIds().containsAll(Set.of("wiki:project", "graph:api", "skill:style")));
        assertFalse(researcher.assetIds().contains("chat:lead"));
    }

    @Test
    void recallHonorsExplicitLoadoutAndNeverMountsSiblingAssets() {
        registry.register(MemoryAsset.wiki("wiki:project", MemoryAsset.OwnerScope.AGENT,
                "lead", "tenant-a", "wiki/project", Set.of(), Set.of()));
        registry.register(MemoryAsset.codeGraph("graph:internal", MemoryAsset.OwnerScope.AGENT,
                "lead", "tenant-a", "graph/internal", Set.of(), Set.of()));
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        long now = System.currentTimeMillis();
        store.store("researcher", MemoryRecord.core("wiki", "project conventions",
                "lifecycle:l3-promotion;asset=wiki:project;tenant=tenant-a",
                now, 0.9, MemoryDomain.USER));
        store.store("researcher", MemoryRecord.core("graph", "private graph detail",
                "lifecycle:l3-promotion;asset=graph:internal;tenant=tenant-a",
                now, 0.99, MemoryDomain.AGENT));

        DelegationToken parent = DelegationToken.rootWithMemoryAssets(
                "lead", "tenant-a", "workflow-a", "trace-a", Set.of("memory:*"),
                Set.of("wiki:project", "graph:internal"));
        DelegationToken child = DelegationToken.issueChildWithMemoryAssets(
                parent, "researcher", Set.of("memory:*"), Set.of("wiki:project"), 60_000, 3);
        MemoryAccessContext context = MemoryAccessContext.of(
                "researcher", "tenant-a", "workflow-a", null, child);
        MemoryRecallHook.RecallResult result = new MemoryRecallHook(store, null, registry).recall(
                MemoryRecallHook.RecallRequest.withLoadout("researcher", "tenant-a", "workflow-a",
                        null, "project", 8, 2_000, Set.of("wiki:project")), context);

        assertEquals(List.of("wiki"), result.records().stream().map(MemoryRecord::key).toList());
        assertTrue(result.permissionFilteredCount() >= 1);
        assertFalse(result.context().contains("private graph detail"));
    }

    private static final class FakeProvider implements MemoryProvider {
        private final Map<String, List<MemoryRecord>> stored = new ConcurrentHashMap<>();

        @Override
        public boolean store(String agentId, MemoryRecord record) {
            stored.computeIfAbsent(agentId, ignored -> new ArrayList<>()).add(record);
            return true;
        }

        @Override
        public String retrieve(String agentId, String query) {
            return stored.getOrDefault(agentId, List.of()).stream()
                    .map(MemoryRecord::content).filter(value -> value.contains(query))
                    .findFirst().orElse("");
        }

        @Override public void clear(String agentId) { stored.remove(agentId); }

        @Override public String providerName() { return "asset-test"; }
    }
}
