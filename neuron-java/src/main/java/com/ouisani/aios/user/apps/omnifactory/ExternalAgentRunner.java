package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.team.MailMessage;
import com.ouisani.aios.core.team.TaskPayload;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 外部 Agent 运行器 — 借鉴 OmniGent 的 Runner Pattern。
 * <p>
 * 将外部成熟的 Agent CLI（如 Claude Code、Codex CLI、SWE-agent、Aider 等）
 * 包装成 AIOS 内部的 Agent 节点，统一接入会话总线和 WorkflowContext。
 * <p>
 * 机制：
 * 1. WorkflowEngine 执行 executor="external" 的节点时，创建 ExternalAgentRunner
 * 2. Runner 在工作目录中启动外部 Agent CLI 进程
 * 3. 通过 stdin/stdout 桥接，将节点 prompt 发送给外部 Agent
 * 4. 收集外部 Agent 的 stdout 输出
 * 5. 将输出写入 WorkflowContext，完成 DAG 数据流
 * <p>
 * OS 类比：Linux 的 compat 层 — 内核可以运行非原生 ABI 的二进制程序
 * （如 Linux 运行 Windows 程序通过 Wine，AIOS 运行 Claude Code 通过 ExternalAgentRunner）。
 * <p>
 * 支持的外部 Agent：
 * <ul>
 *   <li>claude-code — Anthropic 的 Claude Code CLI</li>
 *   <li>codex — OpenAI 的 Codex CLI</li>
 *   <li>swe-agent — SWE-agent 框架</li>
 *   <li>aider — AI pair programming</li>
 *   <li>custom — 自定义命令</li>
 * </ul>
 */
