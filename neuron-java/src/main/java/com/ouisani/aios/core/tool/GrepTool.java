package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.sandbox.ExecOptions;
import com.ouisani.aios.core.sandbox.ExecResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Grep 工具 — 内容搜索，对标 Claude Code 的 GrepTool。
 * <p>
 * 使用 ripgrep (rg) 进行高性能正则搜索，回退到 Java 内置实现。
 * <p>
 * <b>执行后端可插拔</b>：所有 shell 执行走 {@link com.ouisani.aios.core.sandbox.BackendBase#exec_shell}，
 * 由 {@link ToolContext#backend()} 决定路由目标。工具代码不感知后端类型。
 * <p>
 * OS 类比：相当于 Linux 的 grep 命令。
 */
public class GrepTool implements Tool<GrepTool.Input> {

    private static final int MAX_RESULTS = 200;

    public record Input(String pattern, String path, String glob, int contextLines) implements ToolInput {
        public Input {
            if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("pattern required");
            if (path == null || path.isBlank()) path = ".";
            if (glob == null) glob = "";
            if (contextLines < 0) contextLines = 0;
        }

        public Input(String pattern, String path) { this(pattern, path, "", 0); }

        @Override public String toJson() {
            return "{\"pattern\":\"" + pattern.replace("\"", "\\\"")
                    + "\",\"path\":\"" + path.replace("\"", "\\\"")
                    + "\",\"glob\":\"" + glob.replace("\"", "\\\"") + "\"}";
        }
    }

    @Override public String name() { return "grep"; }

    @Override public String description() {
        return "Searches file contents using regex patterns. Supports file type filtering and context lines.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"description\":\"Regex pattern to search for\"},\"path\":{\"type\":\"string\",\"description\":\"Directory or file to search in (default: current dir)\"},\"glob\":{\"type\":\"string\",\"description\":\"File glob filter (e.g. '*.java', '*.{ts,tsx}')\"},\"contextLines\":{\"type\":\"integer\",\"description\":\"Number of context lines (default 0)\"}},\"required\":[\"pattern\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            // Build ripgrep command line — shell-quote pattern to avoid injection
            StringBuilder cmd = new StringBuilder("rg --no-heading --line-number --color=never --max-count=");
            cmd.append(MAX_RESULTS);
            if (input.contextLines() > 0) {
                cmd.append(" -C").append(input.contextLines());
            }
            if (!input.glob().isEmpty()) {
                cmd.append(" --glob=").append(shellQuote(input.glob()));
            }
            cmd.append(" ").append(shellQuote(input.pattern()));
            cmd.append(" ").append(shellQuote(input.path()));

            // ── 后端可插拔：走 context.backend().exec_shell ──
            ExecOptions options = new ExecOptions(
                    30,                        // 30s 超时（与原 process.waitFor(30s) 一致）
                    context.workingDir(),
                    Map.of(),
                    0,                         // 不截断（结果由 MAX_RESULTS 控制）
                    true
            );
            ExecResult result = context.backend().exec_shell(cmd.toString(), options);

            if (result.errorMessage() != null) {
                return ToolOutput.fail("Grep failed: " + result.errorMessage());
            }
            // rg 退出码：0=有匹配，1=无匹配，2=错误
            String output = result.output();
            if (output.isBlank() || result.exitCode() == 1) {
                return ToolOutput.ok("No matches found for pattern: " + input.pattern());
            }
            if (result.exitCode() == 2) {
                return ToolOutput.fail("Grep error: " + output);
            }
            return ToolOutput.ok(output);
        } catch (Exception e) {
            return ToolOutput.fail("Grep failed: " + e.getMessage());
        }
    }

    /** 单引号 shell 引用 — 防止 pattern/path 中的特殊字符被 bash 解析。 */
    private static String shellQuote(String s) {
        if (s == null || s.isEmpty()) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use grep to search file contents. Prefer glob filter to narrow search scope. Results are limited to 200 matches.";
    }

    // ── 强类型 I/O 契约 ──
    @Override
    public List<Port> inputPorts() {
        return List.of(
            new Port("pattern", DataTypes.PLAIN_TEXT, "正则表达式搜索模式"),
            new Port("path", DataTypes.FILE_PATH, "搜索目录或文件路径（默认当前目录）"),
            new Port("glob", DataTypes.PLAIN_TEXT, "文件名过滤模式（如 *.java）")
        );
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(
            new Port("matches", DataTypes.PLAIN_TEXT, "匹配的行（带文件名和行号）")
        );
    }

    @Override
    public Optional<ToolExample> example() {
        return Optional.of(new ToolExample(
            "如果你需要在代码库中搜索特定函数",
            Map.of(
                "pattern", "public.*compile",
                "path", "/vfs/workspace/src",
                "include", "*.java"
            )
        ));
    }
}
