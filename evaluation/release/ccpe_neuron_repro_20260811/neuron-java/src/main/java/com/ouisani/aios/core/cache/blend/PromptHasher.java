package com.ouisani.aios.core.cache.blend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 哈希计算器 — 计算文本内容的哈希值，用于缓存匹配。
 * <p>
 * 借鉴 LMCache 的滚动哈希 + 多项式哈希机制：
 * LMCache 使用 2^20 大小的直接寻址表 + 滚动哈希实现 O(1) 的 chunk 匹配。
 * <p>
 * AIOS 的 PromptHasher 提供两种粒度的哈希：
 * <ul>
 *   <li>{@link #hash(String)} — 整体 SHA-256 哈希，用于验证缓存有效性</li>
 *   <li>{@link #chunkHashes(String, int)} — 分块哈希列表，用于 CacheBlend 非前缀匹配</li>
 * </ul>
 * <p>
 * 分块哈希借鉴 LMCache 的 chunk_size 机制：将长文本按固定大小分块，
 * 每块独立计算哈希，使得非连续的相同片段也能被匹配到。
 *
 * @see CacheBlendEngine
 */
public final class PromptHasher {

    private static final Logger log = LoggerFactory.getLogger(PromptHasher.class);

    /** 默认分块大小（字符数），借鉴 LMCache 的 chunk_size */
    public static final int DEFAULT_CHUNK_SIZE = 512;

    private PromptHasher() {
    }

    /**
     * 计算文本内容的 SHA-256 哈希。
     *
     * @param content 文本内容
     * @return 十六进制哈希字符串（64 字符）
     */
    public static String hash(String content) {
        if (content == null || content.isEmpty()) {
            return "0".repeat(64);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 将文本按固定大小分块，计算每块的哈希。
     * <p>
     * 借鉴 LMCache 的 chunk 分块机制：将长文本按 chunk_size 分块，
     * 每块独立计算哈希，用于非前缀匹配。
     *
     * @param content   文本内容
     * @param chunkSize 分块大小（字符数）
     * @return 哈希列表（每块一个哈希）
     */
    public static List<String> chunkHashes(String content, int chunkSize) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        if (chunkSize <= 0) {
            chunkSize = DEFAULT_CHUNK_SIZE;
        }

        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < content.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, content.length());
            String chunk = content.substring(i, end);
            hashes.add(hash(chunk));
        }
        return hashes;
    }

    /**
     * 使用默认分块大小计算分块哈希。
     *
     * @param content 文本内容
     * @return 哈希列表
     */
    public static List<String> chunkHashes(String content) {
        return chunkHashes(content, DEFAULT_CHUNK_SIZE);
    }

    /**
     * 计算两个文本的内容相似度（基于分块哈希的重合度）。
     * <p>
     * 借鉴 LMCache 的 CacheBlend 匹配率计算：
     * 统计两个文本的分块哈希交集数量，除以较短文本的块数。
     *
     * @param text1 第一个文本
     * @param text2 第二个文本
     * @return 相似度（0.0 ~ 1.0）
     */
    public static double similarity(String text1, String text2) {
        if (text1 == null || text2 == null || text1.isEmpty() || text2.isEmpty()) {
            return 0.0;
        }
        List<String> hashes1 = chunkHashes(text1);
        List<String> hashes2 = chunkHashes(text2);

        // 使用集合交集计算重合度
        var set1 = new java.util.HashSet<>(hashes1);
        set1.retainAll(hashes2);

        int minChunks = Math.min(hashes1.size(), hashes2.size());
        return minChunks == 0 ? 0.0 : (double) set1.size() / minChunks;
    }
}
