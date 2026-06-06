package com.ouisani.aios.core.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Semantic Memory Block — a structured shared memory segment that goes
 * beyond simple key-value pairs to support high-dimensional vectors
 * and context pointers for cross-agent "subconscious" communication.
 * <p>
 * In a traditional OS, shared memory is just raw bytes. In AIOS, we
 * elevate this to a <b>semantic</b> memory block that can store:
 * <ul>
 *   <li><b>String data</b> — traditional key-value pairs (like POSIX shm)</li>
 *   <li><b>Context Pointers</b> — references to LLM conversation contexts,
 *       enabling one agent to point another to relevant context without
 *       copying the entire conversation</li>
 *   <li><b>Vector Embeddings</b> — high-dimensional float arrays that
 *       represent semantic meaning, enabling "subconscious" knowledge
 *       transfer between agents without explicit text exchange</li>
 * </ul>
 * <p>
 * <h3>Analogy: mmap() for Neural Memory</h3>
 * Just as {@code mmap()} maps a file into a process's address space
 * for zero-copy sharing, a {@code SemanticMemoryBlock} maps neural
 * representations into an agent's "cognitive address space" for
 * zero-copy subconscious sharing.
 * <p>
 * <h3>Versioning</h3>
 * Each write increments a version counter. Readers can check the
 * version to detect changes without reading the entire block — this
 * is the foundation of the SIG_CONTEXT_UPDATE interrupt mechanism.
 *
 * @see SharedMemoryManager
 * @see SignalType#SIG_CONTEXT_UPDATE
 */
