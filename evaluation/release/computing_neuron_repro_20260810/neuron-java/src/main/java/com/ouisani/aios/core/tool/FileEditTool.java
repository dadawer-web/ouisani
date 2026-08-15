package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.permission.SafetyCheckResult;

import java.util.Set;

/**
 * 文件编辑工具 — 对标 Claude Code 的 FileEditTool。
 * <p>
 * 安全边界：所有读写走 {@link com.ouisani.aios.core.sandbox.BackendBase#read_file} /
 * {@link com.ouisani.aios.core.sandbox.BackendBase#write_file}，由 {@link ToolContext#backend()}
 * 决定路由目标。LocalBackend 默认通过 VfsManager 虚拟文件系统读写，永远不接触宿主机真实文件系统。
 * Agent 只能编辑 VFS 命名空间内的文件，防止越权修改宿主机敏感文件。
 * <p>
 * <b>执行后端可插拔</b>：未来切换到 DockerBackend/E2BBackend 时，编辑自动路由到容器/云沙箱
 * 内的文件系统，工具代码零改动。
 * <p>
 * 使用 sed 风格的 old_string → new_string 替换模式，
 * 确保编辑操作的精确性和可逆性。
 * <p>
 * OS 类比：相当于 Linux 的 write() + fdatasync()，但经过 VFS 层安全隔离。
 */
public class FileEditTool implements Tool<FileEditTool.Input> {

    /**
     * 敏感文件路径前缀 — 命中时返回 safety ASK（bypass_immune=true，allow 规则无法覆盖）。
     * 借鉴 AgentScope 2.0 的 bypass_immune：这些路径涉及凭证/密钥/版本控制，
     * 即使用户配置了 allow 规则也不能放行，必须显式确认（DEFAULT）或在 overnight 拒绝（DONT_ASK）。
     */
    private static final Set<String> SENSITIVE_PATH_PREFIXES = Set.of(
            ".env", ".aws", ".ssh", ".gnupg", ".git",
            ".claude", ".config/gh", ".npmrc", ".pypirc",
            "credentials", "secrets", "id_rsa", "id_ed25519"
    );

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
            // 后端可插拔：所有读写走 context.backend()
            // LocalBackend 默认通过 VfsManager 读写，含命名空间隔离与路径逃逸检查
            if (!context.backend().file_exists(input.path())) {
                return ToolOutput.fail("File not found in VFS: " + input.path());
            }

            String content = context.backend().read_file(input.path());
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
            boolean success = context.backend().write_file(input.path(), newContent);
            if (!success) {
                return ToolOutput.fail("Failed to write edited content to VFS path: " + input.path());
            }

            return ToolOutput.ok("Edited VFS:" + input.path() + " successfully.");
        } catch (Exception e) {
            return ToolOutput.fail("Failed to edit file in VFS: " + e.getMessage());
        }
    }

    @Override public boolean readOnly() { return false; }

    /**
     * 敏感路径检查 — 借鉴 AgentScope 2.0 的 bypass_immune 标记。
     * <p>
     * 命中 {@link #SENSITIVE_PATH_PREFIXES} 的路径返回 {@link SafetyCheckResult#safetyAsk(String)}，
     * 标记为不可被 allow 规则覆盖：覆盖 .env / .aws / .ssh / .gnupg / .git / 凭证 / 密钥 等敏感文件。
     * <p>
     * 在权限引擎中的处理：
     * <ul>
     *   <li>DEFAULT / ACCEPT_EDITS / AUTO / DONT_ASK：safetyAsk 转 ASK 或 DENY（敏感路径不放行）</li>
     *   <li>BYPASS：safetyAsk 跳过（按 BYPASS 契约）</li>
     * </ul>
     */
    @Override
    public SafetyCheckResult checkPermissionDetailed(Input input, ToolContext context) {
        String path = input.path();
        if (path == null) return Tool.super.checkPermissionDetailed(input, context);
        String normalized = path.replace('\\', '/').toLowerCase();
        for (String prefix : SENSITIVE_PATH_PREFIXES) {
            // 匹配 /path/.env, /path/.env.local, .aws/credentials, /x/id_rsa 等模式
            if (normalized.contains("/" + prefix) || normalized.startsWith(prefix)
                    || normalized.contains("/" + prefix + "/")) {
                return SafetyCheckResult.safetyAsk(
                        "Sensitive path (bypass_immune): " + path
                        + " — modifying " + prefix + " requires explicit confirmation");
            }
        }
        return Tool.super.checkPermissionDetailed(input, context);
    }

    @Override public String prompt() {
        return "Use file_edit for precise edits in the virtual filesystem. The old_string must be unique in the file. For large changes, prefer file_write.";
    }
}
