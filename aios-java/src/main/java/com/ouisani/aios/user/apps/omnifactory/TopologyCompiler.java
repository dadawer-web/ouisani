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

    /** 角色卡物理目录 */
    private static final String ROLES_DIR = "/home/xmy/tryaios/aios-java/aios_roles";
    /** 技能库物理目录 */
    private static final String SKILLS_DIR = "/home/xmy/tryaios/aios-java/aios_skills";

    private static final class Holder {
        static final TopologyCompiler INSTANCE = new TopologyCompiler();
    }

    public static TopologyCompiler getInstance() {
        return Holder.INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════
    //  两段式生成 — 第一段：动态拓扑编译 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 两段式生成 — 第一段：根据用户需求动态编译 DAG 拓扑图。
     * <p>
     * 读取架构师角色卡 + 可用技能说明，构建专用 LLM 提示词，
     * 要求 LLM 返回纯 JSON 格式的 DAG 拓扑（nodes + edges），
     * 供前端 React Flow 画布直接渲染。
     *
     * @param prompt        用户自然语言需求
     * @param enabledSkills 按需装载的技能模块列表（如 ["skills.web_scraper"]）
     * @param enabledRoles  按需装载的角色列表（如 ["System_Architect"]）
     * @return 纯 JSON 字符串，格式为 {"nodes": [...], "edges": [...]}
     */
    public static String compileTopology(String prompt, List<String> enabledSkills, List<String> enabledRoles) {
        log.info("[TopologyCompiler] compileTopology called: promptLen={}, skills={}, roles={}",
                prompt.length(), enabledSkills, enabledRoles);

        // 1. 读取架构师角色卡
        String architectRules = readArchitectRules(enabledRoles);

        // 2. 读取可用技能说明
        String skillsContext = readSkillsContext(enabledSkills);

        // 3. 构建强约束 LLM 提示词（修复提示词过短导致的死锁）
        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append("你是一个顶级分布式系统架构师。你的唯一任务是将用户的需求转化为无环有向图(DAG)拓扑。\n\n");

        fullPrompt.append("【用户需求】: ").append(prompt).append("\n\n");

        fullPrompt.append("【架构师强制法则】: \n");
        fullPrompt.append(architectRules).append("\n\n");

        fullPrompt.append("1. 必须根据需求复杂度，动态扇出(Fan-out)细粒度的原子节点。\n");
        fullPrompt.append("2. 只能使用以下授权的 Skills: ").append(
                enabledSkills != null && !enabledSkills.isEmpty()
                        ? String.join(", ", enabledSkills)
                        : "全部可用"
        ).append("\n");
        fullPrompt.append("3. 极其重要：你必须且只能返回纯 JSON 格式数据，绝对不能包含任何 Markdown 标记（如 ```json）或任何开场白/结束语！\n\n");

        fullPrompt.append("【可用技能库 API 字典】:\n").append(skillsContext).append("\n\n");

        fullPrompt.append("【JSON 格式严格范例 (注意节点数必须动态生成)】:\n");
        fullPrompt.append("{ \"nodes\": [ { \"id\": \"agent_1_name\", \"role\": \"职责描述\", \"blueprintId\": \"agentNode\", \"userParams\": {} } ], \"edges\": [ { \"source\": \"agent_1_name\", \"target\": \"agent_2_name\" } ] }\n");

        // 4. 调用 LLM
        AiosSdk sdk = AiosSdk.getInstance();
        String response = sdk.think("topology_compiler", fullPrompt.toString());

        log.debug("[TopologyCompiler] LLM raw response length: {}", response.length());

        // 5. 极其暴力的 JSON 截取清洗，防止大模型废话
        String cleanJson = extractPureJson(response);

        log.info("[TopologyCompiler] compileTopology complete: responseLen={}", cleanJson.length());
        return cleanJson;
    }

    /**
     * 读取架构师角色卡。优先从 enabledRoles 中查找 System_Architect，
     * 如果不存在则读取默认角色卡。
     */
    private static String readArchitectRules(List<String> enabledRoles) {
        // 优先读取 enabledRoles 中指定的角色
        if (enabledRoles != null && !enabledRoles.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String roleName : enabledRoles) {
                String yamlPath = ROLES_DIR + "/" + roleName + ".yaml";
                try {
                    String content = java.nio.file.Files.readString(java.nio.file.Path.of(yamlPath));
                    sb.append("---\n# Role: ").append(roleName).append("\n");
                    sb.append(content.trim()).append("\n---\n\n");
                } catch (Exception e) {
                    log.warn("[TopologyCompiler] Role file not found: {} (skipped)", yamlPath);
                }
            }
            if (!sb.isEmpty()) return sb.toString();
        }

        // 兜底：读取默认 System_Architect.yaml
        String defaultPath = ROLES_DIR + "/System_Architect.yaml";
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(defaultPath));
        } catch (Exception e) {
            log.warn("[TopologyCompiler] Default architect role not found, using minimal rules");
            return "动态粒度原则：根据任务复杂度决定节点数量。I/O 隔离铁律：一个数据源一个节点。Scatter-Gather：先并行收集再聚合。";
        }
    }

    /**
     * 读取可用技能说明。从 MANIFEST.md 中提取 enabledSkills 对应的段落。
     */
    private static String readSkillsContext(List<String> enabledSkills) {
        String manifestPath = SKILLS_DIR + "/MANIFEST.md";
        try {
            String fullManifest = java.nio.file.Files.readString(java.nio.file.Path.of(manifestPath));

            // 如果 enabledSkills 为空，返回全量
            if (enabledSkills == null || enabledSkills.isEmpty()) {
                return fullManifest;
            }

            // 按需提取对应段落
            StringBuilder sb = new StringBuilder();
            String[] sections = fullManifest.split("(?=^## )", -1);
            for (String section : sections) {
                String trimmed = section.trim();
                if (trimmed.isEmpty()) continue;
                for (String skill : enabledSkills) {
                    if (trimmed.startsWith("## " + skill)) {
                        sb.append(trimmed).append("\n\n");
                        break;
                    }
                }
            }
            return sb.isEmpty() ? fullManifest : sb.toString();
        } catch (Exception e) {
            log.warn("[TopologyCompiler] MANIFEST.md read failed: {}", e.getMessage());
            return "（技能说明读取失败，请使用标准库）";
        }
    }

    /**
     * 构建拓扑编译专用的 System Prompt。
     */
    private static String buildTopologySystemPrompt(String architectRules, String skillsContext) {
        return "你是一个系统架构师。请根据用户的需求，并严格使用提供的 skills，规划出一个高度并行的 DAG 工作流。\n\n"
                + "【架构师法则】\n" + architectRules + "\n\n"
                + "【可用技能库】\n" + skillsContext + "\n\n"
                + "【强制 JSON Schema 契约】\n"
                + "你必须且只能返回如下格式的纯 JSON 数据，严禁包含任何 Markdown 标记或多余的解释文字！\n"
                + "{\n"
                + "  \"nodes\": [\n"
                + "    {\"id\": \"agent_1\", \"role\": \"节点具体职责描述\", \"blueprintId\": \"agentNode\"}\n"
                + "  ],\n"
                + "  \"edges\": [\n"
                + "    {\"source\": \"agent_1\", \"target\": \"agent_2\"}\n"
                + "  ]\n"
                + "}\n\n"
                + "规则：\n"
                + "1. nodes 数组中每个节点必须有 id（如 agent_1_wiki）、role（职责描述）、blueprintId（固定为 agentNode）\n"
                + "2. edges 数组中每条边表示数据流向，source 的输出是 target 的输入\n"
                + "3. 无依赖的并行节点之间不要有 edge\n"
                + "4. 节点数量必须与任务的真实并发需求匹配，简单任务 1-2 个节点，复杂任务按正交性拆分\n"
                + "5. 只输出 JSON，不要任何其他文字！";
    }

    /**
     * 从 LLM 原始输出中提取纯 JSON 字符串。
     * <p>
     * 清理策略：
     * 1. 极其暴力地抹除所有的 &lt;think&gt;...&lt;/think&gt; 思考过程 (支持跨行，兼容 DeepSeek 等推理模型)
     * 2. 去除 ```json ... ``` Markdown 代码块包裹
     * 3. 提取第一个 { 到最后一个 } 之间的内容
     */
    private static String extractPureJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";

        // 1. 极其暴力地抹除所有的 <think>...</think> 思考过程 (支持跨行)
        String cleaned = raw.replaceAll("(?s)<think>.*?</think>", "").trim();

        // 2. 抹除可能的 Markdown 标记
        cleaned = cleaned.replaceAll("```json", "").replaceAll("```", "").trim();

        // 3. 安全截取真正的 JSON 块
        int start = cleaned.indexOf("{");
        int end = cleaned.lastIndexOf("}");
        if (start != -1 && end != -1 && start < end) {
            return cleaned.substring(start, end + 1);
        }

        // 如果什么都找不到，直接原样返回，让前端去报错
        return cleaned;
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
