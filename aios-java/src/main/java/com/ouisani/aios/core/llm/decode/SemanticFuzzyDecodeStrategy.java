package com.ouisani.aios.core.llm.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic Fuzzy Decode Strategy — the resilient fallback when strict
 * JSON parsing fails.
 * <p>
 * When the LLM produces output that doesn't conform to the expected JSON
 * schema (e.g., wrapping the JSON in natural language, using informal
 * field names, or producing partially valid structures), this strategy
 * uses a multi-stage fuzzy parsing pipeline to extract the intent:
 * <p>
 * <h3>Stage 1: Regex Deep Clean</h3>
 * Strip markdown artifacts, extract embedded JSON fragments, and
 * normalize common LLM output patterns (e.g., "好的，我现在调用
 * sys_write 写入数据：{...}" → extract the {...} part).
 * <p>
 * <h3>Stage 2: Field Name Fuzzy Match</h3>
 * If the JSON structure is valid but uses non-standard field names
 * (e.g., "cmd" instead of "action", "content" instead of "prompt"),
 * this stage maps them to the expected schema fields using a
 * pre-defined synonym table.
 * <p>
 * <h3>Stage 3: Fragment Assembly</h3>
 * If no coherent JSON block can be found, attempt to extract individual
 * key-value pairs from the text using regex patterns and assemble them
 * into a valid JSON object.
 * <p>
 * <h3>OS Analogy: Page Fault Handler</h3>
 * In a real OS, when the TLB misses and the page table walk also fails,
 * the kernel falls back to the page fault handler — a slower but more
 * capable mechanism that can handle swap-in, COW, and other complex
 * scenarios. The Semantic Fuzzy Decode Strategy is the page fault
 * handler of our instruction decode pipeline: slower, but resilient.
 *
 * @see DecodeStrategy
 * @see StrictDecodeStrategy
 */
public final class SemanticFuzzyDecodeStrategy implements DecodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticFuzzyDecodeStrategy.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ── Field Name Synonym Table ──

    private static final Map<String, List<String>> FIELD_SYNONYMS = Map.ofEntries(
            Map.entry("action", List.of("cmd", "command", "operation", "syscall", "call", "指令", "操作")),
            Map.entry("path", List.of("file", "filepath", "target", "文件路径", "路径")),
            Map.entry("prompt", List.of("query", "question", "input", "text", "content", "message",
                    "问题", "输入", "内容", "提示")),
            Map.entry("system_prompt", List.of("system", "system_message", "context", "系统提示")),
            Map.entry("payload", List.of("data", "body", "参数", "数据")),
            Map.entry("handle", List.of("fd", "file_descriptor", "描述符")),
            Map.entry("pid", List.of("process_id", "process", "进程号")),
            Map.entry("package", List.of("package_name", "plugin", "module", "包名", "插件")),
            Map.entry("parameters", List.of("params", "args", "arguments", "参数"))
    );

    // ── Regex Patterns for Fragment Extraction ──

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(\\d+)|(true|false|null))");
    private static final Pattern CHINESE_KEY_VALUE_PATTERN = Pattern.compile(
            "(?:action|cmd|指令|操作|path|路径|prompt|提示|问题)[：:]\\s*(.+?)(?:[,，\\n]|$)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "SemanticFuzzy";
    }

    @Override
    public int priority() {
        return 50; // lower priority than Strict — only used as fallback
    }

    @Override
    public <T> T decode(String llmOutput, Class<T> targetClass, LlmProvider llmProvider) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }

        log.info("[Fuzzy] Activating semantic fuzzy decoder for type={}", targetClass.getSimpleName());
        System.out.println("  \u001B[33m[Fuzzy Decoder] Strict parse failed. Engaging semantic fuzzy pipeline...\u001B[0m");

        // Stage 1: Deep regex clean
        T result = attemptDeepClean(llmOutput, targetClass);
        if (result != null) {
            log.info("[Fuzzy] Stage 1 (deep clean) succeeded for type={}", targetClass.getSimpleName());
            System.out.println("  \u001B[32m[Fuzzy Decoder] Stage 1 (deep clean) succeeded!\u001B[0m");
            return result;
        }

        // Stage 2: Field name fuzzy match
        result = attemptFieldFuzzyMatch(llmOutput, targetClass);
        if (result != null) {
            log.info("[Fuzzy] Stage 2 (field fuzzy match) succeeded for type={}", targetClass.getSimpleName());
            System.out.println("  \u001B[32m[Fuzzy Decoder] Stage 2 (field fuzzy match) succeeded!\u001B[0m");
            return result;
        }

        // Stage 3: Fragment assembly
        result = attemptFragmentAssembly(llmOutput, targetClass);
        if (result != null) {
            log.info("[Fuzzy] Stage 3 (fragment assembly) succeeded for type={}", targetClass.getSimpleName());
            System.out.println("  \u001B[32m[Fuzzy Decoder] Stage 3 (fragment assembly) succeeded!\u001B[0m");
            return result;
        }

        log.warn("[Fuzzy] All 3 fuzzy stages failed for type={}", targetClass.getSimpleName());
        System.out.println("  \u001B[31m[Fuzzy Decoder] All fuzzy stages failed. Instruction unparseable.\u001B[0m");
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  Stage 1: Deep Regex Clean
    // ════════════════════════════════════════════════════════════════

    /**
     * Stage 1: Aggressively clean the LLM output and try to extract
     * a valid JSON block.
     * <p>
     * Handles patterns like:
     * <ul>
     *   <li>"好的，我现在调用 sys_write 写入数据：{...}"</li>
     *   <li>"The result is:\n```json\n{...}\n```"</li>
     *   <li>"Here's the JSON: {...} and some trailing text"</li>
     * </ul>
     */
    private <T> T attemptDeepClean(String output, Class<T> targetClass) {
        String cleaned = output.trim();

        // Strip common LLM preamble patterns
        cleaned = stripPreamble(cleaned);

        // Try the strict extractor on the cleaned text
        String json = StrictDecodeStrategy.extractJson(cleaned);
        T result = tryParseJson(json, targetClass);
        if (result != null) return result;

        // Try finding innermost JSON object
        Matcher m = JSON_OBJECT_PATTERN.matcher(cleaned);
        while (m.find()) {
            String candidate = m.group();
            result = tryParseJson(candidate, targetClass);
            if (result != null) return result;
        }

        return null;
    }

    /**
     * Strip common LLM preamble text before the actual JSON.
     */
    private String stripPreamble(String text) {
        // Remove common Chinese preamble patterns
        String[] preamblePatterns = {
                "好的[，,].*?[：:]\\s*",
                "以下是.*?[：:]\\s*",
                "结果如下[：:]\\s*",
                "调用.*?[：:]\\s*",
                "我将.*?[：:]\\s*",
        };
        String cleaned = text;
        for (String pattern : preamblePatterns) {
            cleaned = cleaned.replaceFirst("(?s)" + pattern, "");
        }

        // Remove common English preamble patterns
        cleaned = cleaned.replaceFirst("(?i)^.*?(?=\\{)", "");
        if (cleaned.isEmpty()) cleaned = text;

        return cleaned.trim();
    }

    // ════════════════════════════════════════════════════════════════
    //  Stage 2: Field Name Fuzzy Match
    // ════════════════════════════════════════════════════════════════

    /**
     * Stage 2: If the JSON is structurally valid but uses non-standard
     * field names, try to map them to the expected schema.
     * <p>
     * For example, if the LLM outputs {@code {"cmd": "vfs.read", "file": "/dev/camera0"}},
     * this stage maps "cmd" → "action" and "file" → "path".
     */
    private <T> T attemptFieldFuzzyMatch(String output, Class<T> targetClass) {
        String json = StrictDecodeStrategy.extractJson(output);
        if (json == null || json.isBlank()) return null;

        // Try to parse as a generic Map first
        Map<String, Object> rawMap;
        try {
            rawMap = OBJECT_MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }

        // Apply synonym mapping
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            String key = entry.getKey();
            String mappedKey = resolveFieldName(key);
            if (!mapped.containsKey(mappedKey)) {
                mapped.put(mappedKey, entry.getValue());
            } else {
                // If the mapped key already exists, keep the original key as well
                mapped.put(key, entry.getValue());
            }
        }

        // Try to deserialize the mapped object
        try {
            String remappedJson = OBJECT_MAPPER.writeValueAsString(mapped);
            return OBJECT_MAPPER.readValue(remappedJson, targetClass);
        } catch (Exception e) {
            log.debug("[Fuzzy] Field fuzzy match deserialization failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve a non-standard field name to the canonical schema field name.
     */
    private String resolveFieldName(String key) {
        String lowerKey = key.toLowerCase().replace("-", "_").replace(" ", "_");

        // Direct match
        if (FIELD_SYNONYMS.containsKey(lowerKey)) {
            return lowerKey;
        }

        // Synonym match
        for (Map.Entry<String, List<String>> entry : FIELD_SYNONYMS.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (synonym.equalsIgnoreCase(lowerKey)
                        || lowerKey.contains(synonym.toLowerCase())
                        || synonym.toLowerCase().contains(lowerKey)) {
                    return entry.getKey();
                }
            }
        }

        return key; // no mapping found — keep original
    }

    // ════════════════════════════════════════════════════════════════
    //  Stage 3: Fragment Assembly
    // ════════════════════════════════════════════════════════════════

    /**
     * Stage 3: Last resort — extract individual key-value pairs from
     * the text using regex and assemble them into a JSON object.
     * <p>
     * This handles the most pathological cases where the LLM produces
     * something like:
     * <pre>
     *   action: vfs.read
     *   path: /dev/camera0
     * </pre>
     * or even:
     * <pre>
     *   我要调用 vfs.read，路径是 /dev/camera0
     * </pre>
     */
    private <T> T attemptFragmentAssembly(String output, Class<T> targetClass) {
        Map<String, Object> assembled = new LinkedHashMap<>();

        // Extract JSON-style key-value pairs
        Matcher kvMatcher = KEY_VALUE_PATTERN.matcher(output);
        while (kvMatcher.find()) {
            String key = resolveFieldName(kvMatcher.group(1));
            String stringValue = kvMatcher.group(2);
            String numberValue = kvMatcher.group(3);
            String literalValue = kvMatcher.group(4);

            if (stringValue != null) {
                assembled.put(key, stringValue);
            } else if (numberValue != null) {
                try {
                    assembled.put(key, Integer.parseInt(numberValue));
                } catch (NumberFormatException e) {
                    assembled.put(key, numberValue);
                }
            } else if (literalValue != null) {
                assembled.put(key, switch (literalValue) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> null;
                });
            }
        }

        // Extract action from common patterns if not found
        if (!assembled.containsKey("action")) {
            String action = extractActionFromText(output);
            if (action != null) {
                assembled.put("action", action);
            }
        }

        // Extract path from common patterns if not found
        if (!assembled.containsKey("path")) {
            String path = extractPathFromText(output);
            if (path != null) {
                assembled.put("path", path);
            }
        }

        if (assembled.isEmpty()) {
            return null;
        }

        // Try to deserialize the assembled map
        try {
            String assembledJson = OBJECT_MAPPER.writeValueAsString(assembled);
            log.debug("[Fuzzy] Assembled JSON: {}", assembledJson);
            return OBJECT_MAPPER.readValue(assembledJson, targetClass);
        } catch (Exception e) {
            log.debug("[Fuzzy] Fragment assembly deserialization failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract an action string from natural language text.
     * <p>
     * Matches patterns like:
     * - "调用 vfs.read" → "vfs.read"
     * - "执行 llm.think" → "llm.think"
     * - "action is bin.ps" → "bin.ps"
     */
    private String extractActionFromText(String text) {
        Pattern actionPattern = Pattern.compile(
                "(?i)(?:action|cmd|command|指令|操作|调用|执行)[：:\\s]+[\"']?([a-z]+\\.[a-z_]+)[\"']?",
                Pattern.CASE_INSENSITIVE);
        Matcher m = actionPattern.matcher(text);
        if (m.find()) {
            return m.group(1);
        }

        // Try to find any dotted action pattern (e.g., "vfs.read")
        Pattern dottedPattern = Pattern.compile("\\b([a-z]+\\.[a-z_]+)\\b");
        m = dottedPattern.matcher(text);
        if (m.find()) {
            String candidate = m.group(1);
            // Validate against known namespaces
            if (candidate.startsWith("vfs.") || candidate.startsWith("llm.")
                    || candidate.startsWith("bin.") || candidate.startsWith("handle.")
                    || candidate.startsWith("apt.") || candidate.startsWith("tool.")
                    || candidate.startsWith("storage.") || candidate.startsWith("memory.")) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Extract a VFS path from natural language text.
     * <p>
     * Matches patterns like:
     * - "/dev/camera0"
     * - "/proc/agents"
     * - "路径是 /dev/shm/blackboard"
     */
    private String extractPathFromText(String text) {
        Pattern pathPattern = Pattern.compile("(?:path|文件|路径|target)[：:\\s]+[\"']?(/\\S+?)[\"']?[\\s,，.]");
        Matcher m = pathPattern.matcher(text);
        if (m.find()) {
            return m.group(1);
        }

        // Fallback: find any /dev/... or /proc/... path
        Pattern vfsPath = Pattern.compile("(/[a-z]+/\\S+?)[\\s,，.\"]");
        m = vfsPath.matcher(text);
        if (m.find()) {
            return m.group(1);
        }

        return null;
    }

    // ── Utility ──

    private <T> T tryParseJson(String json, Class<T> targetClass) {
        try {
            return OBJECT_MAPPER.readValue(json, targetClass);
        } catch (Exception e) {
            return null;
        }
    }
}
