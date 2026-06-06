package com.ouisani.aios.core.syscall.schema;

/**
 * LLM namespace payload — strongly-typed contract for all LLM syscalls.
 * <p>
 * Analogous to POSIX's {@code struct aiocb} for async I/O control:
 * every field is explicit, typed, and validated at the kernel boundary.
 *
 * @param prompt      the user prompt to send to the LLM
 * @param temperature sampling temperature (0.0 = deterministic, 1.0 = creative)
 * @param maxTokens   maximum number of tokens to generate
 */
public record LlmPayload(
        String prompt,
        double temperature,
        int maxTokens
) implements SyscallPayload {

    public LlmPayload {
        if (prompt == null || prompt.isEmpty()) {
            throw new IllegalArgumentException("LLM payload requires a non-empty prompt");
        }
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("Temperature must be between 0.0 and 2.0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    /**
     * Convenience constructor with default temperature (0.7) and maxTokens (4096).
     */
    public LlmPayload(String prompt) {
        this(prompt, 0.7, 4096);
    }
}
