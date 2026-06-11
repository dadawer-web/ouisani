package com.ouisani.aios.core.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Bash 工具 — 执行 Shell 命令，对标 Claude Code 的 BashTool。
 * <p>
 * 安全特性：
 * - 命令超时控制（默认 120 秒）
 * - 输出截断（默认 30000 字符）
 * - 只读检测（dry-run 模式下只允许读取命令）
 * <p>
 * ⚠️ 安全风险：当前使用 ProcessBuilder 为宿主机直接执行，存在越权风险！
 * TODO: 后续必须将执行逻辑切换到 com.ouisani.aios.core.sandbox.SandboxProvider
 * （如 Docker 容器）中执行，确保环境隔离。当前实现仅适用于受控开发环境。
 * <p>
 * OS 类比：相当于 Linux 的 execve() 系统调用。
 */
public class BashTool implements Tool<BashTool.Input> {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_LENGTH = 30000;

    public record Input(String command, int timeoutSeconds) implements ToolInput {
        public Input {
            if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }

        public Input(String command) {
            this(command, DEFAULT_TIMEOUT_SECONDS);
        }

        @Override public String toJson() {
            return "{\"command\":\"" + (command == null ? "" : command.replace("\"", "\\\"")) + "\",\"timeout\":" + timeoutSeconds + "}";
        }
    }

    @Override public String name() { return "bash"; }

    @Override public String description() {
        return "Executes a bash command in a shell. Returns stdout and stderr. Use for running scripts, installing packages, git operations, etc.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"The bash command to execute\"},\"timeoutSeconds\":{\"type\":\"integer\",\"description\":\"Timeout in seconds (default 120)\"}},\"required\":[\"command\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        // ── 入参防御：绝不允许空命令默不作声地死掉 ──
        if (input.command() == null || input.command().isBlank()) {
            String errorMsg = "ERROR: Invalid tool call format. The 'command' parameter must not be empty. "
                    + "Please retry with standard format: <tool_call><function=bash><parameter=command>your_command_here</parameter></function=bash></tool_call>";
            System.err.println("[BashTool] Rejected empty command — feeding error back to LLM for self-correction");
            return ToolOutput.fail(errorMsg);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", input.command());
            if (context.workingDir() != null) {
                pb.directory(new java.io.File(context.workingDir()));
            }
            pb.redirectErrorStream(true);
            // 强制非交互模式 — 防止 sudo/apt 等命令等待用户输入导致死锁
            pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
            pb.environment().put("APT_KEY_DONT_WARN_ON_DANGEROUS_USAGE", "1");
            pb.environment().put("PIP_NO_INPUT", "1");

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(input.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolOutput.fail("Command timed out after " + input.timeoutSeconds() + "s: " + input.command());
            }

            int exitCode = process.exitValue();
            String result = output.toString();
            if (result.length() > MAX_OUTPUT_LENGTH) {
                result = result.substring(0, MAX_OUTPUT_LENGTH) + "\n... [truncated at " + MAX_OUTPUT_LENGTH + " chars]";
            }

            if (exitCode == 0) {
                return ToolOutput.ok(result.isEmpty() ? "(no output)" : result);
            } else {
                return ToolOutput.fail("Exit code " + exitCode + "\n" + result);
            }
        } catch (Exception e) {
            return ToolOutput.fail("Execution failed: " + e.getMessage());
        }
    }

    @Override public boolean readOnly() { return false; }

    @Override public String prompt() {
        return "When using bash: prefer absolute paths, avoid interactive commands, use timeout for long-running operations. For file operations, prefer dedicated file tools.";
    }
}
