package com.ouisani.aios.core.syscall.schema;

/**
 * LLM 命名空间载荷 — 所有 LLM syscall 的强类型契约。
 * <p>
 * OS 类比: POSIX 的 {@code struct aiocb}（异步 I/O 控制块）——
 * 每个字段都是显式的、类型化的、在内核边界经过校验的。
 *
 * @param prompt      发送给 LLM 的用户 prompt
 * @param temperature 采样温度（0.0 = 确定性，1.0 = 创造性）
 * @param maxTokens   最大生成 Token 数
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
