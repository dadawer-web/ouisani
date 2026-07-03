package com.ouisani.aios.core.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Glob 工具 — 文件模式匹配搜索，对标 Claude Code 的 GlobTool。
 * <p>
 * 使用系统 find 命令进行高性能文件名搜索。
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
            // Use find with pattern matching
            List<String> cmd = new ArrayList<>();
            cmd.add("find");
            cmd.add(input.path());
            cmd.add("-type");
            cmd.add("f");
            cmd.add("-name");
            cmd.add(input.pattern());
            cmd.add("-not");
            cmd.add("-path");
            cmd.add("*/node_modules/*");
            cmd.add("-not");
            cmd.add("-path");
            cmd.add("*/.git/*");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (context.workingDir() != null) {
                pb.directory(new java.io.File(context.workingDir()));
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count < MAX_RESULTS) {
                    output.append(line).append("\n");
                    count++;
                }
            }
            process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();

            String result = output.toString();
            if (result.isBlank()) {
                return ToolOutput.ok("No files matched pattern: " + input.pattern());
            }
            return ToolOutput.ok(result);
        } catch (Exception e) {
            return ToolOutput.fail("Glob failed: " + e.getMessage());
        }
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
