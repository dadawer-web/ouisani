package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.vfs.VfsJournal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public non-sealed class GraphNode implements VfsNode {

    private static final Pattern TRIPLET_PATTERN =
            Pattern.compile("\\[([^|\\]]+)\\s*\\|\\s*([^|\\]]+)\\s*\\|\\s*([^\\]]+)]");

    private final String path;
    private final LlmProvider llmProvider;
    private final Map<String, Set<Edge>> adjacencyList;
    private final Set<String> allEntities;
    private int ownerUid;
    private int permissions;

    public GraphNode(String path, LlmProvider llmProvider) {
        this(path, llmProvider, 0, 0666);
    }

    public GraphNode(String path, LlmProvider llmProvider, int ownerUid, int permissions) {
        this.path = path;
        this.llmProvider = llmProvider;
        this.adjacencyList = new ConcurrentHashMap<>();
        this.allEntities = ConcurrentHashMap.newKeySet();
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.DEVICE;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    @Override
    public String read() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\":\"").append(path).append("\",");
        sb.append("\"entityCount\":").append(allEntities.size()).append(",");
        sb.append("\"edgeCount\":").append(totalEdgeCount()).append(",");
        sb.append("\"entities\":[");

        List<String> sorted = allEntities.stream().sorted().toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(",");
            String entity = sorted.get(i);
            sb.append("\"").append(escape(entity)).append("\"");
        }
        sb.append("],\"edges\":[");

        boolean first = true;
        List<String> sortedSrc = adjacencyList.keySet().stream().sorted().toList();
        for (String src : sortedSrc) {
            for (Edge edge : adjacencyList.get(src)) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"src\":\"").append(escape(src)).append("\",")
                  .append("\"rel\":\"").append(escape(edge.relation)).append("\",")
                  .append("\"dst\":\"").append(escape(edge.target)).append("\"}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public boolean write(String payload) {
        if (payload == null || payload.isBlank()) return false;

        // WAL: journal the write before applying it
        VfsJournal.getInstance().appendLog(path, "WRITE", payload);

        System.out.printf("  [GraphNode] %s: extracting triplets from %d chars...%n",
                path, payload.length());

        String extraction;
        try {
            extraction = llmProvider.think(
                    "Extract core knowledge triplets from this text in the format [EntityA|Relation|EntityB]. Text: " + payload,
                    "System: Graph Extractor");
        } catch (Exception e) {
            System.out.printf("  [GraphNode] LLM extraction failed: %s%n", e.getMessage());
            return false;
        }

        int count = parseAndStoreTriplets(extraction);
        System.out.printf("  [GraphNode] %s: extracted %d triplets (total entities: %d, edges: %d)%n",
                path, count, allEntities.size(), totalEdgeCount());
        return count > 0;
    }

    int parseAndStoreTriplets(String text) {
        Matcher matcher = TRIPLET_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            String entityA = matcher.group(1).strip();
            String relation = matcher.group(2).strip();
            String entityB = matcher.group(3).strip();

            if (entityA.isEmpty() || relation.isEmpty() || entityB.isEmpty()) continue;

            addEdge(entityA, relation, entityB);
            count++;
        }
        return count;
    }

    public void addEdge(String src, String relation, String dst) {
        allEntities.add(src);
        allEntities.add(dst);
        adjacencyList.computeIfAbsent(src, k -> ConcurrentHashMap.newKeySet())
                .add(new Edge(relation, dst));
    }

    public String querySubgraph(String rootEntity, int depth) {
        if (!allEntities.contains(rootEntity)) {
            return "{\"error\":\"Entity '" + rootEntity + "' not found in graph\"}";
        }

        Set<String> visited = new LinkedHashSet<>();
        List<PathEntry> path = new ArrayList<>();
        Queue<BfsNode> queue = new LinkedList<>();
        queue.add(new BfsNode(rootEntity, 0));

        while (!queue.isEmpty()) {
            BfsNode current = queue.poll();
            if (visited.contains(current.entity)) continue;
            if (current.depth > depth) continue;
            visited.add(current.entity);

            Set<Edge> edges = adjacencyList.getOrDefault(current.entity, Set.of());
            for (Edge edge : edges) {
                path.add(new PathEntry(current.entity, edge.relation, edge.target, current.depth));
                if (!visited.contains(edge.target) && current.depth < depth) {
                    queue.add(new BfsNode(edge.target, current.depth + 1));
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"root\":\"").append(escape(rootEntity)).append("\",");
        sb.append("\"depth\":").append(depth).append(",");
        sb.append("\"visitedNodes\":").append(visited.size()).append(",");
        sb.append("\"paths\":[");

        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append(",");
            PathEntry p = path.get(i);
            String indent = "  ".repeat(p.depth);
            sb.append("{\"depth\":").append(p.depth).append(",")
              .append("\"traversal\":\"").append(escape(indent + p.src + " → " + p.rel + " → " + p.dst)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    public int entityCount() {
        return allEntities.size();
    }

    public int edgeCount() {
        return totalEdgeCount();
    }

    private int totalEdgeCount() {
        return adjacencyList.values().stream().mapToInt(Set::size).sum();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    record Edge(String relation, String target) {}

    record PathEntry(String src, String rel, String dst, int depth) {}

    record BfsNode(String entity, int depth) {}

    /**
     * Create a frozen shadow copy of this GraphNode.
     * Deep-copies the adjacency list and entity set so the snapshot is independent.
     * The returned node is read-only (write always returns false).
     */
    @Override
    public VfsNode createShadowCopy() {
        // Deep copy adjacency list
        Map<String, Set<Edge>> frozenAdj = new LinkedHashMap<>();
        for (var entry : adjacencyList.entrySet()) {
            frozenAdj.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        Set<String> frozenEntities = Set.copyOf(allEntities);

        int edgeCount = frozenAdj.values().stream().mapToInt(Set::size).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\":\"").append(path).append("\",");
        sb.append("\"snapshot\":true,");
        sb.append("\"entityCount\":").append(frozenEntities.size()).append(",");
        sb.append("\"edgeCount\":").append(edgeCount).append(",");
        sb.append("\"entities\":[");
        List<String> sorted = frozenEntities.stream().sorted().toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(sorted.get(i))).append("\"");
        }
        sb.append("],\"edges\":[");
        boolean first = true;
        List<String> sortedSrc = frozenAdj.keySet().stream().sorted().toList();
        for (String src : sortedSrc) {
            for (Edge edge : frozenAdj.get(src)) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"src\":\"").append(escape(src)).append("\",")
                  .append("\"rel\":\"").append(escape(edge.relation)).append("\",")
                  .append("\"dst\":\"").append(escape(edge.target)).append("\"}");
            }
        }
        sb.append("]}");

        return new ShadowCopyNode(path + " [SHADOW]", VfsNodeType.GRAPH, sb.toString(), ownerUid);
    }
}
