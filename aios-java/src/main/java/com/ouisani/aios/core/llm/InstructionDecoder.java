package com.ouisani.aios.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstructionDecoder {

    private static final Logger log = LoggerFactory.getLogger(InstructionDecoder.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 3;

    private InstructionDecoder() {}

    /**
     * 将 LLM 输出解码为强类型 Java 对象。
     * 如果 JSON 解析失败，自动向大模型发起自愈请求，最多重试 3 次。
     *
     * @param llmOutput   大模型的原始输出文本
     * @param targetClass 目标 Java 类型
     * @param llmProvider 用于自愈重试的 LLM 提供者
     * @return 解析后的 Java 对象
     * @throws InstructionDecodeException 超过最大重试次数仍无法解析
     */
    public static <T> T decodeJson(String llmOutput, Class<T> targetClass, LlmProvider llmProvider) {
        // 第一次尝试直接解析
        T result = tryParse(llmOutput, targetClass);
        if (result != null) {
            log.debug("[InstructionDecoder] First-pass decode successful: type={}", targetClass.getSimpleName());
            return result;
        }

        // 进入自愈循环
        String currentOutput = llmOutput;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            String errorMsg = lastParseError;

            System.out.printf("  🔄 [InstructionDecoder] Self-healing attempt %d/%d (error: %s)%n",
                    attempt, MAX_RETRIES, errorMsg.length() > 80 ? errorMsg.substring(0, 80) + "..." : errorMsg);
            log.warn("[InstructionDecoder] Parse failed, self-healing attempt {}/{}: {}", attempt, MAX_RETRIES, errorMsg);

            String healPrompt = "Your previous output was invalid JSON. Error: " + errorMsg
                    + ". Please fix it and output ONLY valid JSON matching the schema for " + targetClass.getSimpleName() + ".";

            currentOutput = llmProvider.think(healPrompt);
            log.info("[InstructionDecoder] LLM self-heal response (attempt {}): {} chars", attempt, currentOutput.length());

            result = tryParse(currentOutput, targetClass);
            if (result != null) {
                System.out.printf("  ✅ [InstructionDecoder] Self-healing successful on attempt %d!%n", attempt);
                log.info("[InstructionDecoder] Self-healing succeeded on attempt {}", attempt);
                return result;
            }
        }

        // 全部失败
        String fatalMsg = "Instruction decode failed after " + MAX_RETRIES + " self-healing attempts. Last error: " + lastParseError;
        System.err.printf("  💀 [InstructionDecoder] FATAL: %s%n", fatalMsg);
        log.error("[InstructionDecoder] {}", fatalMsg);
        throw new InstructionDecodeException(fatalMsg, MAX_RETRIES + 1);
    }

    /**
     * 无自愈版本：仅尝试解析，失败直接抛异常
     */
    public static <T> T decodeJson(String llmOutput, Class<T> targetClass) {
        T result = tryParse(llmOutput, targetClass);
        if (result != null) {
            return result;
        }
        throw new InstructionDecodeException(
                "JSON parse failed (no self-healing): " + lastParseError, 1);
    }

    private static String lastParseError = "";

    private static <T> T tryParse(String output, Class<T> targetClass) {
        try {
            // 尝试提取 JSON 块（LLM 可能在 JSON 前后附加了 markdown 代码块标记）
            String json = extractJson(output);
            return OBJECT_MAPPER.readValue(json, targetClass);
        } catch (JsonProcessingException e) {
            lastParseError = e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getMessage();
            log.debug("[InstructionDecoder] Parse failed: {}", lastParseError);
            return null;
        }
    }

    /**
     * 从 LLM 输出中提取 JSON 内容。
     * 支持 ```json ... ``` 包裹的 markdown 代码块。
     */
    private static String extractJson(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }

        // 尝试提取 ```json ... ``` 代码块
        String trimmed = output.trim();
        int jsonBlockStart = trimmed.indexOf("```json");
        if (jsonBlockStart != -1) {
            int contentStart = trimmed.indexOf('\n', jsonBlockStart) + 1;
            int contentEnd = trimmed.indexOf("```", contentStart);
            if (contentEnd != -1) {
                return trimmed.substring(contentStart, contentEnd).trim();
            }
        }

        // 尝试提取 ``` ... ``` 代码块
        int codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart != -1) {
            int contentStart = trimmed.indexOf('\n', codeBlockStart) + 1;
            int contentEnd = trimmed.indexOf("```", contentStart);
            if (contentEnd != -1) {
                return trimmed.substring(contentStart, contentEnd).trim();
            }
        }

        // 尝试提取 { ... } 或 [ ... ] 范围
        int objStart = trimmed.indexOf('{');
        int arrStart = trimmed.indexOf('[');
        if (objStart != -1 || arrStart != -1) {
            int start = -1;
            if (objStart != -1 && arrStart != -1) {
                start = Math.min(objStart, arrStart);
            } else {
                start = objStart != -1 ? objStart : arrStart;
            }
            // 找到匹配的结束括号
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
            // 深度不匹配，返回从 start 开始的子串
            return trimmed.substring(start);
        }

        return trimmed;
    }
}
