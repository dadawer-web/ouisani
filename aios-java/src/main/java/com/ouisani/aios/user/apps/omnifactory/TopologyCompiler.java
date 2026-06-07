package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图转拓扑编译器 — 将用户自然语言需求编译为事件驱动的智能体网格。
 * <p>
 * OS 类比：相当于编译器前端 (Frontend) — 将人类可读的"意图源码"编译为
 * 内核可执行的"拓扑 IR (Intermediate Representation)"。
 * <p>
 * 编译管线：
 * <pre>
 *   用户自然语言
 *     │
 *     ▼  LLM 编译
 *   拓扑 JSON (原始)
 *     │
 *     ▼  解析 + 蓝图校验
 *   WorkflowManifest (合法 IR)
 *     │
 *     ▼  WorkflowEngine.executeWorkflow()
 *   运行中的 Agent 网格
 * </pre>
 * <p>
 * 蓝图校验器：当拓扑中引用了 registry 中不存在的 blueprintId 时，
 * 编译器会自动触发 LLM 生成缺失的蓝图并注册，实现"按需补全"。
 *
 * @see WorkflowManifest
 * @see WorkflowEngine
 * @see AgentBlueprint
 */
public class TopologyCompiler {

    private static final Logger log = LoggerFactory.getLogger(TopologyCompiler.class);

    private static final class Holder {
        static final TopologyCompiler INSTANCE = new TopologyCompiler();
    }

    public static TopologyCompiler getInstance() {
        return Holder.INSTANCE;
    }

    /** 蓝图注册表 — blueprintId → AgentBlueprint */
    private final Map<String, AgentBlueprint> blueprintRegistry = new HashMap<>();

    private TopologyCompiler() {
        log.info("[OmniFactory] Topology Compiler initialized.");
    }

    /**
     * 注册蓝图到编译器的蓝图注册表。
     *
     * @param blueprint 要注册的蓝图
     */
    public void registerBlueprint(AgentBlueprint blueprint) {
        blueprintRegistry.put(blueprint.blueprintId(), blueprint);
        log.info("[OmniFactory] Blueprint registered: '{}'", blueprint.blueprintId());
    }

    /**
     * 批量注册蓝图。
     */
    public void registerBlueprints(Map<String, AgentBlueprint> blueprints) {
        blueprintRegistry.putAll(blueprints);
        log.info("[OmniFactory] {} blueprints registered. Total: {}", blueprints.size(), blueprintRegistry.size());
    }

    /**
     * 获取蓝图注册表（只读视图）。
     */
    public Map<String, AgentBlueprint> getBlueprintRegistry() {
        return Collections.unmodifiableMap(blueprintRegistry);
    }

