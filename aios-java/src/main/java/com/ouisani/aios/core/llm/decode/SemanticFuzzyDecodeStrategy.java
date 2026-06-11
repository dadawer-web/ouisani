package com.ouisani.aios.core.llm.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义模糊解码策略 — 严格 JSON 解析失败时的弹性回退。
 * <p>
 * 当 LLM 产生的输出不符合预期的 JSON Schema（如用自然语言包裹 JSON、
 * 使用非标准字段名、产生部分有效的结构）时，此策略通过多阶段模糊解析
 * 管线提取意图：
 *
 * <h3>阶段 1：正则深度清洗</h3>
 * 剥离 Markdown 伪影，提取嵌入式 JSON 片段，规范化常见的 LLM 输出模式
 * （如 "好的，我现在调用 sys_write 写入数据：{...}" → 提取 {...} 部分）。
 *
 * <h3>阶段 2：字段名模糊匹配</h3>
 * 如果 JSON 结构有效但使用了非标准字段名（如 "cmd" 而非 "action"，
 * "content" 而非 "prompt"），此阶段通过预定义的同义词表将它们映射到
 * 期望的 Schema 字段。
 *
 * <h3>阶段 3：片段组装</h3>
 * 如果找不到完整的 JSON 块，尝试用正则模式从文本中提取单个键值对，
 * 并组装成有效的 JSON 对象。
 *
 * <h3>OS 类比：Page Fault 处理器</h3>
 * 真实 OS 中，当 TLB 未命中且页表遍历也失败时，内核回退到 Page Fault 处理器 —
 * 更慢但能力更强的机制，可以处理换入、COW 等复杂场景。
 * 语义模糊解码策略就是指令解码管线的 Page Fault 处理器：更慢，但更健壮。
 *
 * @see DecodeStrategy
 * @see StrictDecodeStrategy
 */
