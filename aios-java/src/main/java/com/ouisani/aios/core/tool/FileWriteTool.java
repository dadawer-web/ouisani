package com.ouisani.aios.core.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件写入工具 — 对标 Claude Code 的 FileWriteTool。
 * <p>
 * 完整覆写文件内容，适合创建新文件或大规模重写。
 * <p>
 * OS 类比：相当于 Linux 的 open(O_WRONLY|O_CREAT|O_TRUNC) + write()。
 */
public class FileWriteTool implements Tool<FileWriteTool.Input> {

    public record Input(String path, String content) implements ToolInput {
        public Input {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("path required");
            if (content == null) content = "";
        }

        @Override public String toJson() {
            return "{\"path\":\"" + path.replace("\"", "\\\"") + "\",\"content_length\":" + content.length() + "}";
        }
    }

    @Override public String name() { return "file_write"; }

    @Override public String description() {
        return "Writes content to a file, creating it if it doesn't exist or overwriting if it does. Use for creating new files or complete rewrites.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Absolute file path\"},\"content\":{\"type\":\"string\",\"description\":\"The full content to write to the file\"}},\"required\":[\"path\",\"content\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            Path filePath = Path.of(input.path());

            // Create parent directories if needed
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(filePath, input.content(), StandardCharsets.UTF_8);

            return ToolOutput.ok("Wrote " + input.content().length() + " chars to " + input.path());
        } catch (Exception e) {
            return ToolOutput.fail("Failed to write file: " + e.getMessage());
        }
    }

    @Override public boolean readOnly() { return false; }

    @Override public String prompt() {
        return "Use file_write to create new files or completely rewrite existing ones. For small edits, prefer file_edit.";
    }
}
