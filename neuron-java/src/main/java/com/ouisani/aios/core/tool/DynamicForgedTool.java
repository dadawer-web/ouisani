package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 动态锻造工具 — 借鉴 Agent Zero 的运行时工具生成模式。
 * <p>
 * Agent 在执行任务时发现缺少某个特定工具，可以动态生成一段代码，
 * 将其注册为可复用的工具，实现能力的自生长。
 * <p>
 * OS 类比：Linux 的内核模块（LKM）— 运行时 insmod 加载，
 * 不需要重启系统即可扩展内核能力。
 */
public class DynamicForgedTool implements Tool<DynamicForgedTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(DynamicForgedTool.class);

    private final String toolName;
    private final String toolDescription;
    private final String code;
    private final String entryFunction;
    private final String inputSchema;
    private final String agentId;
    private final ToolSdk sdk;
    private final String workingDir;

    /**
     * 创建动态锻造工具。
     *
     * @param toolName      工具名称（全局唯一）
     * @param toolDescription 工具描述（供 LLM 理解）
     * @param code           工具代码（Python 脚本）
     * @param entryFunction  入口函数名（默认 "main"）
     * @param inputSchema    输入参数 JSON Schema
     * @param agentId        创建该工具的 Agent ID
     * @param sdk            工具层 SDK 契约（{@link ToolSdk}）
     * @param workingDir     工作目录
     */
    public DynamicForgedTool(String toolName, String toolDescription, String code,
                             String entryFunction, String inputSchema,
                             String agentId, ToolSdk sdk, String workingDir) {
        this.toolName = toolName;
        this.toolDescription = toolDescription;
        this.code = code;
        this.entryFunction = entryFunction != null ? entryFunction : "main";
        this.inputSchema = inputSchema != null ? inputSchema : "{\"type\":\"object\",\"properties\":{\"args\":{\"type\":\"string\"}}}";
        this.agentId = agentId;
        this.sdk = sdk;
        this.workingDir = workingDir;
    }

    @Override
    public String name() { return toolName; }

    @Override
    public String description() { return toolDescription; }

    @Override
    public String inputSchema() { return inputSchema; }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        log.info("[DynamicForgedTool] 执行锻造工具: name={}, agent={}", toolName, agentId);

        try {
            // 将输入参数序列化为 JSON 传给 Python 脚本
            String argsJson = input.args();

            // 写入代码到 VFS
            sdk.writeFile(agentId, "/factory/.forged_tools/" + toolName + ".py", code);

            // 构建执行命令：将代码和参数写入临时文件，用 python3 执行
            String command = String.format(
                "cd %s && python3 -u -c \"import sys,json; sys.path.insert(0,'.forged_tools'); " +
                "mod=__import__('%s'); result=mod.%s(json.loads('%s')); print(json.dumps(result,ensure_ascii=False))\"",
                workingDir, toolName, entryFunction,
                argsJson.replace("'", "\\'").replace("\"", "\\\"")
            );

            // 通过 BashTool 执行
            BashTool bashTool = new BashTool();
            BashTool.Input bashInput = new BashTool.Input(command, 60);
            ToolOutput result = bashTool.call(bashInput, new ToolContext(agentId, sdk, workingDir));

            if (result.success()) {
                log.info("[DynamicForgedTool] 锻造工具执行成功: name={}", toolName);
                return result;
            } else {
                log.warn("[DynamicForgedTool] 锻造工具执行失败: name={}, error={}", toolName, result.toText());
                return ToolOutput.fail("Forged tool '" + toolName + "' execution failed: " + result.toText());
            }
        } catch (Exception e) {
            log.error("[DynamicForgedTool] 锻造工具异常: name={}", toolName, e);
            return ToolOutput.fail("Forged tool '" + toolName + "' error: " + e.getMessage());
        }
    }

    @Override
    public boolean readOnly() { return false; }

    @Override
    public String prompt() {
        return "This is a dynamically forged tool. Input args as JSON string.";
    }

    /**
     * 动态锻造工具的输入。
     */
    public record Input(String args) implements ToolInput {
        @Override
        public String toJson() {
            return "{\"args\":\"" + args.replace("\"", "\\\"") + "\"}";
        }
    }
}
