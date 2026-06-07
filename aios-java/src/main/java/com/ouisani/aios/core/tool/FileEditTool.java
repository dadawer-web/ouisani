package com.ouisani.aios.core.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件编辑工具 — 对标 Claude Code 的 FileEditTool。
 * <p>
 * 使用 sed 风格的 old_string → new_string 替换模式，
 * 确保编辑操作的精确性和可逆性。
 * <p>
 * OS 类比：相当于 Linux 的 write() + fdatasync() 系统调用。
 */
public class FileEditTool implements Tool<FileEditTool.Input> {

    public record Input(String path, String oldString, String newString) implements ToolInput {
        public Input {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("path required");
            if (oldString == null) throw new IllegalArgumentException("oldString required");
            if (newString == null) newString = "";
        }

        @Override public String toJson() {
            return "{\"path\":\"" + path.replace("\"", "\\\"")
                    + "\",\"old_string\":\"" + oldString.replace("\"", "\\\"")
                    + "\",\"new_string\":\"" + newString.replace("\"", "\\\"") + "\"}";
        }
    }

    @Override public String name() { return "file_edit"; }

    @Override public String description() {
        return "Performs exact string replacement in a file. Finds old_string and replaces it with new_string. The old_string must be unique in the file.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Absolute file path\"},\"old_string\":{\"type\":\"string\",\"description\":\"The exact text to find and replace\"},\"new_string\":{\"type\":\"string\",\"description\":\"The replacement text\"}},\"required\":[\"path\",\"old_string\",\"new_string\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            Path filePath = Path.of(input.path());
            if (!Files.exists(filePath)) {
                return ToolOutput.fail("File not found: " + input.path());
            }

            String content = Files.readString(filePath, StandardCharsets.UTF_8);

            // Check uniqueness
            int firstIdx = content.indexOf(input.oldString());
            if (firstIdx < 0) {
                return ToolOutput.fail("old_string not found in file. Ensure the string matches exactly.");
            }
            int secondIdx = content.indexOf(input.oldString(), firstIdx + 1);
            if (secondIdx >= 0) {
                return ToolOutput.fail("old_string is not unique in the file (found at least 2 occurrences). Provide more context to make it unique.");
            }

            String newContent = content.replace(input.oldString(), input.newString());
            Files.writeString(filePath, newContent, StandardCharsets.UTF_8);

            return ToolOutput.ok("Edited " + input.path() + " successfully.");
        } catch (Exception e) {
            return ToolOutput.fail("Failed to edit file: " + e.getMessage());
        }
    }

    @Override public boolean readOnly() { return false; }

    @Override public String prompt() {
        return "Use file_edit for precise edits. The old_string must be unique in the file. For large changes, prefer file_write.";
    }
}