public class ExternalAgentRunner extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(ExternalAgentRunner.class);

    /** 默认超时时间（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** 最大输出长度 */
    private static final int MAX_OUTPUT_LENGTH = 50000;

    private final WorkflowNode node;
    private final WorkflowContext context;

    public ExternalAgentRunner(WorkflowNode node, WorkflowContext context) {
        super("external_" + node.instanceId() + "_" + System.currentTimeMillis(),
                ProcessPriority.NORMAL, 100000);
        this.node = node;
        this.context = context;
    }

    @Override
    protected void onStart() {
        // 不使用 onStart，逻辑在 handleTask 中
    }

    @Override
    protected void handleTask(Object rawPayload) {
        if (!(rawPayload instanceof TaskPayload payload)) {
            log.error("[ExternalAgentRunner] 无效的 payload 类型: {}", rawPayload.getClass());
            return;
        }

        WorkflowNode taskNode = payload.node();
        log.info("[ExternalAgentRunner] 启动外部 Agent: node={}, executor={}",
                taskNode.instanceId(), taskNode.executor());

        try {
            // 1. 解析外部 Agent 配置
            ExternalAgentConfig config = parseConfig(taskNode);

            // 2. 构建 prompt（解析 Dify 变量）
            String prompt = buildPrompt(taskNode, config);

            // 3. 写入临时文件（避免 stdin 超长）
            Path promptFile = writePromptFile(prompt, config);

            // 4. 构建命令
            String[] command = buildCommand(config, promptFile);

            // 5. 执行外部 Agent
            String output = executeExternalAgent(command, config);

            // 6. 解析输出并写入 WorkflowContext
            Map<String, Object> outputs = parseOutput(output, config);
            context.commitNodeOutput(taskNode.instanceId(), outputs);

            // 7. 写入 VariablePool
            for (Map.Entry<String, Object> entry : outputs.entrySet()) {
                com.ouisani.aios.core.ipc.VariablePool.getInstance().set(
                    com.ouisani.aios.core.ipc.VariablePool.Scope.TASK,
                    taskNode.instanceId(), entry.getKey(), entry.getValue()
                );
            }

            log.info("[ExternalAgentRunner] 外部 Agent 完成: node={}, outputKeys={}",
                    taskNode.instanceId(), outputs.keySet());

            // 8. 签收回执
            payload.completionReceipt().complete(null);

        } catch (Exception e) {
            log.error("[ExternalAgentRunner] 外部 Agent 执行失败: node={}, error={}",
                    taskNode.instanceId(), e.getMessage(), e);
            payload.completionReceipt().completeExceptionally(e);
        }
    }

    @Override
    protected void onMessage(String msg) {
        // 外部 Agent 不处理消息
    }

    // ════════════════════════════════════════════════════════════════
    //  配置解析
    // ════════════════════════════════════════════════════════════════

    /**
     * 外部 Agent 配置。
     */
    public static class ExternalAgentConfig {
        /** Agent 类型：claude-code / codex / swe-agent / aider / custom */
        public String agentType;
        /** 要执行的命令（custom 模式下直接使用） */
        public String command;
        /** 工作目录 */
        public String workingDir;
        /** 超时时间（秒） */
        public int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        /** 额外环境变量 */
        public Map<String, String> envVars = new ConcurrentHashMap<>();
        /** 输出格式：raw / json / markdown */
        public String outputFormat = "raw";
        /** 是否将 prompt 写入文件（避免 stdin 超长） */
        public boolean usePromptFile = true;
        /** prompt 文件名 */
        public String promptFileName = ".aios_prompt.txt";
    }

    /**
     * 从 WorkflowNode 的 userParams 中解析外部 Agent 配置。
     */
    private ExternalAgentConfig parseConfig(WorkflowNode node) {
        ExternalAgentConfig config = new ExternalAgentConfig();
        Map<String, String> params = node.userParams();

        // Agent 类型：executor 格式为 "external:claude-code" 或直接 "external"
        String executor = node.executor();
        if (executor != null && executor.contains(":")) {
            config.agentType = executor.substring(executor.indexOf(':') + 1);
        } else {
            config.agentType = params.getOrDefault("agentType", "custom");
        }

        // 命令
        config.command = params.get("command");

        // 工作目录
        config.workingDir = params.getOrDefault("workingDir",
                System.getProperty("user.home") + "/.aios/workspaces");

        // 超时
        String timeout = params.get("timeout");
        if (timeout != null) {
            try { config.timeoutSeconds = Integer.parseInt(timeout); }
            catch (NumberFormatException e) { /* 用默认值 */ }
        }

        // 输出格式
        config.outputFormat = params.getOrDefault("outputFormat", "raw");

        // prompt 文件
        config.usePromptFile = !"false".equalsIgnoreCase(params.get("usePromptFile"));
        config.promptFileName = params.getOrDefault("promptFileName", ".aios_prompt.txt");

        // 环境变量（格式：KEY=VALUE;KEY2=VALUE2）
        String envStr = params.get("env");
        if (envStr != null) {
            for (String pair : envStr.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    config.envVars.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }

        return config;
    }

    // ════════════════════════════════════════════════════════════════
    //  Prompt 构建
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建发送给外部 Agent 的 prompt。
     * 解析 Dify 变量 {{nodeId.key}} 为实际值。
     */
    private String buildPrompt(WorkflowNode node, ExternalAgentConfig config) {
        StringBuilder prompt = new StringBuilder();

        // 节点角色描述
        String role = node.role();
        if (role != null && !role.isBlank()) {
            prompt.append("# Task: ").append(role).append("\n\n");
        }

        // 用户参数（解析变量）
        for (Map.Entry<String, String> entry : node.userParams().entrySet()) {
            String key = entry.getKey();
            // 跳过配置参数
            if (key.equals("command") || key.equals("workingDir") || key.equals("timeout") ||
                key.equals("outputFormat") || key.equals("usePromptFile") ||
                key.equals("promptFileName") || key.equals("env") || key.equals("agentType")) {
                continue;
            }
            String value = entry.getValue();
            // Dify 变量解析
            Object resolved = context.resolveValue(value);
            prompt.append(key).append(": ").append(resolved != null ? resolved : value).append("\n");
        }

        // 订阅的上游数据
        if (node.subscribeTopic() != null && !node.subscribeTopic().isBlank()) {
            prompt.append("\n## Input Data\n");
            String[] topics = node.subscribeTopic().split(",");
            for (String topic : topics) {
                topic = topic.trim();
                Object data = context.resolveValue("{{" + topic + "}}");
                if (data != null) {
                    prompt.append(topic).append(": ").append(data).append("\n");
                }
            }
        }

        return prompt.toString();
    }

    /**
     * 将 prompt 写入临时文件。
     */
    private Path writePromptFile(String prompt, ExternalAgentConfig config) throws IOException {
        Path promptFile = Path.of(config.workingDir, config.promptFileName);
        Files.createDirectories(promptFile.getParent());
        Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);
        log.debug("[ExternalAgentRunner] Prompt 已写入: {} ({} chars)", promptFile, prompt.length());
        return promptFile;
    }

    // ════════════════════════════════════════════════════════════════
    //  命令构建
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据 Agent 类型构建执行命令。
     */
    private String[] buildCommand(ExternalAgentConfig config, Path promptFile) {
        String agentType = config.agentType != null ? config.agentType : "custom";
        String promptPath = promptFile.toString();

        return switch (agentType) {
            case "claude-code", "claude" -> new String[]{
                    "claude", "--print", "--input-file", promptPath
            };
            case "codex" -> new String[]{
                    "codex", "--quiet", "--input-file", promptPath
            };
            case "swe-agent" -> new String[]{
                    "python3", "-m", "sweagent", "--config", "default", "--task", promptPath
            };
            case "aider" -> new String[]{
                    "aider", "--message-file", promptPath, "--no-auto-commits"
            };
            default -> {
                // custom 模式：直接使用用户提供的命令
                if (config.command != null && !config.command.isBlank()) {
                    // 替换 {prompt} 占位符
                    String cmd = config.command.replace("{prompt}", promptPath);
                    yield new String[]{"bash", "-c", cmd};
                }
                yield new String[]{"bash", "-c", "cat " + promptPath};
            }
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  执行外部 Agent
    // ════════════════════════════════════════════════════════════════

    /**
     * 执行外部 Agent 进程并收集输出。
     */
    private String executeExternalAgent(String[] command, ExternalAgentConfig config) throws Exception {
        log.info("[ExternalAgentRunner] 执行命令: {} (cwd={}, timeout={}s)",
                String.join(" ", command), config.workingDir, config.timeoutSeconds);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(config.workingDir));
        pb.redirectErrorStream(true);

        // 注入环境变量
        Map<String, String> env = pb.environment();
        env.putAll(config.envVars);
        // 非交互环境
        env.put("DEBIAN_FRONTEND", "noninteractive");
        env.put("TERM", "dumb");

        Process process = pb.start();

        // 读取输出
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // 实时广播输出到 EventBus（供前端流式渲染）
                if (output.length() <= MAX_OUTPUT_LENGTH) {
                    try {
                        com.ouisani.aios.core.network.EventBus.instance().broadcast(
                            "agent.event",
                            com.ouisani.aios.core.network.AiosEventSchema.textMessageContent(
                                agentId, "", 0, line
                            ).toJson()
                        );
                    } catch (Exception ignore) {}
                }
            }
        }

        // 等待完成
        boolean finished = process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("外部 Agent 执行超时（" + config.timeoutSeconds + "秒）");
        }

        int exitCode = process.exitValue();
        String result = output.toString();

        // 截断
        if (result.length() > MAX_OUTPUT_LENGTH) {
            result = result.substring(0, MAX_OUTPUT_LENGTH) + "\n... [truncated at " + MAX_OUTPUT_LENGTH + " chars]";
        }

        if (exitCode != 0) {
            log.warn("[ExternalAgentRunner] 外部 Agent 退出码非零: {} (exit={})", config.agentType, exitCode);
            // 不抛异常，仍然返回输出（外部 Agent 可能返回非零退出码但有部分结果）
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  输出解析
    // ════════════════════════════════════════════════════════════════

    /**
     * 解析外部 Agent 的输出为结构化数据。
     */
    private Map<String, Object> parseOutput(String output, ExternalAgentConfig config) {
        Map<String, Object> outputs = new ConcurrentHashMap<>();

        switch (config.outputFormat) {
            case "json" -> {
                // 尝试从输出中提取 JSON
                String json = extractJson(output);
                if (json != null) {
                    try {
                        com.google.gson.Gson gson = new com.google.gson.Gson();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = gson.fromJson(json, Map.class);
                        if (parsed != null) {
                            outputs.putAll(parsed);
                        }
                    } catch (Exception e) {
                        log.warn("[ExternalAgentRunner] JSON 解析失败，回退到 raw: {}", e.getMessage());
                        outputs.put("result", output);
                    }
                } else {
                    outputs.put("result", output);
                }
            }
            case "markdown" -> {
                outputs.put("result", output);
                outputs.put("markdown", output);
            }
            default -> {
                outputs.put("result", output);
                outputs.put("raw_output", output);
            }
        }

        // 元数据
        outputs.put("agent_type", config.agentType);
        outputs.put("output_length", output.length());

        return outputs;
    }

    /**
     * 从文本中提取 JSON 对象。
     */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }
}
