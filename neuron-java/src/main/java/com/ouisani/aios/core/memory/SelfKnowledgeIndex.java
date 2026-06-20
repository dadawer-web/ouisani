package com.ouisani.aios.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自描述知识索引 — 借鉴 Agent Zero 的 knowledge/main/about/ 机制。
 * <p>
 * Agent 启动时索引自身的架构说明、能力清单、工具列表，
 * 运行时通过关键词检索回忆自身架构和能力。
 * <p>
 * 类比操作系统的 /proc/cpuinfo、/proc/meminfo —
 * 让进程（Agent）能查询自身运行的系统信息。
 */
public class SelfKnowledgeIndex {
    private static final Logger log = LoggerFactory.getLogger(SelfKnowledgeIndex.class);

    /** 知识条目 */
    public record KnowledgeEntry(
            String name,       // 文件名（如 "architecture"）
            String title,      // 标题（如 "AIOS Architecture"）
            String content,    // 完整内容
            Set<String> keywords // 关键词
    ) {}

    private static final SelfKnowledgeIndex INSTANCE = new SelfKnowledgeIndex();
    private final Map<String, KnowledgeEntry> index = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    private SelfKnowledgeIndex() {}

    public static SelfKnowledgeIndex getInstance() { return INSTANCE; }

    /**
     * 从目录加载自描述知识。
     *
     * @param knowledgeDir 知识目录路径（如 aios_skills/self_knowledge/）
     */
    public synchronized void load(String knowledgeDir) {
        if (loaded) return;

        Path dir = Path.of(knowledgeDir);
        if (!Files.isDirectory(dir)) {
            log.warn("[SelfKnowledge] 知识目录不存在: {}", knowledgeDir);
            return;
        }

        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .forEach(this::indexFile);
        } catch (IOException e) {
            log.error("[SelfKnowledge] 加载知识目录失败: {}", e.getMessage());
        }

        loaded = true;
        log.info("[SelfKnowledge] 自描述知识索引完成，共 {} 个条目", index.size());
    }

    private void indexFile(Path mdFile) {
        try {
            String content = Files.readString(mdFile);
            String name = mdFile.getFileName().toString().replace(".md", "");

            // 提取标题（第一个 # 开头的行）
            String title = name;
            for (String line : content.split("\n")) {
                if (line.startsWith("# ")) {
                    title = line.substring(2).trim();
                    break;
                }
            }

            // 提取关键词
            Set<String> keywords = new HashSet<>();
            keywords.add(name);
            keywords.add(title.toLowerCase());
            // 从内容提取 ### 标题作为关键词
            for (String line : content.split("\n")) {
                if (line.startsWith("### ")) {
                    keywords.add(line.substring(4).trim().toLowerCase());
                }
            }

            index.put(name, new KnowledgeEntry(name, title, content, keywords));
        } catch (IOException e) {
            log.debug("[SelfKnowledge] 读取知识文件失败: {}", mdFile);
        }
    }

    /**
     * 检索与查询相关的自描述知识。
     *
     * @param query 查询文本（如 "我能做什么"、"架构是什么"）
     * @param maxResults 最大返回条数
     * @return 匹配的知识条目列表
     */
    public List<KnowledgeEntry> search(String query, int maxResults) {
        if (!loaded || query == null || query.isBlank()) return List.of();

        Set<String> queryKeywords = extractKeywords(query);
        List<Map.Entry<KnowledgeEntry, Integer>> scored = new ArrayList<>();

        for (KnowledgeEntry entry : index.values()) {
            int score = 0;
            for (String kw : queryKeywords) {
                if (entry.name().toLowerCase().contains(kw)) score += 3;
                if (entry.keywords().contains(kw)) score += 2;
                if (entry.content().toLowerCase().contains(kw)) score += 1;
            }
            if (score > 0) scored.add(Map.entry(entry, score));
        }

        return scored.stream()
                .sorted(Map.Entry.<KnowledgeEntry, Integer>comparingByValue().reversed())
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        String[] parts = text.toLowerCase().split("[\\s,，。.！!？?；;：:、\\-]+");
        for (String part : parts) {
            if (part.length() > 1) keywords.add(part);
        }
        return keywords;
    }

    /**
     * 获取指定名称的知识条目。
     */
    public KnowledgeEntry get(String name) {
        return index.get(name);
    }

    /**
     * 获取所有知识条目名称。
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(index.keySet());
    }

    public boolean isLoaded() { return loaded; }
    public int size() { return index.size(); }
}
