package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.permission.PermissionChecker.BashToolLike;
import com.ouisani.aios.core.permission.SafetyCheckResult;
import com.ouisani.aios.core.sandbox.ExecOptions;
import com.ouisani.aios.core.sandbox.ExecResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bash 工具 — 执行 Shell 命令，对标 Claude Code 的 BashTool。
 * <p>
 * 安全特性：
 * - 命令超时控制（默认 120 秒）
 * - 输出截断（默认 30000 字符）
 * - 只读检测（dry-run 模式下只允许读取命令）
 * <p>
 * <b>执行后端可插拔</b>：所有 shell 执行走 {@link com.ouisani.aios.core.sandbox.BackendBase#exec_shell}，
 * 由 {@link ToolContext#backend()} 决定路由到 LocalBackend / DockerBackend / E2BBackend。
 * 工具代码不感知后端类型 — 借鉴 AgentScope 的 BackendBase 抽象。
 * <p>
 * OS 类比：相当于 Linux 的 execve() 系统调用。
 */
public class BashTool implements Tool<BashTool.Input> {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_LENGTH = 30000;

    public record Input(String command, int timeoutSeconds) implements ToolInput, BashToolLike {
        public Input {
            if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }

        public Input(String command) {
            this(command, DEFAULT_TIMEOUT_SECONDS);
        }

        @Override public String toJson() {
            return "{\"command\":\"" + (command == null ? "" : command.replace("\"", "\\\"")) + "\",\"timeout\":" + timeoutSeconds + "}";
        }

        @Override public String getCommand() { return command; }
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
            // ── 后端可插拔：所有 shell 执行走 context.backend().exec_shell ──
            // VFS 路径翻译、PYTHONPATH 注入、非交互式环境变量由 LocalBackend.exec_shell 统一处理。
            // 未来切换到 DockerBackend/E2BBackend 时，工具代码零改动。
            ExecOptions options = new ExecOptions(
                    input.timeoutSeconds(),
                    context.workingDir(),
                    buildPythonPathEnv(),
                    MAX_OUTPUT_LENGTH,
                    true
            );

            ExecResult result = context.backend().exec_shell(input.command(), options);

            if (result.timedOut()) {
                return ToolOutput.fail("Command timed out after " + input.timeoutSeconds() + "s: " + input.command());
            }
            if (result.errorMessage() != null) {
                return ToolOutput.fail("Execution failed: " + result.errorMessage());
            }

            String output = result.output();
            if (result.exitCode() == 0) {
                return ToolOutput.ok(output.isEmpty() ? "(no output)" : output);
            } else {
                return ToolOutput.fail("Exit code " + result.exitCode() + "\n" + output);
            }
        } catch (Exception e) {
            return ToolOutput.fail("Execution failed: " + e.getMessage());
        }
    }

    /**
     * 构建 PYTHONPATH 环境变量 — 使 Python 可解析 {@code from skills.xxx import yyy}。
     * <p>
     * 技能库物理路径通过 {@link com.ouisani.aios.core.config.AiosPaths#skillsDir()} 动态获取。
     * Python import skills.web_scraper 需要父目录在 PYTHONPATH 中。
     * <p>
     * 保留原有"prepend to existing PYTHONPATH"语义（零回归）：LocalBackend 会先从
     * ProcessBuilder 默认环境读取现有 PYTHONPATH，再追加 skillsPhysicalDir 到最前。
     */
    private static Map<String, String> buildPythonPathEnv() {
        try {
            String skillsPhysicalDir = com.ouisani.aios.core.config.AiosPaths.skillsDir();
            if (skillsPhysicalDir != null && !skillsPhysicalDir.isBlank()) {
                String existing = System.getenv("PYTHONPATH");
                String merged = (existing == null || existing.isEmpty())
                        ? skillsPhysicalDir
                        : skillsPhysicalDir + ":" + existing;
                return Map.of("PYTHONPATH", merged);
            }
        } catch (Exception e) {
            // AiosPaths 未配置时不注入 PYTHONPATH — 零回归
        }
        return Map.of();
    }

    @Override public boolean readOnly() { return false; }

    /**
     * 危险命令模式 — 借鉴 AgentScope 2.0 的 bypass_immune 标记。
     * <p>
     * 命中以下模式时返回 {@link SafetyCheckResult#safetyAsk(String)}，标记为不可被 allow 规则覆盖：
     * <ul>
     *   <li>递归强制删除根目录 / 系统目录（rm -rf /, /usr, /etc, /bin, /lib, ~）</li>
     *   <li>修改 shell 启动文件（~/.bashrc, ~/.bash_profile, ~/.profile, ~/.zshrc）— 防持久化注入</li>
     *   <li>命令注入模式：$(...), `...`, | sh, | bash, > /dev/sda</li>
     *   <li>fork bomb</li>
     *   <li>提权：sudo, su（除显式 allow 外）</li>
     * </ul>
     * 在权限引擎中的处理：
     * <ul>
     *   <li>DEFAULT / ACCEPT_EDITS / AUTO / DONT_ASK：safetyAsk 转 ASK 或 DENY（危险操作不放行）</li>
     *   <li>BYPASS：safetyAsk 跳过（按 BYPASS 契约）</li>
     * </ul>
     */
    @Override
    public SafetyCheckResult checkPermissionDetailed(Input input, ToolContext context) {
        String cmd = input.command();
        if (cmd == null) return SafetyCheckResult.allowed();
        String trimmed = cmd.trim();

        // 1. rm -rf 危险路径
        if (DANGEROUS_RM_RF.matcher(trimmed).find()) {
            return SafetyCheckResult.safetyAsk(
                    "Dangerous pattern: recursive force-delete of system/home directory — " + truncate(trimmed, 80));
        }

        // 2. 修改 shell 启动文件（持久化注入风险）
        if (SHELL_RC_PATTERN.matcher(trimmed).find()) {
            return SafetyCheckResult.safetyAsk(
                    "Dangerous pattern: modifying shell startup file — " + truncate(trimmed, 80));
        }

        // 3. 命令注入 / 管道执行
        if (INJECTION_PATTERN.matcher(trimmed).find()) {
            return SafetyCheckResult.safetyAsk(
                    "Dangerous pattern: command injection or pipe-to-shell — " + truncate(trimmed, 80));
        }

        // 4. fork bomb
        if (trimmed.contains(":(){ :|:& };:") || trimmed.contains(":(){:|:&};:")) {
            return SafetyCheckResult.safetyAsk(
                    "Dangerous pattern: fork bomb detected — " + truncate(trimmed, 80));
        }

        // 5. 提权
        if (SUDO_PATTERN.matcher(trimmed).find()) {
            return SafetyCheckResult.safetyAsk(
                    "Dangerous pattern: privilege escalation (sudo/su) — " + truncate(trimmed, 80));
        }

        return Tool.super.checkPermissionDetailed(input, context);
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ── 危险模式正则 ──
    // rm -rf /, rm -rf /usr, rm -rf ~, rm -rf $HOME, rm -rf /* 等
    // 注意：末尾用 (?![a-zA-Z]) 而非 \b，因为 "/" 是非字字符，
    // \b 在 "rm -rf /" 末尾（/ 后接字符串结束）不匹配，会导致裸根目录删除漏检。
    private static final Pattern DANGEROUS_RM_RF = Pattern.compile(
            "\\brm\\s+(-[a-zA-Z]*r[a-zA-Z]*f?|--recursive)\\s+(/|/\\*|~/|\\$HOME|/usr|/etc|/bin|/lib|/boot|/sys|/proc)(?![a-zA-Z])");
    // 修改 ~/.bashrc, ~/.bash_profile, ~/.profile, ~/.zshrc
    private static final Pattern SHELL_RC_PATTERN = Pattern.compile(
            "(~|\\$HOME)?/\\.?(bashrc|bash_profile|profile|zshrc|zshenv)\\b");
    // $(cmd), `cmd`, | sh, | bash, > /dev/sda, curl|sh, wget|sh
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "(\\$\\([^)]*\\)|`[^`]*`|\\|\\s*(sh|bash)\\b|>\\s*/dev/(sda|null|zero)|;\\s*(sh|bash)\\b)");
    // sudo, su（提权）
    private static final Pattern SUDO_PATTERN = Pattern.compile("^\\s*(sudo|su)\\b");

    @Override public String prompt() {
        return "When using bash: prefer absolute paths, avoid interactive commands, use timeout for long-running operations. For file operations, prefer dedicated file tools.";
    }

    // ── 强类型 I/O 契约 ──
    @Override
    public List<Port> inputPorts() {
        return List.of(
            new Port("command", DataTypes.SHELL_COMMAND, "要执行的 Shell 命令"),
            new Port("timeout", DataTypes.PLAIN_TEXT, "超时时间（秒，默认 120）")
        );
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(
            new Port("output", DataTypes.COMMAND_OUTPUT, "命令执行结果（stdout + stderr）")
        );
    }

    @Override
    public Optional<ToolExample> example() {
        return Optional.of(new ToolExample(
            "如果你需要执行系统命令查看目录结构",
            Map.of(
                "command", "ls -la /vfs/workspace/",
                "workdir", "/vfs/workspace"
            )
        ));
    }
}
