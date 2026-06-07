package com.ouisani.aios.core.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
            System.out.println("  \u001B[36m[SHM] Semantic block '" + id + "' allocated (vectors + context pointers enabled)\u001B[0m");
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
}
