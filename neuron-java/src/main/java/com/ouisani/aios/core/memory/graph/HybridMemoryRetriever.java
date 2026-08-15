package com.ouisani.aios.core.memory.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hybrid typed-graph retriever: lexical + vector + metadata/type channels,
 * followed by policy-constrained graph expansion and evidence accounting.
 */
public final class HybridMemoryRetriever {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}_:/.-]+");
    private static final Set<MemoryNodeType> EVIDENCE_TYPES = Set.of(
            MemoryNodeType.EVIDENCE,
            MemoryNodeType.ARTIFACT,
            MemoryNodeType.EVENT,
            MemoryNodeType.DECISION);

    private final TypedMemoryGraphStore store;
    private final VectorScorer vectorScorer;

    public HybridMemoryRetriever(TypedMemoryGraphStore store) {
        this(store, VectorScorer.none());
    }

    /** Convenience factory that lazily embeds graph summaries. */
    public static HybridMemoryRetriever withEmbeddings(TypedMemoryGraphStore store,
                                                       com.ouisani.aios.core.llm.LlmProvider provider) {
        return new HybridMemoryRetriever(store,
                provider == null ? VectorScorer.none() : new MemoryVectorIndex(provider));
    }

    public HybridMemoryRetriever(TypedMemoryGraphStore store, VectorScorer vectorScorer) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.vectorScorer = vectorScorer == null ? VectorScorer.none() : vectorScorer;
    }

    public TypedMemoryGraphStore store() {
        return store;
    }

    public VectorScorer vectorScorer() {
        return vectorScorer;
    }

    /** Convenience form for callers using the default retrieval policy. */
    public RetrievalTrace retrieve(String query, MemoryGraphAccess access) {
        return retrieve(new RetrievalQuery(query, access));
    }

    /** Execute one retrieval and return a complete audit trace. */
    public RetrievalTrace retrieve(RetrievalQuery request) {
        Objects.requireNonNull(request, "request must not be null");
        List<MemoryNode> visible = store.listVisibleNodes(request.access()).stream()
                .filter(node -> node.isValidAt(request.validAt()))
                .filter(node -> request.nodeTypes().isEmpty() || request.nodeTypes().contains(node.type()))
                .filter(node -> matchesIdentity(node, request.identityIds()))
                .filter(node -> matchesMetadata(node, request.metadataFilters()))
                .toList();

        Map<String, Double> vectorScores = safeVectorScores(request, visible);
        List<Candidate> candidates = new ArrayList<>();
        for (MemoryNode node : visible) {
            double lexical = lexicalScore(request.query(), node);
            Double vectorValue = vectorScores.get(node.id());
            double vector = vectorValue == null ? 0.0 : clamp(vectorValue);
            double metadata = metadataScore(node, request);
            double combined = weightedScore(request, lexical, vector, metadata,
                    !request.query().isBlank(), vectorValue != null,
                    metadataSignals(request) > 0);
            if (!Double.isFinite(combined) || (combined <= 0.0 && request.minScore() <= 0.0)
                    || combined < request.minScore()) {
                continue;
            }
            candidates.add(new Candidate(node, lexical, vector, metadata, combined,
                    vectorValue != null));
        }

        candidates.sort(Comparator
                .comparingDouble(Candidate::combined).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (Candidate candidate) -> candidate.node().confidence()).reversed())
                .thenComparing(candidate -> candidate.node().id()));

        List<RetrievalTrace.Ranking> ranking = new ArrayList<>();
        int rankingLimit = Math.min(candidates.size(), 100);
        for (int i = 0; i < rankingLimit; i++) {
            Candidate candidate = candidates.get(i);
            ranking.add(new RetrievalTrace.Ranking(candidate.node().id(), i + 1,
                    candidate.combined(), reasons(candidate)));
        }

        List<Candidate> seeds = candidates.stream().limit(request.seedLimit()).toList();
        List<RetrievalTrace.Seed> seedTrace = seeds.stream()
                .map(candidate -> new RetrievalTrace.Seed(
                        candidate.node().id(), candidate.node().type().name(),
                        channels(candidate), candidate.lexical(), candidate.vector(),
                        candidate.metadata(), candidate.combined(),
                        seedReason(candidate, request)))
                .toList();

        LinkedHashMap<String, MemoryNode> expandedNodes = new LinkedHashMap<>();
        LinkedHashMap<String, MemoryEdge> expandedEdges = new LinkedHashMap<>();
        for (Candidate seed : seeds) {
            MemorySubgraph subgraph = store.querySubgraph(request.access(), seed.node().id(),
                    request.maxDepth(), request.allowedEdges(), request.validAt());
            for (MemoryNode node : subgraph.nodes()) {
                // Identity/metadata policy applies to expansion too; a
                // matching seed must not smuggle an unrelated private fact
                // into the evidence bundle through a typed edge.
                if (matchesIdentity(node, request.identityIds())
                        && matchesMetadata(node, request.metadataFilters())) {
                    expandedNodes.putIfAbsent(node.id(), node);
                }
            }
            for (MemoryEdge edge : subgraph.edges()) {
                if (expandedNodes.containsKey(edge.sourceId())
                        && expandedNodes.containsKey(edge.targetId())) {
                    expandedEdges.putIfAbsent(edge.id(), edge);
                }
            }
        }

        List<RetrievalTrace.ExpandedEdge> edgeTrace = expandedEdges.values().stream()
                .sorted(Comparator.comparing(MemoryEdge::id))
                .map(edge -> new RetrievalTrace.ExpandedEdge(
                        edge.id(), edge.sourceId(), edge.targetId(), edge.type().name(),
                        edge.confidence(), true,
                        request.allowedEdges().isEmpty()
                                ? "allowed (no edge whitelist)"
                                : "allowed by task edge whitelist"))
                .toList();

        EvidenceBuild evidenceBuild = buildEvidence(expandedNodes.values(),
                expandedEdges.values(), candidates, request.evidenceLimit());
        boolean hasSupport = evidenceBuild.evidence().stream()
                .anyMatch(evidence -> "SUPPORT".equals(evidence.role()));
        boolean hasConflict = !evidenceBuild.conflicts().isEmpty();
        boolean sufficient = hasSupport && !hasConflict;
        String insufficiencyReason = sufficient ? "" : reason(hasSupport, hasConflict,
                evidenceBuild.evidence().isEmpty());
        boolean observe = !sufficient
                && request.insufficientEvidenceAction()
                == RetrievalQuery.InsufficientEvidenceAction.OBSERVE;
        List<RetrievalTrace.AnswerSupport> answerSupport = evidenceBuild.evidence().stream()
                .map(evidence -> new RetrievalTrace.AnswerSupport(
                        evidence.evidenceId(), evidence.summary(), false,
                        "awaiting proposed answer coverage"))
                .toList();

        return new RetrievalTrace(request.query(), seedTrace, edgeTrace,
                request.filtersForTrace(vectorScorer.name()), ranking,
                evidenceBuild.evidence(), evidenceBuild.conflicts(), answerSupport,
                sufficient, insufficiencyReason, observe);
    }

    private Map<String, Double> safeVectorScores(RetrievalQuery request,
                                                  Collection<MemoryNode> candidates) {
        try {
            Map<String, Double> scores = vectorScorer.score(request.query(), request.access(), candidates);
            return scores == null ? Map.of() : scores;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static double weightedScore(RetrievalQuery request, double lexical,
                                        double vector, double metadata,
                                        boolean lexicalActive, boolean vectorActive,
                                        boolean metadataActive) {
        double numerator = 0.0;
        double denominator = 0.0;
        if (lexicalActive) {
            numerator += request.lexicalWeight() * lexical;
            denominator += request.lexicalWeight();
        }
        if (vectorActive) {
            numerator += request.vectorWeight() * vector;
            denominator += request.vectorWeight();
        }
        if (metadataActive) {
            numerator += request.metadataWeight() * metadata;
            denominator += request.metadataWeight();
        }
        return denominator == 0.0 ? 0.0 : clamp(numerator / denominator);
    }

    private static double lexicalScore(String query, MemoryNode node) {
        if (query == null || query.isBlank()) return 0.0;
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        String searchable = searchable(node);
        if (searchable.contains(normalizedQuery)) return 1.0;
        List<String> queryTokens = tokens(normalizedQuery);
        if (queryTokens.isEmpty()) return 0.0;
        long hits = queryTokens.stream().filter(searchable::contains).count();
        return clamp((double) hits / queryTokens.size());
    }

    private static double metadataScore(MemoryNode node, RetrievalQuery request) {
        int signals = metadataSignals(request);
        if (signals == 0) return 0.0;
        int matched = 0;
        if (!request.nodeTypes().isEmpty()) {
            if (request.nodeTypes().contains(node.type())) matched++;
        }
        for (Map.Entry<String, String> filter : request.metadataFilters().entrySet()) {
            if (metadataValueMatches(node, filter.getKey(), filter.getValue())) matched++;
        }
        return clamp((double) matched / signals);
    }

    private static int metadataSignals(RetrievalQuery request) {
        return request.nodeTypes().size() > 0 ? 1 + request.metadataFilters().size()
                : request.metadataFilters().size();
    }

    private static boolean matchesMetadata(MemoryNode node, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) return true;
        return filters.entrySet().stream()
                .allMatch(filter -> metadataValueMatches(node, filter.getKey(), filter.getValue()));
    }

    private static boolean metadataValueMatches(MemoryNode node, String key, String expected) {
        if (key == null || expected == null) return false;
        String actual = switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "id", "node_id" -> node.id();
            case "type", "node_type" -> node.type().name();
            case "tenant" -> node.tenant();
            case "source", "source_ref" -> node.sourceRef();
            default -> valueAsString(node.provenance().get(key));
        };
        return actual != null && actual.equalsIgnoreCase(expected.trim());
    }

    private static boolean matchesIdentity(MemoryNode node, Set<String> identities) {
        if (identities == null || identities.isEmpty()) return true;
        Set<String> values = new HashSet<>();
        collectIdentityValues(node.provenance(), values);
        // A node without an identity annotation is neutral context (for
        // example a claim or workspace).  It remains traversable, while an
        // explicitly annotated node must match the requested identity.
        if (values.isEmpty()) return true;
        return identities.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(values::contains);
    }

    private static void collectIdentityValues(Map<String, Object> provenance, Set<String> values) {
        if (provenance == null) return;
        for (Map.Entry<String, Object> entry : provenance.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("identity") || key.contains("agent") || key.contains("user")
                    || key.contains("owner") || key.contains("subject")
                    || key.contains("principal") || key.contains("actor")
                    || key.contains("delegation") || key.contains("token")) {
                collectStrings(entry.getValue(), values);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectStrings(Object value, Set<String> values) {
        if (value == null) return;
        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) collectStrings(nested, values);
        } else if (value instanceof Collection<?> collection) {
            for (Object nested : collection) collectStrings(nested, values);
        } else {
            values.add(String.valueOf(value).toLowerCase(Locale.ROOT));
        }
    }

    private static EvidenceBuild buildEvidence(Collection<MemoryNode> nodes,
                                                Collection<MemoryEdge> edges,
                                                List<Candidate> candidates,
                                                int evidenceLimit) {
        Map<String, Candidate> scoreById = new HashMap<>();
        for (Candidate candidate : candidates) scoreById.put(candidate.node().id(), candidate);
        Map<String, String> roleByNode = new HashMap<>();
        Map<String, LinkedHashSet<String>> edgeIdsByNode = new HashMap<>();
        Map<String, LinkedHashSet<String>> supportByClaim = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> contradictByClaim = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> conflictEdgesByClaim = new LinkedHashMap<>();

        for (MemoryNode node : nodes) {
            if (EVIDENCE_TYPES.contains(node.type())) roleByNode.putIfAbsent(node.id(), "CONTEXT");
        }
        for (MemoryEdge edge : edges) {
            if (edge.type() != MemoryEdgeType.SUPPORTS && edge.type() != MemoryEdgeType.CONTRADICTS) {
                continue;
            }
            String evidenceId = edge.sourceId();
            String role = edge.type() == MemoryEdgeType.SUPPORTS ? "SUPPORT" : "CONFLICT";
            roleByNode.merge(evidenceId, role, HybridMemoryRetriever::preferRole);
            edgeIdsByNode.computeIfAbsent(evidenceId, ignored -> new LinkedHashSet<>()).add(edge.id());
            if (edge.type() == MemoryEdgeType.SUPPORTS) {
                supportByClaim.computeIfAbsent(edge.targetId(), ignored -> new LinkedHashSet<>())
                        .add(evidenceId);
            } else {
                contradictByClaim.computeIfAbsent(edge.targetId(), ignored -> new LinkedHashSet<>())
                        .add(evidenceId);
            }
            conflictEdgesByClaim.computeIfAbsent(edge.targetId(), ignored -> new LinkedHashSet<>())
                    .add(edge.id());
            for (String evidenceRef : edge.evidenceIds()) {
                roleByNode.merge(evidenceRef, role, HybridMemoryRetriever::preferRole);
                edgeIdsByNode.computeIfAbsent(evidenceRef, ignored -> new LinkedHashSet<>()).add(edge.id());
            }
        }

        List<RetrievalTrace.Evidence> evidence = new ArrayList<>();
        for (MemoryNode node : nodes) {
            String role = roleByNode.get(node.id());
            if (role == null) continue;
            Candidate candidate = scoreById.get(node.id());
            double score = candidate == null ? node.confidence() : candidate.combined();
            evidence.add(new RetrievalTrace.Evidence(node.id(), node.id(), role,
                    node.type().name(), node.summary(), node.sourceRef(), node.confidence(),
                    score, new ArrayList<>(edgeIdsByNode.getOrDefault(node.id(), new LinkedHashSet<>()))));
        }
        evidence.sort(Comparator
                .comparingInt((RetrievalTrace.Evidence item) -> roleRank(item.role()))
                .thenComparing(Comparator.comparingDouble(
                        RetrievalTrace.Evidence::score).reversed())
                .thenComparing(RetrievalTrace.Evidence::evidenceId));
        if (evidence.size() > evidenceLimit) evidence = new ArrayList<>(evidence.subList(0, evidenceLimit));

        List<RetrievalTrace.Conflict> conflicts = new ArrayList<>();
        Set<String> claims = new LinkedHashSet<>();
        claims.addAll(supportByClaim.keySet());
        claims.addAll(contradictByClaim.keySet());
        for (String claim : claims) {
            List<String> supporting = new ArrayList<>(supportByClaim.getOrDefault(claim, new LinkedHashSet<>()));
            List<String> contradicting = new ArrayList<>(contradictByClaim.getOrDefault(claim, new LinkedHashSet<>()));
            if (contradicting.isEmpty()) continue;
            conflicts.add(new RetrievalTrace.Conflict(claim, supporting, contradicting,
                    new ArrayList<>(conflictEdgesByClaim.getOrDefault(claim, new LinkedHashSet<>())),
                    supporting.isEmpty() ? "contradicting evidence without support"
                            : "supporting and contradicting evidence target the same claim"));
        }
        return new EvidenceBuild(List.copyOf(evidence), List.copyOf(conflicts));
    }

    private static String preferRole(String previous, String next) {
        if ("CONFLICT".equals(previous) || "CONFLICT".equals(next)) return "CONFLICT";
        if ("SUPPORT".equals(previous) || "SUPPORT".equals(next)) return "SUPPORT";
        return "CONTEXT";
    }

    private static int roleRank(String role) {
        return switch (role) {
            case "SUPPORT" -> 0;
            case "CONFLICT" -> 1;
            default -> 2;
        };
    }

    private static String reason(boolean hasSupport, boolean hasConflict, boolean empty) {
        if (empty) return "no evidence nodes were recovered";
        if (!hasSupport && hasConflict) return "only contradicting evidence was recovered";
        if (!hasSupport) return "no SUPPORTS evidence was recovered";
        return "supporting and contradicting evidence conflict";
    }

    private static String seedReason(Candidate candidate, RetrievalQuery request) {
        List<String> channels = channels(candidate);
        if (channels.isEmpty()) return "no retrieval channel matched";
        return String.join(" + ", channels) + " score; policy filters passed";
    }

    private static List<String> channels(Candidate candidate) {
        List<String> channels = new ArrayList<>();
        if (candidate.lexical() > 0.0) channels.add("lexical");
        if (candidate.vectorPresent()) channels.add("vector");
        if (candidate.metadata() > 0.0) channels.add("metadata/type");
        return List.copyOf(channels);
    }

    private static List<String> reasons(Candidate candidate) {
        List<String> reasons = new ArrayList<>();
        if (candidate.lexical() > 0.0) reasons.add("lexical=" + round(candidate.lexical()));
        if (candidate.vectorPresent()) reasons.add("vector=" + round(candidate.vector()));
        if (candidate.metadata() > 0.0) reasons.add("metadata=" + round(candidate.metadata()));
        return List.copyOf(reasons);
    }

    private static String searchable(MemoryNode node) {
        return (node.id() + " " + node.summary() + " "
                + (node.sourceRef() == null ? "" : node.sourceRef()) + " "
                + node.provenance()).toLowerCase(Locale.ROOT);
    }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        return TOKEN_PATTERN.matcher(value).results().map(match -> match.group()).toList();
    }

    private static String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record Candidate(MemoryNode node, double lexical, double vector,
                             double metadata, double combined, boolean vectorPresent) {}

    private record EvidenceBuild(List<RetrievalTrace.Evidence> evidence,
                                 List<RetrievalTrace.Conflict> conflicts) {}
}
