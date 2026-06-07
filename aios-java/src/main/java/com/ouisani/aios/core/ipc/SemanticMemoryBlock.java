package com.ouisani.aios.core.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 语义内存块 — 超越简单键值对的结构化共享内存段，
 * 支持高维向量和上下文指针，用于跨 Agent 的"潜意识"通信。
 * <p>
 * 传统 OS 的共享内存只是原始字节。AIOS 将其提升为<b>语义</b>内存块，
 * 可以存储：
 * <ul>
 *   <li><b>字符串数据</b> — 传统键值对（类似 POSIX shm）</li>
 *   <li><b>上下文指针</b> — 指向 LLM 对话上下文的引用，
 *       使一个 Agent 可以将另一个 Agent 指向相关上下文，而无需复制整个对话</li>
 *   <li><b>向量嵌入</b> — 高维浮点数组，表示语义含义，
 *       实现 Agent 间的"潜意识"知识传递，无需显式文本交换</li>
 * </ul>
 *
 * <h3>OS 类比: mmap() 与神经记忆</h3>
 * 就像 {@code mmap()} 将文件映射到进程地址空间实现零拷贝共享，
 * {@code SemanticMemoryBlock} 将神经表示映射到 Agent 的"认知地址空间"，
 * 实现零拷贝的潜意识共享。
 *
 * <h3>版本控制</h3>
 * 每次写入递增版本计数器。读者可以通过比较版本号检测变更，
 * 而无需读取整个块 — 这是 SIG_CONTEXT_UPDATE 中断机制的基础。
 *
 * @see SharedMemoryManager
 * @see SignalType#SIG_CONTEXT_UPDATE
 */
