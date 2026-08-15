package com.ouisani.aios.drivers.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embedding 向量 LRU 缓存 — 镜像 jcode-embedding embedding.rs:18,49-52。
 * <p>
 * text-hash → float[]，避免对相同文本重复 ONNX 推理。容量 128，
 * 超出后按 LRU（访问顺序）驱逐最久未用项。所有方法 synchronized
 * 保护（项目用虚拟线程，Predictor 非线程安全，缓存同样需要串行访问）。
 * <p>
 * key 用 SHA-256(text) hex 而非原始文本，避免在 map 中长期持有大字符串。
 */
public final class EmbeddingCache {

    static final int MAX_CAPACITY = 128;

    private final LinkedHashMap<String, float[]> cache;
    private final MessageDigest digest;

    public EmbeddingCache() {
        // accessOrder=true：get/put 都会将被访问项移到末尾，head 即最久未用
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > MAX_CAPACITY;
            }
        };
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论不会缺失
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /** 命中返回向量，未命中返回 null。 */
    public synchronized float[] get(String text) {
        return cache.get(key(text));
    }

    /** 写入缓存；超过容量自动驱逐最久未用项。 */
    public synchronized void put(String text, float[] embedding) {
        cache.put(key(text), embedding);
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void clear() {
        cache.clear();
    }

    /** text → SHA-256 hex（MessageDigest 非线程安全，调用方已 synchronized）。 */
    private String key(String text) {
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
