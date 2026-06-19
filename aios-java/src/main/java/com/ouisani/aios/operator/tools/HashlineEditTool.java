package com.ouisani.aios.operator.tools;

import com.ouisani.aios.core.hashline.HashlineEditor;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Hashline 编辑工具 — 基于哈希的代码精准替换。
 * <p>
 * 对标 oh-my-openagent 的 hashline-core Edit 功能：
 * LLM 通过 {@link HashlineReadTool} 获取目标块的哈希 ID，
 * 然后用此工具精准替换该块，其余代码原样保留。
 * <p>
 * 核心优势：
 * <ul>
 *   <li>精准 — 只修改目标块，不会误改其他代码</li>
 *   <li>安全 — 哈希不匹配时抛异常，触发自愈重试</li>
 *   <li>原子 — 读-改-写一气呵成</li>
 * </ul>
 * <p>
 * 与 OmniMotherAgent 自愈机制的联动：
 * <pre>
 *   hashline_edit → 哈希不匹配 → RuntimeException
 *   → OmniMotherAgent.handleTask() catch 块捕获
 *   → 反思注入：将错误信息怼到 LLM 脸上
 *   → LLM 重新 hashline_read → 获取新哈希 → 再次 hashline_edit
 * </pre>
 *
 * @see HashlineReadTool
 * @see HashlineEditor
 */
public class HashlineEditTool implements Tool<HashlineEditTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(HashlineEditTool.class);

    @Override
    public String name() {
        return "hashline_edit";
    }

    @Override
    public String description() {
        return "Precisely modifies a specific code block in a file identified by its hash. "
                + "You MUST first use hashline_read to get the hash IDs, then provide the exact targetHash "
                + "and the complete newContent for that block. Other blocks remain unchanged. "
                + "If the hash doesn't match (file was modified), the edit will fail and you should "
                + "re-read the file with hashline_read to get updated hashes.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "Path to the source code file"
                    },
                    "targetHash": {
                      "type": "string",
                      "description": "The hash ID of the block to replace (obtained from hashline_read, e.g. 'abc12345')"
                    },
                    "newContent": {
                      "type": "string",
                      "description": "The complete new content for the target block"
                    }
                  },
                  "required": ["path", "targetHash", "newContent"]
                }""";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            Path filePath = resolvePath(input.path(), context.workingDir());
            if (!Files.exists(filePath)) {
                return ToolOutput.fail("File not found: " + filePath);
            }

            String originalCode = Files.readString(filePath);
            String updatedCode = HashlineEditor.applyEdit(originalCode, input.targetHash(), input.newContent());

            // 原子化写入硬盘
            Files.writeString(filePath, updatedCode);

            log.info("[HashlineEdit] File {} updated at hash [{}].", filePath.getFileName(), input.targetHash());
            return ToolOutput.ok("Success: File updated precisely at hash [" + input.targetHash()
                    + "]. Other blocks unchanged.");

        } catch (RuntimeException e) {
            // 哈希不匹配 — 这个异常会被 OmniMotherAgent 的自愈循环捕获！
            log.warn("[HashlineEdit] 编辑失败: {}", e.getMessage());
            throw e; // 重新抛出，让自愈引擎处理
        } catch (IOException e) {
            return ToolOutput.fail("I/O error: " + e.getMessage());
        }
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String prompt() {
        return """
                IMPORTANT: Always use hashline_read first to get the current hash IDs before using hashline_edit.
                If hashline_edit fails with "Hashline mismatch", it means the file was modified since you last read it.
                Re-read the file with hashline_read to get updated hashes, then try again.
                Never guess hash values — always obtain them from hashline_read output.
                """;
    }

    private Path resolvePath(String path, String workingDir) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p;
        return Paths.get(workingDir).resolve(p).normalize();
    }

    /**
     * 工具输入参数
     */
    public record Input(String path, String targetHash, String newContent) implements ToolInput {
        @Override
        public String toJson() {
            return "{\"path\":\"" + path.replace("\"", "\\\"")
                    + "\",\"targetHash\":\"" + targetHash
                    + "\",\"newContent\":\"" + newContent.replace("\"", "\\\"").replace("\n", "\\n")
                    + "\"}";
        }
    }
}