    /**
     * 核心编译逻辑 — 将用户自然语言需求编译为 WorkflowManifest。
     * <p>
     * 编译步骤：
     * <ol>
     *   <li>调用 LLM 将用户意图解析为拓扑 JSON</li>
     *   <li>正则解析 JSON 为 WorkflowNode 列表</li>
     *   <li>校验蓝图引用，自动补全缺失蓝图</li>
     *   <li>构建 WorkflowManifest 并部署</li>
     * </ol>
     *
     * @param userRequest 用户自然语言需求
     * @return 编译生成的工作流清单
     */
    public WorkflowManifest compile(String userRequest) {
        System.out.printf("[OmniFactory] TopologyCompiler: Compiling user intent...%n");
        System.out.printf("[OmniFactory]   User Request: %s%n", userRequest);
        log.info("[OmniFactory] TopologyCompiler: Compiling user intent: {}", userRequest);

        AiosSdk sdk = AiosSdk.getInstance();

        // ── Step 1: LLM 编译 — 将用户意图解析为拓扑 JSON ──
        String compilePrompt = "你是一个编排工程师。请分析用户需求 [" + userRequest
                + "]，并将其解析为工作流拓扑结构 JSON。"
                + "请严格输出一个 JSON 数组，格式为："
                + "[{\"instanceId\":\"(英文小写实例ID，如 fetcher_1)\", "
                + "\"blueprintId\":\"(蓝图类型ID，如 spider_agent)\", "
                + "\"userParams\":{\"参数名\":\"参数值\"}, "
                + "\"subscribeTopic\":\"(上游事件topic，源头为空)\", "
                + "\"publishTopic\":\"(当前节点输出topic)\"}]"
                + "。只输出 JSON，不要其他文字。";

        String topologyJson = sdk.think("topology_compiler", compilePrompt);
        System.out.printf("[OmniFactory]   Topology JSON received (%d chars).%n", topologyJson.length());
        log.debug("[OmniFactory] Topology JSON: {}", topologyJson);

        // ── Step 2: 解析 JSON 为 WorkflowNode 列表 ──
        List<WorkflowNode> nodes = parseTopologyJson(topologyJson);
        System.out.printf("[OmniFactory]   Parsed %d workflow nodes.%n", nodes.size());
        log.info("[OmniFactory] Parsed {} workflow nodes from LLM output.", nodes.size());

        if (nodes.isEmpty()) {
            System.out.println("[OmniFactory]   ⚠ No nodes parsed. Returning empty manifest.");
            log.warn("[OmniFactory] Topology parsing yielded 0 nodes.");
            return new WorkflowManifest("empty_workflow", List.of());
        }

        // ── Step 3: 构建 WorkflowManifest ──
        String workflowName = "wf_" + (userRequest.hashCode() & 0x7FFFFFFF);
        WorkflowManifest manifest = new WorkflowManifest(workflowName, nodes);

        // ── Step 4: 蓝图校验 + 自动补全 ──
        validate(manifest);

        // ── Step 5: 部署 ──
        WorkflowEngine.getInstance().executeWorkflow(manifest, blueprintRegistry);

        System.out.println("[OmniFactory] Topology Compiler engaged. "
                + "User intent successfully compiled into event-driven agent mesh.");
        log.info("[OmniFactory] Topology compilation complete. Workflow '{}' deployed with {} nodes.",
                workflowName, nodes.size());

        return manifest;
    }

    /**
     * 蓝图校验器 — 遍历节点，检查 blueprintId 是否在注册表中存在。
     * <p>
     * 如果某个 blueprintId 不存在，自动触发 LLM 生成该类型的蓝图并注册。
     * 这实现了"按需补全"：用户可以引用尚未定义的蓝图类型，
     * 编译器会自动让母体智能体补全它。
     *
     * @param manifest 待校验的工作流清单
     */
    public void validate(WorkflowManifest manifest) {
        List<String> missingBlueprints = new ArrayList<>();

        // 第一遍：收集缺失的 blueprintId
        for (WorkflowNode node : manifest.nodes()) {
            if (!blueprintRegistry.containsKey(node.blueprintId())) {
                missingBlueprints.add(node.blueprintId());
            }
        }

        if (missingBlueprints.isEmpty()) {
            System.out.printf("[OmniFactory]   ✓ Blueprint validation passed. All %d blueprint(s) found.%n",
                    manifest.nodes().size());
            log.info("[OmniFactory] Blueprint validation passed for workflow '{}'.", manifest.workflowName());
            return;
        }

        // 第二遍：自动补全缺失蓝图
        System.out.printf("[OmniFactory]   ⚠ Missing %d blueprint(s): %s. Auto-generating...%n",
                missingBlueprints.size(), missingBlueprints);
        log.warn("[OmniFactory] Missing blueprints: {}. Auto-generating via LLM.", missingBlueprints);

        AiosSdk sdk = AiosSdk.getInstance();

        for (String missingId : missingBlueprints) {
            System.out.printf("[OmniFactory]     ├─ Auto-generating blueprint '%s'...%n", missingId);

            // 收集引用该 blueprintId 的所有节点，推断蓝图职责
            String inferredRole = inferRoleFromNodes(missingId, manifest.nodes());

            String genPrompt = "你是一个系统架构师。请为名为 '" + missingId + "' 的智能体蓝图生成执行代码。"
                    + "该智能体的职责是：" + inferredRole + "。"
                    + "请编写一段稳定的 Python 代码来实现这个职责。"
                    + "代码应通过 os.getenv('SUBSCRIBE_TOPIC') 读取上游数据，"
                    + "通过标准输出打印 TOPIC:<topic_name> <data> 格式的结果。"
                    + "只输出纯代码，不要 Markdown 标记。";

            String code = sdk.think("topology_compiler", genPrompt);

            // 推断该蓝图需要的参数
            Set<String> paramSet = new LinkedHashSet<>();
            for (WorkflowNode node : manifest.nodes()) {
                if (node.blueprintId().equals(missingId)) {
                    paramSet.addAll(node.userParams().keySet());
                }
            }

            AgentBlueprint newBlueprint = new AgentBlueprint(
                    missingId,
                    inferredRole,
                    code,
                    List.copyOf(paramSet)
            );

            blueprintRegistry.put(missingId, newBlueprint);
            System.out.printf("[OmniFactory]     │  Blueprint '%s' auto-generated: code=%d chars, params=%s%n",
                    missingId, code.length(), paramSet);
            log.info("[OmniFactory] Blueprint '{}' auto-generated: {} chars", missingId, code.length());
        }

        System.out.printf("[OmniFactory]   ✓ All missing blueprints auto-generated. Registry size: %d%n",
                blueprintRegistry.size());
    }

