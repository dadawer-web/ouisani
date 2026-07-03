package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.security.ContainmentZoneManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文件写入工具 — 对标 Claude Code 的 FileWriteTool。
 * <p>
 * 安全边界：通过 VfsManager 虚拟文件系统写入，永远不接触宿主机真实文件系统。
 * Agent 只能写入 VFS 命名空间内的文件，防止越权修改宿主机敏感文件。
 * <p>
 * OS 类比：相当于 Linux 的 open(O_WRONLY|O_CREAT|O_TRUNC) + write()，
 * 但经过 VFS 层的命名空间隔离和路径逃逸检查。
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
        return "Writes content to a file in the virtual filesystem, creating it if it doesn't exist or overwriting if it does. Use for creating new files or complete rewrites.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"VFS virtual file path\"},\"content\":{\"type\":\"string\",\"description\":\"The full content to write to the file\"}},\"required\":[\"path\",\"content\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            // 0. Containment Zone 检查 — 防止 Agent 写入 SYSTEM/SECRETS 等禁止区域
            ContainmentZoneManager.instance().enforceAccess(input.path(),
                    ContainmentZoneManager.Operation.WRITE);

            VfsManager vfs = VfsManager.instance();

            boolean success = vfs.writeText(input.path(), input.content());
            if (!success) {
                return ToolOutput.fail("Failed to write to VFS path (permission denied or path escape): " + input.path());
            }

            return ToolOutput.ok("Wrote " + input.content().length() + " chars to VFS:" + input.path());
        } catch (Exception e) {
            return ToolOutput.fail("Failed to write file to VFS: " + e.getMessage());
        }
    }

    @Override public boolean readOnly() { return false; }

    @Override public String prompt() {
        return "Use file_write to create new files or completely rewrite existing ones in the virtual filesystem. For small edits, prefer file_edit.";
    }

    // ── 强类型 I/O 契约 ──
    @Override
    public List<Port> inputPorts() {
        return List.of(
            new Port("path", DataTypes.FILE_PATH, "VFS 虚拟文件路径"),
            new Port("content", DataTypes.PLAIN_TEXT, "要写入文件的完整内容")
        );
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(
            new Port("result", DataTypes.PLAIN_TEXT, "写入确认信息（字符数 + 路径）")
        );
    }

    @Override
    public Optional<ToolExample> example() {
        return Optional.of(new ToolExample(
            "如果你想把分析结果保存到文件",
            Map.of(
                "path", "/vfs/workspace/result.md",
                "content", "# 分析报告\n\n这是分析结果..."
            )
        ));
    }
}
