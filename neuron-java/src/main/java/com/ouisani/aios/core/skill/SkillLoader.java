package com.ouisani.aios.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 技能加载器 — 对标 Claude Code 的 loadSkillsDir.ts。
 * <p>
 * 从多个来源加载技能：
 * - Bundled — 内置技能（硬编码 + classpath resources/skills/）
 * - User — ~/.claude/skills/
 * - Project — .claude/skills/
 * - Plugin — 插件提供的技能
 * <p>
 * 支持动态插拔：运行时可通过 {@link #activate(String)} / {@link #deactivate(String)}
 * 激活或停用技能，被激活的行为准则技能会自动注入到系统提示词中。
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

    /** 已激活的技能集合 — 运行时动态插拔 */
    private static final Set<String> activeSkills = ConcurrentHashMap.newKeySet();

    /**
     * 加载所有技能 — 对标 getSkillDirCommands()。
     */
    public static Map<String, SkillDef> loadAll(String workingDir) {
        Map<String, SkillDef> skills = new LinkedHashMap<>();

        // 1. Bundled skills（硬编码）
        loadBundledSkills(skills);

        // 1.5 Bundled skills（classpath resources/skills/）
        loadClasspathSkills(skills);

        // 2. User skills
        String userHome = System.getProperty("user.home");
        loadSkillsFromDir(skills, Path.of(userHome, ".claude", "skills"), SkillSource.USER);

        // 3. Project skills
        loadSkillsFromDir(skills, Path.of(workingDir, ".claude", "skills"), SkillSource.PROJECT);

        skillCache.clear();
        skillCache.putAll(skills);

        log.info("[SkillLoader] Loaded {} skills, {} active", skills.size(), activeSkills.size());
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

    // ════════════════════════════════════════════════════════════════
    //  动态插拔 — 运行时激活/停用技能
    // ════════════════════════════════════════════════════════════════

    /**
     * 激活技能 — 被激活的行为准则技能将注入到系统提示词中。
     *
     * @param name 技能名称
     * @return true 表示激活成功，false 表示技能不存在
     */
    public static boolean activate(String name) {
        if (!skillCache.containsKey(name)) {
            log.warn("[SkillLoader] 无法激活不存在的技能: {}", name);
            return false;
        }
        boolean added = activeSkills.add(name);
        if (added) {
            log.info("[SkillLoader] 技能已激活: {} (当前活跃: {})", name, activeSkills);
            System.out.printf("  [SkillLoader] 技能已激活: %s%n", name);
        }
        return added;
    }

    /**
     * 停用技能 — 从系统提示词中移除该技能的行为准则。
     *
     * @param name 技能名称
     * @return true 表示停用成功
     */
    public static boolean deactivate(String name) {
        boolean removed = activeSkills.remove(name);
        if (removed) {
            log.info("[SkillLoader] 技能已停用: {} (当前活跃: {})", name, activeSkills);
            System.out.printf("  [SkillLoader] 技能已停用: %s%n", name);
        }
        return removed;
    }

    /**
     * 获取所有已激活的技能定义。
     */
    public static List<SkillDef> getActiveSkills() {
        return activeSkills.stream()
                .map(skillCache::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 查询技能是否已激活。
     */
    public static boolean isActive(String name) {
        return activeSkills.contains(name);
    }

    /**
     * 获取已激活技能名称集合。
     */
    public static Set<String> getActiveSkillNames() {
        return Collections.unmodifiableSet(activeSkills);
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

    /**
     * 将已激活技能的行为准则格式化为可注入系统提示词的文本。
     * <p>
     * 只有 content 非空的技能才会被包含（行为准则类技能），
     * 纯工具类技能（如 verify、debug）不注入系统提示词。
     */
    public static String formatActiveSkillsAsPrompt() {
        List<SkillDef> active = getActiveSkills();
        if (active.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Active Behavioral Guidelines\n\n");
        sb.append("The following behavioral guidelines are currently active. You MUST follow these principles:\n\n");

        for (SkillDef skill : active) {
            if (skill.content() == null || skill.content().isBlank()) continue;
            sb.append("### Skill: ").append(skill.name()).append("\n\n");
            // 提取 SKILL.md 中 frontmatter 之后的内容（行为准则正文）
            String body = extractBody(skill.content());
            sb.append(body).append("\n\n");
        }

        return sb.toString();
    }

    // ── 内部方法 ──

    /**
     * 从 SKILL.md 内容中提取 frontmatter 之后的正文。
     */
    private static String extractBody(String content) {
        if (content == null || content.isEmpty()) return "";
        // 跳过 YAML frontmatter（两个 --- 之间的部分）
        int firstDash = content.indexOf("---");
        if (firstDash < 0) return content;
        int secondDash = content.indexOf("---", firstDash + 3);
        if (secondDash < 0) return content;
        return content.substring(secondDash + 3).trim();
    }

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

    /**
     * 从 classpath resources/skills/ 目录加载内置技能。
     * <p>
     * 打包在 JAR 中的技能文件（如 karpathy-guidelines/SKILL.md），
     * 通过 ClassLoader 资源流读取，无需文件系统路径。
     */
    private static void loadClasspathSkills(Map<String, SkillDef> skills) {
        try {
            // 尝试列举 resources/skills/ 下的子目录
            var resource = SkillLoader.class.getClassLoader().getResource("skills");
            if (resource == null) {
                log.debug("[SkillLoader] No classpath skills/ directory found");
                return;
            }

            if ("file".equals(resource.getProtocol())) {
                // 开发模式：文件系统上的 resources 目录
                Path skillsDir = Path.of(resource.toURI());
                loadSkillsFromDir(skills, skillsDir, SkillSource.BUNDLED);
            } else if ("jar".equals(resource.getProtocol())) {
                // 生产模式：JAR 包内 — 已知技能列表硬编码扫描
                loadKnownClasspathSkills(skills);
            }
        } catch (Exception e) {
            log.debug("[SkillLoader] Failed to load classpath skills: {}", e.getMessage());
            // 回退：尝试加载已知技能
            loadKnownClasspathSkills(skills);
        }
    }

    /**
     * 加载已知的 classpath 技能 — JAR 包内无法动态列举目录，
     * 因此维护一个已知技能名列表。
     */
    private static void loadKnownClasspathSkills(Map<String, SkillDef> skills) {
        String[] knownSkills = {"karpathy-guidelines"};
        for (String skillName : knownSkills) {
            if (skills.containsKey(skillName)) continue; // 不覆盖已加载的
            try {
                var is = SkillLoader.class.getClassLoader()
                        .getResourceAsStream("skills/" + skillName + "/SKILL.md");
                if (is != null) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    SkillDef def = parseSkillMd(skillName, content, SkillSource.BUNDLED, null);
                    skills.put(skillName, def);
                    log.info("[SkillLoader] Loaded classpath skill: {}", skillName);
                }
            } catch (IOException e) {
                log.warn("[SkillLoader] Failed to load classpath skill {}: {}", skillName, e.getMessage());
            }
        }
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
