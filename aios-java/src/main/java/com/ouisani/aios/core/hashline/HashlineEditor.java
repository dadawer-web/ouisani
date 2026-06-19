package com.ouisani.aios.core.hashline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于哈希的代码微创手术刀。
 * <p>
 * 对标 oh-my-openagent 的 hashline-core Diff Applier：
 * 接收大模型发来的修改请求，通过哈希精确定位目标代码块，
 * 只替换被修改的部分，其余代码原样保留。
 * <p>
 * 与全量重写的对比：
 * <pre>
 *   全量重写：LLM 重写整个文件 → 丢失未修改区域的格式/注释
 *   Hashline：LLM 只提供目标块哈希 + 新内容 → 精准替换，零副作用
 * </pre>
 *
 * @see HashlineCore
 */
public class HashlineEditor {

    private static final Logger log = LoggerFactory.getLogger(HashlineEditor.class);

    /**
     * 精准替换 — 通过哈希定位目标代码块并替换。
     *
     * @param originalCode 硬盘里的原始文件代码
     * @param targetHash   大模型想要修改的代码块的哈希值
     * @param newContent   大模型提供的新代码
     * @return 修改后的完整代码
     * @throws RuntimeException 如果目标哈希未找到
     */
    public static String applyEdit(String originalCode, String targetHash, String newContent) {
        List<HashlineCore.CodeChunk> chunks = HashlineCore.chunkify(originalCode);

        StringBuilder result = new StringBuilder();
        boolean replaced = false;

        for (HashlineCore.CodeChunk chunk : chunks) {
            if (chunk.hash().equals(targetHash)) {
                // 命中目标！进行替换
                log.info("[Hashline] Target chunk [{}] (lines {}-{}) located. Applying precise edit.",
                        targetHash, chunk.startLine(), chunk.endLine());
                result.append(newContent).append("\n\n");
                replaced = true;
            } else {
                // 未命中，原样保留
                result.append(chunk.content()).append("\n\n");
            }
        }

        if (!replaced) {
            log.warn("[Hashline] Failed to apply edit. Hash [{}] not found in the original file.", targetHash);
            // 容错机制：如果没找到，抛出异常让自愈引擎重试
            throw new RuntimeException("Hashline mismatch. Target block [" + targetHash + "] not found.");
        }

        return result.toString().trim() + "\n";
    }

    /**
     * 多块替换 — 一次修改多个代码块。
     *
     * @param originalCode 原始代码
     * @param edits        修改列表，每个元素为 [targetHash, newContent]
     * @return 修改后的完整代码
     */
    public static String applyMultipleEdits(String originalCode, List<String[]> edits) {
        List<HashlineCore.CodeChunk> chunks = HashlineCore.chunkify(originalCode);

        // 构建哈希 → 新内容的映射
        java.util.Map<String, String> editMap = new java.util.HashMap<>();
        for (String[] edit : edits) {
            if (edit.length >= 2) {
                editMap.put(edit[0], edit[1]);
            }
        }

        StringBuilder result = new StringBuilder();
        int replacedCount = 0;

        for (HashlineCore.CodeChunk chunk : chunks) {
            String newContent = editMap.get(chunk.hash());
            if (newContent != null) {
                log.info("[Hashline] Target chunk [{}] (lines {}-{}) replaced.",
                        chunk.hash(), chunk.startLine(), chunk.endLine());
                result.append(newContent).append("\n\n");
                replacedCount++;
            } else {
                result.append(chunk.content()).append("\n\n");
            }
        }

        if (replacedCount == 0 && !edits.isEmpty()) {
            log.warn("[Hashline] 所有 {} 个目标哈希在原始文件中均未找到。", edits.size());
            throw new RuntimeException("Hashline mismatch. No target blocks found.");
        }

        log.info("[Hashline] Applied {}/{} edits successfully.", replacedCount, edits.size());
        return result.toString().trim() + "\n";
    }

    /**
     * 查找目标哈希对应的代码块 — 用于验证和调试。
     *
     * @param originalCode 原始代码
     * @param targetHash   目标哈希
     * @return 匹配的代码块，未找到返回 null
     */
    public static HashlineCore.CodeChunk findChunk(String originalCode, String targetHash) {
        List<HashlineCore.CodeChunk> chunks = HashlineCore.chunkify(originalCode);
        for (HashlineCore.CodeChunk chunk : chunks) {
            if (chunk.hash().equals(targetHash)) {
                return chunk;
            }
        }
        return null;
    }

    /**
     * 列出文件中所有代码块的哈希摘要 — 供 LLM 参考选择修改目标。
     *
     * @param originalCode 原始代码
     * @return 哈希摘要列表
     */
    public static String listChunkHashes(String originalCode) {
        List<HashlineCore.CodeChunk> chunks = HashlineCore.chunkify(originalCode);
        StringBuilder sb = new StringBuilder();
        sb.append("Code chunks in file:\n");
        for (HashlineCore.CodeChunk chunk : chunks) {
            // 只显示第一行作为预览
            String firstLine = chunk.content().split("\n")[0];
            if (firstLine.length() > 60) firstLine = firstLine.substring(0, 60) + "...";
            sb.append(String.format("  [%s] lines %d-%d: %s%n",
                    chunk.hash(), chunk.startLine(), chunk.endLine(), firstLine));
        }
        return sb.toString();
    }
}
