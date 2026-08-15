package com.ouisani.aios.core.ipc;

import com.ouisani.aios.core.memory.MemoryLayer;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.tool.DelegationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.UUID;

/**
 * 共享内存段管理器 (SHM IPC) — AIOS 的进程间共享内存。
 * <p>
 * 管理两种类型的共享内存段：
 * <ol>
 *   <li><b>传统段</b> — 简单的 {@code Map<String, String>} 键值存储，
 *       兼容原始的基于 VFS 的 SHM 接口。</li>
 *   <li><b>语义块</b> — 结构化的 {@link SemanticMemoryBlock} 段，
 *       支持字符串数据、上下文指针和向量嵌入，
 *       用于跨 Agent 的"潜意识"通信。</li>
 * </ol>
 *
 * <h3>OS 类比: POSIX Shared Memory + mmap()</h3>
 * 当 Agent A 向 SemanticMemoryBlock 写入上下文指针或向量嵌入时，
 * 相当于执行 {@code mmap()}：将其认知状态的一部分映射到共享地址空间。
 * Agent B 可以直接读取该区域，无需消息传递 — 数据已在它的"地址空间"中。
 * <p>
 * 唯一需要的通知是轻量级中断信号
 * ({@link SignalType#SIG_CONTEXT_UPDATE})，相当于硬件中断 — 开销极低，即时送达。
 *
 * @see SemanticMemoryBlock
 * @see SignalType#SIG_CONTEXT_UPDATE
 */
