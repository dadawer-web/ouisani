package com.ouisani.aios.user.apps.omnifactory;

import java.util.*;

/**
 * 强类型 Schema 定义 — 描述节点的输入/输出数据契约。
 * <p>
 * 类似 TypeScript 的 interface 或 Protobuf 的 message 定义。
 * 在 GraphValidator 中用于验证上游 OutputSchema 与下游 InputSchema 的类型兼容性。
 * <p>
 * 示例：
 * <pre>
 * SchemaDefinition output = SchemaDefinition.builder("search_result")
 *     .field("url", SchemaType.STRING)
 *     .field("title", SchemaType.STRING)
 *     .field("content", SchemaType.STRING)
 *     .build();
 *
 * SchemaDefinition input = SchemaDefinition.builder("write_input")
 *     .field("content", SchemaType.STRING)  // 只需要 content 字段
 *     .build();
 * </pre>
 */
public class SchemaDefinition {

    /** 字段类型枚举 */
    public enum SchemaType {
        STRING,     // 字符串
        INTEGER,    // 整数
        FLOAT,      // 浮点数
        BOOLEAN,    // 布尔值
        JSON,       // JSON 对象/数组
        FILE_PATH,  // VFS 文件路径
        ANY         // 通配类型（兼容一切）
    }

    private final String name;
    private final Map<String, SchemaType> fields;
    private final Set<String> requiredFields;

    private SchemaDefinition(String name, Map<String, SchemaType> fields, Set<String> requiredFields) {
        this.name = name;
        this.fields = Collections.unmodifiableMap(fields);
        this.requiredFields = Collections.unmodifiableSet(requiredFields);
    }

    public String name() {
        return name;
    }

    public Map<String, SchemaType> fields() {
        return fields;
    }

    public Set<String> requiredFields() {
        return requiredFields;
    }

    /**
     * 检查本 Schema 是否能满足下游所需的字段。
     * <p>
     * 兼容规则：
     * <ol>
     *   <li>下游需要的每个 required 字段，上游必须提供</li>
     *   <li>类型必须兼容（STRING 兼容 FILE_PATH，ANY 兼容一切）</li>
     * </ol>
     *
     * @param requiredInput 下游节点的 InputSchema
     * @return null 表示兼容，否则返回不兼容的字段及原因
     */
    public String checkCompatibility(SchemaDefinition requiredInput) {
        if (requiredInput == null) return null;

        for (String requiredField : requiredInput.requiredFields) {
            SchemaType upstreamType = this.fields.get(requiredField);
            SchemaType downstreamType = requiredInput.fields.get(requiredField);

            if (upstreamType == null) {
                return "上游输出缺少下游必需的字段: '" + requiredField + "'";
            }

            if (!isTypeCompatible(upstreamType, downstreamType)) {
                return "字段 '" + requiredField + "' 类型不兼容: 上游=" + upstreamType
                        + ", 下游=" + downstreamType;
            }
        }

        return null;
    }

    /**
     * 类型兼容性矩阵。
     */
    private boolean isTypeCompatible(SchemaType upstream, SchemaType downstream) {
        if (upstream == SchemaType.ANY || downstream == SchemaType.ANY) return true;
        if (upstream == downstream) return true;
        // STRING 可以兼容 FILE_PATH（文件路径本质是字符串）
        if (upstream == SchemaType.STRING && downstream == SchemaType.FILE_PATH) return true;
        if (upstream == SchemaType.FILE_PATH && downstream == SchemaType.STRING) return true;
        // INTEGER 兼容 FLOAT
        if (upstream == SchemaType.INTEGER && downstream == SchemaType.FLOAT) return true;
        return false;
    }

    @Override
    public String toString() {
        return name + fields.toString();
    }

    /**
     * Builder 模式构建 SchemaDefinition。
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private final Map<String, SchemaType> fields = new LinkedHashMap<>();
        private final Set<String> requiredFields = new LinkedHashSet<>();

        Builder(String name) {
            this.name = name;
        }

        public Builder field(String name, SchemaType type) {
            fields.put(name, type);
            return this;
        }

        public Builder required(String name, SchemaType type) {
            fields.put(name, type);
            requiredFields.add(name);
            return this;
        }

        public SchemaDefinition build() {
            return new SchemaDefinition(name, fields, requiredFields);
        }
    }

    /**
     * 从 LLM JSON 输出中解析 SchemaDefinition。
     * <p>
     * 支持两种格式：
     * <pre>
     * // 简单格式：["url", "title", "content"]
     * // 详细格式：[{"name": "url", "type": "STRING", "required": true}]
     * </pre>
     */
    public static SchemaDefinition parse(String name, String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return null;
        }

        Builder builder = builder(name);
        String cleaned = jsonArray.trim();
        // 去除方括号
        if (cleaned.startsWith("[")) cleaned = cleaned.substring(1);
        if (cleaned.endsWith("]")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        cleaned = cleaned.trim();

        if (cleaned.isEmpty()) return builder.build();

        // 尝试按逗号分割（简单格式）
        String[] parts = cleaned.split(",(?![^{}]*})");
        for (String part : parts) {
            String trimmed = part.trim().replaceAll("\"", "");
            if (trimmed.isEmpty()) continue;

            // 检查是否是对象格式 {"name": "xxx", "type": "STRING"}
            if (trimmed.startsWith("{")) {
                String fieldName = extractValue(trimmed, "name");
                String typeStr = extractValue(trimmed, "type");
                String requiredStr = extractValue(trimmed, "required");
                SchemaType type = parseType(typeStr);
                if (fieldName != null && !fieldName.isEmpty()) {
                    if ("true".equalsIgnoreCase(requiredStr)) {
                        builder.required(fieldName, type);
                    } else {
                        builder.field(fieldName, type);
                    }
                }
            } else {
                // 简单格式，默认 STRING 类型
                builder.field(trimmed, SchemaType.STRING);
            }
        }

        return builder.build();
    }

    private static String extractValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colonIdx = json.indexOf(":", idx);
        if (colonIdx < 0) return null;
        int start = colonIdx + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        int end = start;
        while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    private static SchemaType parseType(String typeStr) {
        if (typeStr == null) return SchemaType.STRING;
        return switch (typeStr.toUpperCase().trim()) {
            case "STRING", "STR", "TEXT" -> SchemaType.STRING;
            case "INT", "INTEGER", "NUMBER" -> SchemaType.INTEGER;
            case "FLOAT", "DOUBLE", "DECIMAL" -> SchemaType.FLOAT;
            case "BOOL", "BOOLEAN" -> SchemaType.BOOLEAN;
            case "JSON", "OBJECT", "ARRAY", "LIST" -> SchemaType.JSON;
            case "FILE", "FILE_PATH", "PATH" -> SchemaType.FILE_PATH;
            case "ANY", "WILDCARD" -> SchemaType.ANY;
            default -> SchemaType.STRING;
        };
    }
}
