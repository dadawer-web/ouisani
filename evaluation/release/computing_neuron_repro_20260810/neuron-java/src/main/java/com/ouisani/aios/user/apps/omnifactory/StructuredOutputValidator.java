package com.ouisani.aios.user.apps.omnifactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 结构化输出验证器 — 借鉴 OMA (open-multi-agent) 的 structured-output.ts 设计。
 * <p>
 * 解决 LLM 输出不稳定问题:JSON 格式错误、字段缺失、executor 值非法等。
 * <p>
 * 两阶段验证:
 * <ol>
 *   <li>JSON 提取 — 三级 fallback:直接 parse → markdown fence 提取 → 首尾大括号截取</li>
 *   <li>Schema 验证 — 逐节点检查必填字段(instanceId/role/executor)和迭代节点完整性</li>
 * </ol>
 * 每级 fallback 都用 Jackson ObjectMapper 真正 parse,不再用正则猜测。
 * <p>
 * 编译器调用 {@link #extractAndValidate} 获取验证结果,失败时把逐字段错误信息
 * 塞回 LLM prompt 让它修正,实现"自旋反馈"。
 */
public final class StructuredOutputValidator {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputValidator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 合法的 executor 基础值 */
    private static final Set<String> VALID_EXECUTORS = Set.of("omni", "operator", "external");

    private StructuredOutputValidator() {}

    // ════════════════════════════════════════════════════════════════
    //  ValidationResult — 验证结果
    // ════════════════════════════════════════════════════════════════

    /**
     * 验证结果 — 包含是否通过、逐字段错误列表、清洗后的 JSON 字符串。
     */
    public static final class ValidationResult {

        private final boolean valid;
        private final List<String> errors;
        private final String cleanedJson;

        private ValidationResult(boolean valid, List<String> errors, String cleanedJson) {
            this.valid = valid;
            this.errors = errors;
            this.cleanedJson = cleanedJson;
        }

        static ValidationResult success(String json) {
            return new ValidationResult(true, List.of(), json);
        }

        static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors, null);
        }

        static ValidationResult failure(String error) {
            return new ValidationResult(false, List.of(error), null);
        }

        public boolean isValid() { return valid; }

        public List<String> errors() { return errors; }

        public String cleanedJson() { return cleanedJson; }

        /** 将错误列表格式化为单行字符串,适合塞回 LLM prompt */
        public String formattedErrors() {
            return String.join("; ", errors);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  主入口 — 提取 + 验证
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 LLM 原始响应中提取并验证拓扑 JSON。
     * <p>
     * 两阶段:先三级 fallback 提取合法 JSON,再逐节点 schema 验证。
     *
     * @param raw LLM 原始响应
     * @return 验证结果(成功时含清洗后的 JSON,失败时含逐字段错误列表)
     */
    public static ValidationResult extractAndValidate(String raw) {
        if (raw == null || raw.isBlank()) {
            return ValidationResult.failure("LLM 响应为空");
        }

        // ── 阶段 1: 三级 fallback 提取合法 JSON ──
        String extracted = extractJson(raw);
        if (extracted == null) {
            log.warn("[StructuredOutputValidator] 三级 fallback 均无法提取合法 JSON, rawLen={}", raw.length());
            return ValidationResult.failure("无法从 LLM 响应中提取合法 JSON (三级 fallback 均失败)");
        }

        // ── 阶段 2: 解析为 JsonNode 做 schema 验证 ──
        JsonNode root;
        try {
            root = MAPPER.readTree(extracted);
        } catch (Exception e) {
            return ValidationResult.failure("JSON 解析失败: " + e.getMessage());
        }

        if (!root.isObject()) {
            return ValidationResult.failure("顶层不是 JSON 对象, 实际类型: " + root.getNodeType());
        }

        List<String> errors = validateTopologySchema(root);
        if (!errors.isEmpty()) {
            log.warn("[StructuredOutputValidator] Schema 验证失败, {} 个错误", errors.size());
            return ValidationResult.failure(errors);
        }

        log.info("[StructuredOutputValidator] 验证通过, jsonLen={}", extracted.length());
        return ValidationResult.success(extracted);
    }

    // ════════════════════════════════════════════════════════════════
    //  三级 JSON 提取
    // ════════════════════════════════════════════════════════════════

    /**
     * 三级 fallback 提取 JSON 字符串。
     * <p>
     * 借鉴 OMA extractJSON 的设计:
     * <ol>
     *   <li>去除 think 标签 + markdown fence 后直接 parse</li>
     *   <li>提取 ```json ... ``` 代码块</li>
     *   <li>首尾大括号截取</li>
     * </ol>
     * 每级都用 Jackson ObjectMapper 验证是否合法 JSON。
     *
     * @param raw LLM 原始响应
     * @return 合法 JSON 字符串,或 null(无法提取)
     */
    public static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // 预处理:去除 <think>...</think> 思考过程 (支持跨行)
        String cleaned = raw.replaceAll("(?s)<think>.*?</think>", "").trim();

        // Case 1: 去除 markdown fence 后直接 parse
        String noMarkdown = cleaned
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        if (isValidJson(noMarkdown)) {
            log.debug("[StructuredOutputValidator] Case 1 (直接 parse) 成功");
            return noMarkdown;
        }

        // Case 2: 提取 ```json ... ``` 代码块
        String fenced = extractFencedJson(cleaned);
        if (fenced != null && isValidJson(fenced)) {
            log.debug("[StructuredOutputValidator] Case 2 (代码块提取) 成功");
            return fenced;
        }

        // Case 3: 首尾大括号截取
        int start = cleaned.indexOf("{");
        int end = cleaned.lastIndexOf("}");
        if (start != -1 && end != -1 && start < end) {
            String bracketed = cleaned.substring(start, end + 1);
            if (isValidJson(bracketed)) {
                log.debug("[StructuredOutputValidator] Case 3 (首尾大括号) 成功");
                return bracketed;
            }
        }

        log.debug("[StructuredOutputValidator] 三级 fallback 均失败");
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  拓扑 Schema 验证
    // ════════════════════════════════════════════════════════════════

    /**
     * 验证拓扑 JSON 是否符合预期 schema。
     * <p>
     * 检查项:
     * <ul>
     *   <li>顶层必须有 nodes 数组且非空</li>
     *   <li>每个节点必须有 instanceId (非空),兼容旧格式 id</li>
     *   <li>每个节点必须有 role (非空)</li>
     *   <li>每个节点必须有 executor (omni/operator/external, 允许 external:子类型)</li>
     *   <li>迭代节点必须有 iteratorDataVariable + iteratorItemAlias + childNodes</li>
     * </ul>
     *
     * @param root 解析后的 JSON 根节点
     * @return 错误列表(空列表表示通过)
     */
    public static List<String> validateTopologySchema(JsonNode root) {
        List<String> errors = new ArrayList<>();

        // 1. 必须有 nodes 数组
        JsonNode nodes = root.get("nodes");
        if (nodes == null || !nodes.isArray()) {
            errors.add("缺少 'nodes' 数组或类型不正确");
            return errors;
        }
        if (nodes.isEmpty()) {
            errors.add("'nodes' 数组为空");
            return errors;
        }

        // 2. 逐节点验证
        int index = 0;
        for (JsonNode node : nodes) {
            String prefix = "nodes[" + index + "]";

            // instanceId 必须非空 (兼容旧格式 id)
            String instanceId = getTextField(node, "instanceId");
            if (instanceId == null) {
                instanceId = getTextField(node, "id");
            }
            if (instanceId == null || instanceId.isBlank()) {
                errors.add(prefix + ": 缺少必填字段 'instanceId'");
            }

            String nodeLabel = (instanceId != null && !instanceId.isBlank())
                    ? "'" + instanceId + "'" : prefix;

            // role 必须非空
            if (isBlank(getTextField(node, "role"))) {
                errors.add(prefix + " (" + nodeLabel + "): 缺少必填字段 'role'");
            }

            // executor 必须是有效值
            String executor = getTextField(node, "executor");
            if (isBlank(executor)) {
                errors.add(prefix + " (" + nodeLabel + "): 缺少必填字段 'executor'");
            } else if (!isValidExecutor(executor)) {
                errors.add(prefix + " (" + nodeLabel + "): executor '" + executor
                        + "' 不是有效值 (允许: omni/operator/external)");
            }

            // 迭代节点完整性验证
            String isIteration = getTextField(node, "isIteration");
            if ("true".equalsIgnoreCase(isIteration)) {
                if (isBlank(getTextField(node, "iteratorDataVariable"))) {
                    errors.add(prefix + " (" + nodeLabel + "): 迭代节点缺少 'iteratorDataVariable'");
                }
                if (isBlank(getTextField(node, "iteratorItemAlias"))) {
                    errors.add(prefix + " (" + nodeLabel + "): 迭代节点缺少 'iteratorItemAlias'");
                }
                JsonNode childNodes = node.get("childNodes");
                if (childNodes == null || !childNodes.isArray() || childNodes.isEmpty()) {
                    errors.add(prefix + " (" + nodeLabel + "): 迭代节点缺少 'childNodes' 数组");
                }
            }

            index++;
        }

        return errors;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部工具方法
    // ════════════════════════════════════════════════════════════════

    private static boolean isValidJson(String json) {
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String extractFencedJson(String text) {
        int start = text.indexOf("```json");
        if (start == -1) start = text.indexOf("```JSON");
        if (start == -1) start = text.indexOf("```");
        if (start == -1) return null;

        int contentStart = text.indexOf("\n", start);
        if (contentStart == -1) return null;
        contentStart++;

        int end = text.indexOf("```", contentStart);
        if (end == -1) return null;

        return text.substring(contentStart, end).trim();
    }

    private static boolean isValidExecutor(String executor) {
        // 允许 "external:claude-code" 这种带子类型的形式
        String base = executor.contains(":")
                ? executor.substring(0, executor.indexOf(":"))
                : executor;
        return VALID_EXECUTORS.contains(base);
    }

    private static String getTextField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) return value.asText();
        return value.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
