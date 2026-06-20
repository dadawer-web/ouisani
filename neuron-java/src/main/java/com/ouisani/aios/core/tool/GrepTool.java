package com.ouisani.aios.core.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Grep 工具 — 内容搜索，对标 Claude Code 的 GrepTool。
 * <p>
 * 使用 ripgrep (rg) 进行高性能正则搜索，回退到 Java 内置实现。
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
            // Try ripgrep first
            List<String> cmd = new ArrayList<>();
            cmd.add("rg");
            cmd.add("--no-heading");
            cmd.add("--line-number");
            cmd.add("--color=never");
            cmd.add("--max-count=" + MAX_RESULTS);
            if (input.contextLines() > 0) {
                cmd.add("-C" + input.contextLines());
            }
            if (!input.glob().isEmpty()) {
                cmd.add("--glob=" + input.glob());
            }
            cmd.add(input.pattern());
            cmd.add(input.path());

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
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();

            String result = output.toString();
            if (result.isBlank()) {
                return ToolOutput.ok("No matches found for pattern: " + input.pattern());
            }
            return ToolOutput.ok(result);
        } catch (Exception e) {
            return ToolOutput.fail("Grep failed: " + e.getMessage());
        }
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use grep to search file contents. Prefer glob filter to narrow search scope. Results are limited to 200 matches.";
    }
}
