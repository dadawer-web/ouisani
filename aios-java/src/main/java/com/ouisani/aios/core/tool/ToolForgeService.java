package com.ouisani.aios.core.tool;

import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具锻造服务 — 借鉴 Agent Zero 的运行时工具生成模式。
 * <p>
 * 当 Agent 发现缺少某个工具时，通过此服务动态生成并注册工具。
 * <p>
 * 锻造流程：
 * 1. Agent 描述所需工具的功能
 * 2. LLM 生成 Python 代码实现
 * 3. 代码在沙箱中验证（语法检查）
 * 4. 通过 sys_register_tool 注册到 ToolRegistry
 * 5. 后续可直接调用
 * <p>
 * OS 类比：Linux 的 insmod — 运行时加载内核模块扩展能力。
 */
public class ToolForgeService {
    private static final Logger log = LoggerFactory.getLogger(ToolForgeService.class);

    private static final ToolForgeService INSTANCE = new ToolForgeService();

    /** 已锻造的工具：toolName → DynamicForgedTool */
    private final ConcurrentHashMap<String, DynamicForgedTool> forgedTools = new ConcurrentHashMap<>();

    /** 工具锻造记录：toolName → ForgedRecord */
    private final ConcurrentHashMap<String, ForgedRecord> forgeHistory = new ConcurrentHashMap<>();

    /** 锻造记录 */
    public record ForgedRecord(
            String toolName,
            String agentId,
            String description,
            long forgedAt,
            int invocationCount
    ) {}

    private ToolForgeService() {}

    public static ToolForgeService getInstance() { return INSTANCE; }