public final class SemanticMemoryBlock {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryBlock.class);

    // ── 块标识 ──

    private final String blockId;
    private final long createdAt;
    private volatile long lastModifiedAt;

    // ── 版本计数器（用于变更检测） ──

    private final AtomicLong version = new AtomicLong(0);

    // ── 数据存储 ──

    /** 传统键值字符串数据（POSIX shm 风格） */
    private final ConcurrentHashMap<String, String> stringData = new ConcurrentHashMap<>();

    /** 上下文指针 — 指向 LLM 对话上下文的引用 */
    private final ConcurrentHashMap<String, ContextPointer> contextPointers = new ConcurrentHashMap<>();

    /** 向量嵌入 — 高维语义表示 */
    private final ConcurrentHashMap<String, float[]> vectorData = new ConcurrentHashMap<>();

    /** 元数据 — 块级标签（owner, project, status 等） */
    private final ConcurrentHashMap<String, String> metadata = new ConcurrentHashMap<>();

    // ── 访问控制 ──

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
    //  字符串数据（传统 SHM）
    // ════════════════════════════════════════════════════════════════

    /** 写入字符串键值对（类比 shm_write） */
    public void putString(String key, String value) {
        rwLock.writeLock().lock();
        try {
            stringData.put(key, value);
            touch();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 读取字符串值（类比 shm_read） */
    public String getString(String key) {
        rwLock.readLock().lock();
        try {
            return stringData.get(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** 批量写入多个字符串键值对 */
    public void putStrings(Map<String, String> entries) {
        rwLock.writeLock().lock();
        try {
            stringData.putAll(entries);
            touch();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 获取所有字符串键 */
    public Set<String> stringKeys() {
        return Collections.unmodifiableSet(stringData.keySet());
    }

    // ════════════════════════════════════════════════════════════════
    //  上下文指针（神经 mmap）
    // ════════════════════════════════════════════════════════════════

    /**
     * 写入上下文指针 — 指向 LLM 对话上下文的引用，供其他 Agent 解引用。
     * <p>
     * 类比 {@code mmap()}：PM Agent 不复制整个对话，而是写入指向其上下文的指针，
     * Coder Agent 可以按需解引用获取完整上下文。
     *
     * @param key           指针名称（如 "prd_context"）
     * @param contextRef    引用标识（如 VFS 路径或 UUID）
     * @param summary       上下文摘要（用于快速浏览）
     * @param embeddingHash 上下文嵌入的哈希值（用于相似性检查）
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

    /** 读取上下文指针 */
    public ContextPointer getContextPointer(String key) {
        rwLock.readLock().lock();
        try {
            return contextPointers.get(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** 获取所有上下文指针键 */
    public Set<String> contextPointerKeys() {
        return Collections.unmodifiableSet(contextPointers.keySet());
    }

    // ════════════════════════════════════════════════════════════════
    //  向量嵌入（潜意识传递）
    // ════════════════════════════════════════════════════════════════

    /**
     * 写入向量嵌入 — 表示语义含义的高维浮点数组。
     * <p>
     * 实现"潜意识"知识传递：PM Agent 将对产品需求的理解写入向量，
     * Coder Agent 读取此向量来对齐代码生成策略，而无需看到原始文本。
     * <p>
     * 类比海马体和新皮层在睡眠巩固期间共享压缩记忆痕迹的方式。
     *
     * @param key      向量名称（如 "prd_embedding"）
     * @param embedding 浮点数组（通常 768 或 1536 维）
     */
    public void putVector(String key, float[] embedding) {
        rwLock.writeLock().lock();
        try {
            vectorData.put(key, embedding.clone()); // 防御性拷贝
            touch();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 读取向量嵌入。
     *
     * @return 防御性拷贝的嵌入数组，若不存在则返回 null
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

    /** 获取所有向量键 */
    public Set<String> vectorKeys() {
        return Collections.unmodifiableSet(vectorData.keySet());
    }

    // ════════════════════════════════════════════════════════════════
    //  元数据
    // ════════════════════════════════════════════════════════════════

    public void setMetadata(String key, String value) { metadata.put(key, value); }
    public String getMetadata(String key) { return metadata.get(key); }
    public Map<String, String> allMetadata() { return Collections.unmodifiableMap(metadata); }

    // ════════════════════════════════════════════════════════════════
    //  版本与变更检测
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取当前版本号。每次写入递增。
     * <p>
     * Agent 可以通过比较版本号检测变更，而无需读取整个块 —
     * 这是 SIG_CONTEXT_UPDATE 中断机制的基础。
     */
    public long version() {
        return version.get();
    }

    /** 检查块是否在给定版本之后被更新过 */
    public boolean hasChangedSince(long lastSeenVersion) {
        return version.get() > lastSeenVersion;
    }

    // ════════════════════════════════════════════════════════════════
    //  诊断信息
    // ════════════════════════════════════════════════════════════════

    public String blockId() { return blockId; }
    public long createdAt() { return createdAt; }
    public long lastModifiedAt() { return lastModifiedAt; }
    public int ownerPid() { return ownerPid; }
    public void setOwnerPid(int pid) { this.ownerPid = pid; }
    public int stringDataSize() { return stringData.size(); }
    public int contextPointerCount() { return contextPointers.size(); }
    public int vectorCount() { return vectorData.size(); }

    /** 将块的字符串数据导出为 JSON 格式 */
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

    // ── 内部方法 ──

    /** 递增版本号并更新修改时间 */

    private void touch() {
        this.version.incrementAndGet();
        this.lastModifiedAt = System.currentTimeMillis();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ════════════════════════════════════════════════════════════════
    //  上下文指针记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 上下文指针 — 指向存储在其他位置（如 VFS 或向量记忆）的 LLM 对话上下文的轻量引用。
     * <p>
     * 不在 Agent 之间复制整个上下文，而是传递一个指针，
     * 接收 Agent 可以按需解引用。类比 C 语言的内存指针：
     * 地址复制很廉价，数据只在需要时才访问。
     *
     * @param contextRef    引用标识（VFS 路径、UUID 等）
     * @param summary       上下文摘要
     * @param embeddingHash 上下文嵌入的哈希值，用于快速比较
     * @param timestamp     指针创建时间
     */
    public record ContextPointer(
            String contextRef,
            String summary,
            long embeddingHash,
            long timestamp
    ) {}
}
