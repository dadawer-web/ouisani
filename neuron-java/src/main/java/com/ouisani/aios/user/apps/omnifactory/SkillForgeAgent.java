package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.exception.SkillFaultException;
import com.ouisani.aios.core.llm.LlmRouterHolder;
import com.ouisani.aios.core.task.AiosTask;
import com.ouisani.aios.core.task.SopDescriptor;
import com.ouisani.aios.core.task.SopManager;
import com.ouisani.aios.core.tool.Port;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolRegistry;
import com.ouisani.aios.user.cli.MoEGatingRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * JIT 动态技能锻造炉 (Skill Forge) — 系统的自我进化引擎。
 * <p>
 * 当 {@link MoEGatingRouter} 抛出 {@link SkillFaultException}（认知盲区）时，
 * SkillForgeAgent 被唤醒，执行以下"进化回路"：
 * <pre>
 *   ┌─────────────────────────────────────────────────────────┐
 *   │  SkillFaultException (认知盲区)                          │
 *   │    │                                                     │
 *   │    ▼  1. 提取上下文                                       │
 *   │  AiosTask + ToolRegistry I/O 契约                        │
 *   │    │                                                     │
 *   │    ▼  2. LLM 驱动生成 (P_CORE)                           │
 *   │  "你是 JIT 驱动工程师，为未知领域锻造 SOP"                │
 *   │    │                                                     │
 *   │    ▼  3. 解析 JSON: {domainName, description, markdownSop}│
 *   │    │                                                     │
 *   │    ▼  4. I/O 落盘                                         │
 *   │  /vfs/system/sops/{domainName}_sop.md   (SopManager)      │
 *   │  /system/drivers/sops/{domainName}.json (MoEGatingRouter) │
 *   │    │                                                     │
 *   │    ▼  5. 热重载                                           │
 *   │  SopManager.refreshCache() + MoEGatingRouter.refreshCache()│
 *   │    │                                                     │
 *   │    ▼  6. 恢复执行 (Resume)                                │
 *   │  TopologyCompiler.compile(userRequest) → WorkflowEngine   │
 *   └─────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>设计哲学</b>：遇到不会的问题 → 自己写技能 → 掌握技能并继续执行。
 * 类比 CPU 的 JIT 编译器：解释执行遇到热点路径时，即时编译为机器码。
 * SkillForgeAgent 在运行时为未知领域即时锻造 SOP 驱动，无需重启系统。
 *
 * @see SkillFaultException
 * @see MoEGatingRouter
 * @see SopManager
 * @see TopologyCompiler
 */
public class SkillForgeAgent {

    private static final Logger log = LoggerFactory.getLogger(SkillForgeAgent.class);

    /** SOP 规则文本的 VFS 路径前缀（SopManager 兼容格式） */
    private static final String SOP_MD_VFS_ROOT = "/vfs/system/sops/";

    /** SopDescriptor JSON 的 VFS 路径前缀（MoEGatingRouter 扫描格式） */
    private static final String SOP_JSON_VFS_ROOT = "/system/drivers/sops/";

    /** LLM 后端标识 */
    private static final String FORGE_BACKEND = "skill_forge";

    private static final class Holder {
        static final SkillForgeAgent INSTANCE = new SkillForgeAgent();
    }

    public static SkillForgeAgent getInstance() {
        return Holder.INSTANCE;
    }

    private SkillForgeAgent() {
    }

    // ════════════════════════════════════════════════════════════════
    //  公开 API — 锻造入口
    // ════════════════════════════════════════════════════════════════