public final class SemanticMemoryBlock {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryBlock.class);

    // ── Block Identity ──

    private final String blockId;
    private final long createdAt;
    private volatile long lastModifiedAt;

    // ── Version Counter (for change detection) ──

    private final AtomicLong version = new AtomicLong(0);

    // ── Data Stores ──

    /** Traditional key-value string data (POSIX shm style). */
    private final ConcurrentHashMap<String, String> stringData = new ConcurrentHashMap<>();

    /** Context pointers — references to LLM conversation contexts. */
    private final ConcurrentHashMap<String, ContextPointer> contextPointers = new ConcurrentHashMap<>();

    /** Vector embeddings — high-dimensional semantic representations. */
    private final ConcurrentHashMap<String, float[]> vectorData = new ConcurrentHashMap<>();

    /** Metadata — block-level tags (owner, project, status, etc.). */
    private final ConcurrentHashMap<String, String> metadata = new ConcurrentHashMap<>();

    // ── Access Control ──

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private volatile int ownerPid = -1;
    private volatile int permissions = 0666;

    // ════════════════════════════════════════════════════════════════
    //  Construction
    // ════════════════════════════════════════════════════════════════

    public SemanticMemoryBlock(String blockId) {
        this.blockId = blockId;
        this.createdAt = System.currentTimeMillis();
        this.lastModifiedAt = this.createdAt;
        log.debug("[SemanticBlock] Created: blockId={}", blockId);
    }

    // ════════════════════════════════════════════════════════════════
    //  String Data (Traditional SHM)
    // ════════════════════════════════════════════════════════════════

    /**
     * Write a string key-value pair (analogous to shm_write).
     */
    public void putString(String key, String value) {
        rwLock.writeLock().lock();
        try {
            stringData.put(key, value);
            touch();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Read a string value by key (analogous to shm_read).
     */
    public String getString(String key) {
        rwLock.readLock().lock();
        try {
            return stringData.get(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Bulk write multiple string key-value pairs.
     */
    public void putStrings(Map<String, String> entries) {
        rwLock.writeLock().lock();
        try {
            stringData.putAll(entries);
            touch();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Get all string keys.
     */
    public Set<String> stringKeys() {
        return Collections.unmodifiableSet(stringData.keySet());
    }

    // ════════════════════════════════════════════════════════════════
    //  Context Pointers (Neural mmap)
    // ════════════════════════════════════════════════════════════════

    /**
     * Write a context pointer — a reference to an LLM conversation
     * context that another agent can dereference.
     * <p>
     * This is the neural equivalent of {@code mmap()}: instead of
     * copying the entire conversation, the PM agent writes a pointer
     * to its context, and the Coder agent can dereference it to
     * access the full context on demand.
     *
     * @param key           the pointer name (e.g., "prd_context")
     * @param contextRef    a reference identifier (e.g., a VFS path or UUID)
     * @param summary       a brief summary of the context (for quick scanning)
     * @param embeddingHash a hash of the context's embedding (for similarity checks)
     */
    public void putContextPointer(String key, String contextRef, String summary, long embeddingHash) {
        rwLock.writeLock().lock();
        try {
            contextPointers.put(key, new ContextPointer(contextRef, summary, embeddingHash, System.currentTimeMillis()));
            touch();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Read a context pointer by key.
     */
    public ContextPointer getContextPointer(String key) {
        rwLock.readLock().lock();
        try {
            return contextPointers.get(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Get all context pointer keys.
     */
    public Set<String> contextPointerKeys() {
        return Collections.unmodifiableSet(contextPointers.keySet());
    }

    // ════════════════════════════════════════════════════════════════
    //  Vector Embeddings (Subconscious Transfer)
    // ════════════════════════════════════════════════════════════════

    /**
     * Write a vector embedding — a high-dimensional float array
     * representing semantic meaning.
     * <p>
     * This enables "subconscious" knowledge transfer: the PM agent
     * writes its understanding of the product requirements as a vector,
     * and the Coder agent can read this vector to align its code
     * generation strategy without ever seeing the raw text.
     * <p>
     * Think of it as the neural equivalent of shared memory for
     * spatial representations — like how the hippocampus and neocortex
     * share compressed memory traces during sleep consolidation.
     *
     * @param key      the vector name (e.g., "prd_embedding")
     * @param embedding the float array (typically 768 or 1536 dimensions)
     */
    public void putVector(String key, float[] embedding) {
        rwLock.writeLock().lock();
        try {
            vectorData.put(key, embedding.clone()); // defensive copy
            touch();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Read a vector embedding by key.
     *
     * @return a defensive copy of the embedding, or null if not found
     */
    public float[] getVector(String key) {
        rwLock.readLock().lock();
        try {
            float[] original = vectorData.get(key);
            return original != null ? original.clone() : null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Get all vector keys.
     */
    public Set<String> vectorKeys() {
        return Collections.unmodifiableSet(vectorData.keySet());
    }

    // ════════════════════════════════════════════════════════════════
    //  Metadata
    // ════════════════════════════════════════════════════════════════

    public void setMetadata(String key, String value) { metadata.put(key, value); }
    public String getMetadata(String key) { return metadata.get(key); }
    public Map<String, String> allMetadata() { return Collections.unmodifiableMap(metadata); }

    // ════════════════════════════════════════════════════════════════
    //  Version & Change Detection
    // ════════════════════════════════════════════════════════════════

    /**
     * Get the current version number. Incremented on every write.
     * <p>
     * Agents can compare versions to detect changes without reading
     * the entire block — this is the foundation of the
     * SIG_CONTEXT_UPDATE interrupt mechanism.
     */
    public long version() {
        return version.get();
    }

    /**
     * Check if the block has been updated since a given version.
     */
    public boolean hasChangedSince(long lastSeenVersion) {
        return version.get() > lastSeenVersion;
    }

    // ════════════════════════════════════════════════════════════════
    //  Diagnostics
    // ════════════════════════════════════════════════════════════════

    public String blockId() { return blockId; }
    public long createdAt() { return createdAt; }
    public long lastModifiedAt() { return lastModifiedAt; }
    public int ownerPid() { return ownerPid; }
    public void setOwnerPid(int pid) { this.ownerPid = pid; }
    public int stringDataSize() { return stringData.size(); }
    public int contextPointerCount() { return contextPointers.size(); }
    public int vectorCount() { return vectorData.size(); }

    /**
     * Dump the block's string data as a JSON-like string.
     */
    public String dump() {
        rwLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"blockId\":\"").append(blockId).append("\"");
            sb.append(",\"version\":").append(version.get());
            sb.append(",\"strings\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : stringData.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":\"")
                        .append(truncate(e.getValue(), 200)).append("\"");
                first = false;
            }
            sb.append("},\"contexts\":[");
            first = true;
            for (Map.Entry<String, ContextPointer> e : contextPointers.entrySet()) {
                if (!first) sb.append(",");
                sb.append("{\"key\":\"").append(e.getKey()).append("\",\"ref\":\"")
                        .append(e.getValue().contextRef()).append("\",\"summary\":\"")
                        .append(truncate(e.getValue().summary(), 100)).append("\"}");
                first = false;
            }
            sb.append("],\"vectors\":[");
            first = true;
            for (String key : vectorData.keySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(key).append("\"");
                first = false;
            }
            sb.append("]}");
            return sb.toString();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ── Internal ──

    private void touch() {
        this.version.incrementAndGet();
        this.lastModifiedAt = System.currentTimeMillis();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ════════════════════════════════════════════════════════════════
    //  Context Pointer Record
    // ════════════════════════════════════════════════════════════════

    /**
     * A context pointer — a lightweight reference to an LLM conversation
     * context stored elsewhere (e.g., in VFS or the Vector Memory).
     * <p>
     * Instead of copying the entire context between agents, we pass
     * a pointer that the receiving agent can dereference on demand.
     * This is the neural equivalent of a memory pointer in C:
     * the address is cheap to copy, the data is accessed only when needed.
     *
     * @param contextRef    the reference (VFS path, UUID, etc.)
     * @param summary       a brief summary of the context
     * @param embeddingHash a hash of the context's embedding for quick comparison
     * @param timestamp     when this pointer was created
     */
    public record ContextPointer(
            String contextRef,
            String summary,
            long embeddingHash,
            long timestamp
    ) {}
}
