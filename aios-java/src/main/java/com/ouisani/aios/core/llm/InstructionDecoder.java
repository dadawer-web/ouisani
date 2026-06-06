package com.ouisani.aios.core.llm;

import com.ouisani.aios.core.llm.decode.DecodeStrategy;
import com.ouisani.aios.core.llm.decode.SemanticFuzzyDecodeStrategy;
import com.ouisani.aios.core.llm.decode.StrictDecodeStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Instruction Decoder — the AIOS kernel's instruction fetch unit.
 * <p>
 * Translates raw LLM text output into typed Java objects using a
 * <b>Dual-Track Decoding Pipeline</b> (双轨制解码管线):
 * <p>
 * <h3>Track 1: Strict Decoder (严格解码器)</h3>
 * The fast path — attempts standard JSON parsing via Jackson.
 * Handles markdown code blocks and embedded JSON extraction.
 * If the LLM follows the schema contract, this is the only track needed.
 * <p>
 * <h3>Track 2: Semantic Fuzzy Decoder (语义模糊解码器)</h3>
 * The resilient fallback — when strict parsing fails, this track
 * uses regex deep cleaning, field name fuzzy matching, and fragment
 * assembly to extract the intent from malformed or informal LLM output.
 * <p>
 * <h3>Chain of Responsibility</h3>
 * The two tracks are implemented as a chain of {@link DecodeStrategy}
 * instances. Each strategy is tried in priority order until one succeeds.
 * This makes the decode pipeline extensible — new strategies can be
 * added without modifying existing code.
 * <p>
 * <h3>OS Analogy: Trap → Fault → Panic</h3>
 * <pre>
 *   Strict parse succeeds  →  TLB hit (fast path)
 *   Strict fails, Fuzzy succeeds  →  Page fault (slow but recovered)
 *   Both fail  →  Kernel panic (InstructionDecodeException)
 * </pre>
 * <p>
 * The key insight: a "flexible kernel" doesn't mean "no rules." It means
 * the kernel tries the fast strict path first, and only falls back to
 * the slow fuzzy path when necessary — just like a real OS handles
 * page faults without compromising memory protection.
 *
 * @see DecodeStrategy
 * @see StrictDecodeStrategy
 * @see SemanticFuzzyDecodeStrategy
 * @see InstructionDecodeException
 */
public class InstructionDecoder {

    private static final Logger log = LoggerFactory.getLogger(InstructionDecoder.class);

    /** The ordered chain of decode strategies. */
    private static final List<DecodeStrategy> strategyChain = new ArrayList<>();

    static {
        // Initialize the dual-track pipeline
        strategyChain.add(new StrictDecodeStrategy());
        strategyChain.add(new SemanticFuzzyDecodeStrategy());

        // Sort by priority (lower number = higher priority)
        strategyChain.sort(Comparator.comparingInt(DecodeStrategy::priority));

        log.info("[InstructionDecoder] Dual-track decode pipeline initialized: {}",
                strategyChain.stream().map(DecodeStrategy::name).toList());
        System.out.println("  \u001B[36m[InstructionDecoder] Dual-track decode pipeline mounted: "
                + strategyChain.stream().map(DecodeStrategy::name).toList() + "\u001B[0m");
    }

    private InstructionDecoder() {}

    // ════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════

    /**
     * Decode LLM output into a typed Java object using the full
     * dual-track pipeline with self-healing.
     * <p>
     * Execution order:
     * <ol>
     *   <li>Strict Decoder (with self-healing retries)</li>
     *   <li>Semantic Fuzzy Decoder (3-stage fuzzy pipeline)</li>
     * </ol>
     * If all strategies fail, throws {@link InstructionDecodeException}.
     *
     * @param llmOutput   the raw text output from the LLM
     * @param targetClass the expected Java type
     * @param llmProvider the LLM provider (for self-healing retries)
     * @return the decoded object
     * @throws InstructionDecodeException if all strategies fail
     */
    public static <T> T decodeJson(String llmOutput, Class<T> targetClass, LlmProvider llmProvider) {
        log.info("[InstructionDecoder] Decoding: type={}, inputLen={}, strategies={}",
                targetClass.getSimpleName(), llmOutput != null ? llmOutput.length() : 0,
                strategyChain.size());

        for (DecodeStrategy strategy : strategyChain) {
            try {
                T result = strategy.decode(llmOutput, targetClass, llmProvider);
                if (result != null) {
                    log.info("[InstructionDecoder] Decode succeeded via strategy '{}': type={}",
                            strategy.name(), targetClass.getSimpleName());
                    return result;
                }
            } catch (Exception e) {
                log.warn("[InstructionDecoder] Strategy '{}' threw exception: {}",
                        strategy.name(), e.getMessage());
                // Continue to next strategy — don't let one strategy's exception
                // crash the entire pipeline
            }
        }

        // All strategies exhausted — kernel panic
        String fatalMsg = "All decode strategies failed for type " + targetClass.getSimpleName()
                + ". Tried: " + strategyChain.stream().map(DecodeStrategy::name).toList();
        log.error("[InstructionDecoder] FATAL: {}", fatalMsg);
        System.err.printf("  \u001B[31m[InstructionDecoder] FATAL: %s\u001B[0m%n", fatalMsg);
        throw new InstructionDecodeException(fatalMsg, strategyChain.size());
    }

    /**
     * Decode without self-healing — tries each strategy once.
     * <p>
     * Useful when the caller doesn't have an LlmProvider available
     * for self-healing retries.
     *
     * @param llmOutput   the raw text output from the LLM
     * @param targetClass the expected Java type
     * @return the decoded object
     * @throws InstructionDecodeException if all strategies fail
     */
    public static <T> T decodeJson(String llmOutput, Class<T> targetClass) {
        return decodeJson(llmOutput, targetClass, null);
    }

    // ════════════════════════════════════════════════════════════════
    //  Pipeline Management
    // ════════════════════════════════════════════════════════════════

    /**
     * Register a custom decode strategy into the pipeline.
     * <p>
     * Strategies are sorted by priority after registration.
     *
     * @param strategy the strategy to add
     */
    public static void registerStrategy(DecodeStrategy strategy) {
        strategyChain.add(strategy);
        strategyChain.sort(Comparator.comparingInt(DecodeStrategy::priority));
        log.info("[InstructionDecoder] Strategy registered: name={}, priority={}, pipeline={}",
                strategy.name(), strategy.priority(),
                strategyChain.stream().map(DecodeStrategy::name).toList());
    }

    /**
     * Remove a strategy by name.
     *
     * @param name the strategy name to remove
     * @return true if a strategy was removed
     */
    public static boolean removeStrategy(String name) {
        boolean removed = strategyChain.removeIf(s -> s.name().equals(name));
        if (removed) {
            log.info("[InstructionDecoder] Strategy removed: name={}, pipeline={}",
                    name, strategyChain.stream().map(DecodeStrategy::name).toList());
        }
        return removed;
    }

    /**
     * Get the current pipeline configuration.
     */
    public static List<String> getPipelineNames() {
        return strategyChain.stream().map(DecodeStrategy::name).toList();
    }
}
