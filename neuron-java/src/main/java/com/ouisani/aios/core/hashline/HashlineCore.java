package com.ouisani.aios.core.hashline;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hashline 核心算法引擎。
 * <p>
 * 对标 oh-my-openagent 的 hashline-core：
 * 将代码文件分割为逻辑块（Chunk），并计算抗干扰的短哈希。
 * LLM 修改代码时，只需指定目标块的哈希值和新内容，
 * 即可实现精准替换，告别全量重写。
 * <p>
 * 核心流程：
 * <pre>
 *   原始代码 → chunkify() → [Chunk(hash=abc, content=..., start=1, end=10), ...]
 *   LLM 修改请求 → applyEdit(originalCode, targetHash="abc", newContent="...")
 *   → 精准替换 hash=abc 的块 → 返回修改后的完整代码
 * </pre>
 *
 * @see HashlineEditor
 */
public class HashlineCore {

    /**
     * 计算一段代码的抗干扰哈希（忽略首尾空格和空行）。
     * <p>
     * 使用 MD5 前 4 字节（8 位十六进制），兼顾速度和极低碰撞率。
     * 规范化处理：去除首尾空白、统一换行符，确保匹配时容错。
     *
     * @param codeChunk 代码片段
     * @return 8 字符十六进制哈希值
     */
    public static String computeHash(String codeChunk) {
        try {
            // 规范化：去除首尾空白，统一换行符，以便匹配时容错
            String normalized = codeChunk.trim().replaceAll("\\r\\n", "\n");
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(normalized.getBytes());
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            // 取前 4 字节（8 位十六进制）即可保证极低的碰撞率
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not supported", e);
        }
    }

    /**
     * 将一个完整的源代码文件，按空行切分为 Chunk。
     * <p>
     * 简易版：按连续空行切分。后续可接入 AST parser 实现方法级切分。
     *
     * @param fullSourceCode 完整源代码
     * @return 代码块列表
     */
    public static List<CodeChunk> chunkify(String fullSourceCode) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] rawChunks = fullSourceCode.split("\\n\\s*\\n"); // 按连续空行切分

        int currentLine = 1;
        for (String block : rawChunks) {
            if (block.trim().isEmpty()) continue;

            String hash = computeHash(block);
            int lineCount = block.split("\n").length;
            chunks.add(new CodeChunk(hash, block, currentLine, currentLine + lineCount - 1));

            // 加 1 是因为切分符 "\\n\\s*\\n" 至少占了一行空行
            currentLine += lineCount + 1;
        }
        return chunks;
    }

    /**
     * 代码块记录 — 包含哈希、内容、起止行号。
     *
     * @param hash      代码块的抗干扰哈希（8 字符十六进制）
     * @param content   代码块的原始内容
     * @param startLine 起始行号（1-based）
     * @param endLine   结束行号（1-based）
     */
    public record CodeChunk(String hash, String content, int startLine, int endLine) {}
}
