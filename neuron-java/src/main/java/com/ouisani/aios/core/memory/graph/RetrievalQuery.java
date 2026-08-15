package com.ouisani.aios.core.memory.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable contract for a typed-memory retrieval.
 *
 * <p>The query keeps policy next to the query rather than hiding it in a
 * retriever singleton.  This makes a retrieval trace self-contained: the
 * viewer can show the exact time, tenant, identity and relation whitelist
 * that produced an answer.</p>
 */
public record RetrievalQuery(
        String query,
        MemoryGraphAccess access,
        Set<MemoryNodeType> nodeTypes,
        Set<MemoryEdgeType> allowedEdges,
        Long validAt,
        Set<String> identityIds,
        Map<String, String> metadataFilters,
        int seedLimit,
        int maxDepth,
        int evidenceLimit,
        double lexicalWeight,
        double vectorWeight,
        double metadataWeight,
        double minScore,
        InsufficientEvidenceAction insufficientEvidenceAction) {

    public enum InsufficientEvidenceAction {
        REFUSE,
        OBSERVE
    }

    public RetrievalQuery {
        query = query == null ? "" : query.trim();
        if (access == null) {
            throw new IllegalArgumentException("access must not be null");
        }
        nodeTypes = immutableEnumSet(nodeTypes);
        allowedEdges = immutableEnumSet(allowedEdges);
        validAt = validAt == null ? System.currentTimeMillis() : validAt;
        if (validAt < 0) {
            throw new IllegalArgumentException("validAt must be >= 0");
        }
        identityIds = immutableStrings(identityIds);
        metadataFilters = immutableMetadata(metadataFilters);
        seedLimit = clamp(seedLimit, 1, 100);
        maxDepth = clamp(maxDepth, 0, 64);
        evidenceLimit = clamp(evidenceLimit, 1, 200);
        lexicalWeight = nonNegative(lexicalWeight, "lexicalWeight");
        vectorWeight = nonNegative(vectorWeight, "vectorWeight");
        metadataWeight = nonNegative(metadataWeight, "metadataWeight");
        if (lexicalWeight + vectorWeight + metadataWeight == 0.0) {
            throw new IllegalArgumentException("at least one retrieval weight must be > 0");
        }
        if (Double.isNaN(minScore) || minScore < 0.0 || minScore > 1.0) {
            throw new IllegalArgumentException("minScore must be in [0, 1]");
        }
        insufficientEvidenceAction = insufficientEvidenceAction == null
                ? InsufficientEvidenceAction.OBSERVE : insufficientEvidenceAction;
    }

    public RetrievalQuery(String query, MemoryGraphAccess access) {
        this(query, access, Set.of(), Set.of(), null, Set.of(), Map.of(),
                8, 2, 20, 0.45, 0.35, 0.20, 0.0,
                InsufficientEvidenceAction.OBSERVE);
    }

    public static RetrievalQuery of(String query, MemoryGraphAccess access) {
        return new RetrievalQuery(query, access);
    }

    public Builder toBuilder() {
        return new Builder(query, access)
                .nodeTypes(nodeTypes)
                .allowedEdges(allowedEdges)
                .validAt(validAt)
                .identityIds(identityIds)
                .metadataFilters(metadataFilters)
                .seedLimit(seedLimit)
                .maxDepth(maxDepth)
                .evidenceLimit(evidenceLimit)
                .weights(lexicalWeight, vectorWeight, metadataWeight)
                .minScore(minScore)
                .insufficientEvidenceAction(insufficientEvidenceAction);
    }

    /** Values copied into the trace's {@code filters} object. */
    public Map<String, Object> filtersForTrace(String vectorScorerName) {
        LinkedHashMap<String, Object> filters = new LinkedHashMap<>();
        filters.put("scope", access.scopeId());
        filters.put("tenant", access.tenantId());
        filters.put("include_shared", access.includeShared());
        filters.put("valid_at", validAt);
        filters.put("node_types", nodeTypes.stream().map(Enum::name).toList());
        filters.put("allowed_edges", allowedEdges.stream().map(Enum::name).toList());
        filters.put("identity_ids", new ArrayList<>(identityIds));
        filters.put("metadata", metadataFilters);
        filters.put("seed_limit", seedLimit);
        filters.put("max_depth", maxDepth);
        filters.put("evidence_limit", evidenceLimit);
        filters.put("weights", Map.of(
                "lexical", lexicalWeight,
                "vector", vectorWeight,
                "metadata", metadataWeight));
        filters.put("min_score", minScore);
        filters.put("insufficient_evidence_action", insufficientEvidenceAction.name());
        filters.put("vector_scorer", vectorScorerName == null ? "none" : vectorScorerName);
        return Collections.unmodifiableMap(filters);
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> values) {
        if (values == null || values.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static Set<String> immutableStrings(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) normalized.add(value.trim());
        }
        return normalized.isEmpty() ? Set.of() : Collections.unmodifiableSet(normalized);
    }

    private static Map<String, String> immutableMetadata(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            if (entry.getValue() != null) {
                normalized.put(entry.getKey().trim(), entry.getValue().trim());
            }
        }
        return normalized.isEmpty()
                ? Map.of() : Collections.unmodifiableMap(normalized);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double nonNegative(double value, String name) {
        if (Double.isNaN(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return value;
    }

    /** Small fluent builder used by HTTP adapters and callers with policies. */
    public static final class Builder {
        private final String query;
        private final MemoryGraphAccess access;
        private Set<MemoryNodeType> nodeTypes = Set.of();
        private Set<MemoryEdgeType> allowedEdges = Set.of();
        private Long validAt;
        private Set<String> identityIds = Set.of();
        private Map<String, String> metadataFilters = Map.of();
        private int seedLimit = 8;
        private int maxDepth = 2;
        private int evidenceLimit = 20;
        private double lexicalWeight = 0.45;
        private double vectorWeight = 0.35;
        private double metadataWeight = 0.20;
        private double minScore;
        private InsufficientEvidenceAction insufficientEvidenceAction = InsufficientEvidenceAction.OBSERVE;

        public Builder(String query, MemoryGraphAccess access) {
            this.query = query;
            this.access = access;
        }

        public Builder nodeTypes(Set<MemoryNodeType> value) { nodeTypes = value; return this; }
        public Builder allowedEdges(Set<MemoryEdgeType> value) { allowedEdges = value; return this; }
        public Builder validAt(Long value) { validAt = value; return this; }
        public Builder identityIds(Set<String> value) { identityIds = value; return this; }
        public Builder metadataFilters(Map<String, String> value) { metadataFilters = value; return this; }
        public Builder seedLimit(int value) { seedLimit = value; return this; }
        public Builder maxDepth(int value) { maxDepth = value; return this; }
        public Builder evidenceLimit(int value) { evidenceLimit = value; return this; }
        public Builder weights(double lexical, double vector, double metadata) {
            lexicalWeight = lexical;
            vectorWeight = vector;
            metadataWeight = metadata;
            return this;
        }
        public Builder minScore(double value) { minScore = value; return this; }
        public Builder insufficientEvidenceAction(InsufficientEvidenceAction value) {
            insufficientEvidenceAction = value;
            return this;
        }

        public RetrievalQuery build() {
            return new RetrievalQuery(query, access, nodeTypes, allowedEdges, validAt,
                    identityIds, metadataFilters, seedLimit, maxDepth, evidenceLimit,
                    lexicalWeight, vectorWeight, metadataWeight, minScore,
                    insufficientEvidenceAction);
        }
    }
}
