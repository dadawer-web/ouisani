package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

    private static final int MAX_TOOL_ROUNDS = 60;

    static {
        log.info("[InstructionDecoder] Enhanced robust string pointer parsing active.");
        System.out.println("  \u001B[36m[InstructionDecoder] Enhanced robust string pointer parsing active.\u001B[0m");
    }

    private final AiosSdk sdk;
    private final String agentId;
    private final String workingDir;
    private final List<Tool<? extends ToolInput>> availableTools;
    private final PermissionChecker permissionChecker;

    /**
     * 创建查询引擎（仅使用内核全局工具）。
     *
     * @param sdk       AIOS SDK
     * @param agentId   Agent ID
     * @param workingDir 工作目录
     */
    public QueryEngine(AiosSdk sdk, String agentId, String workingDir) {
        this(sdk, agentId, workingDir, List.of());
    }

    /**
     * 创建查询引擎（内核全局工具 + 用户空间扩展工具）。
     * <p>
     * 内核工具（Bash, FileRead/Edit/Write 等）对所有 Agent 可用；
     * 扩展工具（TodoWrite, PlanMode 等）仅对具备高级认知能力的 Agent 可用。
     *
     * @param sdk           AIOS SDK
     * @param agentId       Agent ID
     * @param workingDir    工作目录
     * @param extraTools    用户空间扩展工具列表
     */
    public QueryEngine(AiosSdk sdk, String agentId, String workingDir,
                       List<Tool<? extends ToolInput>> extraTools) {
        this.sdk = sdk;
        this.agentId = agentId;
        this.workingDir = workingDir;
        this.availableTools = new ArrayList<>(ToolRegistry.instance().all());
        this.availableTools.addAll(extraTools);
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
        int consecutiveErrors = 0;
        final int MAX_CONSECUTIVE_ERRORS = 3;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            log.info("[QueryEngine] Round {}/{}", round + 1, MAX_TOOL_ROUNDS);

            // 调用 LLM（含异常防御）
            String llmResponse;
            try {
                llmResponse = sdk.think(agentId, fullPrompt);
                consecutiveErrors = 0; // 成功则重置错误计数
            } catch (Exception e) {
                consecutiveErrors++;
                String errMsg = "System Error during LLM inference: " + e.getMessage();
                log.error("\u001B[31m[QueryEngine] Caught exception. Feeding error back to LLM context. ({}/{})\u001B[0m",
                        consecutiveErrors, MAX_CONSECUTIVE_ERRORS, e);

                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    log.error("[QueryEngine] {} consecutive errors — aborting query loop", consecutiveErrors);
                    return "Query aborted after " + consecutiveErrors + " consecutive system errors. Last error: " + errMsg;
                }

                // 将错误注入对话历史，让 LLM 自主判断是否换方式重试
                fullPrompt = fullPrompt + "\n\n[System Error] " + errMsg
                        + "\n\nPlease adjust your approach and try again. If the error persists, inform the user.";
                continue;
            }

            // 检测工具调用
            List<ToolCall> toolCalls = parseToolCalls(llmResponse);

            if (toolCalls.isEmpty()) {
                // 没有工具调用，返回纯文本回复
                log.info("[QueryEngine] No tool calls detected, returning final response");
                return llmResponse;
            }

            // 执行工具调用（含异常防御）
            StringBuilder toolResults = new StringBuilder();
            for (ToolCall tc : toolCalls) {
                log.info("[QueryEngine] Executing tool: {} (round {})", tc.toolName, round + 1);
                System.out.printf("[QueryEngine] ├─ Tool call: %s%n", tc.toolName);

                String result;
                try {
                    result = executeTool(tc);
                    consecutiveErrors = 0; // 成功则重置错误计数
                } catch (Exception e) {
                    consecutiveErrors++;
                    result = "System Error during tool '" + tc.toolName + "' execution: " + e.getMessage();
                    log.error("\u001B[31m[QueryEngine] Tool '{}' threw exception. Feeding error back to LLM context. ({}/{})\u001B[0m",
                            tc.toolName, consecutiveErrors, MAX_CONSECUTIVE_ERRORS, e);

                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        log.error("[QueryEngine] {} consecutive errors — aborting query loop", consecutiveErrors);
                        return "Query aborted after " + consecutiveErrors + " consecutive system errors. Last error: " + result;
                    }
                }

                toolResults.append("<tool_result name=\"").append(tc.toolName).append("\">\n");
                // ── 上下文瘦身：防止工具输出过长导致 Prompt 膨胀 → API 超时 ──
                String compactedResult = result;
                if (result != null && result.length() > 1000) {
                    compactedResult = result.substring(0, 1000)
                            + "\n... [AIOS Kernel: 截断过多重复上下文以防止脑死亡, 原始长度="
                            + result.length() + "] ...";
                    log.info("[QueryEngine] Context memory compacted. Oversized tool outputs sliding-windowed. "
                            + "Tool '{}': {} → {} chars", tc.toolName, result.length(), compactedResult.length());
                }
                toolResults.append(compactedResult).append("\n");
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
     * 合法的工具名称集合 — 用于过滤 LLM 响应中的误匹配 XML 标签。
     * <p>
     * LLM 在描述工具格式时可能输出 &lt;tool_name&gt;{...}&lt;/tool_name&gt; 等示例标签，
     * 这些不应被解析为真正的工具调用。只有与已注册工具名匹配的标签才是合法调用。
     */
    private Set<String> registeredToolNames;

    /**
     * 解析 LLM 响应中的工具调用。
     * <p>
     * 使用纯字符串 indexOf 线性扫描，O(N) 时间复杂度，绝不触发 StackOverflowError。
     * 只匹配已注册工具名称的 XML 标签，过滤掉 LLM 输出的格式示例标签。
     * <p>
     * 匹配格式：{@code <tool_name>JSON params</tool_name>}
     */
    private List<ToolCall> parseToolCalls(String response) {
        if (registeredToolNames == null) {
            registeredToolNames = new HashSet<>();
            for (Tool<? extends ToolInput> tool : availableTools) {
                registeredToolNames.add(tool.name());
            }
        }

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
                ToolCall tc = parseToolCallContent(blockContent);
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
    private ToolCall parseToolCallContent(String blockContent) {
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
    private String cleanTagName(String tagName) {
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
    private int findToolTagStart(String text, int searchStart, java.util.Set<String> toolNames) {
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
    private int findCloseTag(String text, int searchStart, String tagName) {
        String closeTag = "</" + tagName + ">";
        return text.indexOf(closeTag, searchStart);
    }

    /** 从指定位置提取完整的 JSON 对象（花括号匹配，引号感知） */
    private String extractCompleteJsonObject(String text, int startIdx) {
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
    private String extractJsonFieldValue(String json, String fieldName) {
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
    private String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    /**
     * 执行单个工具调用（含权限检查）。
     * <p>
     * 查找顺序：先从当前引擎的 availableTools（含扩展工具）查找，
     * 再回退到全局 ToolRegistry 查找。
     */
    @SuppressWarnings("unchecked")
    private String executeTool(ToolCall tc) {
        // 优先从 availableTools 查找（包含用户空间扩展工具）
        Optional<Tool<ToolInput>> opt = availableTools.stream()
                .filter(t -> t.name().equals(tc.toolName))
                .map(t -> (Tool<ToolInput>) t)
                .findFirst();

        // 回退到全局 ToolRegistry
        if (opt.isEmpty()) {
            opt = ToolRegistry.instance().get(tc.toolName);
        }

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
            case "todo_write" -> new com.ouisani.aios.user.apps.omnifactory.tools.TodoWriteTool.Input(List.of()); // 简化：完整解析需 Jackson
            // ask_user_question 已删除 — 阻塞式人类 I/O 违反异步 IPC 原则
            // 智能体如需与人类交互，必须通过 send_message 发送 type:user_prompt 到 EventBus UI 频道
            case "plan_mode" -> new com.ouisani.aios.user.apps.omnifactory.tools.PlanModeTool.Input(
                    extractJsonString(paramsJson, "action", "enter"),
                    extractJsonString(paramsJson, "plan", ""));
            default -> throw new IllegalArgumentException("Unknown tool for input parsing: " + toolName);
        };
    }

    // ── 简易 JSON 提取工具（纯 indexOf 线性扫描，无正则，绝不 StackOverflow） ──

    private static String extractJsonString(String json, String key) {
        return extractJsonString(json, key, "");
    }

    private static String extractJsonString(String json, String key, String defaultVal) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return defaultVal;

        // 找到冒号后的值
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return defaultVal;

        // 跳过空白
        int valStart = colonIdx + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

        if (valStart >= json.length() || json.charAt(valStart) != '"') return defaultVal;

        // 提取引号内的字符串值，处理转义
        StringBuilder sb = new StringBuilder();
        int i = valStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i < json.length()) {
                    char next = json.charAt(i);
                    if (next == '"') sb.append('"');
                    else if (next == 'n') sb.append('\n');
                    else if (next == 't') sb.append('\t');
                    else if (next == '\\') sb.append('\\');
                    else sb.append(next);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    private static int extractJsonInt(String json, String key, int defaultVal) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return defaultVal;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return defaultVal;

        int valStart = colonIdx + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

        int valEnd = valStart;
        while (valEnd < json.length() && (Character.isDigit(json.charAt(valEnd)) || json.charAt(valEnd) == '-')) {
            valEnd++;
        }

        if (valEnd == valStart) return defaultVal;
        try {
            return Integer.parseInt(json.substring(valStart, valEnd));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static boolean extractJsonBool(String json, String key, boolean defaultVal) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return defaultVal;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return defaultVal;

        int valStart = colonIdx + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

        if (valStart + 4 <= json.length() && json.substring(valStart, valStart + 4).equals("true")) return true;
        if (valStart + 5 <= json.length() && json.substring(valStart, valStart + 5).equals("false")) return false;

        return defaultVal;
    }

    /**
     * 工具调用记录。
     */
    private record ToolCall(String toolName, String paramsJson) {}
}
