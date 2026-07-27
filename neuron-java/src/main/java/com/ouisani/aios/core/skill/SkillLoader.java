package com.ouisani.aios.core.skill;

import com.ouisani.aios.core.config.AiosPaths;
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

    /**
     * 技能来源。
     * <p>
     * 优先级链（早源 shadow 晚源，{@link #loadAll} 按 PROJECT→BUNDLED→USER→LEARNED 顺序加载，
     * first-wins via {@code putIfAbsent}）：PROJECT > BUNDLED > USER > LEARNED。
     */
    public enum SkillSource {
        BUNDLED, USER, PROJECT, PLUGIN, MANAGED, LEARNED
    }

    /**
     * 技能定义。
     * <p>
     * R1 新增 {@code outputContract} 字段 — 描述 skill 期望的输出契约
     * （fenced block 语言标签 + JSON schema 描述），让 LLM 非结构化输出
     * 可被 {@link SkillOutputParser} 提取为结构化结果。
     * <p>
     * 格式约定：{@code <languageTag>:<description>}，例如
     * {@code "json:Reviewer findings as JSON with findings[], severity, sections[]"}。
     * 为空表示该 skill 无结构化输出契约（纯行为准则类 skill）。
     * <p>
     * Skill Catalog 多源加载新增 {@code category}/{@code tags} 字段（frontmatter 索引），
     * 以及 {@link #body()} 懒加载 API：eager 源（BUNDLED/PROJECT/USER）content 非 null；
     * LEARNED 源 content=null，body 首次使用才从 path 读取并缓存。
     * <p>
     * <b>Cap 模型升级（用户需求）</b>：新增 {@link SkillCap} 字段，承载 4 个结构化能力字段
     * （author / artifactSrcUrl / supportedInputs / providerId）。与既有
     * {@link RoleBlueprintLoader} 的"双读模式"对齐：
     * <ul>
     *   <li><b>原文 prompt 侧</b> — {@link #content} / {@link #body()} 完全保留</li>
     *   <li><b>结构化 Cap 侧</b> — {@link #cap()} 供调度器/沙箱/权限层加载时决策</li>
     * </ul>
     * 缺新字段的存量 SKILL.md 仍能正常加载，{@link #cap} 降级为 {@link SkillCap#DEFAULT}
     * （零回归）。所有旧便利构造器自动填充 DEFAULT cap，调用方源码兼容。
     */
    public record SkillDef(
            String name,
            String description,
            String content,         // SKILL.md 内容（LEARNED 源为 null，懒加载）
            List<String> allowedTools,
            List<String> arguments,
            String whenToUse,
            String model,
            SkillSource source,
            Path path,
            String outputContract,  // R1: 输出契约（可空）
            String category,        // 技能分类（frontmatter category，默认空）
            List<String> tags,      // 标签列表（frontmatter tags，默认空）
            SkillCap cap            // Cap 模型（author/srcUrl/inputs/providerId，默认 DEFAULT）
    ) {

        /** compact constructor — 兜底 null 默认值 */
        public SkillDef {
            if (outputContract == null) outputContract = "";
            if (category == null) category = "";
            if (tags == null) tags = List.of();
            if (cap == null) cap = SkillCap.DEFAULT;
        }

        /**
         * 便利构造器（11-arg，含 outputContract/category/tags，无 cap）— 旧调用方兼容。
         * <p>
         * cap 自动填充为 {@link SkillCap#DEFAULT}，等价于未升级的存量 SKILL.md。
         */
        public SkillDef(String name, String description, String content,
                        List<String> allowedTools, List<String> arguments,
                        String whenToUse, String model, SkillSource source, Path path,
                        String outputContract, String category, List<String> tags) {
            this(name, description, content, allowedTools, arguments,
                    whenToUse, model, source, path, outputContract, category, tags, SkillCap.DEFAULT);
        }

        /**
         * 便利构造器（10-arg，含 outputContract，无 category/tags/cap）— R1 调用方兼容。
         */
        public SkillDef(String name, String description, String content,
                        List<String> allowedTools, List<String> arguments,
                        String whenToUse, String model, SkillSource source, Path path,
                        String outputContract) {
            this(name, description, content, allowedTools, arguments,
                    whenToUse, model, source, path, outputContract, "", List.of(), SkillCap.DEFAULT);
        }

        /**
         * 便利构造器（9-arg，无 outputContract）— 旧调用方兼容。
         */
        public SkillDef(String name, String description, String content,
                        List<String> allowedTools, List<String> arguments,
                        String whenToUse, String model, SkillSource source, Path path) {
            this(name, description, content, allowedTools, arguments,
                    whenToUse, model, source, path, "", "", List.of(), SkillCap.DEFAULT);
        }

        /**
         * 技能正文（剥离 frontmatter 后的内容）。
         * <p>
         * eager 源（BUNDLED/PROJECT/USER）content 非 null → 直接 {@code extractBody}；
         * LEARNED 源 content=null → 首次使用从 {@link #path} 懒读取并缓存
         * （{@link SkillLoader#resolveLazyBody}）。借鉴 OpenScience lazy fetch：
         * catalog 只索引 frontmatter，大 skill 的 body 不污染 prompt，真正激活/调用时才解析。
         */
        public String body() {
            if (content != null) return SkillLoader.extractBody(content);
            return SkillLoader.resolveLazyBody(this);
        }
    }

    private static final Map<String, SkillDef> skillCache = new ConcurrentHashMap<>();

    /** 已激活的技能集合 — 运行时动态插拔 */
    private static final Set<String> activeSkills = ConcurrentHashMap.newKeySet();

    /**
     * 加载所有技能 — 对标 OpenScience skill.ts 多源 catalog 组装。
     * <p>
     * 按优先级链顺序加载，**早源 shadow 晚源**（{@code putIfAbsent}）：
     * <ol>
     *   <li>PROJECT — {@code .aios/skills/} 优先，回退 {@code .claude/skills/}（legacy）</li>
     *   <li>BUNDLED — classpath {@code resources/skills/}（INDEX 自动发现）</li>
     *   <li>USER — {@code ~/.aios/skills/} 优先，回退 {@code ~/.claude/skills/}</li>
     *   <li>LEARNED — {@link AiosPaths#learnedSkillsDir}（懒加载：仅索引 frontmatter）</li>
     * </ol>
     * 借鉴 OpenScience：name 冲突时早源 shadow 晚源。
     */
    public static Map<String, SkillDef> loadAll(String workingDir) {
        Map<String, SkillDef> skills = new LinkedHashMap<>();

        // 1. PROJECT（最高优先级）— .aios/skills 优先，回退 .claude/skills（legacy 兼容）
        loadSkillsFromDir(skills, Path.of(workingDir, ".aios", "skills"), SkillSource.PROJECT);
        loadSkillsFromDir(skills, Path.of(workingDir, ".claude", "skills"), SkillSource.PROJECT);

        // 2. BUNDLED — classpath resources/skills（INDEX 自动发现）
        loadClasspathSkills(skills);

        // 3. USER — ~/.aios/skills 优先，回退 ~/.claude/skills
        String userHome = System.getProperty("user.home");
        loadSkillsFromDir(skills, Path.of(userHome, ".aios", "skills"), SkillSource.USER);
        loadSkillsFromDir(skills, Path.of(userHome, ".claude", "skills"), SkillSource.USER);

        // 4. LEARNED — /var/db/memory/learned-skills（懒加载：仅索引 frontmatter，body 首次使用才读）
        loadLearnedSkills(skills);

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
            String body = skill.body();
            if (body.isBlank()) continue;
            sb.append("### Skill: ").append(skill.name()).append("\n\n");
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

    /** LEARNED 源懒加载 body 缓存 — path → 完整 SKILL.md 内容（首次读取后缓存） */
    private static final Map<Path, String> lazyBodyCache = new ConcurrentHashMap<>();

    /**
     * 懒解析 LEARNED 源技能 body — 首次使用从 {@code path} 读取完整 SKILL.md 并缓存，
     * 返回剥离 frontmatter 后的正文。借鉴 OpenScience lazy fetch：catalog 不持有 body，
     * 调用时才 resolve。
     */
    private static String resolveLazyBody(SkillDef skill) {
        if (skill.path() == null) return "";
        String fullContent = lazyBodyCache.computeIfAbsent(skill.path(), p -> {
            try {
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("[SkillLoader] 懒加载技能 body 失败: {} - {}", p, e.getMessage());
                return "";
            }
        });
        return extractBody(fullContent);
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
                // 生产模式：JAR 包内 — 通过 INDEX 自动发现
                loadClasspathSkillsFromIndex(skills);
            }
        } catch (Exception e) {
            log.debug("[SkillLoader] Failed to load classpath skills: {}", e.getMessage());
            // 回退：通过 INDEX 加载
            loadClasspathSkillsFromIndex(skills);
        }
    }

    /**
     * 从 classpath skills/INDEX 加载技能 — JAR 包内无法列举目录,
     * 故读取 INDEX 文件获取技能名列表,再逐个加载 SKILL.md。
     *
     * 借鉴 mobilegym import.meta.glob:加技能 = 丢目录 + SKILL.md + 跑索引脚本,不改 Java。
     */
    private static void loadClasspathSkillsFromIndex(Map<String, SkillDef> skills) {
        var indexStream = SkillLoader.class.getClassLoader().getResourceAsStream("skills/INDEX");
        if (indexStream == null) {
            log.debug("[SkillLoader] No classpath skills/INDEX found");
            return;
        }
        try (var is = indexStream) {
            String indexContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : indexContent.split("\n")) {
                String skillName = line.trim();
                if (skillName.isEmpty() || skills.containsKey(skillName)) continue;
                try (var skillStream = SkillLoader.class.getClassLoader()
                        .getResourceAsStream("skills/" + skillName + "/SKILL.md")) {
                    if (skillStream != null) {
                        String content = new String(skillStream.readAllBytes(), StandardCharsets.UTF_8);
                        SkillDef def = parseSkillMd(skillName, content, SkillSource.BUNDLED, null);
                        skills.putIfAbsent(skillName, def);
                        log.info("[SkillLoader] Loaded classpath skill: {}", skillName);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[SkillLoader] Failed to read skills/INDEX: {}", e.getMessage());
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
                        skills.putIfAbsent(name, def);
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
        return parseSkillMd(name, content, source, path, false);
    }

    /**
     * 解析 SKILL.md frontmatter。
     *
     * @param lazy true → LEARNED 源：丢弃 body（content 存为 null），仅索引 frontmatter，
     *             body 首次使用时从 path 懒读取（{@link SkillDef#body}）
     */
    private static SkillDef parseSkillMd(String name, String content, SkillSource source, Path path, boolean lazy) {
        // 简化的 frontmatter 解析
        String description = "";
        List<String> allowedTools = List.of();
        String whenToUse = "";
        String model = "";
        String outputContract = "";  // R1: 输出契约
        String category = "";        // 技能分类
        List<String> tags = List.of(); // 标签

        // ── Cap 模型新增字段（用户需求：Skill 升级为结构化 Cap 模型）──
        String author = "";                       // author 命名空间
        String artifactSrcUrl = "";                // artifact.srcUrl（远程代码载荷）
        List<String> supportedInputs = List.of();  // supportedInputs（v1 强制 ["text"]）
        String providerId = "";                    // providerId 枚举

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
                } else if (line.startsWith("output-contract:") || line.startsWith("output_contract:")) {
                    // R1: 解析输出契约 — 支持 kebab-case 和 snake_case
                    String key = line.startsWith("output-contract:") ? "output-contract:" : "output_contract:";
                    outputContract = line.substring(key.length()).trim().replace("\"", "");
                } else if (line.startsWith("category:")) {
                    category = line.substring("category:".length()).trim().replace("\"", "");
                } else if (line.startsWith("tags:")) {
                    String tagsStr = line.substring("tags:".length()).trim();
                    tags = Arrays.stream(tagsStr.split("[,\\s]+")).filter(s -> !s.isEmpty()).toList();
                } else if (line.startsWith("author:")) {
                    // Cap: author 命名空间字段
                    author = line.substring("author:".length()).trim().replace("\"", "");
                } else if (line.startsWith("artifact.srcUrl:") || line.startsWith("artifact_src_url:")
                        || line.startsWith("artifact.srcurl:") || line.startsWith("artifact-src-url:")) {
                    // Cap: artifact.srcUrl（远程代码载荷）— 支持 dotted/snake/kebab 多种写法
                    int colon = line.indexOf(':');
                    artifactSrcUrl = line.substring(colon + 1).trim().replace("\"", "");
                } else if (line.startsWith("supported-inputs:") || line.startsWith("supported_inputs:")) {
                    // Cap: supportedInputs（v1 强制 ["text"]，其他值由 SkillCap 规范化）
                    int colon = line.indexOf(':');
                    String inputsStr = line.substring(colon + 1).trim();
                    supportedInputs = Arrays.stream(inputsStr.split("[,\\s]+"))
                            .filter(s -> !s.isEmpty()).toList();
                } else if (line.startsWith("provider-id:") || line.startsWith("provider_id:")
                        || line.startsWith("providerId:")) {
                    // Cap: providerId 枚举
                    int colon = line.indexOf(':');
                    providerId = line.substring(colon + 1).trim().replace("\"", "");
                }
            }
        }

        // ── 构造 SkillCap（best-effort：未知 providerId 降级 AIOS_CORE；非法 URL 降级 null）──
        SkillCap cap = author.isEmpty() && artifactSrcUrl.isEmpty()
                && supportedInputs.isEmpty() && providerId.isEmpty()
                ? SkillCap.DEFAULT
                : SkillCap.of(author, artifactSrcUrl, supportedInputs, providerId);

        if (!cap.equals(SkillCap.DEFAULT)) {
            log.debug("[SkillLoader] Skill '{}' 升级为 Cap 模型: author={}, providerId={}, srcUrl={}",
                    name, cap.author(), cap.providerId(),
                    cap.artifactSrcUrl() == null ? "(none)" : cap.artifactSrcUrl());
            if (!cap.isAuthorConsistentWithProvider()) {
                log.warn("[SkillLoader] Skill '{}' author/providerId 不一致: author={}, providerId={}",
                        name, cap.author(), cap.providerId());
            }
        }

        // LEARNED 源懒加载：丢弃 body，仅留 frontmatter 索引
        String storedContent = lazy ? null : content;
        return new SkillDef(name, description, storedContent, allowedTools, List.of(),
                whenToUse, model, source, path, outputContract, category, tags, cap);
    }

    /**
     * 加载 LEARNED 源技能 — 从 {@link AiosPaths#learnedSkillsDir} 扫描。
     * <p>
     * 借鉴 OpenScience learned skills：RSI 从过往 deterministic-PASS 运行蒸馏的技能
     * （由 {@code LearnedSkillDistiller} 写入）。与 eager 源不同，LEARNED 源懒加载——
     * catalog 只索引 frontmatter，body 首次使用才读（{@link SkillDef#body}）。
     */
    private static void loadLearnedSkills(Map<String, SkillDef> skills) {
        Path learnedDir = Path.of(AiosPaths.learnedSkillsDir());
        if (!Files.exists(learnedDir) || !Files.isDirectory(learnedDir)) return;
        try (Stream<Path> entries = Files.list(learnedDir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                Path skillMd = skillDir.resolve("SKILL.md");
                if (Files.exists(skillMd)) {
                    try {
                        String content = Files.readString(skillMd, StandardCharsets.UTF_8);
                        String name = skillDir.getFileName().toString();
                        if (skills.containsKey(name)) return;  // 早源 shadow
                        SkillDef def = parseSkillMd(name, content, SkillSource.LEARNED, skillMd, true);
                        skills.putIfAbsent(name, def);
                        log.debug("[SkillLoader] Loaded LEARNED skill (lazy): {}", name);
                    } catch (IOException e) {
                        log.warn("[SkillLoader] Failed to read {}: {}", skillMd, e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            log.debug("[SkillLoader] Failed to list learned-skills dir {}: {}", learnedDir, e.getMessage());
        }
    }
}
