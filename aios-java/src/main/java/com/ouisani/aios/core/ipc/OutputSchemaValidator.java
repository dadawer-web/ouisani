package com.ouisani.aios.core.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 强类型输出安检站 (The ABI Firewall)
 * <p>
 * 负责剥离 LLM 的废话，提取 JSON，并做结构化校验。
 * <p>
 * OS 类比：系统调用的 ABI 校验——用户态程序传入的参数必须符合
 * 内核期望的类型和格式，否则直接 EFAULT 拒绝进入。
 * <p>
 * 如果 LLM 输出无法解析为 JSON，抛出的 IllegalArgumentException
 * 会被 11 层自愈引擎的 JsonParseErrorRecovery 精准捕获，
 * 将错误信息重新注入 Prompt 让 LLM 修正输出格式。
 * <p>
 * 两级校验：
 * 1. JSON 语法提取 — 从 LLM 输出中提取有效 JSON
 * 2. JSON Schema 校验 — 验证 JSON 结构是否符合预期 Schema
 */
public class OutputSchemaValidator {
    private static final Logger log = LoggerFactory.getLogger(OutputSchemaValidator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // 匹配 markdown 的 json 代码块
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```(?:json)?\\s*\\n([\\s\\S]*?)\\n```", Pattern.CASE_INSENSITIVE);

    /**
     * 核心安检方法：传入 LLM 原始文本，提取并校验为 JSON。
     * 如果提取失败，将抛出明确的异常，该异常将被 11 层自愈引擎捕获！
     *
     * @param rawLlmOutput LLM 的原始输出文本
     * @return 解析后的 JsonNode
     * @throws IllegalArgumentException 当输出无法解析为 JSON 时
     */
    public static JsonNode enforceJsonStructure(String rawLlmOutput) {
        String cleanText = rawLlmOutput.trim();

        // 1. 如果大模型很听话，直接输出了干净的 JSON
        if ((cleanText.startsWith("{") && cleanText.endsWith("}")) ||
            (cleanText.startsWith("[") && cleanText.endsWith("]"))) {
            try {
                return mapper.readTree(cleanText);
            } catch (Exception e) {
                log.warn("[ABI Firewall] Direct parse failed, falling back to heuristic extraction.");
            }
        }

        // 2. 如果大模型加了 Markdown 包装（最常见的情况）
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(cleanText);
        if (matcher.find()) {
            String extractedJson = matcher.group(1);
            try {
                JsonNode result = mapper.readTree(extractedJson);
                log.info("[ABI Firewall] Successfully extracted and purified JSON from Markdown wrapper.");
                return result;
            } catch (Exception e) {
                throw new RuntimeException("Malformed JSON inside markdown block: " + e.getMessage());
            }
        }

        // 3. 尝试从文本中提取第一个 JSON 对象/数组（宽松模式）
        JsonNode extracted = extractFirstJson(cleanText);
        if (extracted != null) {
            log.info("[ABI Firewall] Extracted embedded JSON from mixed text output.");
            return extracted;
        }

        // 4. 如果大模型完全失控，输出了无法解析的文本
        log.error("[ABI Firewall] LLM Hallucination detected! Output does not contain parsable JSON.");
        log.debug("Raw Bad Output:\n{}", rawLlmOutput);

        // 这个特定的异常信息，将被你系统中的 JsonParseErrorRecovery 钩子精准捕获！
        throw new IllegalArgumentException(
            "ABI_VIOLATION_JSON_EXPECTED: You must output strictly valid JSON format. " +
            "No introductory text, no conversational replies. Do not enclose the JSON in backticks unless properly formatted."
        );
    }

    /**
     * JSON Schema 校验：验证 JSON 数据是否符合给定的 Schema。
     * <p>
     * 实现轻量级 Schema 校验，支持以下关键字：
     * - type: 类型校验 (string, number, integer, boolean, array, object, null)
     * - required: 必填字段
     * - properties: 对象属性校验（递归）
     * - items: 数组元素校验（递归）
     * - enum: 枚举值校验
     * - minLength/maxLength: 字符串长度
     * - minimum/maximum: 数值范围
     * <p>
     * 对标 Dify 的 DifyOutputLayer，但使用自研轻量实现避免引入外部依赖。
     *
     * @param data   待校验的 JSON 数据
     * @param schema JSON Schema 定义
     * @return 校验结果（空列表表示通过）
     */
    public static List<String> validateSchema(JsonNode data, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        validateNode(data, schema, "$", errors);
        return errors;
    }

    /**
     * 提取 JSON 并校验 Schema，一步到位。
     * 如果 Schema 校验失败，抛出包含详细错误信息的异常。
     *
     * @param rawLlmOutput LLM 原始输出
     * @param schema       期望的 JSON Schema
     * @return 校验通过的 JsonNode
     * @throws IllegalArgumentException Schema 校验失败时
     */
    public static JsonNode enforceJsonWithSchema(String rawLlmOutput, JsonNode schema) {
        JsonNode data = enforceJsonStructure(rawLlmOutput);
        List<String> errors = validateSchema(data, schema);
        if (!errors.isEmpty()) {
            String errorSummary = String.join("; ", errors);
            log.error("[ABI Firewall] Schema validation failed: {}", errorSummary);
            throw new IllegalArgumentException(
                "ABI_VIOLATION_SCHEMA_MISMATCH: " + errorSummary +
                ". You must output JSON strictly matching the required schema."
            );
        }
        return data;
    }

    /**
     * 宽松模式：尝试提取 JSON，失败时返回 null 而非抛异常。
     * 适用于不强制要求 JSON 输出的场景。
     */
    public static JsonNode tryExtractJson(String rawLlmOutput) {
        try {
            return enforceJsonStructure(rawLlmOutput);
        } catch (Exception e) {
            log.debug("[ABI Firewall] Lenient mode: JSON extraction failed, returning null.");
            return null;
        }
    }

    // ── 内部方法 ──

    /**
     * 从混合文本中提取第一个 JSON 对象或数组。
     * 处理 LLM 在 JSON 前后添加对话性文字的情况。
     */
    private static JsonNode extractFirstJson(String text) {
        // 查找第一个 { 或 [
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');

        int start;
        char openChar, closeChar;

        if (objStart < 0 && arrStart < 0) return null;
        if (objStart < 0) { start = arrStart; openChar = '['; closeChar = ']'; }
        else if (arrStart < 0) { start = objStart; openChar = '{'; closeChar = '}'; }
        else { start = Math.min(objStart, arrStart); openChar = (start == objStart) ? '{' : '['; closeChar = (start == objStart) ? '}' : ']'; }

        // 从 start 位置开始，找到匹配的闭合括号
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == openChar) depth++;
            else if (c == closeChar) {
                depth--;
                if (depth == 0) {
                    String candidate = text.substring(start, i + 1);
                    try {
                        return mapper.readTree(candidate);
                    } catch (Exception e) {
                        // 解析失败，继续搜索
                    }
                }
            }
        }
        return null;
    }

    /**
     * 递归校验 JSON 节点是否符合 Schema。
     */
    private static void validateNode(JsonNode data, JsonNode schema, String path, List<String> errors) {
        if (schema == null || !schema.isObject()) return;

        // type 校验
        if (schema.has("type")) {
            String expectedType = schema.get("type").asText();
            if (!checkType(data, expectedType)) {
                errors.add(String.format("%s: expected type '%s' but got '%s'", path, expectedType, getActualType(data)));
                return; // 类型不匹配，后续校验无意义
            }
        }

        // enum 校验
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode enumVal : schema.get("enum")) {
                if (data.equals(enumVal)) { found = true; break; }
            }
            if (!found) {
                errors.add(String.format("%s: value not in enum %s", path, schema.get("enum")));
            }
        }

        // 对象类型校验
        if (data.isObject()) {
            // required 校验
            if (schema.has("required")) {
                for (JsonNode reqField : schema.get("required")) {
                    String fieldName = reqField.asText();
                    if (!data.has(fieldName)) {
                        errors.add(String.format("%s: missing required field '%s'", path, fieldName));
                    }
                }
            }

            // properties 校验（递归）
            if (schema.has("properties")) {
                Iterator<String> fieldNames = schema.get("properties").fieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    if (data.has(fieldName)) {
                        validateNode(data.get(fieldName), schema.get("properties").get(fieldName),
                                path + "." + fieldName, errors);
                    }
                }
            }
        }

        // 数组类型校验
        if (data.isArray() && schema.has("items")) {
            for (int i = 0; i < data.size(); i++) {
                validateNode(data.get(i), schema.get("items"), path + "[" + i + "]", errors);
            }
        }

        // 字符串长度校验
        if (data.isTextual()) {
            int len = data.asText().length();
            if (schema.has("minLength") && len < schema.get("minLength").asInt()) {
                errors.add(String.format("%s: string length %d < minLength %d", path, len, schema.get("minLength").asInt()));
            }
            if (schema.has("maxLength") && len > schema.get("maxLength").asInt()) {
                errors.add(String.format("%s: string length %d > maxLength %d", path, len, schema.get("maxLength").asInt()));
            }
        }

        // 数值范围校验
        if (data.isNumber()) {
            double val = data.asDouble();
            if (schema.has("minimum") && val < schema.get("minimum").asDouble()) {
                errors.add(String.format("%s: value %f < minimum %f", path, val, schema.get("minimum").asDouble()));
            }
            if (schema.has("maximum") && val > schema.get("maximum").asDouble()) {
                errors.add(String.format("%s: value %f > maximum %f", path, val, schema.get("maximum").asDouble()));
            }
            // integer 校验
            if ("integer".equals(schema.path("type").asText()) && !data.canConvertToInt()) {
                errors.add(String.format("%s: expected integer but got non-integer number", path));
            }
        }
    }

    private static boolean checkType(JsonNode data, String expectedType) {
        return switch (expectedType) {
            case "string"  -> data.isTextual();
            case "number"  -> data.isNumber();
            case "integer" -> data.isNumber() && data.canConvertToInt();
            case "boolean" -> data.isBoolean();
            case "array"   -> data.isArray();
            case "object"  -> data.isObject();
            case "null"    -> data.isNull();
            default -> true; // 未知类型，放行
        };
    }

    private static String getActualType(JsonNode data) {
        if (data.isTextual()) return "string";
        if (data.isBoolean()) return "boolean";
        if (data.isInt() || data.isLong()) return "integer";
        if (data.isNumber()) return "number";
        if (data.isArray()) return "array";
        if (data.isObject()) return "object";
        if (data.isNull()) return "null";
        return "unknown";
    }
}
