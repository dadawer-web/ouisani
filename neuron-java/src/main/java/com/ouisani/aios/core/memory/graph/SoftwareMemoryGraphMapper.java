package com.ouisani.aios.core.memory.graph;

import java.util.Map;

/**
 * Small vocabulary adapter for Neuron's software-agent world.
 *
 * <p>ABot's place/object/visual-evidence concepts become explicit software
 * nodes: workspaces, repositories and branches are places; files, diffs,
 * screenshots and test logs are evidence/artifacts; tool calls, commits,
 * approvals, recoveries and failures are events; and agent/user/tenant/token
 * identities are entities.</p>
 */
public final class SoftwareMemoryGraphMapper {

    private SoftwareMemoryGraphMapper() {
    }

    public static MemoryNode place(String id, String summary, String sourceRef,
                                   String tenant, MemoryVisibility visibility) {
        return node(id, MemoryNodeType.PLACE, summary, sourceRef, tenant, visibility);
    }

    public static MemoryNode workspace(String id, String summary, String vfsRef,
                                      String tenant) {
        return place(id, summary, vfsRef, tenant, MemoryVisibility.TENANT);
    }

    public static MemoryNode repository(String id, String summary, String repoRef,
                                        String tenant) {
        return place(id, summary, repoRef, tenant, MemoryVisibility.TENANT);
    }

    public static MemoryNode branch(String id, String summary, String branchRef,
                                    String tenant) {
        return place(id, summary, branchRef, tenant, MemoryVisibility.TENANT);
    }

    public static MemoryNode artifact(String id, String summary, String sourceRef,
                                      String tenant, MemoryVisibility visibility) {
        return node(id, MemoryNodeType.ARTIFACT, summary, sourceRef, tenant, visibility);
    }

    public static MemoryNode evidence(String id, String summary, String sourceRef,
                                      String tenant, MemoryVisibility visibility) {
        return node(id, MemoryNodeType.EVIDENCE, summary, sourceRef, tenant, visibility);
    }

    public static MemoryNode event(String id, String summary, String sourceRef,
                                   Map<String, Object> provenance, String tenant,
                                   MemoryVisibility visibility) {
        return new MemoryNode(id, MemoryNodeType.EVENT, summary, sourceRef, 1.0,
                provenance, tenant, visibility);
    }

    public static MemoryNode decision(String id, String summary, String sourceRef,
                                      Map<String, Object> provenance, String tenant,
                                      MemoryVisibility visibility) {
        return new MemoryNode(id, MemoryNodeType.DECISION, summary, sourceRef, 1.0,
                provenance, tenant, visibility);
    }

    public static MemoryNode identity(String id, String summary, String sourceRef,
                                      String tenant, MemoryVisibility visibility) {
        return node(id, MemoryNodeType.ENTITY, summary, sourceRef, tenant, visibility);
    }

    public static MemoryNode session(String id, String summary, String sourceRef,
                                     String tenant, MemoryVisibility visibility) {
        return node(id, MemoryNodeType.SESSION, summary, sourceRef, tenant, visibility);
    }

    public static MemoryEdge observedIn(String evidenceId, String eventId,
                                        double confidence, String... evidenceIds) {
        return edge(evidenceId, eventId, MemoryEdgeType.OBSERVED_IN, confidence, evidenceIds);
    }

    public static MemoryEdge locatedAt(String objectId, String placeId,
                                       double confidence, String... evidenceIds) {
        return edge(objectId, placeId, MemoryEdgeType.LOCATED_AT, confidence, evidenceIds);
    }

    public static MemoryEdge producedBy(String artifactId, String eventId,
                                        double confidence, String... evidenceIds) {
        return edge(artifactId, eventId, MemoryEdgeType.PRODUCED_BY, confidence, evidenceIds);
    }

    public static MemoryEdge supports(String evidenceId, String claimId,
                                      double confidence, String... evidenceIds) {
        return edge(evidenceId, claimId, MemoryEdgeType.SUPPORTS, confidence, evidenceIds);
    }

    public static MemoryEdge contradicts(String evidenceId, String claimId,
                                         double confidence, String... evidenceIds) {
        return edge(evidenceId, claimId, MemoryEdgeType.CONTRADICTS, confidence, evidenceIds);
    }

    public static MemoryEdge supersedes(String newerId, String olderId,
                                        double confidence, String... evidenceIds) {
        return edge(newerId, olderId, MemoryEdgeType.SUPERSEDES, confidence, evidenceIds);
    }

    public static MemoryEdge edge(String sourceId, String targetId, MemoryEdgeType type,
                                  double confidence, String... evidenceIds) {
        return new MemoryEdge(sourceId, targetId, type, confidence,
                evidenceIds == null ? java.util.List.of() : java.util.List.of(evidenceIds),
                null, null);
    }

    private static MemoryNode node(String id, MemoryNodeType type, String summary,
                                   String sourceRef, String tenant,
                                   MemoryVisibility visibility) {
        return new MemoryNode(id, type, summary, sourceRef, 1.0, Map.of(), tenant, visibility);
    }
}
