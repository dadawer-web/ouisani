package com.ouisani.aios.core.memory.graph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.memory.MemoryLayer;
import com.ouisani.aios.core.memory.VersionedMemoryStore;
import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2 typed memory graph built on Neuron's existing memory and VFS primitives.
 *
 * <p>This is deliberately not a second database.  The in-process adjacency
 * table gives fast typed traversal; {@link VersionedMemoryStore} is the
 * versioned durable record of every node/edge; and VFS receives a readable
 * JSON mirror suitable for inspection, snapshots and recovery.  A caller can
 * therefore adopt graph retrieval without introducing Neo4j or changing the
 * legacy text-memory provider contract.</p>
 *
 * <p>Graph data is partitioned by {@code scopeId} (normally an Agent or
 * workflow namespace).  Node-level {@link MemoryVisibility} and tenant checks
 * are applied on every read, including subgraph expansion.  Edges inherit the
 * visibility of both endpoint nodes; an edge never leaks a hidden endpoint.</p>
 */
public final class TypedMemoryGraphStore {

    private static final Logger log = LoggerFactory.getLogger(TypedMemoryGraphStore.class);

    /** Prefix kept separate from ordinary VersionedMemoryStore keys. */
    public static final String RECORD_PREFIX = "__typed_memory_graph_v2__";
    public static final String DEFAULT_VFS_ROOT = "/var/db/memory-graph-v2";

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private final VersionedMemoryStore versionedStore;
    private final VfsManager vfs;
    private final String vfsRoot;
    private final ConcurrentHashMap<String, ScopeGraph> scopes = new ConcurrentHashMap<>();

    public TypedMemoryGraphStore(VersionedMemoryStore versionedStore) {
        this(versionedStore, VfsManager.instance(), DEFAULT_VFS_ROOT);
    }

    public TypedMemoryGraphStore(VersionedMemoryStore versionedStore,
                                 VfsManager vfs,
                                 String vfsRoot) {
        this.versionedStore = Objects.requireNonNull(versionedStore,
                "versionedStore must not be null");
        this.vfs = vfs;
        this.vfsRoot = normalizeVfsRoot(vfsRoot);
    }

    public VersionedMemoryStore versionedStore() {
        return versionedStore;
    }

    public String vfsRoot() {
        return vfsRoot;
    }

    /** Insert or replace a node and retain its previous version in VMS. */
    public boolean upsertNode(String scopeId, MemoryNode node) {
        String scope = requiredScope(scopeId);
        Objects.requireNonNull(node, "node must not be null");
        MemoryRecord record = nodeRecord(scope, node);
        if (!persist(scope, record)) return false;
        scopeGraph(scope).upsertNode(node);
        mirrorNode(scope, node);
        return true;
    }

    /** Alias used by ingestion code that models graph writes as additions. */
    public boolean addNode(String scopeId, MemoryNode node) {
        return upsertNode(scopeId, node);
    }

    /** Insert or replace a typed edge and retain its previous version in VMS. */
    public boolean upsertEdge(String scopeId, MemoryEdge edge) {
        String scope = requiredScope(scopeId);
        Objects.requireNonNull(edge, "edge must not be null");
        MemoryRecord record = edgeRecord(scope, edge);
        if (!persist(scope, record)) return false;
        scopeGraph(scope).upsertEdge(edge);
        mirrorEdge(scope, edge);
        return true;
    }

    /** Alias used by ingestion code that models graph writes as additions. */
    public boolean addEdge(String scopeId, MemoryEdge edge) {
        return upsertEdge(scopeId, edge);
    }

    public Optional<MemoryNode> node(String scopeId, String nodeId) {
        ScopeGraph graph = scopes.get(requiredScope(scopeId));
        return graph == null ? Optional.empty() : graph.node(nodeId);
    }

    /** Read a node after applying the caller's tenant/visibility boundary. */
    public Optional<MemoryNode> node(MemoryGraphAccess access, String nodeId) {
        Objects.requireNonNull(access, "access must not be null");
        LocatedNode located = findVisibleNode(access, nodeId);
        return located == null ? Optional.empty() : Optional.of(located.node());
    }

