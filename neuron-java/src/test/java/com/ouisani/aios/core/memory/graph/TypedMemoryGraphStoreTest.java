package com.ouisani.aios.core.memory.graph;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.memory.VersionedMemoryStore;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedMemoryGraphStoreTest {

    private FakeProvider provider;
    private VersionedMemoryStore versioned;
    private TypedMemoryGraphStore graph;

    @BeforeEach
    void setUp() {
        provider = new FakeProvider();
        versioned = new VersionedMemoryStore(provider);
        graph = new TypedMemoryGraphStore(versioned, null, "/var/db/test-graph");
    }

    @Test
    void storesTypedNodesAndEdgesAndTraversesEvidenceSubgraph() {
        String scope = "agent-a";
        MemoryNode workspace = SoftwareMemoryGraphMapper.workspace(
                "workspace:alpha", "Alpha workspace", "/factory/alpha", "tenant-a");
        MemoryNode commit = SoftwareMemoryGraphMapper.event(
                "event:commit:1", "Committed report", "trace-1",
                Map.of("tool", "git_commit", "status", "success"),
                "tenant-a", MemoryVisibility.TENANT);
        MemoryNode report = SoftwareMemoryGraphMapper.artifact(
                "artifact:report", "Generated report", "/factory/alpha/report.md",
                "tenant-a", MemoryVisibility.TENANT);
        MemoryNode testLog = SoftwareMemoryGraphMapper.evidence(
                "evidence:test:1", "Tests passed", "/factory/alpha/test.log",
                "tenant-a", MemoryVisibility.TENANT);

        assertTrue(graph.upsertNode(scope, workspace));
        assertTrue(graph.upsertNode(scope, commit));
        assertTrue(graph.upsertNode(scope, report));
        assertTrue(graph.upsertNode(scope, testLog));
        assertTrue(graph.upsertEdge(scope, SoftwareMemoryGraphMapper.locatedAt(
                report.id(), workspace.id(), 0.98)));
        assertTrue(graph.upsertEdge(scope, SoftwareMemoryGraphMapper.producedBy(
                report.id(), commit.id(), 0.97, testLog.id())));
        assertTrue(graph.upsertEdge(scope, SoftwareMemoryGraphMapper.supports(
                testLog.id(), report.id(), 0.91)));

        MemorySubgraph subgraph = graph.querySubgraph(
                MemoryGraphAccess.owner(scope, "tenant-a"), report.id(), 2);

        assertEquals(4, subgraph.nodes().size());
        assertTrue(subgraph.containsNode(workspace.id()));
        assertTrue(subgraph.edges().stream().anyMatch(
                edge -> edge.type() == MemoryEdgeType.PRODUCED_BY));
        assertTrue(subgraph.edges().stream().anyMatch(
                edge -> edge.type() == MemoryEdgeType.SUPPORTS));
        assertEquals(4, graph.nodeCount(scope));
        assertEquals(3, graph.edgeCount(scope));
    }

    @Test
    void versionedStoreRetainsNodeHistory() {
        String scope = "agent-a";
        MemoryNode first = new MemoryNode("artifact:1", MemoryNodeType.ARTIFACT,
                "draft", "/factory/draft.md", 0.7, Map.of(), "tenant-a",
                MemoryVisibility.TENANT);
        MemoryNode second = first.withSummary("published");

        assertTrue(graph.upsertNode(scope, first));
        assertTrue(graph.upsertNode(scope, second));

        String key = TypedMemoryGraphStore.RECORD_PREFIX + ":node:" + first.id();
        MemoryRecord current = versioned.current(scope, key);
        assertNotNull(current);
        assertEquals(2L, current.version());
        assertEquals(1, versioned.history(scope, key).size());
        assertTrue(current.content().contains("published"));
    }

    @Test
    void visibilityIsFailClosedForWrongTenant() {
        String scope = "agent-a";
        MemoryNode privateNode = new MemoryNode("private:1", MemoryNodeType.ENTITY,
                "private token", null, 1.0, Map.of(), "tenant-a",
                MemoryVisibility.PRIVATE);
        MemoryNode tenantNode = new MemoryNode("tenant:1", MemoryNodeType.PLACE,
                "tenant workspace", null, 1.0, Map.of(), "tenant-a",
                MemoryVisibility.TENANT);
        MemoryNode publicNode = new MemoryNode("public:1", MemoryNodeType.EVIDENCE,
                "public release", null, 1.0, Map.of(), null,
                MemoryVisibility.PUBLIC);
        graph.upsertNode(scope, privateNode);
        graph.upsertNode(scope, tenantNode);
        graph.upsertNode(scope, publicNode);

        List<MemoryNode> wrongTenant = graph.listVisibleNodes(
                new MemoryGraphAccess("agent-b", "tenant-b"));
        assertFalse(wrongTenant.contains(privateNode));
        assertFalse(wrongTenant.contains(tenantNode));
        assertTrue(wrongTenant.contains(publicNode));

        assertTrue(graph.node(new MemoryGraphAccess(scope, "tenant-a"), privateNode.id()).isPresent());
    }

    @Test
    void hydrateRebuildsAdjacencyFromVersionedRecords() {
        String scope = "agent-a";
        MemoryNode a = new MemoryNode("a", MemoryNodeType.EVENT, "event");
        MemoryNode b = new MemoryNode("b", MemoryNodeType.ARTIFACT, "artifact");
        graph.upsertNode(scope, a);
        graph.upsertNode(scope, b);
        graph.upsertEdge(scope, new MemoryEdge(a.id(), b.id(), MemoryEdgeType.PRODUCED_BY));

        TypedMemoryGraphStore reloaded = new TypedMemoryGraphStore(versioned, null, "/var/db/test-graph");
        assertEquals(0, reloaded.nodeCount(scope));
        assertEquals(3, reloaded.hydrate(scope));
        assertEquals(2, reloaded.nodeCount(scope));
        assertEquals(1, reloaded.edgeCount(scope));
        assertEquals(2, reloaded.querySubgraph(scope, a.id(), 1).nodes().size());
    }

    @Test
    void mapperKeepsSoftwareConceptsTyped() {
        MemoryNode workspace = SoftwareMemoryGraphMapper.workspace(
                "ws", "workspace", "/factory", "tenant-a");
        MemoryNode evidence = SoftwareMemoryGraphMapper.evidence(
                "diff", "patch", "/factory/diff.patch", "tenant-a",
                MemoryVisibility.TENANT);
        MemoryEdge edge = SoftwareMemoryGraphMapper.observedIn(
                evidence.id(), workspace.id(), 0.8);

        assertEquals(MemoryNodeType.PLACE, workspace.type());
        assertEquals(MemoryNodeType.EVIDENCE, evidence.type());
        assertEquals(MemoryEdgeType.OBSERVED_IN, edge.type());
    }

    @Test
    void mirrorsLatestJsonIntoVfsWithoutReplacingVmsHistory() {
        VfsManager vfs = VfsManager.instance();
        vfs.init();
        graph = new TypedMemoryGraphStore(versioned, vfs, "/var/db/test-graph");

        String scope = "agent-vfs";
        MemoryNode node = new MemoryNode("artifact/report.md", MemoryNodeType.ARTIFACT,
                "report", "/factory/report.md", 0.9, Map.of(), "tenant-a",
                MemoryVisibility.TENANT);
        assertTrue(graph.upsertNode(scope, node));

        String encodedScope = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(scope.getBytes(StandardCharsets.UTF_8));
        String encodedId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(node.id().getBytes(StandardCharsets.UTF_8));
        String path = "/var/db/test-graph/" + encodedScope + "/nodes/" + encodedId + ".json";
        assertTrue(vfs.exists(path));
        assertTrue(vfs.readText(path).contains("artifact/report.md"));

        TypedMemoryGraphStore reloadedFromVfs = new TypedMemoryGraphStore(
                new VersionedMemoryStore(new FakeProvider()), vfs, "/var/db/test-graph");
        assertEquals(1, reloadedFromVfs.hydrate(scope));
        assertEquals(1, reloadedFromVfs.nodeCount(scope));
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
            return "typed-graph-test";
        }
    }
}
