package com.ouisani.aios.core.llm.decode;

import com.ouisani.aios.core.llm.LlmProvider;

/**
 * 解码策略接口 — 指令解码链中的单个环节。
 * <p>
 * 每个策略尝试将 LLM 的原始输出解码为类型化的 Java 对象。
 * 成功则返回结果，失败则返回 {@code null}，由链中下一个策略继续尝试。
 *
 * <h3>责任链模式</h3>
 * {@link InstructionDecoder} 维护一个有序的策略列表，
 * 第一个返回非空结果的策略胜出。这使得内核可以先尝试严格解析，
 * 再回退到模糊语义解析 — 调用方无需知道哪个策略最终成功。
 *
 * <h3>OS 类比：Trap 处理器</h3>
 * 真实 OS 中，CPU Trap（如 Page Fault）发生时，内核按顺序尝试多个处理器：
 * 先快速路径（TLB 重填），再慢速路径（页表遍历），最后回退（换入）。
 * 我们的解码链遵循相同模式：
 * <ol>
 *   <li>{@link StrictDecodeStrategy} — 快速路径：严格 JSON 解析（类比 TLB 命中）</li>
 *   <li>{@link SemanticFuzzyDecodeStrategy} — 慢速路径：正则 + 语义匹配（类比 Page Walk）</li>
 * </ol>
 *
 * @see StrictDecodeStrategy
 * @see SemanticFuzzyDecodeStrategy
 * @see InstructionDecoder
 */
public interface DecodeStrategy {

    /**
     * 尝试将 LLM 原始输出解码为目标类型。
     *
     * @param llmOutput   LLM 的原始文本输出
     * @param targetClass 期望的 Java 类型
     * @param llmProvider LLM 提供者（用于自愈重试）
     * @param <T>         目标类型
     * @return 解码后的对象，如果此策略无法解码则返回 {@code null}
     */
    <T> T decode(String llmOutput, Class<T> targetClass, LlmProvider llmProvider);

    /** 返回策略名称，用于日志记录 */
    String name();

    /**
     * 返回策略优先级（数值越小优先级越高）。
     * {@link InstructionDecoder} 在执行前按优先级排序策略。
     */
    default int priority() {
        return 100;
    }
}
