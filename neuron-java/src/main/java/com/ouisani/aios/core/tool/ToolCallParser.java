package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import com.ouisani.aios.core.tool.QueryEngine.ToolCall;

/**
 * 工具调用解析器 — 从 QueryEngine 抽取的 LLM 响应解析工具集。
 * <p>
 * 使用纯字符串 indexOf 线性扫描，O(N) 时间复杂度，绝不触发 StackOverflowError。
 * 只匹配已注册工具名称的 XML 标签，过滤掉 LLM 输出的格式示例标签。
 * <p>
 * 所有方法均为静态、无状态，不依赖 QueryEngine 实例。
 *
 * @see QueryEngine
 */
final class ToolCallParser {

    private static final Logger log = LoggerFactory.getLogger(ToolCallParser.class);

    private ToolCallParser() {}

    /**
     * 解析 LLM 响应中的工具调用。
     * <p>
     * 使用纯字符串 indexOf 线性扫描，O(N) 时间复杂度，绝不触发 StackOverflowError。
     * 只匹配已注册工具名称的 XML 标签，过滤掉 LLM 输出的格式示例标签。
     * <p>
     * 匹配格式：{@code <tool_name>JSON params</tool_name>}
     */
    static List<ToolCall> parseToolCalls(String response, Set<String> registeredToolNames) {
        List<ToolCall> calls = new ArrayList<>();
        int searchStart = 0;
        int len = response.length();

        while (searchStart < len) {
            // ── 查找工具块起点 ──
            // 支持多种格式：<tool_call>, <function=xxx>, <tool_name>
            int blockStart = -1;
            String blockType = null; // "tool_call" | "function=" | "direct_tag"

            // 1. 查找 <tool_call> 块
            int tcIdx = response.indexOf("<tool_call>", searchStart);
            // 2. 查找 <function=xxx> 格式
            int fnIdx = response.indexOf("<function=", searchStart);

            // 取最早出现的
            if (tcIdx >= 0 && (fnIdx < 0 || tcIdx <= fnIdx)) {
                blockStart = tcIdx;
                blockType = "tool_call";
            } else if (fnIdx >= 0) {
                blockStart = fnIdx;
                blockType = "function=";
            }

            if (blockStart < 0) {
                // 3. 没有工具块标记，尝试直接查找已注册工具名标签
                //    如 <glob>...</glob>, <bash>...</bash>
                int tagStart = findToolTagStart(response, searchStart, registeredToolNames);
                if (tagStart < 0) break;

                // 提取标签名
                int tagEnd = response.indexOf('>', tagStart + 1);
                if (tagEnd < 0) { searchStart = tagStart + 1; continue; }

                String tagName = response.substring(tagStart + 1, tagEnd).trim();
                // 清理标签名中的空白和换行
                tagName = cleanTagName(tagName);
                if (tagName.isEmpty()) { searchStart = tagEnd + 1; continue; }

                // 查找闭合标签
                String closeTag = "</" + tagName + ">";
                int closeIdx = response.indexOf(closeTag, tagEnd + 1);
                if (closeIdx < 0) { searchStart = tagEnd + 1; continue; }

                String params = response.substring(tagEnd + 1, closeIdx).trim();
                calls.add(new ToolCall(tagName, params));
                searchStart = closeIdx + closeTag.length();
                continue;
            }

            // ── 处理 <tool_call> 块 ──
            if ("tool_call".equals(blockType)) {
                int contentStart = blockStart + "<tool_call>".length();
                int blockEnd = findCloseTag(response, contentStart, "tool_call");
                if (blockEnd < 0) { searchStart = contentStart; continue; }

                String blockContent = response.substring(contentStart, blockEnd).trim();
                ToolCall tc = parseToolCallContent(blockContent, registeredToolNames);
                if (tc != null) calls.add(tc);

                searchStart = blockEnd + "</tool_call>".length();
                continue;
            }

            // ── 处理 <function=xxx> 格式 ──
            if ("function=".equals(blockType)) {
                int eqIdx = response.indexOf('=', blockStart);
                int tagEnd = response.indexOf('>', eqIdx + 1);
                if (tagEnd < 0) { searchStart = blockStart + 1; continue; }

                String funcName = response.substring(eqIdx + 1, tagEnd).trim();
                funcName = cleanTagName(funcName);

                // 查找闭合标签 </function>
                int closeIdx = response.indexOf("</function>", tagEnd + 1);
                if (closeIdx < 0) {
                    // 也可能用 </function=xxx> 闭合
                    closeIdx = response.indexOf("</function=", tagEnd + 1);
                    if (closeIdx < 0) { searchStart = tagEnd + 1; continue; }
                    // 跳过闭合标签
                    int closeTagEnd = response.indexOf('>', closeIdx);
                    if (closeTagEnd < 0) { searchStart = tagEnd + 1; continue; }

                    String params = response.substring(tagEnd + 1, closeIdx).trim();
                    if (registeredToolNames.contains(funcName)) {
                        calls.add(new ToolCall(funcName, params));
                    }
                    searchStart = closeTagEnd + 1;
                } else {
                    String params = response.substring(tagEnd + 1, closeIdx).trim();
                    if (registeredToolNames.contains(funcName)) {
                        calls.add(new ToolCall(funcName, params));
                    }
                    searchStart = closeIdx + "</function>".length();
                }
                continue;
            }

            // 安全推进
            searchStart = blockStart + 1;
        }

        return calls;
    }

