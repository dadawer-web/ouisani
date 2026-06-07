package com.ouisani.aios.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 技能加载器 — 对标 Claude Code 的 loadSkillsDir.ts。
 * <p>
 * 从多个来源加载技能：
 * - Bundled — 内置技能
 * - User — ~/.claude/skills/
 * - Project — .claude/skills/
 * - Plugin — 插件提供的技能
 * <p>
 * OS 类比：相当于 Linux 的 modprobe — 按需加载内核模块（技能）。
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    /** 技能来源 */
    public enum SkillSource {
        BUNDLED, USER, PROJECT, PLUGIN, MANAGED
    }

    /** 技能定义 */
    public record SkillDef(
            String name,
            String description,
            String content,         // SKILL.md 内容
            List<String> allowedTools,
            List<String> arguments,
            String whenToUse,
            String model,
            SkillSource source,
            Path path
    ) {}

    private static final Map<String, SkillDef> skillCache = new ConcurrentHashMap<>();

    /**
     * 加载所有技能 — 对标 getSkillDirCommands()。
     */
    public static Map<String, SkillDef> loadAll(String workingDir) {
        Map<String, SkillDef> skills = new LinkedHashMap<>();

        // 1. Bundled skills
        loadBundledSkills(skills);

        // 2. User skills
        String userHome = System.getProperty("user.home");
        loadSkillsFromDir(skills, Path.of(userHome, ".claude", "skills"), SkillSource.USER);

        // 3. Project skills
        loadSkillsFromDir(skills, Path.of(workingDir, ".claude", "skills"), SkillSource.PROJECT);

        skillCache.clear();
        skillCache.putAll(skills);

        log.info("[SkillLoader] Loaded {} skills", skills.size());
        return skills;
    }

    /**
     * 获取缓存的技能。
     */
    public static Map<String, SkillDef> getCached() {
        return Collections.unmodifiableMap(skillCache);
    }

    /**
     * 按名称查找技能。
     */
    public static Optional<SkillDef> get(String name) {
        return Optional.ofNullable(skillCache.get(name));
    }

    /**
     * 条件技能激活 — 基于文件路径匹配。
     */
    public static List<SkillDef> activateConditionalSkills(String filePath, Map<String, SkillDef> allSkills) {
        List<SkillDef> activated = new ArrayList<>();
        for (SkillDef skill : allSkills.values()) {
            if (skill.whenToUse() != null && !skill.whenToUse().isEmpty()) {
                // 简单的 glob 匹配
                String pattern = skill.whenToUse().replace("*", ".*");
                if (filePath.matches(pattern)) {
                    activated.add(skill);
                }
            }
        }
        return activated;
    }

    // ── 内部方法 ──

    private static void loadBundledSkills(Map<String, SkillDef> skills) {
        // 内置技能定义
        addBundled(skills, "verify", "Verify code changes by reading and checking the modified files",
                "After making code changes, use this skill to verify the modifications are correct.");
        addBundled(skills, "debug", "Debug issues by systematically investigating error messages and code",
                "When encountering errors, use this skill to systematically debug.");
        addBundled(skills, "remember", "Save important information to project memory for future reference",
                "When learning important project details, use this skill to save them.");
        addBundled(skills, "batch", "Execute the same operation across multiple files",
                "When you need to apply the same change to many files, use this skill.");
        addBundled(skills, "stuck", "Get unstuck by trying alternative approaches",
                "When you're stuck on a problem, use this skill to try different approaches.");
    }

    private static void addBundled(Map<String, SkillDef> skills, String name, String desc, String whenToUse) {
        skills.put(name, new SkillDef(name, desc, "", List.of(), List.of(), whenToUse, "", SkillSource.BUNDLED, null));
    }

    private static void loadSkillsFromDir(Map<String, SkillDef> skills, Path dir, SkillSource source) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return;

        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                Path skillMd = skillDir.resolve("SKILL.md");
                if (Files.exists(skillMd)) {
                    try {
                        String content = Files.readString(skillMd, StandardCharsets.UTF_8);
                        String name = skillDir.getFileName().toString();
                        SkillDef def = parseSkillMd(name, content, source, skillMd);
                        skills.put(name, def);
                        log.debug("[SkillLoader] Loaded {} skill: {}", source, name);
                    } catch (IOException e) {
                        log.warn("[SkillLoader] Failed to read {}: {}", skillMd, e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            log.debug("[SkillLoader] Failed to list {}: {}", dir, e.getMessage());
        }
    }

    private static SkillDef parseSkillMd(String name, String content, SkillSource source, Path path) {
        // 简化的 frontmatter 解析
        String description = "";
        List<String> allowedTools = List.of();
        String whenToUse = "";
        String model = "";

        String[] lines = content.split("\n");
        boolean inFrontmatter = false;

        for (String line : lines) {
            if (line.trim().equals("---")) {
                inFrontmatter = !inFrontmatter;
                continue;
            }
            if (inFrontmatter) {
                if (line.startsWith("description:")) {
                    description = line.substring("description:".length()).trim().replace("\"", "");
                } else if (line.startsWith("allowed-tools:")) {
                    String tools = line.substring("allowed-tools:".length()).trim();
                    allowedTools = Arrays.stream(tools.split("[,\\s]+")).filter(s -> !s.isEmpty()).toList();
                } else if (line.startsWith("when_to_use:")) {
                    whenToUse = line.substring("when_to_use:".length()).trim().replace("\"", "");
                } else if (line.startsWith("model:")) {
                    model = line.substring("model:".length()).trim().replace("\"", "");
                }
            }
        }

        return new SkillDef(name, description, content, allowedTools, List.of(), whenToUse, model, source, path);
    }
}