public final class SharedMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(SharedMemoryManager.class);

    private static final class Holder {
        static final SharedMemoryManager INSTANCE = new SharedMemoryManager();
    }

    public static SharedMemoryManager instance() {
        return Holder.INSTANCE;
    }

    // ── 传统字符串段 ──

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> shmSegments = new ConcurrentHashMap<>();

    // ── 语义内存块 ──

    private final ConcurrentHashMap<String, SemanticMemoryBlock> semanticBlocks = new ConcurrentHashMap<>();

    /** namespace + NUL + memoryId -> current governed record. */
    private final ConcurrentHashMap<String, MemoryRecord> scopedRecords = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MemoryRecord>> scopedHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MemorySubscription> memorySubscriptions = new ConcurrentHashMap<>();

    private SharedMemoryManager() {}

    // ════════════════════════════════════════════════════════════════
    //  传统字符串段 API（向后兼容）
    // ════════════════════════════════════════════════════════════════

    public ConcurrentHashMap<String, String> getOrCreateSegment(String segmentId) {
        return shmSegments.computeIfAbsent(segmentId, id -> {
            log.info("[SHM] Segment created: {}", id);
            return new ConcurrentHashMap<>();
        });
    }

    public ConcurrentHashMap<String, String> getSegment(String segmentId) {
        return shmSegments.get(segmentId);
    }

    public boolean destroySegment(String segmentId) {
        ConcurrentHashMap<String, String> removed = shmSegments.remove(segmentId);
        if (removed != null) {
            log.info("[SHM] 段已销毁: {} (原有 {} 个键)", segmentId, removed.size());
            return true;
        }
        return false;
    }

    public Set<String> listSegments() {
        return Collections.unmodifiableSet(shmSegments.keySet());
    }

    public int segmentCount() {
        return shmSegments.size();
    }

    public void put(String segmentId, String key, String value) {
        getOrCreateSegment(segmentId).put(key, value);
        log.debug("[SHM] put: segment={}, key={}, valueLen={}", segmentId, key, value.length());
    }

    public String get(String segmentId, String key) {
        Map<String, String> segment = shmSegments.get(segmentId);
        if (segment == null) return null;
        return segment.get(key);
    }

    public String dumpSegment(String segmentId) {
        Map<String, String> segment = shmSegments.get(segmentId);
        if (segment == null) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : segment.entrySet()) {
            if (!first) sb.append(", ");
            sb.append("\"").append(entry.getKey()).append("\": \"")
                    .append(entry.getValue().length() > 200
                            ? entry.getValue().substring(0, 200) + "..."
                            : entry.getValue())
                    .append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  语义内存块 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建或获取语义内存块。
     * <p>
     * 类比 {@code shm_open() + mmap()}：创建一个命名的共享内存区域，
     * 多个 Agent 可以访问，实现零拷贝的潜意识通信。
     *
     * @param blockId 块标识（如 "devhouse_project_alpha"）
     * @return SemanticMemoryBlock
     */
    public SemanticMemoryBlock getOrCreateSemanticBlock(String blockId) {
        return semanticBlocks.computeIfAbsent(blockId, id -> {
            SemanticMemoryBlock block = new SemanticMemoryBlock(id);
            log.info("[SHM] Semantic block created: {} (supports vectors + context pointers)", id);
            System.out.println("  \u001B[36m[SHM] 语义块 '" + id + "' 已分配 (向量 + 上下文指针已启用)\u001B[0m");
            return block;
        });
    }

    /** 获取已有的语义内存块，不存在则返回 null */
    public SemanticMemoryBlock getSemanticBlock(String blockId) {
        return semanticBlocks.get(blockId);
    }

    /** 销毁语义内存块 */
    public boolean destroySemanticBlock(String blockId) {
        SemanticMemoryBlock removed = semanticBlocks.remove(blockId);
        if (removed != null) {
            log.info("[SHM] Semantic block destroyed: {} (version={}, strings={}, contexts={}, vectors={})",
                    blockId, removed.version(), removed.stringDataSize(),
                    removed.contextPointerCount(), removed.vectorCount());
            return true;
        }
        return false;
    }

    /** 列出所有语义块 ID */
    public Set<String> listSemanticBlocks() {
        return Collections.unmodifiableSet(semanticBlocks.keySet());
    }

    /** 获取活跃语义块数量 */
    public int semanticBlockCount() {
        return semanticBlocks.size();
    }

    // ════════════════════════════════════════════════════════════════
    //  便捷方法：写入语义块并返回版本号
    // ════════════════════════════════════════════════════════════════

    /**
     * 向语义块写入字符串值并返回新版本号。
     * <p>
     * 调用者可将返回的版本号包含在 {@link SignalType#SIG_CONTEXT_UPDATE} 信号中，
     * 以便接收 Agent 知道应期望哪个版本。
     *
     * @param blockId 块标识
     * @param key     键
     * @param value   值
     * @return 写入后的新版本号
     */
    public long putSemanticString(String blockId, String key, String value) {
        SemanticMemoryBlock block = getOrCreateSemanticBlock(blockId);
        block.putString(key, value);
        log.debug("[SHM] Semantic put: block={}, key={}, valueLen={}, newVersion={}",
                blockId, key, value.length(), block.version());
        return block.version();
    }

    /** 从语义块读取字符串值 */
    public String getSemanticString(String blockId, String key) {
        SemanticMemoryBlock block = semanticBlocks.get(blockId);
        return block != null ? block.getString(key) : null;
    }

    /** 向语义块写入向量嵌入并返回新版本号 */
    public long putSemanticVector(String blockId, String key, float[] embedding) {
        SemanticMemoryBlock block = getOrCreateSemanticBlock(blockId);
        block.putVector(key, embedding);
        log.debug("[SHM] Semantic vector: block={}, key={}, dims={}, newVersion={}",
                blockId, key, embedding.length, block.version());
        return block.version();
    }

    /** 从语义块读取向量嵌入 */
    public float[] getSemanticVector(String blockId, String key) {
        SemanticMemoryBlock block = semanticBlocks.get(blockId);
        return block != null ? block.getVector(key) : null;
    }

    /** 向语义块写入上下文指针并返回新版本号 */
    public long putContextPointer(String blockId, String key, String contextRef, String summary, long embeddingHash) {
        SemanticMemoryBlock block = getOrCreateSemanticBlock(blockId);
        block.putContextPointer(key, contextRef, summary, embeddingHash);
        log.debug("[SHM] Context pointer: block={}, key={}, ref={}, newVersion={}",
                blockId, key, contextRef, block.version());
        return block.version();
    }

    // ════════════════════════════════════════════════════════════════
    // Scoped Shared Memory — namespace/scope/version/provenance
    // ════════════════════════════════════════════════════════════════

    /** Create or update a governed memory record, retaining the old version. */
    public MemoryRecord putMemory(MemoryRecord draft, MemoryAccessContext context) {
        Objects.requireNonNull(draft, "memory record must not be null");
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        String key = memoryKey(draft.namespace(), draft.memoryId());
        final MemoryRecord[] result = new MemoryRecord[1];
        scopedRecords.compute(key, (ignored, existing) -> {
            if (existing == null) {
                requireCreateAllowed(draft, caller);
                result[0] = normalizeCreated(draft, caller);
                return result[0];
            }
            requireAccess(existing, caller, "write");
            result[0] = existing.withContent(draft.content(), draft.source(), caller.agentId(), draft.traceId());
            scopedHistory.computeIfAbsent(key, ignoredKey -> new CopyOnWriteArrayList<>()).add(existing);
            return result[0];
        });
        auditUpdate(result[0], caller, "write");
        publishUpdate(result[0], caller);
        return result[0];
    }

    /** Create-only variant; an existing namespace/key is a conflict. */
    public MemoryRecord createMemory(MemoryRecord draft, MemoryAccessContext context) {
        Objects.requireNonNull(draft, "memory record must not be null");
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        requireCreateAllowed(draft, caller);
        MemoryRecord created = normalizeCreated(draft, caller);
        String key = memoryKey(created.namespace(), created.memoryId());
        if (scopedRecords.putIfAbsent(key, created) != null) {
            throw new IllegalStateException("memory record already exists: " + created.memoryId());
        }
        auditUpdate(created, caller, "create");
        publishUpdate(created, caller);
        return created;
    }

    /** Convenience create/update method for text records. */
    public MemoryRecord putMemory(String namespace, String memoryId, String content,
                                  MemoryScope scope, String source,
                                  MemoryAccessContext context) {
        return putMemory(namespace, memoryId, content, scope, source, null,
                MemoryLayer.L1, context);
    }

    /** Convenience overload for TEAM records, which require a team identity. */
    public MemoryRecord putMemory(String namespace, String memoryId, String content,
                                  MemoryScope scope, String source, String teamId,
                                  MemoryAccessContext context) {
        return putMemory(namespace, memoryId, content, scope, source, teamId,
                MemoryLayer.L1, context);
    }

    /** Convenience overload for explicit L0-L3 lifecycle placement. */
    public MemoryRecord putMemory(String namespace, String memoryId, String content,
                                  MemoryScope scope, String source, String teamId,
                                  MemoryLayer layer, MemoryAccessContext context) {
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        if (caller == null || !caller.hasIdentity()) throw denied(namespace, caller, "missing_agent_identity");
        MemoryRecord draft = MemoryRecord.draft(namespace, memoryId, scope, caller.agentId(),
                caller.effectiveWorkflowId(), caller.effectiveTenantId(), layer, content, source)
                .withTeam(teamId == null ? caller.teamId() : teamId);
        return putMemory(draft, caller);
    }

    /** Return a record if the caller can read it; missing records return null. */
    public MemoryRecord getMemory(String namespace, String memoryId, MemoryAccessContext context) {
        MemoryRecord record = scopedRecords.get(memoryKey(namespace, memoryId));
        if (record == null) return null;
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        requireAccess(record, caller, "read");
        return record.expiredAt(System.currentTimeMillis()) ? null : record;
    }

    public MemoryRecord getMemory(String namespace, String memoryId) {
        return getMemory(namespace, memoryId, MemoryAccessContext.current());
    }

    /** List only records visible to this caller in the requested namespace. */
    public List<MemoryRecord> listMemory(String namespace, MemoryAccessContext context) {
        String ns = required(namespace, "namespace");
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        List<MemoryRecord> visible = new ArrayList<>();
        String prefix = ns + "\u0000";
        for (Map.Entry<String, MemoryRecord> entry : scopedRecords.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            MemoryRecord record = entry.getValue();
            if (!record.expiredAt(System.currentTimeMillis()) && canAccess(record, caller, "read")) visible.add(record);
        }
        visible.sort(Comparator.comparing(MemoryRecord::updatedAt));
        return Collections.unmodifiableList(visible);
    }

    public List<MemoryRecord> listMemory(String namespace) {
        return listMemory(namespace, MemoryAccessContext.current());
    }

    /**
     * List every non-expired record visible to the supplied caller.
     *
     * <p>This is intentionally an authenticated projection of the in-memory
     * index, not a second memory store.  Consumers such as the Wiki compiler
     * use it when they need to build a cross-namespace read model while still
     * applying the same scope, tenant and delegation checks as normal reads.</p>
     */
    public List<MemoryRecord> listVisibleMemory(MemoryAccessContext context) {
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        long now = System.currentTimeMillis();
        List<MemoryRecord> visible = new ArrayList<>();
        for (MemoryRecord record : scopedRecords.values()) {
            if (!record.expiredAt(now) && canAccess(record, caller, "read")) {
                visible.add(record);
            }
        }
        visible.sort(Comparator.comparing(MemoryRecord::updatedAt).reversed());
        return Collections.unmodifiableList(visible);
    }

    /** Append text while advancing the optimistic version. */
    public MemoryRecord appendMemory(String namespace, String memoryId, String suffix,
                                     String source, MemoryAccessContext context) {
        Objects.requireNonNull(suffix, "suffix must not be null");
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        String key = memoryKey(namespace, memoryId);
        final MemoryRecord[] result = new MemoryRecord[1];
        scopedRecords.compute(key, (ignored, existing) -> {
            if (existing == null) throw new IllegalArgumentException("memory record not found: " + memoryId);
            requireAccess(existing, caller, "write");
            String joined = existing.content().isEmpty() ? suffix
                    : existing.content() + System.lineSeparator() + suffix;
            result[0] = existing.withContent(joined, source, caller.agentId(), null);
            scopedHistory.computeIfAbsent(key, ignoredKey -> new CopyOnWriteArrayList<>()).add(existing);
            return result[0];
        });
        auditUpdate(result[0], caller, "append");
        publishUpdate(result[0], caller);
        return result[0];
    }

    /** Compare-and-set; empty means missing record or version conflict. */
    public Optional<MemoryRecord> compareAndSetMemory(String namespace, String memoryId,
                                                       long expectedVersion, String content,
                                                       String source, MemoryAccessContext context) {
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        String key = memoryKey(namespace, memoryId);
        final MemoryRecord[] result = new MemoryRecord[1];
        scopedRecords.compute(key, (ignored, existing) -> {
            if (existing == null) return null;
            requireAccess(existing, caller, "write");
            if (existing.version() != expectedVersion) return existing;
            result[0] = existing.withContent(content, source, caller.agentId(), null);
            scopedHistory.computeIfAbsent(key, ignoredKey -> new CopyOnWriteArrayList<>()).add(existing);
            return result[0];
        });
        if (result[0] == null) return Optional.empty();
        auditUpdate(result[0], caller, "compare_and_set");
        publishUpdate(result[0], caller);
        return Optional.of(result[0]);
    }

    /** Return previous versions, oldest first. */
    public List<MemoryRecord> memoryHistory(String namespace, String memoryId,
                                            MemoryAccessContext context) {
        String key = memoryKey(namespace, memoryId);
        MemoryRecord current = scopedRecords.get(key);
        if (current == null) return List.of();
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        requireAccess(current, caller, "read");
        List<MemoryRecord> history = scopedHistory.get(key);
        return history == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(history));
    }

    /** Snapshot current records for hibernation/recovery integrations. */
    public List<MemoryRecord> snapshotMemory() {
        return List.copyOf(scopedRecords.values());
    }

    /** Restore records captured by {@link #snapshotMemory()} without events. */
    public void restoreMemory(Iterable<MemoryRecord> records) {
        if (records == null) return;
        for (MemoryRecord record : records) {
            if (record == null) continue;
            scopedRecords.merge(memoryKey(record.namespace(), record.memoryId()), record,
                    (oldValue, restored) -> restored.version() >= oldValue.version() ? restored : oldValue);
        }
    }

    /** Subscribe to records only when the supplied identity is allowed to read them. */
    public String subscribeMemoryUpdates(String namespace, MemoryAccessContext context,
                                         Consumer<MemoryRecord> handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        String ns = required(namespace, "namespace");
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        if (caller == null || !caller.hasIdentity()) throw denied(ns, caller, "missing_agent_identity");
        String id = "memsub_" + UUID.randomUUID();
        memorySubscriptions.put(id, new MemorySubscription(ns, caller, handler));
        return id;
    }

    public boolean unsubscribeMemoryUpdates(String subscriptionId) {
        return subscriptionId != null && memorySubscriptions.remove(subscriptionId) != null;
    }

    private void requireCreateAllowed(MemoryRecord record, MemoryAccessContext caller) {
        if (caller == null || !caller.hasIdentity() || !record.ownerAgentId().equals(caller.agentId())) {
            throw denied(record.namespace(), caller, "create_owner_mismatch");
        }
        DelegationToken token = caller.delegationToken();
        if (token != null && (!token.isValid() || !caller.agentId().equals(token.childAgentId()))) {
            throw denied(record.namespace(), caller, "delegation_identity_mismatch");
        }
        if (!caller.allowsNamespace(record.namespace(), "write")) {
            throw denied(record.namespace(), caller, "delegation_namespace_denied");
        }
        if (record.tenantId() != null && !record.tenantId().equals(caller.effectiveTenantId())) {
            throw denied(record.namespace(), caller, "tenant_mismatch");
        }
        if (record.scope() == MemoryScope.TASK && record.workflowId() == null) {
            throw new IllegalArgumentException("TASK memory requires workflowId");
        }
        if (record.scope() == MemoryScope.TEAM && record.teamId() == null) {
            throw new IllegalArgumentException("TEAM memory requires teamId");
        }
    }

    private MemoryRecord normalizeCreated(MemoryRecord draft, MemoryAccessContext caller) {
        return new MemoryRecord(draft.memoryId(), draft.namespace(), draft.scope(), draft.ownerAgentId(),
                draft.workflowId() == null ? caller.effectiveWorkflowId() : draft.workflowId(),
                draft.tenantId() == null ? caller.effectiveTenantId() : draft.tenantId(),
                draft.teamId(), draft.contentType(), draft.layer(), draft.content(), draft.source(), draft.sourceRef(),
                draft.sourceAgentId() == null ? caller.agentId() : draft.sourceAgentId(), draft.traceId(),
                1L, draft.confidence(), draft.tags(), draft.createdAt(), draft.updatedAt(), draft.expiresAt());
    }

    private void requireAccess(MemoryRecord record, MemoryAccessContext caller, String operation) {
        if (!canAccess(record, caller, operation)) {
            throw denied(record.namespace(), caller, accessReason(record, caller, operation));
        }
    }

    private boolean canAccess(MemoryRecord record, MemoryAccessContext caller, String operation) {
        if (caller == null || !caller.hasIdentity()) return false;
        DelegationToken token = caller.delegationToken();
        if (token != null && (!token.isValid() || !caller.agentId().equals(token.childAgentId()))) return false;
        if (record.tenantId() != null && !record.tenantId().equals(caller.effectiveTenantId())) return false;
        if (!caller.allowsNamespace(record.namespace(), operation)) return false;
        return switch (record.scope()) {
            case PRIVATE -> record.ownerAgentId().equals(caller.agentId());
            case TASK -> record.workflowId() != null && record.workflowId().equals(caller.effectiveWorkflowId());
            case TEAM -> record.teamId() != null && record.teamId().equals(caller.teamId());
        };
    }

    private String accessReason(MemoryRecord record, MemoryAccessContext caller, String operation) {
        if (caller == null || !caller.hasIdentity()) return "missing_agent_identity";
        if (record.tenantId() != null && !record.tenantId().equals(caller.effectiveTenantId())) return "tenant_mismatch";
        if (!caller.allowsNamespace(record.namespace(), operation)) return "delegation_namespace_denied";
        return switch (record.scope()) {
            case PRIVATE -> "private_owner_mismatch";
            case TASK -> "workflow_mismatch";
            case TEAM -> "team_mismatch";
        };
    }

    private MemoryAccessDeniedException denied(String namespace, MemoryAccessContext caller, String reason) {
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_PERMISSION, "SHARED_MEMORY", "MEMORY_DENY",
                caller == null ? null : caller.agentId(), namespace, reason, auditContext(caller)));
        return new MemoryAccessDeniedException("scoped memory access denied: namespace=" + namespace
                + ", agent=" + (caller == null ? null : caller.agentId()) + ", reason=" + reason);
    }

    private void auditUpdate(MemoryRecord record, MemoryAccessContext caller, String operation) {
        if (record == null) return;
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_PERMISSION, "SHARED_MEMORY", "MEMORY_" + operation.toUpperCase(),
                caller == null ? null : caller.agentId(), record.namespace(),
                "memoryId=" + record.memoryId() + ",version=" + record.version(), auditContext(caller)));
    }

    private UnifiedAuditLog.AuditContext auditContext(MemoryAccessContext caller) {
        if (caller == null) return UnifiedAuditLog.AuditContext.current();
        DelegationToken token = caller.delegationToken();
        return new UnifiedAuditLog.AuditContext(
                caller.effectiveTenantId(), caller.effectiveWorkflowId(), null,
                TraceContext.getCurrentTraceId(), caller.agentId(),
                token == null ? null : token.parentAgentId(),
                token == null ? null : token.tokenId(), null, -1);
    }

    private void publishUpdate(MemoryRecord record, MemoryAccessContext caller) {
        for (MemorySubscription subscription : memorySubscriptions.values()) {
            if (subscription.namespace().equals(record.namespace())
                    && canAccess(record, subscription.context(), "read")) {
                Thread.startVirtualThread(() -> {
                    try {
                        subscription.handler().accept(record);
                    } catch (RuntimeException e) {
                        log.debug("[SHM] scoped memory subscriber failed: {}", e.getMessage());
                    }
                });
            }
        }
        // The legacy EventBus has no authenticated subscriber identity. Do not
        // expose scoped metadata for private or tenant-bound records through
        // its global channel; authenticated callers use the scoped API above.
        if (record.scope() == MemoryScope.PRIVATE || record.tenantId() != null) return;
        String payload = "{\"memoryId\":\"" + json(record.memoryId())
                + "\",\"namespace\":\"" + json(record.namespace())
                + "\",\"scope\":\"" + record.scope() + "\",\"version\":" + record.version()
                + ",\"agentId\":\"" + json(caller == null ? null : caller.agentId()) + "\"}";
        EventBus.instance().broadcast("MEMORY_UPDATE", payload);
    }

    private record MemorySubscription(String namespace, MemoryAccessContext context,
                                      Consumer<MemoryRecord> handler) {}

    private static String memoryKey(String namespace, String memoryId) {
        return required(namespace, "namespace") + "\u0000" + required(memoryId, "memoryId");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
