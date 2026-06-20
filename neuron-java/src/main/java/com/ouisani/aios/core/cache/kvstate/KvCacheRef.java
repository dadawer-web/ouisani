package com.ouisani.aios.core.cache.kvstate;

import java.io.Serializable;
import java.util.Objects;

/**
 * KV Cache 引用 — 指向外部推理引擎（如 vLLM + LMCache）中存储的 KV Cache 张量。
 * <p>
 * 借鉴 LMCache 的 MemoryObj 概念：AIOS 不直接存储 KV 张量数据，
 * 而是存储对它的引用（URI），由底层推理引擎负责实际的张量存取。
 * <p>
 * <h3>落地场景</h3>
 * 当母体 OmniMotherAgent 第一次读取并解析完庞大的项目源码树（AST）后，
 * 底层的推理引擎（如 vLLM + LMCache）会生成这部分文本的 KV Cache。
 * AIOS 获取此 Cache 的唯一 ID（如 {@code lmcache://global_ast_v1}），
 * 并存入 {@link KvCacheRef}。
 * <p>
 * 下游的 Python_Coder 需要这些上下文时，它不向大模型发送那几万字的代码，
 * 而是直接发送一条包含 {@code kvTensorUri} 的指令。
 * 大模型直接从 LMCache 内存中加载张量状态，瞬间开始输出代码。
 *
 * @see KvCacheRegistry
 * @see KvCacheVfsStore
 */
public record KvCacheRef(
        /** KV Cache 张量的唯一 URI（如 lmcache://global_ast_v1） */
        String kvTensorUri,
        /** 生成此 KV Cache 的模型标识（如 "qwen2.5-32b"） */
        String modelId,
        /** Token 范围起始 */
        int tokenStart,
        /** Token 范围结束 */
        int tokenEnd,
        /** 原始文本内容的 SHA-256 哈希（用于验证缓存有效性） */
        String contentHash,
        /** 创建时间戳 */
        long createdAt,
        /** 最后访问时间戳 */
        long lastAccessedAt,
        /** 引用计数（类似 LMCache 的 pin 机制，>0 表示被锁定不可驱逐） */
        int refCount
) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 紧凑构造器 — 校验必填字段。
     */
    public KvCacheRef {
        Objects.requireNonNull(kvTensorUri, "kvTensorUri cannot be null");
        Objects.requireNonNull(modelId, "modelId cannot be null");
        Objects.requireNonNull(contentHash, "contentHash cannot be null");
        if (kvTensorUri.isBlank()) {
            throw new IllegalArgumentException("kvTensorUri cannot be blank");
        }
        if (tokenStart < 0 || tokenEnd < tokenStart) {
            throw new IllegalArgumentException(
                    "Invalid token range: [" + tokenStart + ", " + tokenEnd + ")");
        }
        if (refCount < 0) {
            throw new IllegalArgumentException("refCount cannot be negative");
        }
    }

    /**
     * 工厂方法 — 创建新的 KV Cache 引用。
     *
     * @param kvTensorUri KV Cache 张量的唯一 URI
     * @param modelId     生成此 KV Cache 的模型标识
     * @param tokenStart  Token 范围起始
     * @param tokenEnd    Token 范围结束
     * @param contentHash 原始文本内容的 SHA-256 哈希
     * @return 新的 KvCacheRef 实例
     */
    public static KvCacheRef create(
            String kvTensorUri, String modelId,
            int tokenStart, int tokenEnd, String contentHash) {
        long now = System.currentTimeMillis();
        return new KvCacheRef(kvTensorUri, modelId, tokenStart, tokenEnd,
                contentHash, now, now, 0);
    }

    /**
     * 记录一次访问 — 返回更新了 lastAccessedAt 的新实例（不改变 refCount）。
     *
     * @return 新的 KvCacheRef 实例
     */
    public KvCacheRef recordAccess() {
        return new KvCacheRef(kvTensorUri, modelId, tokenStart, tokenEnd,
                contentHash, createdAt, System.currentTimeMillis(), refCount);
    }

    /**
     * 增加引用计数（pin）— 防止被驱逐。
     *
     * @return 新的 KvCacheRef 实例
     */
    public KvCacheRef pin() {
        return new KvCacheRef(kvTensorUri, modelId, tokenStart, tokenEnd,
                contentHash, createdAt, System.currentTimeMillis(), refCount + 1);
    }

    /**
     * 减少引用计数（unpin）— 允许被驱逐。
     *
     * @return 新的 KvCacheRef 实例
     */
    public KvCacheRef unpin() {
        int newCount = Math.max(0, refCount - 1);
        return new KvCacheRef(kvTensorUri, modelId, tokenStart, tokenEnd,
                contentHash, createdAt, System.currentTimeMillis(), newCount);
    }

    /**
     * Token 数量。
     *
     * @return tokenEnd - tokenStart
     */
    public int tokenCount() {
        return tokenEnd - tokenStart;
    }

    /**
     * 是否被锁定（引用计数 > 0）。
     *
     * @return true 如果被锁定，不可驱逐
     */
    public boolean isPinned() {
        return refCount > 0;
    }

    @Override
    public String toString() {
        return "KvCacheRef{" +
                "uri='" + kvTensorUri + '\'' +
                ", model='" + modelId + '\'' +
                ", tokens=[" + tokenStart + "," + tokenEnd + ")" +
                ", hash='" + contentHash.substring(0, Math.min(12, contentHash.length())) + "...'" +
                ", refCount=" + refCount +
                '}';
    }
}
