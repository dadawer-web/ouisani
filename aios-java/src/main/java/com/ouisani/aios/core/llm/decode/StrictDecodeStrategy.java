package com.ouisani.aios.core.llm.decode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strict Decode Strategy — the first line of defense in the decode chain.
 * <p>
 * This strategy attempts to parse the LLM output as well-formed JSON
 * using Jackson's {@link ObjectMapper}. It handles common LLM output
 * artifacts (markdown code blocks, surrounding text) by extracting
 * the JSON block before parsing.
 * <p>
 * <h3>OS Analogy: int 0x80 — the Standard System Call Interface</h3>
 * Just as a user-space program issues a system call via the standard
 * {@code int 0x80} interrupt with strictly defined register conventions,
 * the Strict Decode Strategy expects the LLM to follow the JSON schema
 * contract precisely. If the LLM complies, this is the fastest path.
 * <p>
 * <h3>Self-Healing</h3>
 * If the initial parse fails, this strategy can ask the LLM to fix
 * its own output (up to {@code MAX_RETRIES} times) before giving up.
 * This is analogous to a kernel retrying a failed I/O operation.
 *
 * @see DecodeStrategy
 * @see SemanticFuzzyDecodeStrategy
 */
public final class StrictDecodeStrategy implements DecodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(StrictDecodeStrategy.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 3;

    @Override
    public String name() {
        return "StrictJSON";
    }

    @Override
    public int priority() {
        return 10; // highest priority — try strict first
    }

    @Override
    public <T> T decode(String llmOutput, Class<T> targetClass, LlmProvider llmProvider) {
        // First attempt: direct parse
        T result = tryParse(llmOutput, targetClass);
        if (result != null) {
            log.debug("[Strict] First-pass decode successful: type={}", targetClass.getSimpleName());
            return result;
        }

        // Self-healing loop: ask the LLM to fix its own invalid JSON
        if (llmProvider == null) {
            log.debug("[Strict] No LlmProvider for self-healing, giving up");
            return null;
        }

        String currentOutput = llmOutput;
        String lastError = "";

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            lastError = getLastError();

            log.info("[Strict] Self-healing attempt {}/{} for type={}", attempt, MAX_RETRIES, targetClass.getSimpleName());
            System.out.printf("  \u001B[33m[Strict Decoder] Self-healing attempt %d/%d (error: %s)\u001B[0m%n",
                    attempt, MAX_RETRIES,
                    lastError.length() > 80 ? lastError.substring(0, 80) + "..." : lastError);

            String healPrompt = "Your previous output was invalid JSON. Error: " + lastError
                    + ". Please fix it and output ONLY valid JSON matching the schema for "
                    + targetClass.getSimpleName() + ". No markdown, no explanation.";

            try {
                currentOutput = llmProvider.think(healPrompt);
            } catch (Exception e) {
                log.warn("[Strict] LLM self-heal call failed: {}", e.getMessage());
                return null;
            }

            result = tryParse(currentOutput, targetClass);
            if (result != null) {
                log.info("[Strict] Self-healing succeeded on attempt {}", attempt);
                System.out.printf("  \u001B[32m[Strict Decoder] Self-healing successful on attempt %d!%n\u001B[0m", attempt);
                return result;
            }
        }

        log.debug("[Strict] All {} self-healing attempts exhausted for type={}", MAX_RETRIES, targetClass.getSimpleName());
        return null; // signal failure — let the next strategy in the chain try
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON Extraction & Parsing
    // ════════════════════════════════════════════════════════════════

    private static volatile String lastParseError = "";

    static String getLastError() {
        return lastParseError;
    }

    private static <T> T tryParse(String output, Class<T> targetClass) {
        try {
            String json = extractJson(output);
            return OBJECT_MAPPER.readValue(json, targetClass);
        } catch (JsonProcessingException e) {
            lastParseError = e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getMessage();
            log.debug("[Strict] Parse failed: {}", lastParseError);
            return null;
        }
    }

    /**
     * Extract JSON from LLM output that may be wrapped in markdown
     * code blocks or surrounded by natural language text.
     */
    static String extractJson(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }

        String trimmed = output.trim();

        // Try ```json ... ``` code block
        int jsonBlockStart = trimmed.indexOf("```json");
        if (jsonBlockStart != -1) {
            int contentStart = trimmed.indexOf('\n', jsonBlockStart) + 1;
            int contentEnd = trimmed.indexOf("```", contentStart);
            if (contentEnd != -1) {
                return trimmed.substring(contentStart, contentEnd).trim();
            }
        }

        // Try ``` ... ``` code block
        int codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart != -1) {
            int contentStart = trimmed.indexOf('\n', codeBlockStart) + 1;
            int contentEnd = trimmed.indexOf("```", contentStart);
            if (contentEnd != -1) {
                return trimmed.substring(contentStart, contentEnd).trim();
            }
        }

        // Try { ... } or [ ... ] bracket matching
        int objStart = trimmed.indexOf('{');
        int arrStart = trimmed.indexOf('[');
        if (objStart != -1 || arrStart != -1) {
            int start;
            if (objStart != -1 && arrStart != -1) {
                start = Math.min(objStart, arrStart);
            } else {
                start = objStart != -1 ? objStart : arrStart;
            }
            char openChar = trimmed.charAt(start);
            char closeChar = openChar == '{' ? '}' : ']';
            int depth = 0;
            for (int i = start; i < trimmed.length(); i++) {
                if (trimmed.charAt(i) == openChar) depth++;
                if (trimmed.charAt(i) == closeChar) depth--;
                if (depth == 0) {
                    return trimmed.substring(start, i + 1);
                }
            }
            return trimmed.substring(start);
        }

        return trimmed;
    }
}
