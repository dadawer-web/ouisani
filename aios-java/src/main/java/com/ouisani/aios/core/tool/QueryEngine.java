package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询引擎 — AIOS 的核心推理循环，对标 Claude Code 的 query.ts。
 * <p>
 * 实现完整的 Agent Loop：
 * 1. 用户输入 → 构建系统提示词
 * 2. 调用 LLM → 解析响应
 * 3. 检测工具调用 → 执行工具 → 将结果反馈给 LLM
 * 4. 重复直到 LLM 不再调用工具（生成纯文本回复）
 * <p>
 * OS 类比：相当于 CPU 的指令周期 — 取指(IF) → 译码(ID) → 执行(EX) → 写回(WB)，
 * 直到遇到 HALT 指令。
 */
public class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);

    private static final int MAX_TOOL_ROUNDS = 20;

    private final AiosSdk sdk;
    private final String agentId;
    private final String workingDir;
    private final List<Tool<? extends ToolInput>> availableTools;
    private final PermissionChecker permissionChecker;

    /**
     * 工具调用解析模式 — 匹配 XML 格式的工具调用：
     * <tool_name>{"param":"value"}</tool_name>
     * 或 function_call JSON 格式
     */
    private static final Pattern TOOL_CALL_XML = Pattern.compile(
            "<(\\w+)>(.*?)</\\1>", Pattern.DOTALL);

    /**
     * 创建查询引擎。
     *
     * @param sdk       AIOS SDK
     * @param agentId   Agent ID
     * @param workingDir 工作目录
     */
    public QueryEngine(AiosSdk sdk, String agentId, String workingDir) {
        this.sdk = sdk;
        this.agentId = agentId;
        this.workingDir = workingDir;
        this.availableTools = new ArrayList<>(ToolRegistry.instance().all());
        this.permissionChecker = new PermissionChecker();
    }

    /**
     * 执行一次完整的查询循环。
     *
     * @param userMessage 用户输入
     * @return 最终的文本回复
     */
    public String query(String userMessage) {
        return query(userMessage, "");
    }

    /**
     * 执行一次完整的查询循环，带额外系统上下文。
     *
     * @param userMessage      用户输入
     * @param systemContext    额外的系统上下文（如 RAG 搜索结果）
     * @return 最终的文本回复
     */
    public String query(String userMessage, String systemContext) {
        log.info("[QueryEngine] Starting query loop for agent={}, message length={}", agentId, userMessage.length());

        // 构建系统提示词
        String systemPrompt = buildSystemPrompt(systemContext);

        // 构建完整 prompt（包含工具描述）
        String fullPrompt = systemPrompt + "\n\n" + userMessage;

        // Agent Loop — 最多 MAX_TOOL_ROUNDS 轮
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            log.info("[QueryEngine] Round {}/{}", round + 1, MAX_TOOL_ROUNDS);

            // 调用 LLM
            String llmResponse = sdk.think(agentId, fullPrompt);

            // 检测工具调用
            List<ToolCall> toolCalls = parseToolCalls(llmResponse);

            if (toolCalls.isEmpty()) {
                // 没有工具调用，返回纯文本回复
                log.info("[QueryEngine] No tool calls detected, returning final response");
                return llmResponse;
            }

            // 执行工具调用
            StringBuilder toolResults = new StringBuilder();
            for (ToolCall tc : toolCalls) {
                log.info("[QueryEngine] Executing tool: {} (round {})", tc.toolName, round + 1);
                System.out.printf("[QueryEngine] ├─ Tool call: %s%n", tc.toolName);

                String result = executeTool(tc);
                toolResults.append("<tool_result name=\"").append(tc.toolName).append("\">\n");
                toolResults.append(result).append("\n");
                toolResults.append("</tool_result>\n\n");
            }

            // 将工具结果追加到对话上下文，继续循环
            fullPrompt = fullPrompt + "\n\nAssistant: " + llmResponse
                    + "\n\nTool Results:\n" + toolResults
                    + "\n\nPlease continue based on the tool results above.";
        }

        log.warn("[QueryEngine] Max tool rounds ({}) reached", MAX_TOOL_ROUNDS);
        return "Query reached maximum tool execution rounds (" + MAX_TOOL_ROUNDS + ").";
    }

    /**
     * 构建系统提示词 — 包含工具描述和使用指南。
     */
    private String buildSystemPrompt(String extraContext) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an AIOS agent with access to the following tools:\n\n");

        // 工具列表
        for (Tool<? extends ToolInput> tool : availableTools) {
            sb.append("### ").append(tool.name()).append("\n");
            sb.append(tool.description()).append("\n");
            sb.append("Input: ").append(tool.inputSchema()).append("\n");
            String p = tool.prompt();
            if (p != null && !p.isBlank()) {
                sb.append("Notes: ").append(p).append("\n");
            }
            sb.append("\n");
        }

        // 工具调用格式说明
        sb.append("## Tool Call Format\n");
        sb.append("To call a tool, use XML tags with the tool name and JSON parameters:\n");
        sb.append("<tool_name>{\"param\":\"value\"}</tool_name>\n");
        sb.append("You may call multiple tools in one response. After receiving tool results, continue your reasoning.\n\n");

        // 额外上下文
        if (extraContext != null && !extraContext.isBlank()) {
            sb.append("## Additional Context\n");
            sb.append(extraContext).append("\n\n");
        }

        sb.append("## Working Directory\n");
        sb.append(workingDir != null ? workingDir : System.getProperty("user.dir")).append("\n");

        return sb.toString();
    }

    /**
     * 解析 LLM 响应中的工具调用。
     */
    private List<ToolCall> parseToolCalls(String response) {
        List<ToolCall> calls = new ArrayList<>();
        Matcher matcher = TOOL_CALL_XML.matcher(response);
        while (matcher.find()) {
            String toolName = matcher.group(1);
            String params = matcher.group(2).trim();
            calls.add(new ToolCall(toolName, params));
        }
        return calls;
    }

    /**
     * 执行单个工具调用（含权限检查）。
     */
    @SuppressWarnings("unchecked")
    private String executeTool(ToolCall tc) {
        Optional<Tool<ToolInput>> opt = ToolRegistry.instance().get(tc.toolName);

        if (opt.isEmpty()) {
            String msg = "Unknown tool: " + tc.toolName + ". Available tools: "
                    + availableTools.stream().map(Tool::name).reduce((a, b) -> a + ", " + b).orElse("none");
            log.warn("[QueryEngine] {}", msg);
            return msg;
        }

        Tool<ToolInput> tool = opt.get();
        ToolContext context = new ToolContext(agentId, sdk, workingDir);

        try {
            ToolInput input = parseInput(tc.paramsJson(), tool);

            // ── 权限检查 ──
            PermissionDecision decision = permissionChecker.checkPermission(tool, input, context);
            if (decision.isDenied()) {
                log.warn("[QueryEngine] Tool '{}' denied: {}", tc.toolName, decision.message());
                return "Permission denied: " + decision.message();
            }
            if (decision.needsPrompt()) {
                log.info("[QueryEngine] Tool '{}' requires confirmation (auto-allowing in agent mode)", tc.toolName);
            }

            // ── PreToolUse Hook ──
            Map<String, Object> hookData = new HashMap<>();
            hookData.put("tool_name", tc.toolName);
            hookData.put("input", input.toJson());
            HookManager.HookResult preResult = HookManager.instance().trigger(
                    HookManager.HookEvent.PRE_TOOL_USE, hookData);
            if (!preResult.proceed()) {
                return "Blocked by PreToolUse hook: " + preResult.message();
            }

            // ── 执行工具 ──
            long startTime = System.currentTimeMillis();
            ToolOutput output = tool.call(input, context);
            long duration = System.currentTimeMillis() - startTime;

            // ── PostToolUse Hook ──
            Map<String, Object> postData = new HashMap<>();
            postData.put("tool_name", tc.toolName);
            postData.put("success", output.success());
            postData.put("duration_ms", duration);
            HookManager.instance().trigger(HookManager.HookEvent.POST_TOOL_USE, postData);

            // ── 遥测记录 ──
            TelemetryService.instance().recordToolUsage(tc.toolName, duration);

            log.info("[QueryEngine] Tool '{}' completed: success={} ({}ms)", tc.toolName, output.success(), duration);
            return output.toText();
        } catch (Exception e) {
            log.error("[QueryEngine] Tool '{}' execution failed: {}", tc.toolName, e.getMessage());
            return "Tool execution error: " + e.getMessage();
        }
    }

    /**
     * 简单的 JSON 参数解析 — 根据工具类型构造对应的 Input record。
     * <p>
     * 这是一个简化的实现，生产环境应使用 Jackson/Gson 进行完整解析。
     */
    private ToolInput parseInput(String paramsJson, Tool<ToolInput> tool) {
        String toolName = tool.name();

        return switch (toolName) {
            case "bash" -> new BashTool.Input(extractJsonString(paramsJson, "command"),
                    extractJsonInt(paramsJson, "timeoutSeconds", 120));
            case "file_read" -> new FileReadTool.Input(extractJsonString(paramsJson, "path"),
                    extractJsonInt(paramsJson, "offset", 0),
                    extractJsonInt(paramsJson, "limit", 2000));
            case "file_edit" -> new FileEditTool.Input(extractJsonString(paramsJson, "path"),
                    extractJsonString(paramsJson, "old_string"),
                    extractJsonString(paramsJson, "new_string"));
            case "file_write" -> new FileWriteTool.Input(extractJsonString(paramsJson, "path"),
                    extractJsonString(paramsJson, "content"));
            case "grep" -> new GrepTool.Input(extractJsonString(paramsJson, "pattern"),
                    extractJsonString(paramsJson, "path", "."),
                    extractJsonString(paramsJson, "glob", ""),
                    extractJsonInt(paramsJson, "contextLines", 0));
            case "glob" -> new GlobTool.Input(extractJsonString(paramsJson, "pattern"),
                    extractJsonString(paramsJson, "path", "."));
            case "web_fetch" -> new WebFetchTool.Input(extractJsonString(paramsJson, "url"),
                    extractJsonString(paramsJson, "prompt", ""));
            case "agent" -> new AgentTool.Input(
                    extractJsonString(paramsJson, "prompt"),
                    extractJsonString(paramsJson, "subagent_type", ""),
                    extractJsonBool(paramsJson, "run_in_background", false),
                    extractJsonString(paramsJson, "description", ""));
            case "todo_write" -> new TodoWriteTool.Input(List.of()); // 简化：完整解析需 Jackson
            case "ask_user_question" -> new AskUserQuestionTool.Input(
                    List.of(new AskUserQuestionTool.Question(
                            extractJsonString(paramsJson, "question", "No question"),
                            extractJsonString(paramsJson, "header", "?"),
                            List.of(new AskUserQuestionTool.QuestionOption("A", "Option A")),
                            false)));
            case "plan_mode" -> new PlanModeTool.Input(
                    extractJsonString(paramsJson, "action", "enter"),
                    extractJsonString(paramsJson, "plan", ""));
            default -> throw new IllegalArgumentException("Unknown tool for input parsing: " + toolName);
        };
    }

    // ── 简易 JSON 提取工具 ──

    private static String extractJsonString(String json, String key) {
        return extractJsonString(json, key, "");
    }

    private static String extractJsonString(String json, String key, String defaultVal) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\n", "\n") : defaultVal;
    }

    private static int extractJsonInt(String json, String key, int defaultVal) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultVal;
    }

    private static boolean extractJsonBool(String json, String key, boolean defaultVal) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)");
        Matcher m = p.matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : defaultVal;
    }

    /**
     * 工具调用记录。
     */
    private record ToolCall(String toolName, String paramsJson) {}
}