    public List<MemoryNode> listNodes(String scopeId) {
        ScopeGraph graph = scopes.get(requiredScope(scopeId));
        return graph == null ? List.of() : graph.nodes();
    }

    public List<MemoryNode> listVisibleNodes(MemoryGraphAccess access) {
        Objects.requireNonNull(access, "access must not be null");
        List<MemoryNode> visible = new ArrayList<>();
        scopes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entry.getValue().nodes().stream()
                        .filter(node -> access.canRead(entry.getKey(), node))
                        .forEach(visible::add));
        return List.copyOf(visible);
    }

    public List<MemoryEdge> outgoingEdges(String scopeId, String nodeId) {
        ScopeGraph graph = scopes.get(requiredScope(scopeId));
        return graph == null ? List.of() : graph.outgoing(nodeId);
    }

    public List<MemoryEdge> incomingEdges(String scopeId, String nodeId) {
        ScopeGraph graph = scopes.get(requiredScope(scopeId));
        return graph == null ? List.of() : graph.incoming(nodeId);
    }

    /**
     * Search the current graph snapshot by lexical evidence fields.  This is
     * the deterministic seed stage; callers may feed the returned nodes into
     * {@link #querySubgraph(MemoryGraphAccess, String, int)} for typed
     * expansion.
     */
    public List<MemoryNode> search(MemoryGraphAccess access, String query,
                                   MemoryNodeType type) {
        Objects.requireNonNull(access, "access must not be null");
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        ScopeGraph graph = scopes.get(access.scopeId());
        if (graph == null) return List.of();
        return graph.nodes().stream()
                .filter(node -> access.canRead(access.scopeId(), node))
                .filter(node -> type == null || node.type() == type)
                .filter(node -> needle.isEmpty() || searchable(node).contains(needle))
                .toList();
    }

    public List<MemoryNode> search(MemoryGraphAccess access, String query) {
        return search(access, query, null);
    }

    /**
     * BFS over both outgoing and incoming typed edges.  The returned graph is
     * a compact local evidence subgraph, not a flat text-memory result.
     */
    public MemorySubgraph querySubgraph(String scopeId, String seedId, int depth) {
        String scope = requiredScope(scopeId);
        MemoryNode seed = node(scope, seedId).orElse(null);
        if (seed == null) return new MemorySubgraph(seedId, depth, List.of(), List.of());
        return querySubgraph(new MemoryGraphAccess(scope, seed.tenant(), true), seedId, depth);
    }

    public MemorySubgraph querySubgraph(MemoryGraphAccess access, String seedId, int depth) {
        return querySubgraph(access, seedId, depth, Set.of(), System.currentTimeMillis());
    }

    /**
     * BFS over a task-scoped set of typed relations at a caller supplied
     * point in time.  An empty edge set means "all edge types" for backwards
     * compatibility; a non-empty set is a hard traversal whitelist.  The
     * whitelist is applied before a neighbour is enqueued, so a disallowed
     * relation cannot leak an otherwise unrelated node into the evidence
     * subgraph.
     */
    public MemorySubgraph querySubgraph(MemoryGraphAccess access, String seedId, int depth,
                                        Set<MemoryEdgeType> allowedEdgeTypes,
                                        Long validAt) {
        Objects.requireNonNull(access, "access must not be null");
        int maxDepth = Math.max(0, Math.min(depth, 64));
        LocatedNode seed = findVisibleNode(access, seedId);
        if (seed == null) {
            return new MemorySubgraph(seedId, maxDepth, List.of(), List.of());
        }

        long now = validAt == null ? System.currentTimeMillis() : validAt;
        Set<MemoryEdgeType> whitelist = allowedEdgeTypes == null
                ? Set.of() : Set.copyOf(allowedEdgeTypes);
        LinkedHashMap<String, MemoryNode> nodes = new LinkedHashMap<>();
        LinkedHashMap<String, MemoryEdge> edges = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<Visit> queue = new ArrayDeque<>();
        queue.add(new Visit(seed, 0));

        while (!queue.isEmpty()) {
            Visit visit = queue.removeFirst();
            LocatedNode locatedCurrent = visit.node();
            MemoryNode current = locatedCurrent.node();
            String visitKey = locatedCurrent.ownerScope() + "|" + current.id();
            if (!visited.add(visitKey)) continue;
            if (!current.isValidAt(now)) continue;
            nodes.putIfAbsent(visitKey, current);

            ScopeGraph graph = scopes.get(locatedCurrent.ownerScope());
            if (graph == null) continue;
            List<MemoryEdge> adjacent = new ArrayList<>();
            adjacent.addAll(graph.outgoing(current.id()));
            adjacent.addAll(graph.incoming(current.id()));
            adjacent.sort(Comparator.comparing(MemoryEdge::id));

            for (MemoryEdge edge : adjacent) {
                if (!edge.isValidAt(now)) continue;
                if (!whitelist.isEmpty() && !whitelist.contains(edge.type())) continue;
                String otherId = edge.sourceId().equals(current.id())
                        ? edge.targetId() : edge.sourceId();
                LocatedNode other = findNode(locatedCurrent.ownerScope(), otherId);
                if (other == null || !other.node().isValidAt(now)
                        || !access.canRead(other.ownerScope(), other.node())) continue;

                List<String> visibleEvidence = edge.evidenceIds().stream()
                        .filter(evidenceId -> {
                            LocatedNode evidence = findNode(locatedCurrent.ownerScope(), evidenceId);
                            return evidence == null || access.canRead(evidence.ownerScope(), evidence.node());
                        })
                        .toList();
                MemoryEdge visibleEdge = visibleEvidence.size() == edge.evidenceIds().size()
                        ? edge : edge.withEvidenceIds(visibleEvidence);
                edges.putIfAbsent(visibleEdge.id(), visibleEdge);
                String otherVisitKey = other.ownerScope() + "|" + other.node().id();
                if (visit.depth() < maxDepth && !visited.contains(otherVisitKey)) {
                    queue.addLast(new Visit(other, visit.depth() + 1));
                }
            }
        }

        return new MemorySubgraph(seedId, maxDepth,
                List.copyOf(nodes.values()), List.copyOf(edges.values()));
    }

    private LocatedNode findVisibleNode(MemoryGraphAccess access, String nodeId) {
        LocatedNode preferred = findNode(access.scopeId(), nodeId);
        if (preferred != null && access.canRead(preferred.ownerScope(), preferred.node())) {
            return preferred;
        }
        return scopes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new LocatedNode(entry.getKey(), entry.getValue().node(nodeId).orElse(null)))
                .filter(located -> located.node() != null
                        && access.canRead(located.ownerScope(), located.node()))
                .findFirst()
                .orElse(null);
    }

    private LocatedNode findNode(String preferredScope, String nodeId) {
        if (nodeId == null) return null;
        ScopeGraph preferred = scopes.get(preferredScope);
        if (preferred != null) {
            MemoryNode node = preferred.node(nodeId).orElse(null);
            if (node != null) return new LocatedNode(preferredScope, node);
        }
        return scopes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new LocatedNode(entry.getKey(), entry.getValue().node(nodeId).orElse(null)))
                .filter(located -> located.node() != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * Rebuild the in-memory adjacency index from current VMS records.  If the
     * VMS table has not been rehydrated after a process restart, the VFS JSON
     * mirror is used as a best-effort fallback.
     *
     * @return number of nodes and edges loaded
     */
    public int hydrate(String scopeId) {
        String scope = requiredScope(scopeId);
        ScopeGraph graph = scopeGraph(scope);
        graph.clear();
        int loaded = 0;
        for (MemoryRecord record : versionedStore.listCurrent(scope)) {
            loaded += loadRecord(graph, record);
        }
        if (loaded == 0) loaded += hydrateFromVfs(scope, graph);
        return loaded;
    }

    public int nodeCount(String scopeId) {
        ScopeGraph graph = scopes.get(requiredScope(scopeId));
        return graph == null ? 0 : graph.nodeCount();
    }

    public int edgeCount(String scopeId) {
        ScopeGraph graph = scopes.get(requiredScope(scopeId));
        return graph == null ? 0 : graph.edgeCount();
    }

    /** Clear only the hot adjacency index; durable VMS/VFS history remains. */
    public void clearIndex(String scopeId) {
        ScopeGraph graph = scopes.get(requiredScope(scopeId));
        if (graph != null) graph.clear();
    }

    private MemoryRecord nodeRecord(String scope, MemoryNode node) {
        return MemoryRecord.of(
                nodeKey(node.id()),
                GSON.toJson(node),
                provenanceMarker(scope, "node", node.tenant(), node.visibility()),
                System.currentTimeMillis(),
                node.confidence(),
                MemoryDomain.AGENT,
                layerFor(node.type()),
                1L);
    }

    private MemoryRecord edgeRecord(String scope, MemoryEdge edge) {
        return MemoryRecord.of(
                edgeKey(edge),
                GSON.toJson(edge),
                provenanceMarker(scope, "edge", null, null),
                System.currentTimeMillis(),
                edge.confidence(),
                MemoryDomain.AGENT,
                MemoryLayer.L0,
                1L);
    }

    private boolean persist(String scope, MemoryRecord record) {
        try {
            return versionedStore.store(scope, record);
        } catch (RuntimeException e) {
            log.warn("[TypedMemoryGraphStore] durable write failed: scope={}, key={}, error={}",
                    scope, record.key(), e.getMessage());
            return false;
        }
    }

    private void mirrorNode(String scope, MemoryNode node) {
        mirror(scope, "nodes", node.id(), GSON.toJson(node));
    }

    private void mirrorEdge(String scope, MemoryEdge edge) {
        mirror(scope, "edges", edge.id(), GSON.toJson(edge));
    }

    private void mirror(String scope, String kind, String id, String json) {
        if (vfs == null || !vfs.isInitialized()) return;
        String path = vfsRoot + "/" + safeSegment(scope) + "/" + kind + "/"
                + safeSegment(id) + ".json";
        try {
            // VFS is an audit/recovery mirror.  A rate-limit or permission
            // failure must not roll back a successful VMS write.
            vfs.writeText(path, json);
        } catch (RuntimeException e) {
            log.debug("[TypedMemoryGraphStore] VFS mirror skipped: path={}, error={}",
                    path, e.getMessage());
        }
    }

    private int loadRecord(ScopeGraph graph, MemoryRecord record) {
        if (record == null || record.key() == null || record.content() == null) return 0;
        try {
            if (record.key().startsWith(RECORD_PREFIX + ":node:")) {
                MemoryNode node = GSON.fromJson(record.content(), MemoryNode.class);
                if (node != null) {
                    graph.upsertNode(node);
                    return 1;
                }
            } else if (record.key().startsWith(RECORD_PREFIX + ":edge:")) {
                MemoryEdge edge = GSON.fromJson(record.content(), MemoryEdge.class);
                if (edge != null) {
                    graph.upsertEdge(edge);
                    return 1;
                }
            }
        } catch (RuntimeException e) {
            log.warn("[TypedMemoryGraphStore] invalid durable graph record ignored: key={}, error={}",
                    record.key(), e.getMessage());
        }
        return 0;
    }

    private int hydrateFromVfs(String scope, ScopeGraph graph) {
        if (vfs == null || !vfs.isInitialized()) return 0;
        String prefix = vfsRoot + "/" + safeSegment(scope) + "/";
        int loaded = 0;
        try {
            for (String path : vfs.listFilesUnder(prefix + "nodes")) {
                String json = vfs.readText(path);
                try {
                    MemoryNode node = GSON.fromJson(json, MemoryNode.class);
                    if (node != null) {
                        graph.upsertNode(node);
                        loaded++;
                    }
                } catch (RuntimeException e) {
                    log.debug("[TypedMemoryGraphStore] invalid VFS node mirror ignored: {}", path);
                }
            }
            for (String path : vfs.listFilesUnder(prefix + "edges")) {
                String json = vfs.readText(path);
                try {
                    MemoryEdge edge = GSON.fromJson(json, MemoryEdge.class);
                    if (edge != null) {
                        graph.upsertEdge(edge);
                        loaded++;
                    }
                } catch (RuntimeException e) {
                    log.debug("[TypedMemoryGraphStore] invalid VFS edge mirror ignored: {}", path);
                }
            }
        } catch (RuntimeException e) {
            log.debug("[TypedMemoryGraphStore] VFS hydration skipped: scope={}, error={}",
                    scope, e.getMessage());
        }
        return loaded;
    }

    private ScopeGraph scopeGraph(String scopeId) {
        return scopes.computeIfAbsent(requiredScope(scopeId), ignored -> new ScopeGraph());
    }

    private static MemoryLayer layerFor(MemoryNodeType type) {
        return switch (type) {
            case EVIDENCE, EVENT -> MemoryLayer.L0;
            case ARTIFACT, DECISION -> MemoryLayer.L1;
            case ENTITY, PLACE, SESSION -> MemoryLayer.L2;
        };
    }

    private static String nodeKey(String nodeId) {
        return RECORD_PREFIX + ":node:" + nodeId;
    }

    private static String edgeKey(MemoryEdge edge) {
        return RECORD_PREFIX + ":edge:" + edge.id();
    }

    private static String provenanceMarker(String scope, String kind,
                                           String tenant, MemoryVisibility visibility) {
        return "typed-memory-graph-v2"
                + ";kind=" + marker(scope == null ? kind : kind)
                + ";agent=" + marker(scope)
                + (tenant == null ? "" : ";tenant=" + marker(tenant))
                + (visibility == null ? "" : ";visibility=" + visibility.name());
    }

    private static String marker(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace(";", "_").replace("=", "_");
    }

    private static String searchable(MemoryNode node) {
        return (node.id() + " " + node.summary() + " "
                + (node.sourceRef() == null ? "" : node.sourceRef()) + " "
                + node.provenance()).toLowerCase(Locale.ROOT);
    }

    private static String requiredScope(String scopeId) {
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
        return scopeId.trim();
    }

    private static String normalizeVfsRoot(String root) {
        String normalized = root == null || root.isBlank() ? DEFAULT_VFS_ROOT : root.trim();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String safeSegment(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record LocatedNode(String ownerScope, MemoryNode node) {}

    private record Visit(LocatedNode node, int depth) {}

    /** Thread-safe adjacency index for one Agent/workflow scope. */
    private static final class ScopeGraph {
        private final ConcurrentHashMap<String, MemoryNode> nodes = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ConcurrentHashMap<String, MemoryEdge>> outgoing
                = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ConcurrentHashMap<String, MemoryEdge>> incoming
                = new ConcurrentHashMap<>();

        void upsertNode(MemoryNode node) {
            nodes.put(node.id(), node);
        }

        void upsertEdge(MemoryEdge edge) {
            outgoing.computeIfAbsent(edge.sourceId(), ignored -> new ConcurrentHashMap<>())
                    .put(edge.id(), edge);
            incoming.computeIfAbsent(edge.targetId(), ignored -> new ConcurrentHashMap<>())
                    .put(edge.id(), edge);
        }

        Optional<MemoryNode> node(String id) {
            return id == null ? Optional.empty() : Optional.ofNullable(nodes.get(id));
        }

        List<MemoryNode> nodes() {
            return nodes.values().stream()
                    .sorted(Comparator.comparing(MemoryNode::id))
                    .toList();
        }

        List<MemoryEdge> outgoing(String id) {
            return sortedEdges(outgoing.get(id));
        }

        List<MemoryEdge> incoming(String id) {
            return sortedEdges(incoming.get(id));
        }

        int nodeCount() {
            return nodes.size();
        }

        int edgeCount() {
            return outgoing.values().stream().mapToInt(Map::size).sum();
        }

        void clear() {
            nodes.clear();
            outgoing.clear();
            incoming.clear();
        }

        private static List<MemoryEdge> sortedEdges(Map<String, MemoryEdge> edges) {
            if (edges == null || edges.isEmpty()) return List.of();
            return edges.values().stream()
                    .sorted(Comparator.comparing(MemoryEdge::id))
                    .toList();
        }
    }
}
