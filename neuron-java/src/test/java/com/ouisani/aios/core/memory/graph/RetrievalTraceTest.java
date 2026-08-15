package com.ouisani.aios.core.memory.graph;

import com.ouisani.aios.core.memory.VersionedMemoryStore;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalTraceTest {

    @Test
    void traceSeparatesSupportAndConflictAndHonoursEdgeWhitelist() {
        VersionedMemoryStore vms = new VersionedMemoryStore(new FakeProvider());
        TypedMemoryGraphStore graph = new TypedMemoryGraphStore(vms, null, "/var/db/retrieval-trace-test");
        String scope = "agent-retrieval";
        String tenant = "tenant-retrieval";
        MemoryNode claim = new MemoryNode("claim:deploy", MemoryNodeType.ARTIFACT,
                "deployment succeeded", "/repo/deploy.md", 0.9, Map.of(), tenant,
                MemoryVisibility.TENANT);
        MemoryNode support = new MemoryNode("evidence:healthy", MemoryNodeType.EVIDENCE,
                "deployment log says service healthy", "/logs/healthy.log", 0.95,
                Map.of("agentId", "agent-retrieval"), tenant, MemoryVisibility.TENANT);
        MemoryNode contradiction = new MemoryNode("evidence:failed", MemoryNodeType.EVIDENCE,
                "deployment health check failed", "/logs/failed.log", 0.8,
                Map.of("agentId", "agent-retrieval"), tenant, MemoryVisibility.TENANT);
        graph.upsertNode(scope, claim);
        graph.upsertNode(scope, support);
        graph.upsertNode(scope, contradiction);
        graph.upsertEdge(scope, SoftwareMemoryGraphMapper.supports(support.id(), claim.id(), 0.95));
        graph.upsertEdge(scope, SoftwareMemoryGraphMapper.contradicts(
                contradiction.id(), claim.id(), 0.8));

        MemoryGraphAccess access = MemoryGraphAccess.owner(scope, tenant);
        RetrievalQuery allRelations = new RetrievalQuery("deployment", access,
                SetOf.nodeTypes(), SetOf.edges(MemoryEdgeType.SUPPORTS, MemoryEdgeType.CONTRADICTS),
                System.currentTimeMillis(), Set.of("agent-retrieval"), Map.of(),
                8, 2, 20, 0.6, 0.2, 0.2, 0.0,
                RetrievalQuery.InsufficientEvidenceAction.REFUSE);
        RetrievalTrace conflicted = new HybridMemoryRetriever(graph).retrieve(allRelations);
        assertTrue(conflicted.conflicts().stream().anyMatch(c -> c.claimNodeId().equals(claim.id())));
        assertFalse(conflicted.sufficient());
        assertTrue(conflicted.toJson().contains("expanded_edges"));
        assertTrue(conflicted.evidenceBundle().stream().anyMatch(e -> "SUPPORT".equals(e.role())));
        assertTrue(conflicted.evidenceBundle().stream().anyMatch(e -> "CONFLICT".equals(e.role())));

        RetrievalQuery supportOnly = allRelations.toBuilder()
                .allowedEdges(Set.of(MemoryEdgeType.SUPPORTS))
                .insufficientEvidenceAction(RetrievalQuery.InsufficientEvidenceAction.OBSERVE)
                .build();
        RetrievalTrace grounded = new HybridMemoryRetriever(graph).retrieve(supportOnly);
        assertTrue(grounded.conflicts().isEmpty());
        assertTrue(grounded.sufficient());
        GroundedAnswerer.GroundedAnswer answer = new GroundedAnswerer().ground(
                "deployment log says service healthy", grounded);
        assertEquals(GroundedAnswerer.GroundingVerdict.PASS, answer.verdict());
        assertTrue(answer.evidenceIds().contains(support.id()));
    }

    @Test
    void identityAndValidityFiltersAreAppliedBeforeSeeding() {
        VersionedMemoryStore vms = new VersionedMemoryStore(new FakeProvider());
        TypedMemoryGraphStore graph = new TypedMemoryGraphStore(vms, null, "/var/db/retrieval-filter-test");
        String scope = "agent-filter";
        long now = System.currentTimeMillis();
        MemoryNode visible = new MemoryNode("event:visible", MemoryNodeType.EVENT,
                "approved deployment", now - 1_000, now + 60_000, "/trace/visible",
                1.0, Map.of("userId", "user-a"), "tenant-filter", MemoryVisibility.TENANT, 2);
        MemoryNode expired = new MemoryNode("event:expired", MemoryNodeType.EVENT,
                "approved deployment expired", now - 10_000, now - 5_000,
                "/trace/expired", 1.0, Map.of("userId", "user-a"),
                "tenant-filter", MemoryVisibility.TENANT, 2);
        graph.upsertNode(scope, visible);
        graph.upsertNode(scope, expired);

        RetrievalQuery query = new RetrievalQuery("approved", new MemoryGraphAccess(
                scope, "tenant-filter", true), Set.of(MemoryNodeType.EVENT), Set.of(),
                now, Set.of("user-a"), Map.of(), 8, 1, 10,
                1.0, 0.0, 0.0, 0.0,
                RetrievalQuery.InsufficientEvidenceAction.OBSERVE);
        RetrievalTrace trace = new HybridMemoryRetriever(graph).retrieve(query);
        assertEquals(List.of("event:visible"), trace.seeds().stream()
                .map(RetrievalTrace.Seed::nodeId).toList());
        assertTrue(trace.filters().toString().contains("valid_at"));
    }

    private static final class SetOf {
        static java.util.Set<MemoryNodeType> nodeTypes() { return java.util.Set.of(); }
        static java.util.Set<MemoryEdgeType> edges(MemoryEdgeType... values) {
            return java.util.Set.of(values);
        }
    }

    private static final class FakeProvider implements MemoryProvider {
        private final Map<String, List<MemoryRecord>> records = new ConcurrentHashMap<>();

        @Override
        public boolean store(String agentId, MemoryRecord record) {
            records.computeIfAbsent(agentId, ignored -> new ArrayList<>()).add(record);
            return true;
        }

        @Override
        public String retrieve(String agentId, String query) {
            return records.getOrDefault(agentId, List.of()).stream()
                    .map(MemoryRecord::content)
                    .filter(content -> content != null && (query == null || content.contains(query)))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
        }

        @Override
        public void clear(String agentId) {
            records.remove(agentId);
        }

        @Override
        public String providerName() {
            return "retrieval-trace-test";
        }
    }
}
