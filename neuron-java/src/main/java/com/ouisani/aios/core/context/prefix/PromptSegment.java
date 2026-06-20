package com.ouisani.aios.core.context.prefix;

import java.util.Objects;

/**
 * Prompt 段落 — 前缀复用优化的有序段落。
 * <p>
 * 每个 Prompt 由多个 {@link PromptSegment} 按固定的 {@link SegmentType} 顺序拼接而成。
 * 每个段落携带内容哈希，用于前缀匹配和缓存命中检测。
 * <p>
 * <h3>设计原理</h3>
 * 借鉴 LMCache 的前缀匹配机制：
 * {@code batched_contains} 按顺序检查 keys，返回连续命中的 chunk 数。
 * <p>
 * AIOS 的 Prompt 段落设计确保：
 * <ul>
 *   <li>前面的段落（STATIC_SYSTEM, SHARED_CONTEXT）保持绝对稳定</li>
 *   <li>后面的段落（TOOL_LIST, DYNAMIC_TASK）可以动态变化</li>
 *   <li>前缀哈希可以用于快速判断缓存是否仍然有效</li>
 * </ul>
 *
 * @see SegmentType
 * @see PrefixOptimizedPromptBuilder
 */
public record PromptSegment(
        /** 段落类型 */
        SegmentType type,
        /** 段落内容 */
        String content,
        /** 内容的 SHA-256 哈希（由 {@link PrefixOptimizedPromptBuilder} 自动计算） */
        String contentHash
) {

    public PromptSegment {
        Objects.requireNonNull(type, "SegmentType cannot be null");
        Objects.requireNonNull(content, "content cannot be null");
        Objects.requireNonNull(contentHash, "contentHash cannot be null");
    }

    /**
     * 工厂方法 — 创建段落并自动计算哈希。
     *
     * @param type    段落类型
     * @param content 段落内容
     * @return 新的 PromptSegment 实例
     */
    public static PromptSegment of(SegmentType type, String content) {
        return new PromptSegment(type, content, PrefixOptimizedPromptBuilder.hashContent(content));
    }

    /**
     * 段落字符数。
     *
     * @return 内容长度
     */
    public int length() {
        return content.length();
    }

    /**
     * 是否为静态段落（STATIC_SYSTEM 或 SHARED_CONTEXT）。
     * <p>
     * 静态段落的内容在多次调用中应保持不变，是前缀缓存命中的关键。
     *
     * @return true 如果是静态段落
     */
    public boolean isStatic() {
        return type == SegmentType.STATIC_SYSTEM || type == SegmentType.SHARED_CONTEXT;
    }

    @Override
    public String toString() {
        return "PromptSegment{" +
                "type=" + type +
                ", len=" + content.length() +
                ", hash='" + contentHash.substring(0, Math.min(12, contentHash.length())) + "...'" +
                '}';
    }
}
