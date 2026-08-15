package com.ouisani.aios.core.memory.graph;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.VectorMath;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight vector adapter for the V2 graph.
 *
 * <p>It intentionally stores only an in-process index.  The durable node and
 * evidence records remain in VMS/VFS, while deployments can populate this
 * index from Neuron's current {@code SemanticNode} vectors or replace it
 * with a remote ANN implementation later.</p>
 */
public final class MemoryVectorIndex implements VectorScorer {

    private final LlmProvider provider;
    private final ConcurrentHashMap<String, float[]> vectors = new ConcurrentHashMap<>();

    public MemoryVectorIndex(LlmProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
    }

    /** Register a precomputed embedding under a graph scope and node id. */
    public void put(String scopeId, String nodeId, float[] vector) {
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        vectors.put(key(scopeId, nodeId), vector.clone());
    }

    /** Index a node using its compact summary. */
    public void indexNode(String scopeId, MemoryNode node) {
        Objects.requireNonNull(node, "node must not be null");
        float[] embedding = provider.embed(node.summary());
        if (embedding != null && embedding.length > 0) {
            put(scopeId, node.id(), embedding);
        }
    }

    public void remove(String scopeId, String nodeId) {
        if (scopeId != null && nodeId != null) vectors.remove(key(scopeId, nodeId));
    }

    public int size() {
        return vectors.size();
    }

    @Override
    public Map<String, Double> score(String query, MemoryGraphAccess access,
                                     Collection<MemoryNode> candidates) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return Collections.emptyMap();
        }
        final float[] queryVector;
        try {
            queryVector = provider.embed(query);
        } catch (RuntimeException ignored) {
            return Collections.emptyMap();
        }
        if (queryVector == null || queryVector.length == 0) return Collections.emptyMap();

        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        for (MemoryNode candidate : candidates) {
            if (candidate == null) continue;
            float[] vector = vectors.get(key(access.scopeId(), candidate.id()));
            if (vector == null) vector = vectors.get(candidate.id());
            if (vector == null) {
                // Lazy indexing makes the adapter useful even when the
                // caller has not explicitly copied SemanticNode vectors into
                // the V2 index.  The result is cached for later queries.
                try {
                    float[] generated = provider.embed(candidate.summary());
                    if (generated != null && generated.length > 0) {
                        put(access.scopeId(), candidate.id(), generated);
                        vector = generated;
                    }
                } catch (RuntimeException ignored) {
                    // Missing embedding support remains an explicit gap in
                    // the trace rather than failing lexical retrieval.
                }
            }
            if (vector == null || vector.length != queryVector.length) continue;
            try {
                double cosine = VectorMath.cosineSimilarity(queryVector, vector);
                // Similarity is exposed as a normalized score so all three
                // retrieval channels share the [0, 1] range.
                scores.put(candidate.id(), clamp((cosine + 1.0) / 2.0));
            } catch (RuntimeException ignored) {
                // A malformed single vector must not make the whole query fail.
            }
        }
        return Collections.unmodifiableMap(scores);
    }

    @Override
    public String name() {
        return "memory-vector-index";
    }

    private static String key(String scopeId, String nodeId) {
        return scopeId + "\u0000" + nodeId;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
