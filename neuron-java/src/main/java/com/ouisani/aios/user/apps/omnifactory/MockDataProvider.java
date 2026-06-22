package com.ouisani.aios.user.apps.omnifactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 数据提供者 — 为部分执行 (Partial Execution) 提供上游节点的 Mock 输出。
 * <p>
 * 当 ExecutionMode 为 EXECUTE_SINGLE_NODE 时，目标节点的所有上游依赖
 * 不会真实执行，而是通过此提供者注入 Mock JSON 数据到 WorkflowContext。
 * <p>
 * <h3>工作流程</h3>
 * <pre>
 *   1. 前端 (aios-workflow-ui) 用户在节点详情面板填写 Mock 数据
 *   2. MockDataProvider.injectMockData(context, mockNodeIds) 注入到 WorkflowContext
 *   3. 当目标 Agent 获取前置依赖时，它不知道数据是 Mock 的还是真实跑出来的
 * </pre>
 * <p>
 * <h3>Mock 数据格式</h3>
 * <pre>
 *   {
 *     "node_id": "search_node_01",
 *     "output": {
 *       "result": "mock search result",
 *       "count": 42,
 *       "status": "success"
 *     }
 *   }
 * </pre>
 * <p>
 * OS 类比：相当于 Linux 的 /dev/null + ftrace 注入 — 用假数据替代真实 I/O。
 *
 * @see PartialExecutionPlanner
 * @see WorkflowContext
 */
public class MockDataProvider {

    /** Mock 数据存储: nodeId → Mock 输出 */
    private final Map<String, Map<String, Object>> mockDataMap = new ConcurrentHashMap<>();

    /** 标记哪些节点使用了 Mock 数据（供审计） */
    private final Set<String> mockedNodes = ConcurrentHashMap.newKeySet();

    /**
     * 注册 Mock 数据 — 为指定节点设置 Mock 输出。
     *
     * @param nodeId   节点 ID
     * @param mockData Mock 输出数据
     */
    public void registerMockData(String nodeId, Map<String, Object> mockData) {
        mockDataMap.put(nodeId, new HashMap<>(mockData));
    }

    /**
     * 注册 Mock 数据 — 从 JSON 字符串解析。
     *
     * @param nodeId   节点 ID
     * @param jsonMock JSON 格式的 Mock 数据
     */
    public void registerMockDataFromJson(String nodeId, String jsonMock) {
        Map<String, Object> data = parseSimpleJson(jsonMock);
        registerMockData(nodeId, data);
    }

    /**
     * 注入 Mock 数据到 WorkflowContext — 将所有已注册的 Mock 数据
     * 写入 context 的 globalMemory，使后续节点可以正常读取上游输出。
     *
     * @param context     工作流上下文
     * @param mockNodeIds 需要 Mock 的节点 ID 集合
     */
    public void injectMockData(WorkflowContext context, Set<String> mockNodeIds) {
        for (String nodeId : mockNodeIds) {
            Map<String, Object> mockOutput = mockDataMap.get(nodeId);
            if (mockOutput != null) {
                context.commitNodeOutput(nodeId, mockOutput);
                mockedNodes.add(nodeId);
            } else {
                // 没有注册 Mock 数据的节点，注入空输出
                context.commitNodeOutput(nodeId, Map.of("mocked", true, "output", "(no mock data provided)"));
                mockedNodes.add(nodeId);
            }
        }
    }

    /**
     * 检查指定节点是否使用了 Mock 数据。
     */
    public boolean isMocked(String nodeId) {
        return mockedNodes.contains(nodeId);
    }

    /**
     * 获取所有使用了 Mock 数据的节点 ID。
     */
    public Set<String> getMockedNodes() {
        return mockedNodes;
    }

    /**
     * 清除所有 Mock 数据。
     */
    public void clear() {
        mockDataMap.clear();
        mockedNodes.clear();
    }

    /**
     * 获取 Mock 数据的 JSON 表示 — 供前端回显。
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"mockData\":[");
        boolean first = true;
        for (Map.Entry<String, Map<String, Object>> entry : mockDataMap.entrySet()) {
            if (!first) sb.append(",");
            sb.append("{\"nodeId\":\"").append(entry.getKey()).append("\",\"output\":");
            sb.append(mapToJson(entry.getValue()));
            sb.append("}");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  内部工具方法
    // ════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSimpleJson(String json) {
        // 简易 JSON 解析 — 避免引入 JSON 库依赖
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isBlank()) return result;

        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1).trim();
        }

        // 按逗号分割键值对（不处理嵌套对象）
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : json.toCharArray()) {
            if (c == '{' || c == '[') depth++;
            if (c == '}' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                parseKeyValuePair(current.toString().trim(), result);
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parseKeyValuePair(current.toString().trim(), result);
        }

        return result;
    }

    private static void parseKeyValuePair(String pair, Map<String, Object> result) {
        int colonIdx = pair.indexOf(':');
        if (colonIdx < 0) return;

        String key = pair.substring(0, colonIdx).trim();
        String value = pair.substring(colonIdx + 1).trim();

        // 去除引号
        if (key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            result.put(key, value.substring(1, value.length() - 1));
        } else {
            // 尝试解析为数字或布尔
            try {
                if (value.contains(".")) {
                    result.put(key, Double.parseDouble(value));
                } else {
                    result.put(key, Integer.parseInt(value));
                }
            } catch (NumberFormatException e) {
                if ("true".equalsIgnoreCase(value)) {
                    result.put(key, true);
                } else if ("false".equalsIgnoreCase(value)) {
                    result.put(key, false);
                } else {
                    result.put(key, value);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object v = entry.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof String s) {
                sb.append("\"").append(s.replace("\"", "\\\"")).append("\"");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof Map) {
                sb.append(mapToJson((Map<String, Object>) v));
            } else {
                sb.append("\"").append(v.toString().replace("\"", "\\\"")).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
