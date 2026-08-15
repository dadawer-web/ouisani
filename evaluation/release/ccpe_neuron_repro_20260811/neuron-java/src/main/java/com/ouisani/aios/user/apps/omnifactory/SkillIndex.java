package com.ouisani.aios.user.apps.omnifactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能索引 — 借鉴 Langflow 的 component_index.json 按需加载机制。
 * <p>
 * 不再将完整 MANIFEST.md 全量注入 Prompt，而是：
 * 1. 启动时扫描技能目录，构建轻量级索引（名称 + 关键词 + 摘要）
 * 2. 根据任务描述匹配相关技能
 * 3. 只将匹配到的技能描述注入 Prompt，减少 token 消耗
 */
public class SkillIndex {
    private static final Logger log = LoggerFactory.getLogger(SkillIndex.class);

    /** 技能索引条目 */
    public record SkillEntry(
        String name,
        String description,
        Set<String> keywords,
        String filePath
    ) {}

    private static final SkillIndex INSTANCE = new SkillIndex();
    private final Map<String, SkillEntry> index = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    private SkillIndex() {}

    public static SkillIndex getInstance() { return INSTANCE; }

    /**
     * 从技能目录构建索引。
     * 扫描 aios_skills/ 目录下的所有 Python 文件，提取名称、描述和关键词。
     */
    public synchronized void buildIndex(String skillsDir) {
        if (loaded) return;

        Path dir = Path.of(skillsDir);
        if (!Files.isDirectory(dir)) {
            log.warn("[SkillIndex] 技能目录不存在: {}", skillsDir);
            return;
        }

        try (var stream = Files.walk(dir, 2)) {
            stream.filter(p -> p.toString().endsWith(".py"))
                 .forEach(p -> indexSkillFile(p));
        } catch (IOException e) {
            log.error("[SkillIndex] 扫描技能目录失败: {}", e.getMessage());
        }

        // 同时解析 MANIFEST.md 中的技能描述
        Path manifest = dir.resolve("MANIFEST.md");
        if (Files.exists(manifest)) {
            parseManifest(manifest);
        }

        loaded = true;
        log.info("[SkillIndex] 索引构建完成，共 {} 个技能条目。", index.size());
    }

    private void indexSkillFile(Path pyFile) {
        try {
            String content = Files.readString(pyFile);
            String name = pyFile.getFileName().toString().replace(".py", "");

            // 提取 docstring 作为描述
            String description = "";
            Pattern docPattern = Pattern.compile("\"\"\"([\\s\\S]*?)\"\"\"");
            Matcher m = docPattern.matcher(content);
            if (m.find()) {
                description = m.group(1).trim().substring(0, Math.min(200, m.group(1).trim().length()));
            }

            // 提取关键词：函数名、类名
            Set<String> keywords = new HashSet<>();
            keywords.add(name);
            Pattern funcPattern = Pattern.compile("def\\s+(\\w+)");
            Matcher fm = funcPattern.matcher(content);
            while (fm.find()) keywords.add(fm.group(1));
            Pattern classPattern = Pattern.compile("class\\s+(\\w+)");
            Matcher cm = classPattern.matcher(content);
            while (cm.find()) keywords.add(cm.group(1));

            index.put(name, new SkillEntry(name, description, keywords, pyFile.toString()));
        } catch (IOException e) {
            log.debug("[SkillIndex] 读取技能文件失败: {}", pyFile);
        }
    }

    private void parseManifest(Path manifestPath) {
        try {
            String content = Files.readString(manifestPath);
            // 简单解析：每个 ### 标题下的内容作为一个技能条目
            String[] sections = content.split("###\\s+");
            for (String section : sections) {
                if (section.isBlank()) continue;
                String[] lines = section.split("\n", 2);
                String name = lines[0].trim();
                String desc = lines.length > 1 ? lines[1].trim().substring(0, Math.min(200, lines[1].trim().length())) : "";
                Set<String> kw = new HashSet<>();
                kw.add(name);
                // 从描述中提取关键词
                for (String word : name.split("[_\\s-]")) {
                    if (word.length() > 2) kw.add(word.toLowerCase());
                }
                index.merge(name, new SkillEntry(name, desc, kw, manifestPath.toString()),
                    (existing, newEntry) -> new SkillEntry(
                        existing.name(),
                        existing.description().isEmpty() ? newEntry.description() : existing.description(),
                        existing.keywords(),
                        existing.filePath()
                    ));
            }
        } catch (IOException e) {
            log.debug("[SkillIndex] 解析 MANIFEST.md 失败: {}", e.getMessage());
        }
    }

    /**
     * 根据任务描述匹配相关技能 — 借鉴 Langflow 的 lazy_load 按需加载。
     * <p>
     * 匹配策略：
     * 1. 任务描述中的关键词与技能名称/关键词的交集
     * 2. 按匹配度排序，返回 topN 个最相关的技能
     *
     * @param taskDescription 任务描述
     * @param topN 返回的最大技能数
     * @return 匹配的技能条目列表
     */
    public List<SkillEntry> matchSkills(String taskDescription, int topN) {
        if (!loaded) return List.of();

        // 从任务描述中提取关键词
        Set<String> taskKeywords = extractKeywords(taskDescription);

        // 计算每个技能的匹配分数
        List<Map.Entry<SkillEntry, Integer>> scored = new ArrayList<>();
        for (SkillEntry entry : index.values()) {
            int score = 0;
            for (String kw : taskKeywords) {
                if (entry.name().toLowerCase().contains(kw)) score += 3; // 名称匹配权重高
                if (entry.keywords().contains(kw)) score += 2; // 关键词匹配
                if (entry.description().toLowerCase().contains(kw)) score += 1; // 描述匹配
            }
            if (score > 0) scored.add(Map.entry(entry, score));
        }

        // 按分数降序排序，取 topN
        return scored.stream()
                .sorted(Map.Entry.<SkillEntry, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        // 中文分词（简单按标点和空格分割）
        String[] parts = text.toLowerCase().split("[\\s,，。.！!？?；;：:、\\-]+");
        for (String part : parts) {
            if (part.length() > 1) keywords.add(part);
            // 对中文，也加入连续2-4字的子串
            if (part.length() >= 2) {
                for (int i = 0; i < part.length() - 1; i++) {
                    keywords.add(part.substring(i, Math.min(i + 3, part.length())));
                }
            }
        }
        return keywords;
    }

    public int size() { return index.size(); }
    public boolean isLoaded() { return loaded; }
}
