package com.ouisani.aios.core.memory.graph;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Vector channel used by {@link HybridMemoryRetriever}.
 *
 * <p>Keeping this as a small adapter lets Neuron reuse the existing
 * {@code SemanticNode} / embedding implementation without making the typed
 * graph depend on a particular vector database.  A missing vector index is
 * explicit: {@link #none()} returns no scores and the trace records that
 * fact instead of pretending lexical matches are vector matches.</p>
 */
@FunctionalInterface
public interface VectorScorer {

    /** Return scores keyed by visible memory-node id, normally in [0, 1]. */
    Map<String, Double> score(String query,
                              MemoryGraphAccess access,
                              Collection<MemoryNode> candidates);

    /** Human-readable name recorded in a retrieval trace. */
    default String name() {
        return getClass().getSimpleName();
    }

    /** A deterministic no-vector implementation for installations without embeddings. */
    static VectorScorer none() {
        return new VectorScorer() {
            @Override
            public Map<String, Double> score(String query, MemoryGraphAccess access,
                                             Collection<MemoryNode> candidates) {
                return Collections.emptyMap();
            }

            @Override
            public String name() {
                return "none";
            }
        };
    }
}
