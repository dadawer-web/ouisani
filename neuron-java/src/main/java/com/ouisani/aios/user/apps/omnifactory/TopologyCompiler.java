package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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
                你是 AIOS 通用神经符号编译器 (Universal Neuro-Symbolic Compiler)。你的任务是将用户的自然语言意图——无论领域——拆解为带有严格依赖关系的有向无环图 (DAG)。
                用户输入可能是写代码、写小说、查股票、做数学题、发送邮件、做调研报告等任何领域。

                【核心架构规范】
                1. 节点级动态分发 (Executor):
                   - 对于每个节点，你必须指定最合适的 `executor`。
                   - 填写 "omni"：当任务涉及逻辑推理、内容创作、搜索引擎、爬虫、文件读写、Bash 系统命令等无需物理视觉的任务。
                   - 填写 "operator"：【仅当】任务必须移动真实的物理鼠标、敲击键盘、或调用宿主机 GUI 打开真实软件时使用。
                   - 填写 "external"：当任务需要调用外部成熟 Agent CLI（如 Claude Code、Codex、SWE-agent、Aider）时使用。也可指定具体类型："external:claude-code"、"external:codex"、"external:swe-agent"、"external:aider"。

                2. 内存状态流转与变量引用 (Memory Context):
                   - 节点之间通过内存总线传递数据，而不是写死在硬盘。
                   - 如果下游节点需要使用上游节点的数据，请在下游节点的 `userParams` 中使用 Dify 风格的占位符：`{{上游节点ID.变量名}}`。
                   - 例如：节点 `search_news` 的输出将被下游节点引用为 `{{search_news.url_list}}`。

                3. 严格的拓扑依赖 (Upstream Dependencies):
                   - 并发原则：没有任何依赖的节点，将在底层被引擎高并发同时拉起。
                   - 阻塞原则：必须等待前置任务完成的节点，必须在 `upstreamDependencies` 数组中明确声明前置节点的 ID。

                4. 批处理与迭代节点 (Iteration / Child Engine) 【极度重要】:
                   - 当任务需要对一个列表/数组中的多个元素进行重复操作时（例如："分别总结 5 篇文章"、"批量爬取 3 个网页"），**绝对不要**在单节点的代码里写 for 循环！
                   - 你必须生成一个特殊的迭代节点，设置 `"isIteration": true`。
                   - 必须指定 `"iteratorDataVariable"`，即你要遍历的数组变量（通常引用上游，如 `"{{search_node.url_list}}"`）。
                   - 必须指定 `"iteratorItemAlias"`，即当前循环元素的局部别名（例如 `"item"`）。
                   - 必须在 `"childNodes"` 数组中，定义这个循环内部要执行的子任务流。在子任务流中，你可以通过 `{{item}}` 来引用当前遍历到的元素！

                """);

        // ── 通用神经符号编译器指令 — 强制领域不可知论 + ETL 流水线 + I/O 倒推 + 微任务原子化 ──
        fullPrompt.append(universalCompilerDirective());

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
                2. executor='omni'：逻辑思考、内容创作、文件读写、网页搜索、Bash 命令等纯数字任务（默认）
                3. executor='operator'：仅当必须操作物理鼠标、键盘、查看屏幕截图时使用
                3.1 executor='external'：调用外部成熟 Agent CLI（Claude Code/Codex/SWE-agent/Aider）。可指定具体类型如 "external:claude-code"、"external:codex"、"external:swe-agent"、"external:aider"
                4. upstreamDependencies 数组声明该节点必须等待哪些上游节点完成后才能启动
                5. 无依赖的并行节点的 upstreamDependencies 为空数组 []
                6. 下游节点通过 {{上游节点ID.变量名}} 引用上游输出
                7. 非迭代节点必须设置 "isIteration": false（或省略）
                8. 迭代节点必须设置 "isIteration": true，并包含 iteratorDataVariable、iteratorItemAlias、childNodes
                9. childNodes 中的子节点可通过 {{iteratorItemAlias值}} 引用当前遍历到的元素
                10. 节点数量必须与任务的真实复杂度匹配 — 复杂任务至少 10 个节点，简单任务至少 4 个节点，严禁只生成 3 个粗放节点！
                11. 先用 think 标签写出数据流转倒推逻辑，然后输出纯 JSON，不要任何其他文字！
                """);

        // 4. 调用 LLM + schema 验证 + 自旋反馈重试 (借鉴 OMA structured-output)
        AiosSdk sdk = AiosSdk.getInstance();
        final int maxAttempts = 3;
        String currentPrompt = fullPrompt.toString();
        String cleanJson = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info("[TopologyCompiler] compileTopology 尝试 {}/{}", attempt, maxAttempts);
            String response = sdk.think("topology_compiler", currentPrompt);
            log.debug("[TopologyCompiler] LLM 原始响应长度: {}", response.length());

            // 三级 fallback 提取 + 拓扑 schema 验证
            StructuredOutputValidator.ValidationResult result =
                    StructuredOutputValidator.extractAndValidate(response);

            if (result.isValid()) {
                cleanJson = result.cleanedJson();
                log.info("[TopologyCompiler] compileTopology 验证通过 (尝试 {}), jsonLen={}",
                        attempt, cleanJson.length());
                break;
            }

            // 验证失败 — 将逐字段错误信息塞回 prompt 让 LLM 修正
            log.warn("[TopologyCompiler] compileTopology 尝试 {} 验证失败: {}",
                    attempt, result.formattedErrors());
            if (attempt < maxAttempts) {
                currentPrompt = fullPrompt + "\n\n【上次输出存在以下问题】\n"
                        + result.formattedErrors()
                        + "\n请修正以上问题，重新输出纯 JSON（不要 Markdown 标记）。";
            }
        }

        // 3 次都失败 — fallback 到旧的暴力提取，让前端报错（向后兼容）
        if (cleanJson == null) {
            log.error("[TopologyCompiler] compileTopology {} 次尝试均未通过 schema 验证, fallback 到暴力提取",
                    maxAttempts);
            String lastResponse = sdk.think("topology_compiler", fullPrompt.toString());
            cleanJson = extractPureJson(lastResponse);
        }

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
     * 委托给 {@link StructuredOutputValidator#extractJson} 做三级 fallback 提取
     * (直接 parse → markdown fence → 首尾大括号),每级都用 Jackson 验证。
     * 如果三级都失败,fallback 到旧的暴力正则提取,保证向后兼容。
     */
    private static String extractPureJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";

        // 优先用 StructuredOutputValidator 做真正的 JSON parse 验证
        String extracted = StructuredOutputValidator.extractJson(raw);
        if (extracted != null) {
            return extracted;
        }

        // Fallback: 旧的暴力正则提取 (不验证 JSON 合法性,让下游报错)
        log.warn("[TopologyCompiler] StructuredOutputValidator 提取失败, fallback 到暴力正则");
        String cleaned = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
        cleaned = cleaned.replaceAll("```json", "").replaceAll("```", "").trim();
        int start = cleaned.indexOf("{");
        int end = cleaned.lastIndexOf("}");
        if (start != -1 && end != -1 && start < end) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    // ════════════════════════════════════════════════════════════════
    //  通用神经符号编译器指令 (Universal Neuro-Symbolic Compiler Directive)
    // ════════════════════════════════════════════════════════════════

    /**
     * 通用神经符号编译器指令 — 4 条核心法则。
     * <p>
     * 将编译器从"软件工程专用"升级为"领域不可知的通用 AGI 调度器"。
     * 适用于写代码、写小说、查股票、做数学题、发送邮件等一切任务。
     * <p>
     * 四大核心指令：
     * <ol>
     *   <li>领域不可知论 — 根据 ExpertDomain SOP 切换思维，不预设是写代码</li>
     *   <li>万物皆 ETL — Extract(获取) → Transform(处理) → Load(落地)</li>
     *   <li>I/O 逆向倒推 — 从最终产物开始，严格匹配 ToolRegistry 的 InputSchema/OutputSchema</li>
     *   <li>微任务原子化 — 严禁宏大节点，10-20 个细碎动作</li>
     * </ol>
     */
    private static String universalCompilerDirective() {
        // 使用字符串拼接构建 think 标签，避免被编辑器吞掉
        String thinkOpen = "<" + "think" + ">";
        String thinkClose = "</" + "think" + ">";
        return """
                【通用神经符号编译器指令 — 4 条核心法则 — 极度重要，违反即失败】

                法则 1: 领域不可知论 (Domain Agnosticism)
                - 你不是一个纯粹的程序员。你是 AIOS 的内核调度器 (Kernel Scheduler)。
                - 用户输入可能是：写代码、写小说、查股票、做数学题、发送邮件、做调研报告、翻译文档、分析数据、系统运维……任何领域。
                - 你必须根据注入的 ExpertDomain (专家领域 SOP) 来切换拆解思维。如果没有注入 SOP，则从用户需求中推断领域。
                - 严禁预设"这是一个软件工程项目"。不要默认生成 pom.xml、Controller、Service 等代码节点，除非用户明确要求写代码。
                - 示例：用户说"调研丰田固态电池最新进展" → 你的节点应该是 WebSearch → WebScrape → 总结对比 → 生成报告，而不是"创建项目结构"。

                法则 2: 万物皆 ETL 流水线 (Everything is ETL)
                - 无论什么任务，你都必须将其抽象为数据的流转：
                  a) Extract (获取信息)：WebSearch 搜索、WebScrape 爬取、FileRead 读取本地文件、Bash 执行命令获取输出、用户输入。
                  b) Transform (处理信息)：LLM 推理总结、数据清洗、格式转换、翻译、计算、对比分析、内容生成。
                  c) Load (落地/发送信息)：FileWrite 保存文件、SendMessage 发送消息、Bash 执行落地命令、输出到终端。
                - 每个节点必须明确属于 E、T、L 中的某一类，并在 role 中体现（例如："[E] 搜索丰田固态电池新闻"、"[T] 对比三篇文章的技术参数"、"[L] 保存调研报告为 Markdown"）。

                法则 3: 严格的 I/O 逆向倒推 (Backward Chaining with Schema Matching)
                - 你必须从最终目标产物开始倒推，而不是从用户需求正向分解。
                - 思考链示例（通用领域）：
                  要发邮件 → 需要邮件正文(Transform) → 需要调研数据(Extract) → 需要 WebSearch 查询。
                  要保存报告 → 需要报告内容(Transform) → 需要原始资料(Extract) → 需要 WebScrape 爬取。
                  要运行程序 → 需要启动脚本(L) → 需要源码文件(T) → 需要项目结构(E)。
                - 严格匹配 ToolRegistry 中可用工具的 InputSchema 和 OutputSchema：
                  上游节点的 OutputPort 类型必须与下游节点的 InputPort 类型兼容。
                  例如：WebSearch 输出 UrlList → WebScrape 接受 UrlList 输入 → 输出 WebPageContent → LLM 总结接受 WebPageContent 输入 → 输出 MarkdownText → FileWrite 接受 MarkdownText 输入。
                - 每条数据流必须有明确的类型契约（PlainText → PlainText, JsonData → JsonData, UrlList → UrlList, WebPageContent → MarkdownText 等）。

                法则 4: 微任务原子化 (Micro-Tasking Atomization)
                - 严禁出现类似"撰写调研报告"、"开发整个系统"、"分析数据"这种宏大节点。
                - 必须拆分为极其细碎的动作，每个节点只做一件事：
                  好的示例："节点1: WebSearch(query='丰田 固态电池 2026')"、"节点2: WebScrape(urls=节点1.output)"、"节点3: 总结三篇文章的核心技术参数"、"节点4: 对比参数生成表格"、"节点5: FileWrite(path='/report.md', content=节点4.output)"
                - 一个节点最好只对应一次工具调用或一个 LLM 推理步骤。
                - 自检：如果一个节点的 role 描述超过 15 个字，或包含"和"/"并"/"以及"等连接词，说明它不够原子化，必须继续拆分。
                - 复杂任务应该生成 10-20 个节点。简单任务至少 4 个节点。如果你的节点数 < 4，说明拆解粒度太粗。

                法则 5: 强制思考过程 (Mandatory Thinking Process)
                - 在输出最终的 JSON 之前，你必须先使用 %s 标签，详细写出你的"数据流转倒推逻辑"。
                - 在 %s 标签内，你必须按以下格式思考：
                  1) 领域判定：这是什么领域的任务？（代码/调研/创作/运维/邮件/数学/翻译/...）
                  2) 最终产物：用户想要的最终结果是什么？（文件? 消息? 终端输出? 邮件?）
                  3) ETL 倒推：从最终产物开始倒推，每一步需要什么上游数据？
                  4) 工具匹配：每一步应该用 ToolRegistry 中的哪个工具？输入输出类型是否匹配？
                  5) 节点列表：按依赖顺序列出所有微任务节点（10-20 个），标注 E/T/L 类型。
                - %s 标签内的内容不会被解析为 JSON，你可以自由思考。
                - 完成思考后，紧接着输出纯 JSON（不要包裹在 %s 中，直接以 { 开头）。
                """.formatted(thinkOpen, thinkOpen, thinkOpen, thinkClose);
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
     * 采用逆向推导 (Backward Chaining) + 强类型 I/O 契约 + 自旋反馈机制：
     * <ol>
     *   <li>向 LLM 发送需求，要求使用逆向推导思维生成包含 nodes 和 edges 的 DAG</li>
     *   <li>将 LLM 的 JSON 解析为 WorkflowManifest（含端口级连线）</li>
     *   <li>调用 GraphValidator.validate(manifest) 进行静态类型校验</li>
     *   <li>校验通过则返回；失败则将异常信息追加到 Prompt 中重新生成</li>
     *   <li>最多重试 maxCompileAttempts=3 次，全部失败则快速失败拒绝执行</li>
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
        final int maxCompileAttempts = 3;

        // ── 基础编译 Prompt（逆向推导 + 强类型 I/O 契约） ──
        String basePrompt = buildCompilePrompt(userRequest);
        String currentPrompt = basePrompt;

        WorkflowManifest manifest = null;

        for (int attempt = 1; attempt <= maxCompileAttempts; attempt++) {
            System.out.printf("[OmniFactory]   ═══ 编译尝试 %d/%d ═══%n", attempt, maxCompileAttempts);
            log.info("[TopologyCompiler] Compile attempt {}/{}", attempt, maxCompileAttempts);

            // ── Step 1: LLM 生成（逆向推导 Backward Chaining） ──
            long startMs = System.currentTimeMillis();
            String topologyJson = sdk.think("topology_compiler", currentPrompt);
            long elapsedMs = System.currentTimeMillis() - startMs;
            System.out.printf("[OmniFactory]   [Step 1] LLM 生成拓扑 JSON: %d chars (%dms)%n",
                    topologyJson.length(), elapsedMs);
            log.info("[TopologyCompiler] Attempt {}: LLM generated {} chars ({}ms)",
                    attempt, topologyJson.length(), elapsedMs);

            // ── Step 1.5: 结构化输出验证 (借鉴 OMA structured-output) ──
            // 在正则解析前用 Jackson 做 JSON parse + schema 验证,提前拦截格式错误
            StructuredOutputValidator.ValidationResult schemaResult =
                    StructuredOutputValidator.extractAndValidate(topologyJson);
            if (!schemaResult.isValid()) {
                System.out.printf("[OmniFactory]   [Step 1.5] ⚠ Schema 验证失败: %s%n",
                        schemaResult.formattedErrors());
                log.warn("[TopologyCompiler] Attempt {} schema validation failed: {}",
                        attempt, schemaResult.formattedErrors());
                if (attempt < maxCompileAttempts) {
                    currentPrompt = basePrompt
                            + "\n\n【上次输出存在以下格式问题】\n"
                            + schemaResult.formattedErrors()
                            + "\n请修正以上问题，重新输出纯 JSON。";
                    continue;
                }
                break;
            }
            // 用验证后的清洗 JSON 替代原始响应,提高后续正则解析成功率
            topologyJson = schemaResult.cleanedJson();
            System.out.printf("[OmniFactory]   [Step 1.5] ✓ Schema 验证通过%n");

            // ── Step 2: 反序列化为 WorkflowManifest ──
            manifest = TopologyJsonParser.parseTopologyToManifest(topologyJson, userRequest);
            if (manifest.nodes().isEmpty()) {
                System.out.printf("[OmniFactory]   [Step 2] ⚠ 解析产生 0 个节点%n");
                log.warn("[TopologyCompiler] Attempt {}: 0 nodes parsed", attempt);
                if (attempt < maxCompileAttempts) {
                    currentPrompt = basePrompt
                            + "\n\n【上次输出错误】\n你上一次的输出没有产生任何有效节点。"
                            + "请确保输出包含 'nodes' 数组，且每个节点有 instanceId 字段。";
                    continue;
                }
                break;
            }
            System.out.printf("[OmniFactory]   [Step 2] ✓ 解析成功: %d 个节点, %d 条边%n",
                    manifest.nodes().size(), manifest.edges().size());

            // ── Step 3: 静态校验 (GraphValidator) ──
            try {
                GraphValidator.getInstance().validate(manifest);
                System.out.printf("[OmniFactory]   [Step 3] ✓ 图验证通过！%n");
                log.info("[TopologyCompiler] Attempt {} passed graph validation", attempt);

                // ── Step 4a: 校验通过，跳出循环 ──
                break;

            } catch (TopologyCompileException e) {
                System.out.printf("[OmniFactory]   [Step 3] ⚠ 图验证失败: %s%n", e.getMessage());
                log.error("[TopologyCompiler] Attempt {} graph validation failed: {}", attempt, e.getMessage());

                if (attempt < maxCompileAttempts) {
                    // ── Step 4b: 自旋反馈 — 将异常信息追加到 Prompt ──
                    currentPrompt = basePrompt
                            + "\n\n你的图纸存在类型不匹配或连线错误：\n"
                            + e.toString()
                            + "\n\n请修正 I/O 端口并重新生成图纸。";
                    System.out.printf("[OmniFactory]   [Step 4] → 反馈错误给 LLM，准备重试...%n");
                    continue;
                }

                // ── Step 4c: 3 次都失败，快速失败 ──
                System.out.printf("[OmniFactory]   ✗ 编译失败：%d 次尝试均未通过图验证，拒绝执行%n",
                        maxCompileAttempts);
                throw new RuntimeException(String.format(
                        "拓扑编译失败：%d 次尝试均未通过 GraphValidator 校验。最后错误: %s",
                        maxCompileAttempts, e.getMessage()), e);
            }
        }

        if (manifest == null || manifest.nodes().isEmpty()) {
            throw new RuntimeException("拓扑编译失败：未能生成有效的工作流节点");
        }

        // ── 蓝图校验 + 自动补全 ──
        validate(manifest);

        // ── 部署 ──
        WorkflowEngine.getInstance().executeWorkflow(manifest, blueprintRegistry);

        System.out.println("[OmniFactory] Topology Compiler engaged. "
                + "User intent successfully compiled into event-driven agent mesh.");
        log.info("[OmniFactory] Topology compilation complete. Workflow '{}' deployed with {} nodes.",
                manifest.workflowName(), manifest.nodes().size());

        return manifest;
    }

    /**
     * 构建编译 Prompt — 逆向推导 (Backward Chaining) + 强类型 I/O 契约。
     * <p>
     * 逆向推导思维：从最终目标出发，反向推导需要哪些前置节点和数据，
     * 确保每条数据流都有明确的类型契约。
     *
     * @param userRequest 用户自然语言需求
     * @return 完整的编译提示词
     */
    private String buildCompilePrompt(String userRequest) {
        return """
                你是 AIOS 通用神经符号编译器 (Universal Neuro-Symbolic Compiler)。请使用逆向推导 (Backward Chaining) 思维分析用户需求——无论领域——将其拆解为带有严格依赖关系和强类型 I/O 契约的 DAG。
                用户输入可能是写代码、写小说、查股票、做数学题、发送邮件、做调研报告等任何领域。

                【逆向推导思维 (Backward Chaining)】
                1. 首先确定最终目标：用户想要什么最终结果？（文件? 消息? 终端输出? 邮件? 报告?）
                2. 从最终结果反向推导：要产出这个结果，需要什么输入数据？
                3. 继续反向推导：这些输入数据从哪里来？需要哪些前置节点？
                4. 重复直到所有输入都可以从初始状态（用户输入/文件系统/网络）获取
                5. 确保每一步的数据流转都有明确的类型契约

                【核心架构规范】
                1. 每个节点必须指定 executor: "omni"（逻辑/创作/搜索/Bash）、"operator"（物理鼠标/键盘/GUI）或 "external"（外部 Agent CLI，如 "external:claude-code"）
                2. 节点间通过内存总线传递数据，下游用 {{上游节点ID.变量名}} 引用上游输出
                3. upstreamDependencies 数组声明该节点必须等待的上游节点 ID
                4. 批处理与迭代节点 (Iteration)：当需要对列表中的多个元素重复操作时，生成迭代节点，设置 "isIteration": true

                【强类型 I/O 契约 — 极度重要】
                每个节点必须声明 inputPorts 和 outputPorts，描述"吃进去什么"和"吐出来什么"：
                - inputPorts: [{"name": "端口名", "dataType": "数据类型", "description": "描述", "required": true}]
                - outputPorts: [{"name": "端口名", "dataType": "数据类型", "description": "描述", "required": true}]

                标准数据类型: PlainText, MarkdownText, JsonData, UrlList, FilePath, FilePathList, ShellCommand, CommandOutput, WebPageContent, Url, HtmlText, CodeSnippet

                同时必须声明 edges 数组，显式连接上游输出端口到下游输入端口：
                - edges: [{"sourceNodeId": "节点A", "sourcePortName": "输出端口名", "targetNodeId": "节点B", "targetPortName": "输入端口名"}]

                """ + universalCompilerDirective() + "\n用户需求: [" + userRequest + "]\n\n"
                + """
                【输出 JSON 格式要求 — 必须严格遵守】
                你必须严格输出如下格式的 JSON（不要包含任何 Markdown 标记）：
                {
                  "workflowName": "任务流名称",
                  "nodes": [
                    {
                      "instanceId": "fetch_data",
                      "role": "获取数据",
                      "executor": "omni",
                      "upstreamDependencies": [],
                      "isIteration": false,
                      "inputPorts": [],
                      "outputPorts": [
                        {"name": "raw_data", "dataType": "JsonData", "description": "获取的原始JSON数据", "required": true}
                      ]
                    },
                    {
                      "instanceId": "process_data",
                      "role": "处理数据",
                      "executor": "omni",
                      "upstreamDependencies": ["fetch_data"],
                      "isIteration": false,
                      "inputPorts": [
                        {"name": "data", "dataType": "JsonData", "description": "待处理的JSON数据", "required": true}
                      ],
                      "outputPorts": [
                        {"name": "result", "dataType": "MarkdownText", "description": "处理结果报告", "required": true}
                      ]
                    }
                  ],
                  "edges": [
                    {"sourceNodeId": "fetch_data", "sourcePortName": "raw_data", "targetNodeId": "process_data", "targetPortName": "data"}
                  ]
                }

                规则：
                1. 使用逆向推导：从最终目标出发，反向推导所需节点
                2. 每个节点必须声明 inputPorts 和 outputPorts
                3. 下游节点的 inputPort 类型必须与上游节点的 outputPort 类型兼容
                4. 必填 inputPort（required=true）必须有对应的 edge 连线
                5. edges 数组必须显式连接所有端口级数据流
                6. 起始节点（无上游依赖）的 inputPorts 可以为空数组 []
                7. 节点数量必须与任务的真实复杂度匹配 — 复杂任务至少 10 个节点，简单任务至少 4 个节点，严禁只生成 3 个粗放节点！
                8. 先用 think 标签写出数据流转倒推逻辑，然后输出纯 JSON，不要任何其他文字！
                """;
    }

    // ════════════════════════════════════════════════════════════════
    //  蓝图校验 + 自动补全 (Blueprint Validation & Auto-Completion)
    // ════════════════════════════════════════════════════════════════

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

}