public final class SemanticFuzzyDecodeStrategy implements DecodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticFuzzyDecodeStrategy.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ── 字段名同义词表 ──

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

    // ── 片段提取的正则模式 ──
    // 注意：JSON_OBJECT_PATTERN 已从正则替换为 indexOf 线性扫描，防止 StackOverflowError
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
        return 50; // 低于 Strict — 仅作为回退使用
    }

    /**
     * 执行三阶段模糊解码管线。
     *
     * @param llmOutput   LLM 原始输出
     * @param targetClass 目标类型
     * @param llmProvider LLM 提供者（此策略未使用）
     * @return 解码结果，失败返回 null
     */
    @Override
    public <T> T decode(String llmOutput, Class<T> targetClass, LlmProvider llmProvider) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }

        log.info("[Fuzzy] Activating semantic fuzzy decoder for type={}", targetClass.getSimpleName());
        System.out.println("  \u001B[33m[Fuzzy Decoder] Strict parse failed. Engaging semantic fuzzy pipeline...\u001B[0m");

        // 阶段 1：正则深度清洗
        T result = attemptDeepClean(llmOutput, targetClass);
        if (result != null) {
            log.info("[Fuzzy] Stage 1 (deep clean) succeeded for type={}", targetClass.getSimpleName());
            System.out.println("  \u001B[32m[Fuzzy Decoder] Stage 1 (deep clean) succeeded!\u001B[0m");
            return result;
        }

        // 阶段 2：字段名模糊匹配
        result = attemptFieldFuzzyMatch(llmOutput, targetClass);
        if (result != null) {
            log.info("[Fuzzy] Stage 2 (field fuzzy match) succeeded for type={}", targetClass.getSimpleName());
            System.out.println("  \u001B[32m[Fuzzy Decoder] Stage 2 (field fuzzy match) succeeded!\u001B[0m");
            return result;
        }

        // 阶段 3：片段组装
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
     * 阶段 1：激进清洗 LLM 输出并尝试提取有效的 JSON 块。
     * <p>
     * 处理的模式如：
     * <ul>
     *   <li>"好的，我现在调用 sys_write 写入数据：{...}"</li>
     *   <li>"The result is:\n```json\n{...}\n```"</li>
     *   <li>"Here's the JSON: {...} and some trailing text"</li>
     * </ul>
     */
    private <T> T attemptDeepClean(String output, Class<T> targetClass) {
        String cleaned = output.trim();

        // 剥离常见的 LLM 前导文本
        cleaned = stripPreamble(cleaned);

        // 在清洗后的文本上尝试严格提取器
        String json = StrictDecodeStrategy.extractJson(cleaned);
        T result = tryParseJson(json, targetClass);
        if (result != null) return result;

        // 线性扫描查找 JSON 对象（indexOf 替代正则，防止 StackOverflowError）
        result = findJsonObjectsLinear(cleaned, targetClass);
        if (result != null) return result;

        return null;
    }

    /**
     * 使用 indexOf 线性扫描查找文本中的 JSON 对象，O(N) 时间复杂度。
     * <p>
     * 替代原来的 JSON_OBJECT_PATTERN 正则，避免在大段 LLM 输出上触发 StackOverflowError。
     */
    private <T> T findJsonObjectsLinear(String text, Class<T> targetClass) {
        int searchStart = 0;
        while (searchStart < text.length()) {
            int braceStart = text.indexOf('{', searchStart);
            if (braceStart < 0) break;

            // 找到匹配的闭合花括号
            int depth = 0;
            int pos = braceStart;
            boolean inString = false;
            boolean escape = false;

            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (escape) {
                    escape = false;
                } else if (c == '\\' && inString) {
                    escape = true;
                } else if (c == '"' && !escape) {
                    inString = !inString;
                } else if (!inString) {
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            // 找到完整的 JSON 对象
                            String candidate = text.substring(braceStart, pos + 1);
                            T result = tryParseJson(candidate, targetClass);
                            if (result != null) return result;
                            break;
                        }
                    }
                }
                pos++;
            }

            searchStart = braceStart + 1;
        }
        return null;
    }

    /** 剥离 JSON 前的常见 LLM 前导文本 */
    private String stripPreamble(String text) {
        // 移除常见的中文前导模式
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

        // 移除常见的英文前导模式
        cleaned = cleaned.replaceFirst("(?i)^.*?(?=\\{)", "");
        if (cleaned.isEmpty()) cleaned = text;

        return cleaned.trim();
    }

    // ════════════════════════════════════════════════════════════════
    //  Stage 2: Field Name Fuzzy Match
    // ════════════════════════════════════════════════════════════════

    /**
     * 阶段 2：如果 JSON 结构有效但使用了非标准字段名，尝试映射到期望的 Schema。
     * <p>
     * 例如 LLM 输出 {@code {"cmd": "vfs.read", "file": "/dev/camera0"}}，
     * 此阶段将 "cmd" → "action"、"file" → "path" 进行映射。
     */
    private <T> T attemptFieldFuzzyMatch(String output, Class<T> targetClass) {
        String json = StrictDecodeStrategy.extractJson(output);
        if (json == null || json.isBlank()) return null;

        // 先尝试解析为通用 Map
        Map<String, Object> rawMap;
        try {
            rawMap = OBJECT_MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }

        // 应用同义词映射
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            String key = entry.getKey();
            String mappedKey = resolveFieldName(key);
            if (!mapped.containsKey(mappedKey)) {
                mapped.put(mappedKey, entry.getValue());
            } else {
                // 如果映射后的键已存在，保留原始键以避免覆盖
                mapped.put(key, entry.getValue());
            }
        }

        // 尝试反序列化映射后的对象
        try {
            String remappedJson = OBJECT_MAPPER.writeValueAsString(mapped);
            return OBJECT_MAPPER.readValue(remappedJson, targetClass);
        } catch (Exception e) {
            log.debug("[Fuzzy] Field fuzzy match deserialization failed: {}", e.getMessage());
            return null;
        }
    }

    /** 将非标准字段名解析为规范的 Schema 字段名 */
    private String resolveFieldName(String key) {
        String lowerKey = key.toLowerCase().replace("-", "_").replace(" ", "_");

        // 直接匹配
        if (FIELD_SYNONYMS.containsKey(lowerKey)) {
            return lowerKey;
        }

        // 同义词匹配
        for (Map.Entry<String, List<String>> entry : FIELD_SYNONYMS.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (synonym.equalsIgnoreCase(lowerKey)
                        || lowerKey.contains(synonym.toLowerCase())
                        || synonym.toLowerCase().contains(lowerKey)) {
                    return entry.getKey();
                }
            }
        }

        return key; // 未找到映射 — 保留原始键
    }

    // ════════════════════════════════════════════════════════════════
    //  Stage 3: Fragment Assembly
    // ════════════════════════════════════════════════════════════════

    /**
     * 阶段 3：最后手段 — 用正则从文本中提取单个键值对并组装为 JSON 对象。
     * <p>
     * 处理最极端的情况，如 LLM 输出：
     * <pre>
     *   action: vfs.read
     *   path: /dev/camera0
     * </pre>
     * 甚至：
     * <pre>
     *   我要调用 vfs.read，路径是 /dev/camera0
     * </pre>
     */
    private <T> T attemptFragmentAssembly(String output, Class<T> targetClass) {
        Map<String, Object> assembled = new LinkedHashMap<>();

        // 提取 JSON 风格的键值对
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

        // 如果未找到 action，从常见模式中提取
        if (!assembled.containsKey("action")) {
            String action = extractActionFromText(output);
            if (action != null) {
                assembled.put("action", action);
            }
        }

        // 如果未找到 path，从常见模式中提取
        if (!assembled.containsKey("path")) {
            String path = extractPathFromText(output);
            if (path != null) {
                assembled.put("path", path);
            }
        }

        if (assembled.isEmpty()) {
            return null;
        }

        // 尝试反序列化组装后的 Map
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
     * 从自然语言文本中提取 action 字符串。
     * <p>
     * 匹配模式如：
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

        // 尝试查找任何点分格式的 action 模式（如 "vfs.read"）
        Pattern dottedPattern = Pattern.compile("\\b([a-z]+\\.[a-z_]+)\\b");
        m = dottedPattern.matcher(text);
        if (m.find()) {
            String candidate = m.group(1);
            // 验证是否属于已知的命名空间
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
     * 从自然语言文本中提取 VFS 路径。
     * <p>
     * 匹配模式如：
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

        // 回退：查找任何 /dev/... 或 /proc/... 路径
        Pattern vfsPath = Pattern.compile("(/[a-z]+/\\S+?)[\\s,，.\"]");
        m = vfsPath.matcher(text);
        if (m.find()) {
            return m.group(1);
        }

        return null;
    }

    // ── 工具方法 ──

    private <T> T tryParseJson(String json, Class<T> targetClass) {
        try {
            return OBJECT_MAPPER.readValue(json, targetClass);
        } catch (Exception e) {
            return null;
        }
    }
}
