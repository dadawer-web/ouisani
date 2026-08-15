package com.ouisani.aios.core.memory.graph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auditable output of one hybrid graph retrieval.
 *
 * <p>The JSON names intentionally mirror the wire contract used by the
 * Memory Viewer: seeds, typed expansion, filters, ranking, evidence,
 * conflicts and answer coverage can be inspected without reconstructing
 * hidden retriever state.</p>
 */
public record RetrievalTrace(
        String query,
        List<Seed> seeds,
        List<ExpandedEdge> expandedEdges,
        Map<String, Object> filters,
        List<Ranking> ranking,
        List<Evidence> evidenceBundle,
        List<Conflict> conflicts,
        List<AnswerSupport> answerSupport,
        boolean sufficient,
        String insufficiencyReason,
        boolean shouldObserve) {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public RetrievalTrace {
        query = query == null ? "" : query;
        seeds = immutable(seeds);
        expandedEdges = immutable(expandedEdges);
        filters = immutableMap(filters);
        ranking = immutable(ranking);
        evidenceBundle = immutable(evidenceBundle);
        conflicts = immutable(conflicts);
        answerSupport = immutable(answerSupport);
        insufficiencyReason = insufficiencyReason == null ? "" : insufficiencyReason;
    }

    public RetrievalTrace(String query,
                          List<Seed> seeds,
                          List<ExpandedEdge> expandedEdges,
                          Map<String, Object> filters,
                          List<Ranking> ranking,
                          List<Evidence> evidenceBundle,
                          List<Conflict> conflicts,
                          List<AnswerSupport> answerSupport) {
        this(query, seeds, expandedEdges, filters, ranking, evidenceBundle,
                conflicts, answerSupport, evidencePresent(evidenceBundle),
                evidencePresent(evidenceBundle) ? "" : "no evidence", false);
    }

    public RetrievalTrace withAnswerSupport(List<AnswerSupport> nextAnswerSupport,
                                            boolean nextSufficient,
                                            String nextReason,
                                            boolean nextShouldObserve) {
        return new RetrievalTrace(query, seeds, expandedEdges, filters, ranking,
                evidenceBundle, conflicts, nextAnswerSupport, nextSufficient,
                nextReason, nextShouldObserve);
    }

    /** Stable map representation with the public snake_case field names. */
    public Map<String, Object> asMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("query", query);
        map.put("seeds", seeds.stream().map(Seed::asMap).toList());
        map.put("expanded_edges", expandedEdges.stream().map(ExpandedEdge::asMap).toList());
        map.put("filters", filters);
        map.put("ranking", ranking.stream().map(Ranking::asMap).toList());
        map.put("evidence_bundle", evidenceBundle.stream().map(Evidence::asMap).toList());
        map.put("conflicts", conflicts.stream().map(Conflict::asMap).toList());
        map.put("answer_support", answerSupport.stream().map(AnswerSupport::asMap).toList());
        map.put("sufficient", sufficient);
        map.put("insufficiency_reason", insufficiencyReason);
        map.put("should_observe", shouldObserve);
        return Collections.unmodifiableMap(map);
    }

    public String toJson() {
        return GSON.toJson(asMap());
    }

    public record Seed(
            String nodeId,
            String nodeType,
            List<String> channels,
            double lexicalScore,
            double vectorScore,
            double metadataScore,
            double combinedScore,
            String reason) {
        public Seed {
            nodeId = nodeId == null ? "" : nodeId;
            nodeType = nodeType == null ? "" : nodeType;
            channels = immutable(channels);
            reason = reason == null ? "" : reason;
        }

        private Map<String, Object> asMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("node_id", nodeId);
            map.put("node_type", nodeType);
            map.put("channels", channels);
            map.put("lexical_score", lexicalScore);
            map.put("vector_score", vectorScore);
            map.put("metadata_score", metadataScore);
            map.put("combined_score", combinedScore);
            map.put("reason", reason);
            return map;
        }
    }

    public record ExpandedEdge(
            String edgeId,
            String sourceId,
            String targetId,
            String type,
            double confidence,
            boolean allowed,
            String reason) {
        public ExpandedEdge {
            edgeId = edgeId == null ? "" : edgeId;
            sourceId = sourceId == null ? "" : sourceId;
            targetId = targetId == null ? "" : targetId;
            type = type == null ? "" : type;
            reason = reason == null ? "" : reason;
        }

        private Map<String, Object> asMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("edge_id", edgeId);
            map.put("source_id", sourceId);
            map.put("target_id", targetId);
            map.put("type", type);
            map.put("confidence", confidence);
            map.put("allowed", allowed);
            map.put("reason", reason);
            return map;
        }
    }

    public record Ranking(
            String nodeId,
            int rank,
            double score,
            List<String> reasons) {
        public Ranking {
            nodeId = nodeId == null ? "" : nodeId;
            rank = Math.max(1, rank);
            reasons = immutable(reasons);
        }

        private Map<String, Object> asMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("node_id", nodeId);
            map.put("rank", rank);
            map.put("score", score);
            map.put("reasons", reasons);
            return map;
        }
    }

    public record Evidence(
            String evidenceId,
            String nodeId,
            String role,
            String nodeType,
            String summary,
            String sourceRef,
            double confidence,
            double score,
            List<String> edgeIds) {
        public Evidence {
            evidenceId = evidenceId == null ? "" : evidenceId;
            nodeId = nodeId == null ? "" : nodeId;
            role = role == null ? "CONTEXT" : role;
            nodeType = nodeType == null ? "" : nodeType;
            summary = summary == null ? "" : summary;
            sourceRef = sourceRef == null ? "" : sourceRef;
            edgeIds = immutable(edgeIds);
        }

        private Map<String, Object> asMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("evidence_id", evidenceId);
            map.put("node_id", nodeId);
            map.put("role", role);
            map.put("node_type", nodeType);
            map.put("summary", summary);
            map.put("source_ref", sourceRef);
            map.put("confidence", confidence);
            map.put("score", score);
            map.put("edge_ids", edgeIds);
            return map;
        }
    }

    public record Conflict(
            String claimNodeId,
            List<String> supportingEvidenceIds,
            List<String> contradictingEvidenceIds,
            List<String> edgeIds,
            String reason) {
        public Conflict {
            claimNodeId = claimNodeId == null ? "" : claimNodeId;
            supportingEvidenceIds = immutable(supportingEvidenceIds);
            contradictingEvidenceIds = immutable(contradictingEvidenceIds);
            edgeIds = immutable(edgeIds);
            reason = reason == null ? "" : reason;
        }

        private Map<String, Object> asMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("claim_node_id", claimNodeId);
            map.put("supporting_evidence_ids", supportingEvidenceIds);
            map.put("contradicting_evidence_ids", contradictingEvidenceIds);
            map.put("edge_ids", edgeIds);
            map.put("reason", reason);
            return map;
        }
    }

    public record AnswerSupport(
            String evidenceId,
            String claim,
            boolean covered,
            String reason) {
        public AnswerSupport {
            evidenceId = evidenceId == null ? "" : evidenceId;
            claim = claim == null ? "" : claim;
            reason = reason == null ? "" : reason;
        }

        private Map<String, Object> asMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("evidence_id", evidenceId);
            map.put("claim", claim);
            map.put("covered", covered);
            map.put("reason", reason);
            return map;
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static boolean evidencePresent(List<Evidence> values) {
        return values != null && !values.isEmpty();
    }
}