    /**
     * 解析 <tool_call> 块内的内容，提取工具名和参数。
     * <p>
     * 支持的内部格式：
     * <ul>
     *   <li>function=glob with parameter=pattern</li>
     *   <li>JSON format with name and arguments</li>
     *   <li>Direct tool name tags</li>
     * </ul>
     */
    static ToolCall parseToolCallContent(String blockContent, Set<String> registeredToolNames) {
        // 格式 1：<function=xxx><parameter=yyy>value</parameter></function>
        int fnIdx = blockContent.indexOf("<function=");
        if (fnIdx >= 0) {
            int eqIdx = fnIdx + 10; // skip "<function="
            int tagEnd = blockContent.indexOf('>', eqIdx);
            if (tagEnd >= 0) {
                String funcName = blockContent.substring(eqIdx, tagEnd).trim();
                funcName = cleanTagName(funcName);

                if (!registeredToolNames.contains(funcName)) return null;

                // 提取参数
                StringBuilder params = new StringBuilder();
                int paramSearchStart = tagEnd + 1;
                while (paramSearchStart < blockContent.length()) {
                    int paramStart = blockContent.indexOf("<parameter=", paramSearchStart);
                    if (paramStart < 0) break;

                    int paramEqIdx = paramStart + 11; // skip "<parameter="
                    int paramTagEnd = blockContent.indexOf('>', paramEqIdx);
                    if (paramTagEnd < 0) break;

                    String paramName = blockContent.substring(paramEqIdx, paramTagEnd).trim();
                    paramName = cleanTagName(paramName);

                    int paramCloseIdx = blockContent.indexOf("</parameter>", paramTagEnd + 1);
                    if (paramCloseIdx < 0) break;

                    String paramValue = blockContent.substring(paramTagEnd + 1, paramCloseIdx).trim();

                    // 构建 JSON 参数
                    if (!params.isEmpty()) params.append(",");
                    params.append("\"").append(escapeJsonString(paramName)).append("\":")
                          .append("\"").append(escapeJsonString(paramValue)).append("\"");

                    paramSearchStart = paramCloseIdx + "</parameter>".length();
                }

                String paramsJson = params.isEmpty() ? "{}" : "{" + params + "}";
                return new ToolCall(funcName, paramsJson);
            }
        }

        // 格式 2：JSON 格式 {"name": "xxx", "arguments": {...}}
        int jsonStart = blockContent.indexOf('{');
        if (jsonStart >= 0) {
            String json = extractCompleteJsonObject(blockContent, jsonStart);
            if (json != null) {
                // 尝试从 JSON 中提取 name 和 arguments
                String name = extractJsonFieldValue(json, "name");
                String args = extractJsonFieldValue(json, "arguments");
                if (name != null && registeredToolNames.contains(name)) {
                    return new ToolCall(name, args != null ? args : "{}");
                }
                // 也可能是 "function" 字段
                String func = extractJsonFieldValue(json, "function");
                if (func != null && registeredToolNames.contains(func)) {
                    return new ToolCall(func, args != null ? args : "{}");
                }
            }
        }

        // 格式 3：内部直接包含已注册工具名标签
        for (String toolName : registeredToolNames) {
            String openTag = "<" + toolName + ">";
            int idx = blockContent.indexOf(openTag);
            if (idx >= 0) {
                int contentStart = idx + openTag.length();
                String closeTag = "</" + toolName + ">";
                int closeIdx = blockContent.indexOf(closeTag, contentStart);
                String params = closeIdx >= 0
                        ? blockContent.substring(contentStart, closeIdx).trim()
                        : blockContent.substring(contentStart).trim();
                return new ToolCall(toolName, params);
            }
        }

        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  鲁棒字符串扫描辅助方法 — O(N) indexOf，绝不使用 Regex
    // ════════════════════════════════════════════════════════════════

    /** 清理标签名中的空白、换行和非法字符 */
    static String cleanTagName(String tagName) {
        if (tagName == null) return "";
        // 去除所有空白字符（空格、换行、制表符等）
        StringBuilder sb = new StringBuilder(tagName.length());
        for (int i = 0; i < tagName.length(); i++) {
            char c = tagName.charAt(i);
            if (!Character.isWhitespace(c) && c != '/' && c != '<' && c != '>') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** 查找已注册工具名对应的标签起始位置 */
    static int findToolTagStart(String text, int searchStart, Set<String> toolNames) {
        int earliest = -1;
        for (String name : toolNames) {
            int idx = text.indexOf('<' + name + '>', searchStart);
            if (idx >= 0 && (earliest < 0 || idx < earliest)) {
                earliest = idx;
            }
        }
        return earliest;
    }

    /** 查找闭合标签位置，容忍标签前后的空白 */
    static int findCloseTag(String text, int searchStart, String tagName) {
        String closeTag = "</" + tagName + ">";
        return text.indexOf(closeTag, searchStart);
    }

    /** 从指定位置提取完整的 JSON 对象（花括号匹配，引号感知） */
    static String extractCompleteJsonObject(String text, int startIdx) {
        if (startIdx >= text.length() || text.charAt(startIdx) != '{') return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int pos = startIdx;

        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (escape) {
                escape = false;
            } else if (c == '\\' && inString) {
                escape = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(startIdx, pos + 1);
                    }
                }
            }
            pos++;
        }
        return null;
    }

    /** 从 JSON 字符串中提取指定字段的值（简单线性扫描，不依赖正则） */
    static String extractJsonFieldValue(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;

        // 找到冒号
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;

        // 跳过冒号后的空白
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length()) return null;

        char firstChar = json.charAt(valueStart);
        if (firstChar == '"') {
            // 字符串值
            int endIdx = json.indexOf('"', valueStart + 1);
            // 处理转义引号
            while (endIdx > 0 && json.charAt(endIdx - 1) == '\\') {
                endIdx = json.indexOf('"', endIdx + 1);
            }
            if (endIdx < 0) return null;
            return json.substring(valueStart + 1, endIdx);
        } else if (firstChar == '{' || firstChar == '[') {
            // 对象或数组值 — 使用括号匹配
            return extractCompleteJsonObject(json, valueStart);
        } else {
            // 数字、布尔值等
            int valueEnd = valueStart + 1;
            while (valueEnd < json.length() && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}') {
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd).trim();
        }
    }

    /** JSON 字符串转义 */
    static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