    /**
     * JIT 锻造 + 恢复执行 — 完整的"自我进化回路"。
     * <p>
     * 当捕获到 {@link SkillFaultException} 时调用此方法：
     * <ol>
     *   <li>从异常中提取被挂起的任务和可用工具</li>
     *   <li>调用 P_CORE LLM 为未知领域锻造 SOP</li>
     *   <li>将 SOP 写入 VFS 驱动目录</li>
     *   <li>热重载路由矩阵</li>
     *   <li>重新编译并下发被挂起的任务</li>
     * </ol>
     *
     * @param fault 缺能中断异常（携带 AiosTask + ToolRegistry 现场）
     * @return 锻造出的新 SOP 领域描述符
     * @throws RuntimeException 如果锻造失败（LLM 不可用 / JSON 解析失败 / 写盘失败）
     */
    public SopDescriptor forgeAndResume(SkillFaultException fault) {
        if (fault == null) {
            throw new IllegalArgumentException("SkillFaultException 不能为 null");
        }

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("[SkillForge] 🔥 JIT 技能锻造炉启动 — 认知盲区 detected");
        log.info("[SkillForge]   缺能领域: {}", fault.bestMatchDomain());
        log.info("[SkillForge]   最高得分: {}", String.format("%.4f", fault.bestScore()));
        log.info("[SkillForge]   认知阈值: {}", String.format("%.4f", fault.threshold()));
        log.info("═══════════════════════════════════════════════════════════════");

        long startMs = System.currentTimeMillis();

        // ── Step 1: 提取锻造上下文 ──
        AiosTask task = fault.task();
        ToolRegistry toolRegistry = fault.toolRegistry();
        String userRequest = extractUserRequest(task);
        String toolContracts = exportToolContracts(toolRegistry);

        log.info("[SkillForge] [Step 1] 上下文提取完成: task={}, tools={}",
                task != null ? task.taskId() : "(null)",
                toolRegistry != null ? toolRegistry.all().size() : 0);

        // ── Step 2: LLM 驱动生成 SOP ──
        ForgeResult forgeResult = callLlmToForgeSop(userRequest, toolContracts);
        log.info("[SkillForge] [Step 2] LLM 锻造完成: domain={}, sopLen={}",
                forgeResult.domainName(), forgeResult.markdownSop().length());

        // ── Step 3: I/O 落盘 ──
        SopDescriptor descriptor = persistSop(forgeResult);
        log.info("[SkillForge] [Step 3] SOP 已落盘: {} → VFS", descriptor.domainName());

        // ── Step 4: 热重载 ──
        hotReload();
        log.info("[SkillForge] [Step 4] 热重载完成 — 路由矩阵已刷新");

        // ── Step 5: 恢复执行 ──
        resumeTask(task, userRequest);

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("[SkillForge] ✅ JIT 锻造回路完成 ({}ms) — 新领域 '{}' 已注入系统",
                elapsedMs, descriptor.domainName());
        log.info("═══════════════════════════════════════════════════════════════");

        return descriptor;
    }

    /**
     * 仅锻造 SOP，不恢复执行（供外部调用方自行控制恢复流程）。
     *
     * @param fault 缺能中断异常
     * @return 锻造出的新 SOP 领域描述符
     */
    public SopDescriptor forge(SkillFaultException fault) {
        if (fault == null) {
            throw new IllegalArgumentException("SkillFaultException 不能为 null");
        }

        AiosTask task = fault.task();
        ToolRegistry toolRegistry = fault.toolRegistry();
        String userRequest = extractUserRequest(task);
        String toolContracts = exportToolContracts(toolRegistry);

        ForgeResult forgeResult = callLlmToForgeSop(userRequest, toolContracts);
        SopDescriptor descriptor = persistSop(forgeResult);
        hotReload();

        return descriptor;
    }

    // ════════════════════════════════════════════════════════════════
    //  Step 1: 锻造上下文提取
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 AiosTask 中提取用户原始需求。
     */
    private String extractUserRequest(AiosTask task) {
        if (task == null) {
            return "(未知任务 — AiosTask 为 null)";
        }
        String desc = task.description();
        if (desc != null && !desc.isBlank()) {
            return desc;
        }
        String name = task.name();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return "(任务 ID: " + task.taskId() + ")";
    }

