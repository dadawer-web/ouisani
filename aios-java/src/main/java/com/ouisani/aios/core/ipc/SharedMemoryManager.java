package com.ouisani.aios.core.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Memory Segment Manager (SHM IPC) for AIOS.
 * <p>
 * Manages two types of shared memory segments:
 * <ol>
 *   <li><b>Legacy segments</b> — simple {@code Map<String, String>} key-value
 *       stores, compatible with the original VFS-based SHM interface.</li>
 *   <li><b>Semantic blocks</b> — structured {@link SemanticMemoryBlock} segments
 *       that support string data, context pointers, and vector embeddings
 *       for cross-agent "subconscious" communication.</li>
 * </ol>
 * <p>
 * <h3>Architecture: mmap() for Neural Memory</h3>
 * When Agent A writes a context pointer or vector embedding into a
 * SemanticMemoryBlock, it's performing the neural equivalent of
 * {@code mmap()}: mapping a region of its cognitive state into a
 * shared address space. Agent B can then read this region without
 * any message passing — the data is already in its "address space".
 * <p>
 * The only notification needed is a lightweight interrupt signal
 * ({@link SignalType#SIG_CONTEXT_UPDATE}), which is the neural
 * equivalent of a hardware interrupt — near-zero overhead, instant delivery.
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

    // ── Legacy String Segments ──

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> shmSegments = new ConcurrentHashMap<>();

    // ── Semantic Memory Blocks ──

    private final ConcurrentHashMap<String, SemanticMemoryBlock> semanticBlocks = new ConcurrentHashMap<>();

    private SharedMemoryManager() {}

    // ════════════════════════════════════════════════════════════════
    //  Legacy String Segment API (backward compatible)
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
            log.info("[SHM] Segment destroyed: {} (had {} keys)", segmentId, removed.size());
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
    //  Semantic Memory Block API
    // ════════════════════════════════════════════════════════════════

    /**
     * Create or retrieve a SemanticMemoryBlock.
     * <p>
     * This is the neural equivalent of {@code shm_open() + mmap()}:
     * it creates a named shared memory region that multiple agents
     * can access for zero-copy subconscious communication.
     *
     * @param blockId the block identifier (e.g., "devhouse_project_alpha")
     * @return the SemanticMemoryBlock
     */
    public SemanticMemoryBlock getOrCreateSemanticBlock(String blockId) {
        return semanticBlocks.computeIfAbsent(blockId, id -> {
            SemanticMemoryBlock block = new SemanticMemoryBlock(id);
            log.info("[SHM] Semantic block created: {} (supports vectors + context pointers)", id);
            System.out.println("  \u001B[36m[SHM] Semantic block '" + id + "' allocated (vectors + context pointers enabled)\u001B[0m");
            return block;
        });
    }

    /**
     * Get an existing SemanticMemoryBlock.
     *
     * @param blockId the block identifier
     * @return the block, or null if not found
     */
    public SemanticMemoryBlock getSemanticBlock(String blockId) {
        return semanticBlocks.get(blockId);
    }

    /**
     * Destroy a SemanticMemoryBlock.
     *
     * @param blockId the block identifier
     * @return true if the block was removed
     */
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

    /**
     * List all semantic block IDs.
     */
    public Set<String> listSemanticBlocks() {
        return Collections.unmodifiableSet(semanticBlocks.keySet());
    }

    /**
     * Get the number of active semantic blocks.
     */
    public int semanticBlockCount() {
        return semanticBlocks.size();
    }

    // ════════════════════════════════════════════════════════════════
    //  Convenience: Write to Semantic Block + Return Version
    // ════════════════════════════════════════════════════════════════

    /**
     * Write a string value to a semantic block and return the new version.
     * <p>
     * The caller can use the returned version to include in a
     * {@link SignalType#SIG_CONTEXT_UPDATE} signal, so the receiving
     * agent knows which version to expect.
     *
     * @param blockId the block identifier
     * @param key     the key
     * @param value   the value
     * @return the new version number after the write
     */
    public long putSemanticString(String blockId, String key, String value) {
        SemanticMemoryBlock block = getOrCreateSemanticBlock(blockId);
        block.putString(key, value);
        log.debug("[SHM] Semantic put: block={}, key={}, valueLen={}, newVersion={}",
                blockId, key, value.length(), block.version());
        return block.version();
    }

    /**
     * Read a string value from a semantic block.
     */
    public String getSemanticString(String blockId, String key) {
        SemanticMemoryBlock block = semanticBlocks.get(blockId);
        return block != null ? block.getString(key) : null;
    }

    /**
     * Write a vector embedding to a semantic block and return the new version.
     */
    public long putSemanticVector(String blockId, String key, float[] embedding) {
        SemanticMemoryBlock block = getOrCreateSemanticBlock(blockId);
        block.putVector(key, embedding);
        log.debug("[SHM] Semantic vector: block={}, key={}, dims={}, newVersion={}",
                blockId, key, embedding.length, block.version());
        return block.version();
    }

    /**
     * Read a vector embedding from a semantic block.
     */
    public float[] getSemanticVector(String blockId, String key) {
        SemanticMemoryBlock block = semanticBlocks.get(blockId);
        return block != null ? block.getVector(key) : null;
    }

    /**
     * Write a context pointer to a semantic block and return the new version.
     */
    public long putContextPointer(String blockId, String key, String contextRef, String summary, long embeddingHash) {
        SemanticMemoryBlock block = getOrCreateSemanticBlock(blockId);
        block.putContextPointer(key, contextRef, summary, embeddingHash);
        log.debug("[SHM] Context pointer: block={}, key={}, ref={}, newVersion={}",
                blockId, key, contextRef, block.version());
        return block.version();
    }
}