    /**
     * 从引用同一 blueprintId 的节点中推断蓝图职责描述。
     */
    private String inferRoleFromNodes(String blueprintId, List<WorkflowNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (WorkflowNode node : nodes) {
            if (node.blueprintId().equals(blueprintId)) {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append("实例 ").append(node.instanceId());
                if (!node.subscribeTopic().isEmpty()) {
                    sb.append(" 订阅 ").append(node.subscribeTopic());
                }
                if (!node.publishTopic().isEmpty()) {
                    sb.append(" 发布到 ").append(node.publishTopic());
                }
                if (!node.userParams().isEmpty()) {
                    sb.append(" 参数=").append(node.userParams());
                }
            }
        }
        return sb.isEmpty() ? blueprintId + " 智能体" : sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  Topology JSON Parser — 正则提取，兼容 LLM 输出
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 LLM 返回的拓扑 JSON 数组中提取 WorkflowNode 列表。
     */
    private List<WorkflowNode> parseTopologyJson(String json) {
        List<WorkflowNode> nodes = new ArrayList<>();

        // 去除 Markdown 代码块包裹
        String cleaned = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        // 匹配每个 JSON 对象 {...}（支持嵌套一层 userParams 对象）
        Pattern objectPattern = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}");
        Matcher objectMatcher = objectPattern.matcher(cleaned);

        while (objectMatcher.find()) {
            String obj = objectMatcher.group();

            String instanceId = extractJsonValue(obj, "instanceId");
            String blueprintId = extractJsonValue(obj, "blueprintId");
            String role = extractJsonValue(obj, "role");
            String subscribeTopic = extractJsonValue(obj, "subscribeTopic");
            String publishTopic = extractJsonValue(obj, "publishTopic");

            // 解析 userParams 对象
            Map<String, String> userParams = extractUserParams(obj);

            if (instanceId != null && !instanceId.isBlank() && blueprintId != null && !blueprintId.isBlank()) {
                nodes.add(new WorkflowNode(
                        instanceId.trim(),
                        role != null ? role.trim() : "",
                        blueprintId.trim(),
                        userParams,
                        subscribeTopic != null ? subscribeTopic.trim() : "",
                        publishTopic != null ? publishTopic.trim() : ""
                ));
            }
        }

        return nodes;
    }

    /**
     * 从 JSON 对象中提取 userParams 字典。
     * <p>
     * 匹配 "userParams":{"key1":"val1","key2":"val2"} 格式。
     */
    private Map<String, String> extractUserParams(String jsonObj) {
        Map<String, String> params = new LinkedHashMap<>();

        // 先提取 userParams 对象的内容
        Pattern paramsPattern = Pattern.compile("\"userParams\"\\s*:\\s*\\{([^}]*)\\}");
        Matcher paramsMatcher = paramsPattern.matcher(jsonObj);
        if (!paramsMatcher.find()) return params;

        String paramsContent = paramsMatcher.group(1);

        // 逐个提取 key:value 对
        Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher kvMatcher = kvPattern.matcher(paramsContent);
        while (kvMatcher.find()) {
            params.put(kvMatcher.group(1), kvMatcher.group(2));
        }

        return params;
    }

    /**
     * 从 JSON 对象中提取指定 key 的字符串值。
     */
    private String extractJsonValue(String jsonObj, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = p.matcher(jsonObj);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
