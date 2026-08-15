package com.ouisani.aios.core.memory.graph;

import java.util.List;

/** Immutable evidence subgraph returned by a typed traversal. */
public record MemorySubgraph(
        String seedId,
        int depth,
        List<MemoryNode> nodes,
        List<MemoryEdge> edges) {

    public MemorySubgraph {
        seedId = seedId == null ? "" : seedId;
        depth = Math.max(0, depth);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public boolean containsNode(String nodeId) {
        return nodes.stream().anyMatch(node -> node.id().equals(nodeId));
    }
}
