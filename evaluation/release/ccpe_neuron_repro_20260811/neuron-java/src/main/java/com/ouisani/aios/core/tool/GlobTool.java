package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.sandbox.ExecOptions;
import com.ouisani.aios.core.sandbox.ExecResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Glob 工具 — 文件模式匹配搜索，对标 Claude Code 的 GlobTool。
 * <p>
 * 使用系统 find 命令进行高性能文件名搜索。
 * <p>
 * <b>执行后端可插拔</b>：所有 shell 执行走 {@link com.ouisani.aios.core.sandbox.BackendBase#exec_shell}，
 * 由 {@link ToolContext#backend()} 决定路由目标。工具代码不感知后端类型。
 * <p>
 * OS 类比：相当于 Linux 的 find + glob 模式匹配。
 */
public class GlobTool implements Tool<GlobTool.Input> {

    private static final int MAX_RESULTS = 500;

    public record Input(String pattern, String path) implements ToolInput {
        public Input {
            if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("pattern required");
            if (path == null || path.isBlank()) path = ".";
        }

        public Input(String pattern) { this(pattern, "."); }

        @Override public String toJson() {
            return "{\"pattern\":\"" + pattern.replace("\"", "\\\"")
                    + "\",\"path\":\"" + path.replace("\"", "\\\"") + "\"}";
        }
    }

    @Override public String name() { return "glob"; }

    @Override public String description() {
        return "Fast file pattern matching tool. Supports glob patterns like '**/*.java', 'src/**/*.ts', etc.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"description\":\"Glob pattern (e.g. '**/*.java', 'src/**/*.{ts,tsx}')\"},\"path\":{\"type\":\"string\",\"description\":\"Directory to search in (default: current dir)\"}},\"required\":[\"pattern\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            // Build find command line — shell-quote path and pattern to avoid injection
            StringBuilder cmd = new StringBuilder("find ");
            cmd.append(shellQuote(input.path()));
            cmd.append(" -type f -name ").append(shellQuote(input.pattern()));
            cmd.append(" -not -path '*/node_modules/*' -not -path '*/.git/*'");
            cmd.append(" | head -n ").append(MAX_RESULTS);

            // ── 后端可插拔：走 context.backend().exec_shell ──
            ExecOptions options = new ExecOptions(
                    15,                        // 15s 超时（与原 process.waitFor(15s) 一致）
                    context.workingDir(),
                    Map.of(),
                    0,                         // 不截断（结果由 head -n 控制）
                    true
            );
            ExecResult result = context.backend().exec_shell(cmd.toString(), options);

            if (result.errorMessage() != null) {
                return ToolOutput.fail("Glob failed: " + result.errorMessage());
            }
            String output = result.output();
            if (output.isBlank()) {
                return ToolOutput.ok("No files matched pattern: " + input.pattern());
            }
            return ToolOutput.ok(output);
        } catch (Exception e) {
            return ToolOutput.fail("Glob failed: " + e.getMessage());
        }
    }

    /** 单引号 shell 引用 — 防止 path/pattern 中的特殊字符被 bash 解析。 */
    private static String shellQuote(String s) {
        if (s == null || s.isEmpty()) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use glob to find files by name pattern. Results are limited to 500 matches.";
    }

    // ── 强类型 I/O 契约 ──
    @Override
    public List<Port> inputPorts() {
        return List.of(
            new Port("pattern", DataTypes.PLAIN_TEXT, "Glob 文件名匹配模式（如 **/*.java）"),
            new Port("path", DataTypes.FILE_PATH, "搜索目录路径（默认当前目录）")
        );
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(
            new Port("files", DataTypes.FILE_PATH_LIST, "匹配的文件路径列表")
        );
    }

    @Override
    public Optional<ToolExample> example() {
        return Optional.of(new ToolExample(
            "如果你需要查找所有 JSON 配置文件",
            Map.of(
                "pattern", "**/*.json",
                "path", "/vfs/workspace"
            )
        ));
    }
}
