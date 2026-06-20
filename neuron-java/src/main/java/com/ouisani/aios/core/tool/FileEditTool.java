package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.VfsManager;

/**
 * 文件编辑工具 — 对标 Claude Code 的 FileEditTool。
 * <p>
 * 安全边界：通过 VfsManager 虚拟文件系统读写，永远不接触宿主机真实文件系统。
 * Agent 只能编辑 VFS 命名空间内的文件，防止越权修改宿主机敏感文件。
 * <p>
 * 使用 sed 风格的 old_string → new_string 替换模式，
 * 确保编辑操作的精确性和可逆性。
 * <p>
 * OS 类比：相当于 Linux 的 write() + fdatasync()，但经过 VFS 层安全隔离。
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
        return "Performs exact string replacement in a file in the virtual filesystem. Finds old_string and replaces it with new_string. The old_string must be unique in the file.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"VFS virtual file path\"},\"old_string\":{\"type\":\"string\",\"description\":\"The exact text to find and replace\"},\"new_string\":{\"type\":\"string\",\"description\":\"The replacement text\"}},\"required\":[\"path\",\"old_string\",\"new_string\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            VfsManager vfs = VfsManager.instance();

            if (!vfs.exists(input.path())) {
                return ToolOutput.fail("File not found in VFS: " + input.path());
            }

            String content = vfs.readText(input.path());
            if (content == null) {
                return ToolOutput.fail("Permission denied or read failed for VFS path: " + input.path());
            }

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
            boolean success = vfs.writeText(input.path(), newContent);
            if (!success) {
                return ToolOutput.fail("Failed to write edited content to VFS path: " + input.path());
            }

            return ToolOutput.ok("Edited VFS:" + input.path() + " successfully.");
        } catch (Exception e) {
            return ToolOutput.fail("Failed to edit file in VFS: " + e.getMessage());
        }
    }

    @Override public boolean readOnly() { return false; }

    @Override public String prompt() {
        return "Use file_edit for precise edits in the virtual filesystem. The old_string must be unique in the file. For large changes, prefer file_write.";
    }
}
