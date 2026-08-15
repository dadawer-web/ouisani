package com.ouisani.aios.core.network;

import com.ouisani.aios.user.apps.omnifactory.WorkflowManifest;
import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网关 JSON 解析器 — 从 AppGateway 抽取的深度感知 JSON 工具集。
 * <p>
 * 寄居在 core/network 包，紧邻 AppGateway 复用。
 * user 态（如 TopologyCompiler）通过本类调用 JSON 工具，依赖方向保持 user → core。
 * <p>
 * OS 类比：Linux 内核的 JSON 解析器 (json-c) — 独立工具库，被多个子系统复用。
 */
public final class GatewayJsonParser {

    private GatewayJsonParser() {}

    /**
     * 将前端传来的 JSON 解析为 WorkflowManifest。
     * <p>
     * 预期格式：
     * <pre>
     * {
     *   "workflowName": "my_workflow",
     *   "nodes": [
     *     {
     *       "instanceId": "agent_1",
     *       "blueprintId": "spider_agent",
     *       "role": "爬取数据",
     *       "subscribeTopic": "",
     *       "publishTopic": "topic_agent_1_agent_2",
     *       "userParams": {}
     *     }
     *   ]
     * }
     * </pre>
     */
    public static WorkflowManifest parseWorkflowManifest(String json) {
        String workflowName = extractJsonField(json, "workflowName");
        if (workflowName == null || workflowName.isBlank()) {
            workflowName = "dashboard_workflow";
        }

        // 提取 nodes 数组部分
        String nodesArray = extractJsonArray(json, "nodes");
        if (nodesArray == null || nodesArray.isBlank()) {
            throw new IllegalArgumentException("负载中缺少 'nodes' 数组或数组为空");
        }

        // 逐个解析节点对象 — 使用安全的深度感知分割器
        List<WorkflowNode> nodes = new ArrayList<>();
        List<String> rawNodes = splitJsonObjectsSafe(nodesArray);
        for (String obj : rawNodes) {
            String instanceId = extractJsonField(obj, "instanceId");
            String blueprintId = extractJsonField(obj, "blueprintId");
            String role = extractJsonField(obj, "role");
            String executor = extractJsonField(obj, "executor");
            String subscribeTopic = extractJsonField(obj, "subscribeTopic");
            String publishTopic = extractJsonField(obj, "publishTopic");
            Map<String, String> userParams = extractUserParams(obj);

            if (instanceId != null && !instanceId.isBlank()) {
                WorkflowNode node = new WorkflowNode(
                        instanceId.trim(),
                        role != null ? role.trim() : "",
                        blueprintId != null ? blueprintId.trim() : instanceId.trim(),
                        userParams,
                        subscribeTopic != null ? subscribeTopic.trim() : "",
                        publishTopic != null ? publishTopic.trim() : "",
                        executor != null ? executor.trim() : "omni"
                );

                // 解析 upstreamDependencies — 决定哪些节点可以并发执行
                String depsArray = extractJsonArray(obj, "upstreamDependencies");
                if (depsArray != null && !depsArray.isBlank()) {
                    for (String dep : depsArray.split(",")) {
                        String trimmed = dep.trim().replaceAll("[\"\\[\\]\\s]", "");
                        if (!trimmed.isEmpty()) {
                            node.addDependency(trimmed);
                        }
                    }
                }

                nodes.add(node);
            }
        }

        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("负载中未找到有效节点");
        }

        // 解析 enabledSkills / enabledRoles 数组
        List<String> enabledSkills = extractJsonStringArray(json, "enabledSkills");
        List<String> enabledRoles = extractJsonStringArray(json, "enabledRoles");

        // 解析 agentType（默认 "omni"）
        String agentType = extractJsonField(json, "agentType");
        if (agentType == null || agentType.isBlank()) agentType = "omni";

        String missionId = extractJsonField(json, "missionId");
        return new WorkflowManifest(workflowName, nodes, enabledSkills, enabledRoles, agentType, List.of(), null, missionId);
    }

    /**
     * 从 JSON 中提取指定 key 对应的字符串数组。
     * <p>
     * 例如：{"enabledSkills": ["skills.web_scraper", "skills.file_ops"]}
     */
    public static List<String> extractJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String arrayContent = extractJsonArray(json, key);
        if (arrayContent == null || arrayContent.isBlank()) return result;

        Pattern stringPattern = Pattern.compile("\"([^\"]+)\"");
        Matcher matcher = stringPattern.matcher(arrayContent);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * 从 JSON 中提取指定 key 的字符串值。
     */
    public static String extractJsonField(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        // 尝试无引号格式
        Pattern rawP = Pattern.compile("\"" + key + "\"\\s*:\\s*([^,}\\s]+)");
        Matcher rawM = rawP.matcher(json);
        if (rawM.find()) return rawM.group(1).trim();
        return null;
    }

    /**
     * 从 JSON 中提取指定 key 对应的对象内容（含花括号）。
     * 用于提取 HOT_PATCH_PARAM 中的 params 字段。
     */
    public static String extractJsonObject(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        int start = m.start() + m.group().length() - 1, depth = 0, pos = start;
        boolean inStr = false, esc = false;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (esc) esc = false;
            else if (c == '\\') esc = true;
            else if (c == '"') inStr = !inStr;
            else if (!inStr) {
                if (c == '{') depth++; else if (c == '}') {
                    depth--; if (depth == 0) return json.substring(start, pos + 1);
                }
            }
            pos++;
        }
        return null;
    }

    /**
     * 从 JSON 中提取指定 key 对应的数组内容（不含方括号）。
     */
    public static String extractJsonArray(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        int start = m.end(), depth = 1, pos = start;
        boolean inStr = false, esc = false;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (esc) esc = false;
            else if (c == '\\') esc = true;
            else if (c == '"') inStr = !inStr;
            else if (!inStr) {
                if (c == '[') depth++; else if (c == ']') depth--;
            }
            pos++;
        }
        return depth == 0 ? json.substring(start, pos - 1) : null;
    }

    /**
     * 从 JSON 对象中提取 userParams 字典。
     */
    public static Map<String, String> extractUserParams(String jsonObj) {
        Map<String, String> params = new LinkedHashMap<>();
        String content = extractJsonObject(jsonObj, "userParams");
        if (content == null || content.length() < 2) return params;
        content = content.substring(1, content.length() - 1);
        Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher kvMatcher = kvPattern.matcher(content);
        while (kvMatcher.find()) {
            params.put(kvMatcher.group(1), kvMatcher.group(2));
        }
        return params;
    }

    /**
     * 安全分割 JSON 数组中的对象 — 无视字符串内的花括号干扰。
     * <p>
     * 使用深度感知的状态机遍历，正确处理转义字符和字符串内的花括号，
     * 避免正则表达式在嵌套 JSON 或字符串含花括号时误匹配。
     */
    public static List<String> splitJsonObjectsSafe(String jsonArrayInner) {
        List<String> objects = new ArrayList<>();
        if (jsonArrayInner == null) return objects;
        int depth = 0, objStart = -1;
        boolean inString = false, escape = false;
        for (int pos = 0; pos < jsonArrayInner.length(); pos++) {
            char c = jsonArrayInner.charAt(pos);
            if (escape) { escape = false; }
            else if (c == '\\') { escape = true; }
            else if (c == '"') { inString = !inString; }
            else if (!inString) {
                if (c == '{') {
                    if (depth == 0) objStart = pos;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart != -1) {
                        objects.add(jsonArrayInner.substring(objStart, pos + 1));
                        objStart = -1;
                    }
                }
            }
        }
        return objects;
    }
}
