package com.ouisani.aios.operator.tools;

import com.ouisani.aios.core.hashline.HashlineCore;
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
import java.util.List;

/**
 * Hashline 读取工具 — 将文件按代码块分割并标注哈希 ID。
 * <p>
 * 对标 oh-my-openagent 的 hashline-core Read 功能：
 * LLM 先用此工具读取文件，获取每个代码块的哈希 ID，
 * 再用 {@link HashlineEditTool} 精准修改指定块。
 * <p>
 * 工作流程：
 * <pre>
 *   1. hashline_read(path) → 返回带哈希 ID 的代码块列表
 *   2. LLM 分析需要修改哪个块
 *   3. hashline_edit(path, targetHash, newContent) → 精准替换
 * </pre>
 * <p>
 * 与全量重写的对比：
 * <pre>
 *   file_read + file_write → LLM 重写整个文件 → 丢失格式/注释
 *   hashline_read + hashline_edit → 只改目标块 → 零副作用
 * </pre>
 *
 * @see HashlineEditTool
 * @see HashlineCore
 */
public class HashlineReadTool implements Tool<HashlineReadTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(HashlineReadTool.class);

    @Override
    public String name() {
        return "hashline_read";
    }

    @Override
    public String description() {
        return "Reads a source code file and returns its content segmented into blocks with unique Hash IDs. "
                + "Each block is marked with a hash like [HASH:abc12345]. "
                + "Use these hashes with hashline_edit to modify specific blocks precisely, "
                + "instead of rewriting the entire file. This prevents accidental changes to other parts of the file.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "Absolute or relative path to the source code file"
                    }
                  },
                  "required": ["path"]
                }""";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            Path filePath = resolvePath(input.path(), context.workingDir());
            if (!Files.exists(filePath)) {
                return ToolOutput.fail("File not found: " + filePath);
            }

            String content = Files.readString(filePath);
            List<HashlineCore.CodeChunk> chunks = HashlineCore.chunkify(content);

            if (chunks.isEmpty()) {
                return ToolOutput.ok("File is empty or contains no code blocks.");
            }

            StringBuilder response = new StringBuilder();
            response.append("File: ").append(filePath.getFileName()).append("\n");
            response.append("Total blocks: ").append(chunks.size()).append("\n\n");

            for (HashlineCore.CodeChunk chunk : chunks) {
                response.append("<<<< [HASH: ").append(chunk.hash()).append("] ")
                        .append("lines ").append(chunk.startLine()).append("-").append(chunk.endLine())
                        .append(" >>>>\n");
                response.append(chunk.content());
                response.append("\n====================================\n\n");
            }

            response.append("TIP: Use hashline_edit with a HASH value above to modify a specific block.");

            log.info("[HashlineRead] File {} segmented into {} blocks.", filePath.getFileName(), chunks.size());
            return ToolOutput.ok(response.toString());

        } catch (IOException e) {
            return ToolOutput.fail("Error reading file: " + e.getMessage());
        }
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String prompt() {
        return """
                When you need to modify a file, prefer using hashline_read first to see the file's block structure,
                then use hashline_edit to modify only the specific block that needs changing.
                This is much safer than rewriting the entire file with file_write, as it preserves
                all unchanged blocks exactly as they are (formatting, comments, etc.).
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
    public record Input(String path) implements ToolInput {
        @Override
        public String toJson() {
            return "{\"path\":\"" + path.replace("\"", "\\\"") + "\"}";
        }
    }
}