    /**
     * 锻造一个新工具 — 核心入口。
     * <p>
     * 使用 LLM 根据描述生成代码，验证后注册。
     *
     * @param description 工具功能描述（自然语言）
     * @param agentId     请求锻造的 Agent ID
     * @param sdk         AIOS SDK
     * @param workingDir  工作目录
     * @return 锻造成功的工具名称，失败返回 null
     */
    public String forge(String description, String agentId, AiosSdk sdk, String workingDir) {
        log.info("[ToolForge] Agent '{}' 请求锻造工具: {}", agentId, description);

        try {
            // 1. 用 LLM 生成工具代码
            String toolName = generateToolName(description);
            String prompt = buildForgePrompt(toolName, description);
            String llmResponse = sdk.think(agentId, prompt);

            // 2. 从 LLM 响应中提取代码
            String code = extractCode(llmResponse);
            if (code == null || code.isBlank()) {
                log.warn("[ToolForge] LLM 未生成有效代码: agent={}", agentId);
                return null;
            }

            // 3. 提取入口函数和描述
            String entryFunction = extractEntryFunction(code);
            String toolDesc = extractDescription(llmResponse, description);
            String inputSchema = extractInputSchema(llmResponse);

            // 4. 语法验证（通过 bash 执行 python3 -c "compile(...)"）
            boolean syntaxOk = validateSyntax(code, agentId, sdk, workingDir);
            if (!syntaxOk) {
                log.warn("[ToolForge] 工具代码语法验证失败: tool={}", toolName);
                // 不阻止注册，但记录警告
            }

            // 5. 创建并注册动态工具
            DynamicForgedTool tool = new DynamicForgedTool(
                    toolName, toolDesc, code, entryFunction, inputSchema,
                    agentId, sdk, workingDir
            );

            // 注册到 ToolRegistry
            ToolRegistry.instance().register(tool);

            // 记录锻造历史
            forgedTools.put(toolName, tool);
            forgeHistory.put(toolName, new ForgedRecord(toolName, agentId, toolDesc, System.currentTimeMillis(), 0));

            log.info("[ToolForge] 工具锻造成功: name='{}', agent='{}', codeLen={}", toolName, agentId, code.length());
            return toolName;

        } catch (Exception e) {
            log.error("[ToolForge] 工具锻造失败: agent='{}', error={}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * 调用已锻造的工具。
     */
    public String invokeForgedTool(String toolName, String argsJson, String agentId, AiosSdk sdk, String workingDir) {
        DynamicForgedTool tool = forgedTools.get(toolName);
        if (tool == null) {
            return "Error: Forged tool '" + toolName + "' not found";
        }

        ToolOutput result = tool.call(new DynamicForgedTool.Input(argsJson),
                new ToolContext(agentId, sdk, workingDir));

        // 更新调用计数
        ForgedRecord old = forgeHistory.get(toolName);
        if (old != null) {
            forgeHistory.put(toolName, new ForgedRecord(
                    old.toolName(), old.agentId(), old.description(),
                    old.forgedAt(), old.invocationCount() + 1
            ));
        }

        return result.toText();
    }

    /**
     * 列出所有已锻造的工具。
     */
    public Map<String, ForgedRecord> listForgedTools() {
        return Collections.unmodifiableMap(forgeHistory);
    }

    /**
     * 注销已锻造的工具。
     */
    public boolean unregisterForgedTool(String toolName) {
        forgedTools.remove(toolName);
        forgeHistory.remove(toolName);
        // 注意：ToolRegistry 目前没有 unregister 方法，这里只从锻造记录中移除
        log.info("[ToolForge] 工具已注销: name='{}'", toolName);
        return true;
    }

    /**
     * 注册外部创建的 DynamicForgedTool（供 SyscallDispatcher 调用）。
     */
    public void registerForgedTool(DynamicForgedTool tool, String agentId) {
        forgedTools.put(tool.name(), tool);
        forgeHistory.put(tool.name(), new ForgedRecord(
                tool.name(), agentId, tool.description(),
                System.currentTimeMillis(), 0
        ));
        log.info("[ToolForge] 外部工具已注册: name='{}', agent='{}'", tool.name(), agentId);
    }

    // ── 内部方法 ──

    private String generateToolName(String description) {
        // 从描述中提取关键词作为工具名
        String[] words = description.toLowerCase().split("[\\s,，。.]+");
        StringBuilder name = new StringBuilder("forged_");
        for (String word : words) {
            if (word.length() > 2 && name.length() < 30) {
                name.append(word).append("_");
            }
        }
        // 添加短 hash 避免冲突
        name.append(Integer.toHexString(description.hashCode() & 0xFFFF));
        return name.toString();
    }

    private String buildForgePrompt(String toolName, String description) {
        return """
            You are a tool code generator. Generate a Python script that implements the following tool:

            Tool Name: %s
            Description: %s

            Requirements:
            1. The script must define a main function: def main(args: dict) -> dict
            2. The function takes a dict of input arguments and returns a dict result
            3. Use only Python standard library (no pip install needed)
            4. Handle errors gracefully with try/except
            5. Return results as JSON-serializable dict

            Output format:
            ```python
            # your code here
            ```

            After the code, describe the input schema in JSON format:
            INPUT_SCHEMA: {"type":"object","properties":{...}}
            """.formatted(toolName, description);
    }

    private String extractCode(String llmResponse) {
        // 提取 ```python ... ``` 代码块
        int start = llmResponse.indexOf("```python");
        if (start < 0) start = llmResponse.indexOf("```");
        if (start < 0) return llmResponse; // 无代码块标记，整体当作代码

        start = llmResponse.indexOf('\n', start) + 1;
        int end = llmResponse.indexOf("```", start);
        if (end < 0) end = llmResponse.length();

        return llmResponse.substring(start, end).trim();
    }

    private String extractEntryFunction(String code) {
        // 默认入口函数为 main
        if (code.contains("def main(")) return "main";
        // 查找第一个 def 函数
        int idx = code.indexOf("def ");
        if (idx >= 0) {
            int paren = code.indexOf('(', idx);
            if (paren > idx) {
                return code.substring(idx + 4, paren).trim();
            }
        }
        return "main";
    }

    private String extractDescription(String llmResponse, String fallback) {
        // 尝试提取 LLM 生成的描述
        for (String line : llmResponse.split("\n")) {
            if (line.startsWith("DESCRIPTION:") || line.startsWith("Description:")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return fallback;
    }

    private String extractInputSchema(String llmResponse) {
        // 尝试提取 INPUT_SCHEMA
        for (String line : llmResponse.split("\n")) {
            if (line.startsWith("INPUT_SCHEMA:")) {
                return line.substring("INPUT_SCHEMA:".length()).trim();
            }
        }
        return null;
    }

    private boolean validateSyntax(String code, String agentId, AiosSdk sdk, String workingDir) {
        try {
            // 用 python3 -c "compile(...)" 验证语法
            String escapedCode = code.replace("'", "'\"'\"'");
            String command = "python3 -c \"compile('" + escapedCode.substring(0, Math.min(escapedCode.length(), 500)) +
                    "','<forged_tool>','exec')\" 2>&1";

            BashTool bashTool = new BashTool();
            BashTool.Input input = new BashTool.Input(command, 10);
            ToolOutput result = bashTool.call(input, new ToolContext(agentId, sdk, workingDir));
            return result.success() && !result.toText().toLowerCase().contains("syntaxerror");
        } catch (Exception e) {
            return false; // 验证失败不阻止注册
        }
    }
}
