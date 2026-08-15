package com.ouisani.aios.core.ipc;

import com.ouisani.aios.core.memory.MemoryLayer;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.io.Serializable;

/**
 * Immutable, provenance-carrying record stored by scoped shared memory.
 *
 * <p>The legacy semantic-block API remains available. This record is the
 * governed API used when memory must carry namespace, scope and identity
 * boundaries.</p>
 */
public record MemoryRecord(
        String memoryId,
        String namespace,
        MemoryScope scope,
        String ownerAgentId,
        String workflowId,
        String tenantId,
        String teamId,
        String contentType,
        MemoryLayer layer,
        String content,
        String source,
        String sourceRef,
        String sourceAgentId,
        String traceId,
        long version,
        double confidence,
        Set<String> tags,
        long createdAt,
        long updatedAt,
        Long expiresAt) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Full pre-layer constructor kept source-compatible with existing integrations. */
    public MemoryRecord(String memoryId, String namespace, MemoryScope scope,
                        String ownerAgentId, String workflowId, String tenantId,
                        String teamId, String contentType, String content,
                        String source, String sourceRef, String sourceAgentId,
                        String traceId, long version, double confidence,
                        Set<String> tags, long createdAt, long updatedAt,
                        Long expiresAt) {
        this(memoryId, namespace, scope, ownerAgentId, workflowId, tenantId, teamId,
                contentType, MemoryLayer.L1, content, source, sourceRef, sourceAgentId,
                traceId, version, confidence, tags, createdAt, updatedAt, expiresAt);
    }

    public MemoryRecord {
        memoryId = required(memoryId, "memoryId");
        namespace = required(namespace, "namespace");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        ownerAgentId = required(ownerAgentId, "ownerAgentId");
        workflowId = clean(workflowId);
        tenantId = clean(tenantId);
        teamId = clean(teamId);
        contentType = clean(contentType) == null ? "text/plain" : clean(contentType);
        layer = Objects.requireNonNull(layer, "layer must not be null");
        content = content == null ? "" : content;
        source = clean(source) == null ? "agent" : clean(source);
        sourceRef = clean(sourceRef);
        sourceAgentId = clean(sourceAgentId);
        traceId = clean(traceId);
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
        tags = normalizeTags(tags);
        long now = System.currentTimeMillis();
        createdAt = createdAt > 0 ? createdAt : now;
        updatedAt = updatedAt > 0 ? updatedAt : createdAt;
        if (updatedAt < createdAt) throw new IllegalArgumentException("updatedAt must be >= createdAt");
        if (expiresAt != null && expiresAt <= 0) expiresAt = null;
    }

    /** Small constructor for the common text-memory path. */
    public MemoryRecord(String memoryId, String namespace, MemoryScope scope,
                        String ownerAgentId, String workflowId, String tenantId,
                        String content, String source) {
        this(memoryId, namespace, scope, ownerAgentId, workflowId, tenantId, null,
                "text/plain", MemoryLayer.L1, content, source, null, ownerAgentId, null,
                1L, 1.0, Set.of(), 0L, 0L, null);
    }

    /** Layer-aware convenience constructor; access scope remains independent. */
    public MemoryRecord(String memoryId, String namespace, MemoryScope scope,
                        String ownerAgentId, String workflowId, String tenantId,
                        MemoryLayer layer, String content, String source) {
        this(memoryId, namespace, scope, ownerAgentId, workflowId, tenantId, null,
                "text/plain", layer, content, source, null, ownerAgentId, null,
                1L, 1.0, Set.of(), 0L, 0L, null);
    }

    public static MemoryRecord draft(String namespace, String memoryId, MemoryScope scope,
                                     String ownerAgentId, String workflowId, String tenantId,
                                     String content, String source) {
        return new MemoryRecord(memoryId, namespace, scope, ownerAgentId, workflowId,
                tenantId, content, source);
    }

    public static MemoryRecord draft(String namespace, String memoryId, MemoryScope scope,
                                     String ownerAgentId, String workflowId, String tenantId,
                                     MemoryLayer layer, String content, String source) {
        return new MemoryRecord(memoryId, namespace, scope, ownerAgentId, workflowId,
                tenantId, layer, content, source);
    }

    public static MemoryRecord newRecord(String namespace, MemoryScope scope,
                                         String ownerAgentId, String workflowId,
                                         String tenantId, String content, String source) {
        return draft(namespace, "mem_" + UUID.randomUUID(), scope, ownerAgentId,
                workflowId, tenantId, content, source);
    }

    public static MemoryRecord newRecord(String namespace, MemoryScope scope,
                                         String ownerAgentId, String workflowId,
                                         String tenantId, MemoryLayer layer,
                                         String content, String source) {
        return draft(namespace, "mem_" + UUID.randomUUID(), scope, ownerAgentId,
                workflowId, tenantId, layer, content, source);
    }

    public MemoryRecord withContent(String newContent, String newSource,
                                    String modifiedBy, String newTraceId) {
        return new MemoryRecord(memoryId, namespace, scope, ownerAgentId, workflowId,
                tenantId, teamId, contentType, layer, newContent,
                newSource == null ? source : newSource, sourceRef,
                modifiedBy == null ? sourceAgentId : modifiedBy,
                newTraceId == null ? traceId : newTraceId,
                version + 1, confidence, tags, createdAt, System.currentTimeMillis(), expiresAt);
    }

    public MemoryRecord withVersion(long newVersion) {
        return new MemoryRecord(memoryId, namespace, scope, ownerAgentId, workflowId,
                tenantId, teamId, contentType, layer, content, source, sourceRef, sourceAgentId,
                traceId, newVersion, confidence, tags, createdAt, updatedAt, expiresAt);
    }

    public MemoryRecord withTeam(String newTeamId) {
        return new MemoryRecord(memoryId, namespace, scope, ownerAgentId, workflowId,
                tenantId, newTeamId, contentType, layer, content, source, sourceRef, sourceAgentId,
                traceId, version, confidence, tags, createdAt, updatedAt, expiresAt);
    }

    /** Move a record between lifecycle layers without changing access scope. */
    public MemoryRecord withLayer(MemoryLayer newLayer) {
        return new MemoryRecord(memoryId, namespace, scope, ownerAgentId, workflowId,
                tenantId, teamId, contentType, newLayer, content, source, sourceRef,
                sourceAgentId, traceId, version, confidence, tags, createdAt, updatedAt,
                expiresAt);
    }

    public boolean expiredAt(long now) {
        return expiresAt != null && now >= expiresAt;
    }

    private static String required(String value, String name) {
        String cleaned = clean(value);
        if (cleaned == null) throw new IllegalArgumentException(name + " must not be blank");
        return cleaned;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Set<String> normalizeTags(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String cleaned = clean(value);
                if (cleaned != null) normalized.add(cleaned);
            }
        }
        return Set.copyOf(normalized);
    }
}
