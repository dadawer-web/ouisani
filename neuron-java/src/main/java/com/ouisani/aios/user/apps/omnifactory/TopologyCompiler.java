package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.network.AppGateway;
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
    private static final String ROLES_DIR = com.ouisani.aios.core.config.AiosPaths.rolesDir();
    /** 技能库物理目录 */
    private static final String SKILLS_DIR = com.ouisani.aios.core.config.AiosPaths.skillsDir();

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
        log.info("[TopologyCompiler] compileTopology 已调用: promptLen={}, skills={}, roles={}",
                prompt.length(), enabledSkills, enabledRoles);

        // 1. 读取架构师角色卡
        String architectRules = readArchitectRules(enabledRoles);

        // 2. 读取可用技能说明
        String skillsContext = readSkillsContext(enabledSkills);

        // 3. 构建 Dify 风格的强约束 LLM 提示词
        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append("""
                你是一个顶级的 AGI 操作系统工作流编译器。你的任务是将用户的自然语言意图，拆解为带有严格依赖关系的有向无环图 (DAG)。

                【核心架构规范】
                1. 节点级动态分发 (Executor):
                   - 对于每个节点，你必须指定最合适的 `executor`。
                   - 填写 "omni"：当任务涉及逻辑推理、代码编写、搜索引擎、爬虫、文件读写、Bash 系统命令等无需物理视觉的任务。
                   - 填写 "operator"：【仅当】任务必须移动真实的物理鼠标、敲击键盘、或调用宿主机 GUI 打开真实软件时使用。
                   - 填写 "external"：当任务需要调用外部成熟 Agent CLI（如 Claude Code、Codex、SWE-agent、Aider）时使用。也可指定具体类型："external:claude-code"、"external:codex"、"external:swe-agent"、"external:aider"。

                2. 内存状态流转与变量引用 (Memory Context):
                   - 节点之间通过内存总线传递数据，而不是写死在硬盘。
                   - 如果下游节点需要使用上游节点的数据，请在下游节点的 `userParams` 中使用 Dify 风格的占位符：`{{上游节点ID.变量名}}`。
                   - 例如：节点 `search_github` 的输出将被下游节点引用为 `{{search_github.trending_url}}`。

                3. 严格的拓扑依赖 (Upstream Dependencies):
                   - 并发原则：没有任何依赖的节点，将在底层被引擎高并发同时拉起。
                   - 阻塞原则：必须等待前置任务完成的节点，必须在 `upstreamDependencies` 数组中明确声明前置节点的 ID。

                4. 批处理与迭代节点 (Iteration / Child Engine) 【极度重要】:
                   - 当任务需要对一个列表/数组中的多个元素进行重复操作时（例如："分别总结 5 篇文章"、"批量测试 3 个接口"），**绝对不要**在单节点的代码里写 for 循环！
                   - 你必须生成一个特殊的迭代节点，设置 `"isIteration": true`。
                   - 必须指定 `"iteratorDataVariable"`，即你要遍历的数组变量（通常引用上游，如 `"{{spider_node.article_list}}"`）。
                   - 必须指定 `"iteratorItemAlias"`，即当前循环元素的局部别名（例如 `"item"`）。
                   - 必须在 `"childNodes"` 数组中，定义这个循环内部要执行的子任务流。在子任务流中，你可以通过 `{{item}}` 来引用当前遍历到的元素！

                """);

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

        fullPrompt.append("""
                【输出 JSON 格式要求 — 必须严格遵守】
                你必须严格输出如下格式的 JSON（不要包含任何 Markdown 标记）：
                {
                  "workflowName": "批量分析工作流",
                  "nodes": [
                    {
                      "instanceId": "fetch_list",
                      "role": "获取数据列表",
                      "executor": "omni",
                      "upstreamDependencies": [],
                      "isIteration": false
                    },
                    {
                      "instanceId": "batch_process_loop",
                      "role": "批量处理列表中的每一个元素",
                      "executor": "omni",
                      "upstreamDependencies": ["fetch_list"],
                      "isIteration": true,
                      "iteratorDataVariable": "{{fetch_list.data_array}}",
                      "iteratorItemAlias": "current_item",
                      "childNodes": [
                        {
                          "instanceId": "process_single_item",
                          "role": "处理单个元素并生成报告",
                          "executor": "omni",
                          "upstreamDependencies": [],
                          "userParams": {
                            "target_data": "{{current_item}}"
                          }
                        }
                      ]
                    }
                  ]
                }

                规则：
                1. 每个节点必须有 instanceId、role、executor（'omni'、'operator' 或 'external'）
                2. executor='omni'：逻辑思考、写代码、文件读写、网页搜索、Bash 命令等纯数字任务（默认）
                3. executor='operator'：仅当必须操作物理鼠标、键盘、查看屏幕截图时使用
                3.1 executor='external'：调用外部成熟 Agent CLI（Claude Code/Codex/SWE-agent/Aider）。可指定具体类型如 "external:claude-code"、"external:codex"、"external:swe-agent"、"external:aider"
                4. upstreamDependencies 数组声明该节点必须等待哪些上游节点完成后才能启动
                5. 无依赖的并行节点的 upstreamDependencies 为空数组 []
                6. 下游节点通过 {{上游节点ID.变量名}} 引用上游输出
                7. 非迭代节点必须设置 "isIteration": false（或省略）
                8. 迭代节点必须设置 "isIteration": true，并包含 iteratorDataVariable、iteratorItemAlias、childNodes
                9. childNodes 中的子节点可通过 {{iteratorItemAlias值}} 引用当前遍历到的元素
                10. 节点数量必须与任务的真实并发需求匹配
                11. 只输出 JSON，不要任何其他文字！
                """);

        // 4. 调用 LLM
        AiosSdk sdk = AiosSdk.getInstance();
        String response = sdk.think("topology_compiler", fullPrompt.toString());

        log.debug("[TopologyCompiler] LLM 原始响应长度: {}", response.length());

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
                    log.warn("[TopologyCompiler] 角色文件未找到: {} (skipped)", yamlPath);
                }
            }
            if (!sb.isEmpty()) return sb.toString();
        }

        // 兜底：读取默认 System_Architect.yaml
        String defaultPath = ROLES_DIR + "/System_Architect.yaml";
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(defaultPath));
        } catch (Exception e) {
            log.warn("[TopologyCompiler] 默认架构师角色未找到，使用最小规则");
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
            log.warn("[TopologyCompiler] MANIFEST.md 读取失败: {}", e.getMessage());
            return "（技能说明读取失败，请使用标准库）";
        }
    }

    /**
     * 构建拓扑编译专用的 System Prompt。
     */
    private static String buildTopologySystemPrompt(String architectRules, String skillsContext) {
        return """
                你是一个顶级的 AGI 操作系统工作流编译器。你的任务是将用户的自然语言意图，拆解为带有严格依赖关系的有向无环图 (DAG)。

                【核心架构规范】
                1. 节点级动态分发 (Executor):
                   - 对于每个节点，你必须指定最合适的 `executor`。
                   - 填写 "omni"：当任务涉及逻辑推理、代码编写、搜索引擎、爬虫、文件读写、Bash 系统命令等无需物理视觉的任务。
                   - 填写 "operator"：【仅当】任务必须移动真实的物理鼠标、敲击键盘、或调用宿主机 GUI 打开真实软件时使用。
                   - 填写 "external"：当任务需要调用外部成熟 Agent CLI（如 Claude Code、Codex、SWE-agent、Aider）时使用。也可指定具体类型："external:claude-code"、"external:codex"、"external:swe-agent"、"external:aider"。

                2. 内存状态流转与变量引用 (Memory Context):
                   - 节点之间通过内存总线传递数据，而不是写死在硬盘。
                   - 如果下游节点需要使用上游节点的数据，请在下游节点的 `userParams` 中使用 Dify 风格的占位符：`{{上游节点ID.变量名}}`。
                   - 例如：节点 `search_github` 的输出将被下游节点引用为 `{{search_github.trending_url}}`。

                3. 严格的拓扑依赖 (Upstream Dependencies):
                   - 并发原则：没有任何依赖的节点，将在底层被引擎高并发同时拉起。
                   - 阻塞原则：必须等待前置任务完成的节点，必须在 `upstreamDependencies` 数组中明确声明前置节点的 ID。

                4. 批处理与迭代节点 (Iteration / Child Engine) 【极度重要】:
                   - 当任务需要对一个列表/数组中的多个元素进行重复操作时（例如："分别总结 5 篇文章"、"批量测试 3 个接口"），**绝对不要**在单节点的代码里写 for 循环！
                   - 你必须生成一个特殊的迭代节点，设置 `"isIteration": true`。
                   - 必须指定 `"iteratorDataVariable"`，即你要遍历的数组变量（通常引用上游，如 `"{{spider_node.article_list}}"`）。
                   - 必须指定 `"iteratorItemAlias"`，即当前循环元素的局部别名（例如 `"item"`）。
                   - 必须在 `"childNodes"` 数组中，定义这个循环内部要执行的子任务流。在子任务流中，你可以通过 `{{item}}` 来引用当前遍历到的元素！

                """
                + "\n【架构师法则】\n" + architectRules + "\n\n"
                + "【可用技能库】\n" + skillsContext + "\n\n"
                + """
                【输出 JSON 格式要求 — 必须严格遵守】
                你必须严格输出如下格式的 JSON（不要包含任何 Markdown 标记）：
                {
                  "workflowName": "批量分析工作流",
                  "nodes": [
                    {
                      "instanceId": "fetch_list",
                      "role": "获取数据列表",
                      "executor": "omni",
                      "upstreamDependencies": [],
                      "isIteration": false
                    },
                    {
                      "instanceId": "batch_process_loop",
                      "role": "批量处理列表中的每一个元素",
                      "executor": "omni",
                      "upstreamDependencies": ["fetch_list"],
                      "isIteration": true,
                      "iteratorDataVariable": "{{fetch_list.data_array}}",
                      "iteratorItemAlias": "current_item",
                      "childNodes": [
                        {
                          "instanceId": "process_single_item",
                          "role": "处理单个元素并生成报告",
                          "executor": "omni",
                          "upstreamDependencies": [],
                          "userParams": {
                            "target_data": "{{current_item}}"
                          }
                        }
                      ]
                    }
                  ]
                }

                规则：
                1. 每个节点必须有 instanceId、role、executor（'omni'、'operator' 或 'external'）
                2. executor='omni'：逻辑思考、写代码、文件读写、网页搜索、Bash 命令等纯数字任务（默认）
                3. executor='operator'：仅当必须操作物理鼠标、键盘、查看屏幕截图时使用
                3.1 executor='external'：调用外部成熟 Agent CLI（Claude Code/Codex/SWE-agent/Aider）。可指定具体类型如 "external:claude-code"、"external:codex"、"external:swe-agent"、"external:aider"
                4. upstreamDependencies 数组声明该节点必须等待哪些上游节点完成后才能启动
                5. 无依赖的并行节点的 upstreamDependencies 为空数组 []
                6. 下游节点通过 {{上游节点ID.变量名}} 引用上游输出
                7. 非迭代节点必须设置 "isIteration": false（或省略）
                8. 迭代节点必须设置 "isIteration": true，并包含 iteratorDataVariable、iteratorItemAlias、childNodes
                9. childNodes 中的子节点可通过 {{iteratorItemAlias值}} 引用当前遍历到的元素
                10. 节点数量必须与任务的真实并发需求匹配
                11. 只输出 JSON，不要任何其他文字！
                """;
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
        log.info("[OmniFactory] Topology Compiler 已初始化。");
    }

    /**
     * 注册蓝图到编译器的蓝图注册表。
     *
     * @param blueprint 要注册的蓝图
     */
    public void registerBlueprint(AgentBlueprint blueprint) {
        blueprintRegistry.put(blueprint.blueprintId(), blueprint);
        log.info("[OmniFactory] Blueprint 已注册: '{}'", blueprint.blueprintId());
    }

    /**
     * 批量注册蓝图。
     */
    public void registerBlueprints(Map<String, AgentBlueprint> blueprints) {
        blueprintRegistry.putAll(blueprints);
        log.info("[OmniFactory] {} 个 Blueprint 已注册。总计: {}", blueprints.size(), blueprintRegistry.size());
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
        String compilePrompt = """
                你是一个顶级的 AGI 操作系统工作流编译器。请分析用户需求，将其拆解为带有严格依赖关系的 DAG。

                【核心架构规范】
                1. 每个节点必须指定 executor: "omni"（逻辑/代码/搜索/Bash）、"operator"（物理鼠标/键盘/GUI）或 "external"（外部 Agent CLI，如 "external:claude-code"/"external:codex"/"external:swe-agent"/"external:aider"）
                2. 节点间通过内存总线传递数据，下游用 {{上游节点ID.变量名}} 引用上游输出
                3. upstreamDependencies 数组声明该节点必须等待的上游节点 ID
                4. 批处理与迭代节点 (Iteration) 【极度重要】:
                   - 当任务需要对列表/数组中的多个元素进行重复操作时，**绝对不要**在单节点代码里写 for 循环！
                   - 必须生成迭代节点，设置 "isIteration": true
                   - 必须指定 "iteratorDataVariable"（遍历的数组变量，如 "{{fetch_node.data_list}}"）
                   - 必须指定 "iteratorItemAlias"（当前循环元素的别名，如 "item"）
                   - 必须在 "childNodes" 中定义循环内的子任务流，子节点通过 {{item}} 引用当前元素

                用户需求: [""" + userRequest + "]\n\n"
                + "请严格输出如下格式的 JSON（不要 Markdown 标记）：\n"
                + "{\n"
                + "  \"workflowName\": \"任务流名称\",\n"
                + "  \"nodes\": [\n"
                + "    { \"instanceId\": \"fetch_list\", \"role\": \"获取数据列表\", \"executor\": \"omni\", \"upstreamDependencies\": [], \"isIteration\": false },\n"
                + "    { \"instanceId\": \"batch_loop\", \"role\": \"批量处理每个元素\", \"executor\": \"omni\", \"upstreamDependencies\": [\"fetch_list\"],\n"
                + "      \"isIteration\": true, \"iteratorDataVariable\": \"{{fetch_list.data_array}}\", \"iteratorItemAlias\": \"item\",\n"
                + "      \"childNodes\": [\n"
                + "        { \"instanceId\": \"process_item\", \"role\": \"处理单个元素\", \"executor\": \"omni\", \"upstreamDependencies\": [], \"userParams\": { \"target\": \"{{item}}\" } }\n"
                + "      ]\n"
                + "    }\n"
                + "  ]\n"
                + "}\n"
                + "只输出 JSON，不要其他文字。";

        String topologyJson = sdk.think("topology_compiler", compilePrompt);
        System.out.printf("[OmniFactory]   已收到拓扑 JSON (%d chars).%n", topologyJson.length());
        log.debug("[OmniFactory] Topology JSON: {}", topologyJson);

        // ── Step 2: 解析 JSON 为 WorkflowNode 列表 ──
        List<WorkflowNode> nodes = parseTopologyJson(topologyJson);
        System.out.printf("[OmniFactory]   已解析 %d 个工作流节点.%n", nodes.size());
        log.info("[OmniFactory] 已解析 {} 个工作流节点 from LLM output.", nodes.size());

        if (nodes.isEmpty()) {
            System.out.println("[OmniFactory]   ⚠ No nodes parsed. Returning empty manifest.");
            log.warn("[OmniFactory] 拓扑解析产生 0 个节点。");
            return new WorkflowManifest("empty_workflow", List.of());
        }

        // ── Step 3: 构建 WorkflowManifest ──
        // 优先使用 LLM 在 JSON 中生成的 workflowName（保留语义），
        // 回退到基于用户输入的简短摘要
        String workflowName = extractJsonValue(topologyJson, "workflowName");
        if (workflowName == null || workflowName.isBlank()) {
            // 从用户原始输入中提取前 20 字符作为摘要
            workflowName = userRequest.length() > 20
                    ? userRequest.substring(0, 20).trim()
                    : userRequest.trim();
        }
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
     * 从 LLM 返回的拓扑 JSON 中提取 WorkflowNode 列表。
     * <p>
     * 支持两种格式：
     * - 新格式：{ "workflowName": "...", "nodes": [...] }（Dify 风格，含 upstreamDependencies）
     * - 旧格式：直接返回节点数组或 edges 数组
     * <p>
     * 支持迭代节点（Iteration Node）的递归解析：
     * - isIteration, iteratorDataVariable, iteratorItemAlias
     * - childNodes 数组递归解析为子 WorkflowNode 列表
     */
    private List<WorkflowNode> parseTopologyJson(String json) {
        List<WorkflowNode> nodes = new ArrayList<>();

        // 去除 Markdown 代码块包裹和 <think/> 标签
        String cleaned = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        cleaned = cleaned.replaceAll("(?s)<think.*?</think*>", "").trim();

        // 提取 nodes 数组内容
        String nodesArray = extractNodesArray(cleaned);
        if (nodesArray == null || nodesArray.isBlank()) {
            log.warn("[TopologyCompiler] LLM 输出中未找到 'nodes' 数组");
            return nodes;
        }

        // 使用安全的深度感知分割器提取每个 JSON 对象
        List<String> rawNodes = AppGateway.splitJsonObjectsSafe(nodesArray);

        for (String obj : rawNodes) {
            WorkflowNode node = parseSingleNode(obj);
            if (node != null) {
                nodes.add(node);
            }
        }

        // ── 解析 edges 数组（借鉴 Langflow Edge 路由） ──
        String edgesArray = AppGateway.extractJsonArray(cleaned, "edges");
        if (edgesArray != null && !edgesArray.isBlank()) {
            List<WorkflowEdge> edges = parseEdges(edgesArray);
            if (!edges.isEmpty()) {
                WorkflowEngine.getInstance().setEdges(edges);
                log.info("[TopologyCompiler] 已解析 {} 条 Edge，已设置到 WorkflowEngine。", edges.size());
            }
        }

        return nodes;
    }

    /**
     * 解析单个节点 JSON 对象为 WorkflowNode（含迭代节点递归解析）。
     */
    private WorkflowNode parseSingleNode(String obj) {
        String instanceId = extractJsonValue(obj, "instanceId");
        // 兼容旧格式：id → instanceId
        if (instanceId == null) instanceId = extractJsonValue(obj, "id");
        String blueprintId = extractJsonValue(obj, "blueprintId");
        String role = extractJsonValue(obj, "role");
        String executor = extractJsonValue(obj, "executor");
        String subscribeTopic = extractJsonValue(obj, "subscribeTopic");
        String publishTopic = extractJsonValue(obj, "publishTopic");

        // 解析 userParams 对象
        Map<String, String> userParams = extractUserParams(obj);

        // 解析 upstreamDependencies 数组
        List<String> upstreamDeps = extractUpstreamDependencies(obj);

        if (instanceId == null || instanceId.isBlank()) {
            return null;
        }

        WorkflowNode node = new WorkflowNode(
                instanceId.trim(),
                role != null ? role.trim() : "",
                blueprintId != null ? blueprintId.trim() : instanceId.trim(),
                userParams,
                subscribeTopic != null ? subscribeTopic.trim() : "",
                publishTopic != null ? publishTopic.trim() : "",
                executor != null ? executor.trim() : "omni"
        );

        // 注入上游依赖
        for (String dep : upstreamDeps) {
            node.addDependency(dep.trim());
        }

        // ── 条件路由字段解析 ──
        String condition = extractJsonValue(obj, "condition");
        if (condition != null && !condition.isBlank()) {
            node.setCondition(condition.trim());
        }

        // ── Frozen 字段解析 ──
        String frozenStr = extractJsonValue(obj, "frozen");
        if ("true".equalsIgnoreCase(frozenStr)) {
            node.setFrozen(true);
        }

        // ── 声明式端口解析（借鉴 Langflow Edge 端口路由） ──
        String inputPortsArray = AppGateway.extractJsonArray(obj, "inputPorts");
        if (inputPortsArray != null && !inputPortsArray.isBlank()) {
            List<Port> inputPorts = parsePorts(inputPortsArray);
            node.setInputPorts(inputPorts);
        }

        String outputPortsArray = AppGateway.extractJsonArray(obj, "outputPorts");
        if (outputPortsArray != null && !outputPortsArray.isBlank()) {
            List<Port> outputPorts = parsePorts(outputPortsArray);
            node.setOutputPorts(outputPorts);
        }

        // ── 迭代节点专属字段解析 ──
        String isIterationStr = extractJsonValue(obj, "isIteration");
        if ("true".equalsIgnoreCase(isIterationStr)) {
            node.setIteration(true);

            String iteratorDataVariable = extractJsonValue(obj, "iteratorDataVariable");
            if (iteratorDataVariable != null) {
                node.setIteratorDataVariable(iteratorDataVariable.trim());
            }

            String iteratorItemAlias = extractJsonValue(obj, "iteratorItemAlias");
            if (iteratorItemAlias != null) {
                node.setIteratorItemAlias(iteratorItemAlias.trim());
            }

            // 递归解析 childNodes 数组
            String childNodesArray = AppGateway.extractJsonArray(obj, "childNodes");
            if (childNodesArray != null && !childNodesArray.isBlank()) {
                List<String> rawChildNodes = AppGateway.splitJsonObjectsSafe(childNodesArray);
                for (String childObj : rawChildNodes) {
                    WorkflowNode childNode = parseSingleNode(childObj);
                    if (childNode != null) {
                        node.getChildNodes().add(childNode);
                    }
                }
            }

            log.info("[TopologyCompiler] Iteration node parsed: id={}, dataVar={}, itemAlias={}, childCount={}",
                    instanceId, iteratorDataVariable, iteratorItemAlias, node.getChildNodes().size());
        }

        return node;
    }

    /**
     * 从 JSON 中提取 "nodes" 数组的内部内容。
     */
    private String extractNodesArray(String json) {
        // 先尝试 Dify 格式：{ "workflowName": "...", "nodes": [...] }
        String nodesArray = AppGateway.extractJsonArray(json, "nodes");
        if (nodesArray != null) return nodesArray;

        // 如果整个 JSON 就是一个数组 [...]
        String trimmed = json.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        return null;
    }

    /**
     * 从 JSON 对象中提取 upstreamDependencies 数组。
     * <p>
     * 匹配 "upstreamDependencies": ["id1", "id2"] 格式。
     */
    private List<String> extractUpstreamDependencies(String jsonObj) {
        List<String> deps = new ArrayList<>();
        String arrayContent = AppGateway.extractJsonArray(jsonObj, "upstreamDependencies");
        if (arrayContent == null || arrayContent.isBlank()) return deps;

        // 提取数组中的每个字符串值
        Pattern strPattern = Pattern.compile("\"([^\"]+)\"");
        Matcher m = strPattern.matcher(arrayContent);
        while (m.find()) {
            deps.add(m.group(1));
        }
        return deps;
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

    /**
     * 解析端口数组 JSON 为 Port 列表。
     * <p>
     * 匹配格式：[{"name": "data_in", "dataType": "json"}, ...]
     */
    private List<Port> parsePorts(String portsArray) {
        List<Port> ports = new ArrayList<>();
        List<String> rawPortObjs = AppGateway.splitJsonObjectsSafe(portsArray);
        for (String portObj : rawPortObjs) {
            String name = extractJsonValue(portObj, "name");
            String dataType = extractJsonValue(portObj, "dataType");
            if (name != null && !name.isBlank()) {
                ports.add(new Port(name.trim(), dataType != null ? dataType.trim() : "any"));
            }
        }
        return ports;
    }

    /**
     * 解析边数组 JSON 为 WorkflowEdge 列表。
     * <p>
     * 匹配格式：[{"sourceNodeId": "a", "sourcePortName": "result", "targetNodeId": "b", "targetPortName": "data_in"}, ...]
     */
    private List<WorkflowEdge> parseEdges(String edgesArray) {
        List<WorkflowEdge> edges = new ArrayList<>();
        List<String> rawEdgeObjs = AppGateway.splitJsonObjectsSafe(edgesArray);
        for (String edgeObj : rawEdgeObjs) {
            String sourceNodeId = extractJsonValue(edgeObj, "sourceNodeId");
            String sourcePortName = extractJsonValue(edgeObj, "sourcePortName");
            String targetNodeId = extractJsonValue(edgeObj, "targetNodeId");
            String targetPortName = extractJsonValue(edgeObj, "targetPortName");
            if (sourceNodeId != null && targetNodeId != null
                    && sourcePortName != null && targetPortName != null) {
                edges.add(new WorkflowEdge(
                        sourceNodeId.trim(), sourcePortName.trim(),
                        targetNodeId.trim(), targetPortName.trim()));
            }
        }
        return edges;
    }
}
