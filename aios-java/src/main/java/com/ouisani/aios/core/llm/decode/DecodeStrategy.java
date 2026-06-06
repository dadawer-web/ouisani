package com.ouisani.aios.core.llm.decode;

import com.ouisani.aios.core.llm.LlmProvider;

/**
 * Decode Strategy — a single link in the instruction decode chain.
 * <p>
 * Each strategy attempts to decode raw LLM output into a typed Java
 * object. If it succeeds, it returns the result. If it fails, it
 * returns {@code null} and the next strategy in the chain is tried.
 * <p>
 * <h3>Chain of Responsibility Pattern</h3>
 * The {@link InstructionDecoder} maintains an ordered list of strategies.
 * The first strategy that returns a non-null result wins. This allows
 * the kernel to try strict parsing first, then fall back to fuzzy
 * semantic parsing — all without the caller knowing which strategy
 * actually succeeded.
 * <p>
 * <h3>OS Analogy: Trap Handlers</h3>
 * In a real OS, when a CPU trap occurs (e.g., page fault), the kernel
 * tries multiple handlers in order: first the fast path (TLB refill),
 * then the slow path (page table walk), then the fallback (swap in).
 * Our decode chain follows the same pattern:
 * <ol>
 *   <li>{@link StrictDecodeStrategy} — fast path: strict JSON parsing (like TLB hit)</li>
 *   <li>{@link SemanticFuzzyDecodeStrategy} — slow path: regex + semantic matching (like page walk)</li>
 * </ol>
 *
 * @see StrictDecodeStrategy
 * @see SemanticFuzzyDecodeStrategy
 * @see InstructionDecoder
 */
public interface DecodeStrategy {

    /**
     * Attempt to decode the raw LLM output into the target type.
     *
     * @param llmOutput   the raw text output from the LLM
     * @param targetClass the expected Java type
     * @param llmProvider the LLM provider (for self-healing retries)
     * @param <T>         the target type
     * @return the decoded object, or {@code null} if this strategy cannot decode it
     */
    <T> T decode(String llmOutput, Class<T> targetClass, LlmProvider llmProvider);

    /**
     * Return the name of this strategy for logging.
     */
    String name();

    /**
     * Return the priority of this strategy (lower = higher priority).
     * <p>
     * The {@link InstructionDecoder} sorts strategies by priority
     * before executing them.
     */
    default int priority() {
        return 100;
    }
}
