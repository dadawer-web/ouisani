package com.ouisani.aios.core.llm.decode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 严格解码策略 — 解码链的第一道防线。
 * <p>
 * 使用 Jackson 的 {@link ObjectMapper} 将 LLM 输出解析为标准 JSON。
 * 处理常见的 LLM 输出伪影（Markdown 代码块、周围文本），在解析前先提取 JSON 块。
 *
 * <h3>OS 类比：int 0x80 — 标准系统调用接口</h3>
 * 正如用户态程序通过标准 {@code int 0x80} 中断和严格的寄存器约定发起系统调用，
 * 严格解码策略期望 LLM 精确遵循 JSON Schema 约定。如果 LLM 遵守约定，这是最快的路径。
 *
 * <h3>自愈机制</h3>
 * 如果首次解析失败，此策略可要求 LLM 修复自己的输出（最多 {@code MAX_RETRIES} 次）。
 * 类比内核重试失败的 I/O 操作。
 *
 * @see DecodeStrategy
 * @see SemanticFuzzyDecodeStrategy
 */
public final class StrictDecodeStrategy implements DecodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(StrictDecodeStrategy.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 自愈最大重试次数 */
    private static final int MAX_RETRIES = 3;

    @Override
    public String name() {
        return "StrictJSON";
    }

    @Override
    public int priority() {
        return 10; // 最高优先级 — 优先尝试严格解析
    }

    /**
     * 执行严格解码：先直接解析，失败后进入自愈循环。
     *
     * @param llmOutput   LLM 原始输出
     * @param targetClass 目标类型
     * @param llmProvider LLM 提供者（用于自愈重试），可为 null
     * @return 解码结果，失败返回 null
     */
    @Override
    public <T> T decode(String llmOutput, Class<T> targetClass, LlmProvider llmProvider) {
        // 首次尝试：直接解析
        T result = tryParse(llmOutput, targetClass);
        if (result != null) {
            log.debug("[Strict] First-pass decode successful: type={}", targetClass.getSimpleName());
            return result;
        }

        // 自愈循环：要求 LLM 修复自己的无效 JSON
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
        return null; // 信号失败 — 让链中下一个策略尝试
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON Extraction & Parsing
    // ════════════════════════════════════════════════════════════════

    /** 最近一次解析错误信息，用于自愈提示 */
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
     * 从 LLM 输出中提取 JSON 块。
     * 处理 Markdown 代码块包裹和自然语言文本包围的情况。
     * 提取顺序：```json 块 → ``` 块 → 括号匹配 → 原文返回
     */
    static String extractJson(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }

        String trimmed = output.trim();

        // 尝试 ```json ... ``` 代码块
        int jsonBlockStart = trimmed.indexOf("```json");
        if (jsonBlockStart != -1) {
            int contentStart = trimmed.indexOf('\n', jsonBlockStart) + 1;
            int contentEnd = trimmed.indexOf("```", contentStart);
            if (contentEnd != -1) {
                return trimmed.substring(contentStart, contentEnd).trim();
            }
        }

        // 尝试 ``` ... ``` 代码块
        int codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart != -1) {
            int contentStart = trimmed.indexOf('\n', codeBlockStart) + 1;
            int contentEnd = trimmed.indexOf("```", contentStart);
            if (contentEnd != -1) {
                return trimmed.substring(contentStart, contentEnd).trim();
            }
        }

        // 尝试 { ... } 或 [ ... ] 括号匹配
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
