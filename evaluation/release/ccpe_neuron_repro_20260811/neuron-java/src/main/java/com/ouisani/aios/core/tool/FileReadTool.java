package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.security.ContainmentZoneManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文件读取工具 — 对标 Claude Code 的 FileReadTool。
 * <p>
 * 读取策略：所有读取走 {@link com.ouisani.aios.core.sandbox.BackendBase#read_file}，
 * 由 {@link ToolContext#backend()} 决定路由目标。LocalBackend 默认优先从 VFS 虚拟文件
 * 系统读取；若 VFS 中不存在，回退到宿主机真实文件系统（Agent 常传入宿主机路径）。
 * <p>
 * <b>执行后端可插拔</b>：未来切换到 DockerBackend/E2BBackend 时，读取自动路由到容器/云沙箱
 * 内的文件系统，工具代码零改动。
 * <p>
 * OS 类比：相当于 Linux 的 read() 系统调用，但经过 VFS 层的命名空间隔离。
 */
public class FileReadTool implements Tool<FileReadTool.Input> {

    private static final int MAX_READ_SIZE = 100_000;

    public record Input(String path, int offset, int limit) implements ToolInput {
        public Input {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("path required");
            if (offset < 0) offset = 0;
            if (limit <= 0) limit = 2000;
        }

        public Input(String path) { this(path, 0, 2000); }

        @Override public String toJson() {
            return "{\"path\":\"" + path.replace("\"", "\\\"") + "\",\"offset\":" + offset + ",\"limit\":" + limit + "}";
        }
    }

    @Override public String name() { return "file_read"; }

    @Override public String description() {
        return "Reads a file from the virtual filesystem. Supports line offset and limit for reading specific sections of large files.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"VFS virtual file path\"},\"offset\":{\"type\":\"integer\",\"description\":\"Line number to start reading from (0-based, default 0)\"},\"limit\":{\"type\":\"integer\",\"description\":\"Maximum number of lines to read (default 2000)\"}},\"required\":[\"path\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            // 0. Containment Zone 检查 — 防止 Agent 读取 SECRETS 等禁止区域
            ContainmentZoneManager.instance().enforceAccess(input.path(),
                    ContainmentZoneManager.Operation.READ);

            // 1. 后端可插拔：所有读取走 context.backend().read_file
            //    LocalBackend 默认优先从 VFS 读取，VFS 不存在时回退宿主机文件系统
            String content = context.backend().read_file(input.path());
            if (content == null) {
                return ToolOutput.fail("File not found: " + input.path()
                        + " (checked VFS and host filesystem)");
            }
            return formatOutput(content, input.offset(), input.limit());
        } catch (Exception e) {
            return ToolOutput.fail("Failed to read file: " + e.getMessage());
        }
    }

    private ToolOutput formatOutput(String content, int offset, int limit) {
        String[] lines = content.split("\n");
        int start = Math.min(offset, lines.length);
        int end = Math.min(start + limit, lines.length);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(i + 1).append("→").append(lines[i]).append("\n");
        }

        String result = sb.toString();
        if (result.length() > MAX_READ_SIZE) {
            result = result.substring(0, MAX_READ_SIZE) + "\n... [truncated]";
        }

        return ToolOutput.ok(result);
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use file_read to examine file contents in the virtual filesystem. For large files, use offset and limit to read specific sections.";
    }

    @Override
    public Optional<ToolExample> example() {
        return Optional.of(new ToolExample(
            "如果你需要读取之前保存的配置文件",
            Map.of("path", "/vfs/workspace/config.json")
        ));
    }
}