    /**
     * 从 ToolRegistry 中动态导出所有可用原子工具的 I/O 契约。
     * <p>
     * 格式示例：
     * <pre>
     * ## 可用原子工具契约
     *
     * ### file_write
     * 描述: 写文件到磁盘
     * Input:  [file_path: FilePath (目标文件路径), content: PlainText (文件内容)]
     * Output: [result: PlainText (写入结果)]
     *
     * ### bash
     * 描述: 执行 Shell 命令
     * Input:  [command: ShellCommand (要执行的命令), workdir: PlainText (工作目录)]
     * Output: [output: CommandOutput (命令输出)]
     * </pre>
     *
     * @param toolRegistry 工具注册表（可能为 null）
     * @return 格式化的工具契约列表
     */
    private String exportToolContracts(ToolRegistry toolRegistry) {
        if (toolRegistry == null) {
            toolRegistry = ToolRegistry.instance();
        }

        var tools = toolRegistry.all();
        if (tools.isEmpty()) {
            return "(系统当前无已注册工具)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 可用原子工具契约 (").append(tools.size()).append(" 个)\n\n");

        for (var tool : tools) {
            sb.append("### ").append(tool.name()).append("\n");

            String desc = tool.description();
            if (desc != null && !desc.isBlank()) {
                sb.append("描述: ").append(desc).append("\n");
            }

            // 导出 Input 端口
            List<Port> inputs = tool.inputPorts();
            if (inputs != null && !inputs.isEmpty()) {
                sb.append("Input:  [");
                for (int i = 0; i < inputs.size(); i++) {
                    Port p = inputs.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(p.name()).append(": ").append(p.dataType());
                    if (p.required()) sb.append("*");
                    if (p.description() != null && !p.description().isEmpty()) {
                        sb.append(" (").append(p.description()).append(")");
                    }
                }
                sb.append("]\n");
            } else {
                sb.append("Input:  (无)\n");
            }

            // 导出 Output 端口
            List<Port> outputs = tool.outputPorts();
            if (outputs != null && !outputs.isEmpty()) {
                sb.append("Output: [");
                for (int i = 0; i < outputs.size(); i++) {
                    Port p = outputs.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(p.name()).append(": ").append(p.dataType());
                    if (p.required()) sb.append("*");
                    if (p.description() != null && !p.description().isEmpty()) {
                        sb.append(" (").append(p.description()).append(")");
                    }
                }
                sb.append("]\n");
            } else {
                sb.append("Output: (无)\n");
            }

            sb.append("\n");
        }

        sb.append("(* = 必填端口)\n");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  Step 2: LLM 驱动生成
    // ════════════════════════════════════════════════════════════════

    /**
     * 调用 P_CORE LLM 为未知领域锻造 SOP。
     * <p>
     * System Prompt 设定 LLM 为"JIT 驱动工程师"角色，
     * 要求使用微任务化 (Micro-tasking) 和现有原子工具，
     * 返回 JSON: {domainName, description, markdownSop}
     *
     * @param userRequest   用户的原始任务需求
     * @param toolContracts 可用工具的 I/O 契约列表
     * @return 锻造结果（领域名 + 描述 + SOP Markdown）
     */
    private ForgeResult callLlmToForgeSop(String userRequest, String toolContracts) {
        String systemPrompt = """
                你是 AIOS 内核的 JIT 驱动工程师。系统遇到了一个未知领域的任务，
                当前没有匹配的 SOP（标准作业流程）驱动。
                你的职责是：为这个新领域即时锻造一个通用的执行 SOP。

                【锻造铁律】
                1. 微任务化 (Micro-tasking)：SOP 中的每个步骤必须极其单一、确定，
                   且最多只能调用 1 到 2 个系统已有的原子工具。
                2. 工具复用：只能使用系统当前已注册的原子工具，不能臆造不存在的工具。
                3. 数据流转：明确指出每个步骤的输入从哪里来、输出到哪里去。
                4. 通用性：SOP 必须是该领域的通用流程，而非针对单个任务的临时方案。

                【输出格式 — 必须严格遵守】
                你必须返回纯 JSON（不要 Markdown 标记），包含三个字段：
                {
                  "domainName": "领域名（大写蛇形，如 QUANTUM_COMPUTING）",
                  "description": "领域的一句话描述（供后续匹配计算使用）",
                  "markdownSop": "Markdown 格式的标准作业流程（可包含多行，用 \\n 转义换行）"
                }

                注意：
                - domainName 必须是大写蛇形命名（UPPER_SNAKE_CASE）
                - description 应简明扼要，概括该领域的核心能力
                - markdownSop 是完整的 Markdown 文档，包含步骤、工具调用、数据流转
                """;

        String userPrompt = """
                【未知任务】
                %s

                【当前系统可用的原子工具契约】
                %s

                请为这个任务所属的领域锻造一个通用的 SOP 驱动。
                记住：只能使用上述已注册的原子工具，必须微任务化。
                返回 JSON（不要 Markdown 标记）：
                """.formatted(userRequest, toolContracts);

        // 调用 P_CORE（最聪明的大模型）
        String llmResponse;
        try {
            if (LlmRouterHolder.isInitialized()) {
                llmResponse = LlmRouterHolder.get().think(userPrompt, systemPrompt);
            } else {
                // 降级：使用 VfsManager 的 LlmProvider
                var provider = VfsManager.instance().getLlmProvider();
                if (provider == null || !provider.isAvailable()) {
                    throw new RuntimeException("无可用的 LLM Provider — 无法锻造 SOP");
                }
                llmResponse = provider.think(userPrompt, systemPrompt);
            }
        } catch (Exception e) {
            log.error("[SkillForge] LLM 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("JIT 锻造失败：LLM 不可用 — " + e.getMessage(), e);
        }

        if (llmResponse == null || llmResponse.isBlank()) {
            throw new RuntimeException("JIT 锻造失败：LLM 返回空响应");
        }

        log.debug("[SkillForge] LLM 原始响应长度: {}", llmResponse.length());

        // 解析 JSON 响应
        ForgeResult result = parseForgeResult(llmResponse);
        if (result == null) {
            throw new RuntimeException("JIT 锻造失败：无法解析 LLM 返回的 JSON");
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  Step 2b: JSON 解析
    // ════════════════════════════════════════════════════════════════

    /**
     * 解析 LLM 返回的锻造结果 JSON。
     * <p>
     * 提取三个字段：domainName, description, markdownSop。
     * markdownSop 可能包含转义的换行符（\n）和引号（\"）。
     *
     * @param response LLM 原始响应
     * @return 锻造结果，解析失败返回 null
     */
    private ForgeResult parseForgeResult(String response) {
        if (response == null || response.isBlank()) return null;

        // 清理 Markdown 标记
        String cleaned = response.trim()
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .replaceAll("(?s)<think.*?</think>", "")
                .trim();

        // 提取 domainName
        String domainName = extractJsonStringField(cleaned, "domainName");
        if (domainName == null || domainName.isBlank()) {
            log.error("[SkillForge] JSON 缺少 domainName 字段");
            return null;
        }

        // 提取 description
        String description = extractJsonStringField(cleaned, "description");
        if (description == null) {
            description = domainName; // 降级：用 domainName 作为描述
        }

        // 提取 markdownSop（可能包含转义字符）
        String markdownSop = extractJsonStringField(cleaned, "markdownSop");
        if (markdownSop == null || markdownSop.isBlank()) {
            log.error("[SkillForge] JSON 缺少 markdownSop 字段");
            return null;
        }

        // 反转义 JSON 字符串
        markdownSop = unescapeJson(markdownSop);
        description = unescapeJson(description);

        log.info("[SkillForge] 解析成功: domain={}, descLen={}, sopLen={}",
                domainName, description.length(), markdownSop.length());

        return new ForgeResult(domainName.trim(), description.trim(), markdownSop);
    }

    /**
     * 从 JSON 中提取字符串字段值（支持转义字符）。
     * <p>
     * 此方法比简单正则更健壮：它会跟踪转义序列，
     * 正确处理包含引号和换行符的字符串值。
     *
     * @param json JSON 字符串
     * @param key  字段名
     * @return 字段值（已去除首尾引号），未找到返回 null
     */
    private String extractJsonStringField(String json, String key) {
        // 查找 "key" 位置
        String keyPattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(keyPattern);
        if (keyIdx < 0) {
            // 尝试不区分大小写
            keyIdx = json.toLowerCase().indexOf(keyPattern.toLowerCase());
            if (keyIdx < 0) return null;
        }

        // 查找冒号后的位置
        int colonIdx = json.indexOf(':', keyIdx + keyPattern.length());
        if (colonIdx < 0) return null;

        // 跳过冒号后的空白
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        // 查找开头的引号
        if (start >= json.length() || json.charAt(start) != '"') return null;
        start++; // 跳过开头引号

        // 扫描到结尾引号（跟踪转义）
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                // 转义序列 — 保留原样（后续 unescapeJson 会处理）
                sb.append(c).append(json.charAt(i + 1));
                i += 2;
            } else if (c == '"') {
                // 非转义引号 — 字符串结束
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }

        // 未找到结尾引号 — 返回已提取的部分
        log.warn("[SkillForge] JSON 字段 '{}' 未找到结尾引号", key);
        return sb.toString();
    }

    /**
     * 反转义 JSON 字符串中的转义序列。
     */
    private String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    // ════════════════════════════════════════════════════════════════
    //  Step 3: I/O 落盘
    // ════════════════════════════════════════════════════════════════

    /**
     * 将锻造结果写入 VFS 驱动目录。
     * <p>
     * 同时写入两个位置：
     * <ul>
     *   <li>{@code /vfs/system/sops/{domainName}_sop.md} — SopManager 读取的 SOP 规则文本</li>
     *   <li>{@code /system/drivers/sops/{domainName}.json} — MoEGatingRouter 扫描的 SopDescriptor</li>
     * </ul>
     *
     * @param result LLM 锻造结果
     * @return 构建的 SopDescriptor
     */
    private SopDescriptor persistSop(ForgeResult result) {
        VfsManager vfs = VfsManager.instance();

        String domainName = result.domainName();

        // 1. 写入 SOP Markdown 规则文本（SopManager 兼容格式）
        String mdPath = SOP_MD_VFS_ROOT + domainName + "_sop.md";
        try {
            vfs.writeText(mdPath, result.markdownSop());
            log.info("[SkillForge] SOP Markdown 已写入: {}", mdPath);
        } catch (Exception e) {
            log.error("[SkillForge] 写入 SOP Markdown 失败: {} - {}", mdPath, e.getMessage());
            throw new RuntimeException("I/O 落盘失败: " + e.getMessage(), e);
        }

        // 2. 写入 SopDescriptor JSON（MoEGatingRouter 扫描格式）
        SopDescriptor descriptor = new SopDescriptor(
                domainName,
                result.description(),
                inferRequiredTools(result.markdownSop())
        );

        String jsonPath = SOP_JSON_VFS_ROOT + domainName + ".json";
        String json = sopDescriptorToJson(descriptor);
        try {
            vfs.writeText(jsonPath, json);
            log.info("[SkillForge] SopDescriptor JSON 已写入: {}", jsonPath);
        } catch (Exception e) {
            log.error("[SkillForge] 写入 SopDescriptor JSON 失败: {} - {}", jsonPath, e.getMessage());
            throw new RuntimeException("I/O 落盘失败: " + e.getMessage(), e);
        }

        return descriptor;
    }

    /**
     * 从 SOP Markdown 中推断必需的工具列表。
     * <p>
     * 扫描 Markdown 文本中提到的工具名称，
     * 与 ToolRegistry 中已注册的工具进行匹配。
     */
    private List<String> inferRequiredTools(String markdownSop) {
        java.util.Set<String> tools = new java.util.LinkedHashSet<>();
        var registered = ToolRegistry.instance().all();

        for (var tool : registered) {
            String toolName = tool.name();
            if (markdownSop.toLowerCase().contains(toolName.toLowerCase())) {
                tools.add(toolName);
            }
        }

        if (tools.isEmpty()) {
            log.warn("[SkillForge] SOP Markdown 中未匹配到任何已注册工具");
            return List.of();
        }

        return List.copyOf(tools);
    }

    /**
     * 将 SopDescriptor 序列化为 JSON 字符串。
     */
    private String sopDescriptorToJson(SopDescriptor sop) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"domainName\": \"").append(escapeJson(sop.domainName())).append("\",\n");
        sb.append("  \"description\": \"").append(escapeJson(sop.description())).append("\",\n");
        sb.append("  \"requiredTools\": [");
        for (int i = 0; i < sop.requiredTools().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(escapeJson(sop.requiredTools().get(i))).append("\"");
        }
        sb.append("]\n");
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    // ════════════════════════════════════════════════════════════════
    //  Step 4: 热重载
    // ════════════════════════════════════════════════════════════════

    /**
     * 热重载路由矩阵 — 刷新 SOP 缓存使新锻造的驱动立即生效。
     * <p>
     * 刷新两个缓存：
     * <ul>
     *   <li>{@link SopManager#refreshCache()} — SOP 规则文本缓存</li>
     *   <li>{@link MoEGatingRouter#refreshCache()} — SopDescriptor + 向量缓存</li>
     * </ul>
     */
    private void hotReload() {
        try {
            SopManager.getInstance().refreshCache();
            log.info("[SkillForge] SopManager 缓存已刷新");
        } catch (Exception e) {
            log.warn("[SkillForge] SopManager 缓存刷新失败: {}", e.getMessage());
        }

        try {
            MoEGatingRouter.getInstance().refreshCache();
            log.info("[SkillForge] MoEGatingRouter 缓存已刷新");
        } catch (Exception e) {
            log.warn("[SkillForge] MoEGatingRouter 缓存刷新失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Step 5: 恢复执行 (Resume)
    // ════════════════════════════════════════════════════════════════

    /**
     * 恢复被挂起的任务 — 重新路由 + 重新编译 + 重新部署。
     * <p>
     * 锻造 SOP 后，原任务的认知盲区已被消除：
     * <ol>
     *   <li>通过 MoEGatingRouter 重新路由（新 SOP 应匹配成功）</li>
     *   <li>通过 TopologyCompiler 重新编译 DAG 拓扑</li>
     *   <li>TopologyCompiler 内部会调用 WorkflowEngine 部署工作流</li>
     * </ol>
     *
     * @param task        被挂起的原始任务
     * @param userRequest 用户的原始需求
     */
    private void resumeTask(AiosTask task, String userRequest) {
        log.info("[SkillForge] [Step 5] 恢复执行 — 重新路由 + 编译 + 部署");

        try {
            // 1. 重新路由（新 SOP 应匹配成功）
            List<SopDescriptor> matched = MoEGatingRouter.getInstance().route(userRequest, task);
            log.info("[SkillForge]   重新路由成功: {} 个领域匹配",
                    matched.size());

            // 2. 重新编译 + 部署
            // TopologyCompiler.compile() 内部会调用 WorkflowEngine.executeWorkflow()
            WorkflowManifest manifest = TopologyCompiler.getInstance().compile(userRequest);
            log.info("[SkillForge]   重新编译完成: {} 个节点, {} 条边",
                    manifest.nodes().size(), manifest.edges().size());

        } catch (SkillFaultException e) {
            // 锻造后仍然无法匹配 — 放弃
            log.error("[SkillForge]   ⚠ 锻造后重新路由仍然失败！bestScore={}",
                    String.format("%.4f", e.bestScore()));
            throw new RuntimeException(
                    "JIT 锻造回路失败：即使锻造了新 SOP，系统仍无法匹配用户任务。"
                    + "可能需要人工介入。", e);

        } catch (Exception e) {
            log.error("[SkillForge]   恢复执行失败: {}", e.getMessage(), e);
            throw new RuntimeException("任务恢复失败: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据类
    // ════════════════════════════════════════════════════════════════

    /**
     * LLM 锻造结果 — 从 JSON 解析出的三个字段。
     */
    private record ForgeResult(
            /** 领域名（大写蛇形，如 QUANTUM_COMPUTING） */
            String domainName,
            /** 领域描述（供后续匹配计算） */
            String description,
            /** SOP Markdown 规则文本 */
            String markdownSop
    ) {}
}
