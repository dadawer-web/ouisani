package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.vfs.VectorNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic Context Injector — transparently augments LLM prompts
 * with relevant background knowledge retrieved from Vector Memory.
 * <p>
 * Inspired by cutting-edge AIOS designs where the kernel automatically
 * injects long-term memory into every Agent-LLM interaction, ensuring
 * that Agents always have access to the most relevant contextual knowledge
 * without explicit retrieval calls.
 * <p>
 * The injection target is the global {@link VectorNode} mounted at
 * {@code /dev/vec_mem}. When an Agent issues an {@code llm.think} syscall,
 * the ContextInjector intercepts the prompt, queries the vector store for
 * top-3 similar entries, and prepends a formatted memory block.
 */
public final class ContextInjector {

    private static final Logger log = LoggerFactory.getLogger(ContextInjector.class);

    private static final String VEC_MEM_PATH = "/dev/vec_mem";
    private static final int TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    // Pattern to extract text and similarity from VectorNode.search() JSON output
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "\"similarity\":([0-9.]+),\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final class Holder {
        static final ContextInjector INSTANCE = new ContextInjector();
    }

    public static ContextInjector getInstance() {
        return Holder.INSTANCE;
    }

    private ContextInjector() {}

    /**
     * Augment a prompt with relevant background knowledge from Vector Memory.
     * <p>
     * If high-similarity entries are found, they are formatted as:
     * <pre>
     * [System Augmented Memory:
     *   1. (0.87) knowledge text here
     *   2. (0.72) another relevant fact
     * ]
     * </pre>
     * and prepended to the original prompt.
     *
     * @param originalPrompt the raw prompt from the Agent
     * @return the augmented prompt (or the original if no relevant memory found)
     */
    public String augmentPrompt(String originalPrompt) {
        if (originalPrompt == null || originalPrompt.isBlank()) {
            return originalPrompt;
        }

        VectorNode vecMem = resolveVectorMemory();
        if (vecMem == null) {
            log.debug("[Context Injector] /dev/vec_mem not available, skipping augmentation");
            return originalPrompt;
        }

        if (vecMem.recordCount() == 0) {
            log.debug("[Context Injector] Vector Memory is empty, skipping augmentation");
            return originalPrompt;
        }

        try {
            String searchResult = vecMem.search(originalPrompt, TOP_K);
            String augmentedMemory = extractRelevantMemory(searchResult);

            if (augmentedMemory == null || augmentedMemory.isEmpty()) {
                log.debug("[Context Injector] No high-similarity results found, prompt unchanged");
                return originalPrompt;
            }

            String augmented = "[System Augmented Memory:\n" + augmentedMemory + "]\n\n" + originalPrompt;

            log.info("[Context Injector] Transparently augmented prompt with Vector Memory!");
            System.out.printf("  🧠 [Context Injector] Transparently augmented prompt with Vector Memory!%n");

            return augmented;
        } catch (Exception e) {
            log.warn("[Context Injector] Augmentation failed, using original prompt: {}", e.getMessage());
            return originalPrompt;
        }
    }

    private VectorNode resolveVectorMemory() {
        var nodeOpt = VfsManager.instance().resolve(VEC_MEM_PATH);
        if (nodeOpt.isEmpty()) {
            return null;
        }
        if (nodeOpt.get() instanceof VectorNode vecNode) {
            return vecNode;
        }
        return null;
    }

    /**
     * Parse the JSON search result from VectorNode.search() and extract
     * entries above the similarity threshold.
     */
    private String extractRelevantMemory(String searchResult) {
        if (searchResult == null || searchResult.equals("[]")) {
            return null;
        }

        Matcher matcher = RESULT_PATTERN.matcher(searchResult);
        StringBuilder sb = new StringBuilder();
        int rank = 0;

        while (matcher.find()) {
            double similarity = Double.parseDouble(matcher.group(1));
            String text = unescapeJson(matcher.group(2));

            if (similarity >= SIMILARITY_THRESHOLD) {
                rank++;
                sb.append("  ").append(rank).append(". (")
                  .append(String.format("%.2f", similarity)).append(") ")
                  .append(text).append("\n");
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    private String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
