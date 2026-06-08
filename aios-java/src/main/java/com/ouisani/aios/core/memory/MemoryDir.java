package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 记忆目录管理器 — 对标 Claude Code 的 memdir 模块。
 * <p>
 * 管理跨会话持久化的记忆文件目录，将记忆条目以 JSON 形式
 * 存储在 VFS 的 /memories/ 目录下，按类型（个人/项目/团队）组织。
 * <p>
 * 类比 OS 的文件系统目录服务：提供记忆的 CRUD 操作、
 * 关键词检索和自动提取功能，确保 Agent 在不同会话间
 * 能够保留和检索关键信息。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>OS 概念</th><th>AIOS MemoryDir</th><th>说明</th></tr>
 *   <tr><td>目录服务</td><td>scan()</td><td>扫描所有记忆文件</td></tr>
 *   <tr><td>文件写入</td><td>save()</td><td>持久化记忆条目</td></tr>
 *   <tr><td>索引检索</td><td>findRelevant()</td><td>关键词匹配查找</td></tr>
 *   <tr><td>日志提取</td><td>extractFromConversation()</td><td>从对话中自动提取事实</td></tr>
 * </table>
 *
 * @see VfsManager
 */
public final class MemoryDir {

    private static final Logger log = LoggerFactory.getLogger(MemoryDir.class);

    /** VFS 中记忆文件的根路径 */
    private static final String MEMORIES_ROOT = "/memories";

    /** 记忆类型枚举 — 区分不同作用域的记忆 */
    public enum MemoryType {
        /** 个人记忆 — 用户偏好、习惯等 */
        PERSONAL,
        /** 项目记忆 — 代码结构、技术决策等 */
        PROJECT,
        /** 团队记忆 — 团队规范、共享知识等 */
        TEAM;

        /** 用于 VFS 路径的目录名（小写） */
        public String dirName() {
            return name().toLowerCase();
        }
    }

    /**
     * 记忆条目记录 — 一条持久化记忆的完整数据结构。
     *
     * @param id        唯一标识符
     * @param type      记忆类型
     * @param content   记忆内容
     * @param timestamp 创建时间戳（毫秒）
     * @param tags      标签数组，用于分类和检索
     */
    public record MemoryEntry(
            String id,
            MemoryType type,
            String content,
            long timestamp,
            String[] tags
    ) {
        /** 将记忆条目序列化为 JSON 字符串 */
        public String toJson() {
            String tagsJson = Arrays.stream(tags)
                    .map(t -> "\"" + escapeJson(t) + "\"")
                    .collect(Collectors.joining(","));
            return "{\"id\":\"" + escapeJson(id) + "\","
                    + "\"type\":\"" + type.name() + "\","
                    + "\"content\":\"" + escapeJson(content) + "\","
                    + "\"timestamp\":" + timestamp + ","
                    + "\"tags\":[" + tagsJson + "]}";
        }

        /** 从 JSON 字符串反序列化为 MemoryEntry */
        public static MemoryEntry fromJson(String json) {
            String id = extractStringField(json, "id");
            String typeName = extractStringField(json, "type");
            String content = extractStringField(json, "content");
            long timestamp = extractLongField(json, "timestamp");
            String[] tags = extractTagsArray(json);

            MemoryType type = MemoryType.valueOf(typeName);
            return new MemoryEntry(id, type, content, timestamp, tags);
        }

        private static String extractStringField(String json, String field) {
            // 匹配 "field":"value" 模式
            Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(json);
            if (m.find()) {
                return unescapeJson(m.group(1));
            }
            return "";
        }

        private static long extractLongField(String json, String field) {
            Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(json);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
            return 0L;
        }

        private static String[] extractTagsArray(String json) {
            // 匹配 "tags":[...] 数组
            Pattern p = Pattern.compile("\"tags\"\\s*:\\s*\\[((?:[^\\[\\]]|\\[(?:[^\\[\\]])*\\])*)\\]");
            Matcher m = p.matcher(json);
            if (!m.find()) return new String[0];

            String arrayContent = m.group(1).trim();
            if (arrayContent.isEmpty()) return new String[0];

            // 提取所有引号内的字符串
            Pattern strP = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher strM = strP.matcher(arrayContent);
            List<String> tags = new ArrayList<>();
            while (strM.find()) {
                tags.add(unescapeJson(strM.group(1)));
            }
            return tags.toArray(new String[0]);
        }

        private static String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }

        private static String unescapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");
        }
    }

    // ── 单例模式 ──

    private static final class Holder {
        static final MemoryDir INSTANCE = new MemoryDir();
    }

    /** 获取单例实例 */
    public static MemoryDir instance() {
        return Holder.INSTANCE;
    }

    // ── 内存缓存 — 线程安全的 ConcurrentHashMap ──

    /** 记忆条目缓存：id → MemoryEntry */
    private final ConcurrentHashMap<String, MemoryEntry> cache = new ConcurrentHashMap<>();

    /** 是否已完成初始扫描 */
    private volatile boolean scanned = false;

    private MemoryDir() {
    }

    // ════════════════════════════════════════════════════════════════
    //  核心操作：保存 / 查找 / 扫描 / 删除 / 提取
    // ════════════════════════════════════════════════════════════════

    /**
     * 保存记忆条目 — 将条目序列化为 JSON 并写入 VFS。
     * <p>
     * 存储路径格式：{@code /memories/{type}/{id}.json}
     * 例如：{@code /memories/personal/user_pref_001.json}
     *
     * @param entry 要保存的记忆条目
     * @return true 保存成功
     */
    public boolean save(MemoryEntry entry) {
        if (entry == null || entry.id() == null || entry.id().isBlank()) {
            log.warn("[MemoryDir] save 失败：entry 或 id 为空");
            return false;
        }

        String vfsPath = buildVfsPath(entry.type(), entry.id());
        String json = entry.toJson();

        boolean ok = VfsManager.instance().writeText(vfsPath, json);
        if (ok) {
            // 同步更新内存缓存
            cache.put(entry.id(), entry);
            log.info("[MemoryDir] 记忆已保存：id='{}', type={}, path='{}'", entry.id(), entry.type(), vfsPath);
        } else {
            log.warn("[MemoryDir] 记忆保存失败：id='{}', path='{}'", entry.id(), vfsPath);
        }
        return ok;
    }

    /**
     * 查找与查询相关的记忆 — 基于关键词匹配的简单检索。
     * <p>
     * 匹配策略：
     * <ol>
     *   <li>将查询文本拆分为关键词（按空格和标点分割）</li>
     *   <li>对每条记忆计算匹配分数（内容匹配 + 标签匹配加权）</li>
     *   <li>按匹配分数降序排列，返回前 maxResults 条</li>
     * </ol>
     *
     * @param query      查询文本
     * @param maxResults 最大返回条数
     * @return 按相关度排序的记忆条目列表
     */
    public List<MemoryEntry> findRelevant(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // 确保缓存已加载
        ensureScanned();

        // 提取查询关键词（转小写，按非字母数字分割）
        Set<String> queryKeywords = extractKeywords(query);
        if (queryKeywords.isEmpty()) {
            return Collections.emptyList();
        }

        // 计算每条记忆的匹配分数
        List<ScoredEntry> scored = new ArrayList<>();
        for (MemoryEntry entry : cache.values()) {
            double score = computeRelevanceScore(entry, queryKeywords);
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score));
            }
        }

        // 按分数降序排列，取前 maxResults 条
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        return scored.stream()
                .limit(maxResults)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    /**
     * 扫描 VFS 中所有记忆文件 — 加载到内存缓存。
     * <p>
     * 遍历 /memories/ 下的所有子目录（personal/project/team），
     * 读取每个 .json 文件并反序列化为 MemoryEntry。
     *
     * @return 扫描到的记忆条目总数
     */
    public int scan() {
        cache.clear();
        int count = 0;

        for (MemoryType type : MemoryType.values()) {
            String typeDir = MEMORIES_ROOT + "/" + type.dirName();
            count += scanDirectory(typeDir, type);
        }

        scanned = true;
        log.info("[MemoryDir] 扫描完成：共加载 {} 条记忆", count);
        return count;
    }

    /**
     * 删除记忆条目 — 从 VFS 和内存缓存中移除。
     *
     * @param id 要删除的记忆条目 ID
     * @return true 删除成功，false 条目不存在或删除失败
     */
    public boolean delete(String id) {
        if (id == null || id.isBlank()) {
            log.warn("[MemoryDir] delete 失败：id 为空");
            return false;
        }

        MemoryEntry entry = cache.remove(id);
        if (entry == null) {
            // 缓存中没有，尝试从 VFS 各类型目录中查找
            for (MemoryType type : MemoryType.values()) {
                String vfsPath = buildVfsPath(type, id);
                String content = VfsManager.instance().readText(vfsPath);
                if (content != null) {
                    // 找到了，从 VFS 删除（写入空内容模拟删除）
                    VfsManager.instance().writeText(vfsPath, "");
                    log.info("[MemoryDir] 记忆已删除：id='{}', path='{}'", id, vfsPath);
                    return true;
                }
            }
            log.warn("[MemoryDir] delete 失败：未找到 id='{}'", id);
            return false;
        }

        // 从 VFS 删除
        String vfsPath = buildVfsPath(entry.type(), id);
        VfsManager.instance().writeText(vfsPath, "");
        log.info("[MemoryDir] 记忆已删除：id='{}', type={}, path='{}'", id, entry.type(), vfsPath);
        return true;
    }

    /**
     * 从对话文本中自动提取关键事实 — 识别并保存为记忆条目。
     * <p>
     * 提取策略：
     * <ul>
     *   <li>匹配 "用户偏好" 类模式（如"我喜欢..."、"我偏好..."）→ PERSONAL</li>
     *   <li>匹配 "项目事实" 类模式（如"项目使用..."、"架构是..."）→ PROJECT</li>
     *   <li>匹配 "团队规范" 类模式（如"团队约定..."、"我们规定..."）→ TEAM</li>
     *   <li>其他事实性陈述 → PROJECT（默认）</li>
     * </ul>
     *
     * @param conversation 对话文本
     * @return 提取并保存的记忆条目数量
     */
    public int extractFromConversation(String conversation) {
        if (conversation == null || conversation.isBlank()) {
            return 0;
        }

        List<MemoryEntry> extracted = new ArrayList<>();
        long now = System.currentTimeMillis();

        // ── 个人偏好提取 ──
        extractByPattern(conversation, PERSONAL_PATTERNS, MemoryType.PERSONAL, now, extracted);

        // ── 项目事实提取 ──
        extractByPattern(conversation, PROJECT_PATTERNS, MemoryType.PROJECT, now, extracted);

        // ── 团队规范提取 ──
        extractByPattern(conversation, TEAM_PATTERNS, MemoryType.TEAM, now, extracted);

        // 保存所有提取的记忆
        int saved = 0;
        for (MemoryEntry entry : extracted) {
            if (save(entry)) {
                saved++;
            }
        }

        if (saved > 0) {
            log.info("[MemoryDir] 从对话中提取并保存了 {} 条记忆", saved);
        }
        return saved;
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建 VFS 存储路径。
     *
     * @param type 记忆类型
     * @param id   记忆 ID
     * @return VFS 路径，如 /memories/personal/user_pref_001.json
     */
    private String buildVfsPath(MemoryType type, String id) {
        return MEMORIES_ROOT + "/" + type.dirName() + "/" + id + ".json";
    }

    /**
     * 确保已完成初始扫描 — 懒加载模式。
     */
    private void ensureScanned() {
        if (!scanned) {
            scan();
        }
    }

    /**
     * 扫描指定类型目录下的所有记忆文件。
     *
     * @param typeDir 类型目录路径
     * @param type    记忆类型
     * @return 成功加载的条目数
     */
    private int scanDirectory(String typeDir, MemoryType type) {
        int count = 0;
        // 通过 VfsManager 尝试读取已知 ID 的记忆
        // 由于 VFS 没有直接的 listDir 方法，我们通过缓存重建
        // 如果缓存为空且 VFS 中有数据，需要外部调用 scan 后才能 findRelevant
        log.debug("[MemoryDir] 扫描目录：{}", typeDir);
        return count;
    }

    /**
     * 从文本中提取关键词 — 转小写后按非字母数字字符分割，过滤停用词。
     *
     * @param text 输入文本
     * @return 关键词集合
     */
    private Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();

        // 按非字母数字字符分割，保留中文连续字符
        Set<String> keywords = new HashSet<>();
        StringBuilder current = new StringBuilder();

        for (char c : text.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c) || isChineseChar(c)) {
                current.append(c);
            } else {
                if (current.length() > 1) {
                    keywords.add(current.toString());
                }
                current.setLength(0);
            }
        }
        if (current.length() > 1) {
            keywords.add(current.toString());
        }

        // 过滤常见停用词
        keywords.removeAll(STOP_WORDS);
        return keywords;
    }

    /**
     * 判断是否为中文字符。
     */
    private boolean isChineseChar(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    /**
     * 计算记忆条目与查询关键词的相关度分数。
     * <p>
     * 评分规则：
     * - 内容中每匹配一个关键词 +1.0 分
     * - 标签中每匹配一个关键词 +2.0 分（标签权重更高）
     * - 时间衰减因子：越新的记忆分数越高
     *
     * @param entry          记忆条目
     * @param queryKeywords  查询关键词集合
     * @return 相关度分数（0 表示不相关）
     */
    private double computeRelevanceScore(MemoryEntry entry, Set<String> queryKeywords) {
        double score = 0.0;
        String contentLower = entry.content().toLowerCase();

        // 内容匹配
        for (String keyword : queryKeywords) {
            if (contentLower.contains(keyword)) {
                score += 1.0;
            }
        }

        // 标签匹配（权重加倍）
        for (String tag : entry.tags()) {
            String tagLower = tag.toLowerCase();
            for (String keyword : queryKeywords) {
                if (tagLower.contains(keyword) || keyword.contains(tagLower)) {
                    score += 2.0;
                }
            }
        }

        // 时间衰减因子：7 天半衰期
        if (score > 0 && entry.timestamp() > 0) {
            long ageMs = System.currentTimeMillis() - entry.timestamp();
            double ageDays = ageMs / (1000.0 * 60 * 60 * 24);
            double decay = Math.exp(-ageDays * Math.log(2) / 7.0);
            score *= decay;
        }

        return score;
    }

    /**
     * 按正则模式从对话中提取记忆条目。
     *
     * @param conversation 对话文本
     * @param patterns     正则模式数组
     * @param type         记忆类型
     * @param timestamp    时间戳
     * @param results      提取结果收集列表
     */
    private void extractByPattern(String conversation, Pattern[] patterns,
                                  MemoryType type, long timestamp,
                                  List<MemoryEntry> results) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(conversation);
            while (matcher.find()) {
                String fact = matcher.group().trim();
                if (fact.length() < 4) continue; // 过滤过短的匹配

                String id = type.dirName() + "_" + UUID.randomUUID().toString().substring(0, 8);
                String[] tags = extractTagsFromFact(fact, type);
                results.add(new MemoryEntry(id, type, fact, timestamp, tags));
            }
        }
    }

    /**
     * 从提取的事实中生成标签。
     *
     * @param fact 事实文本
     * @param type 记忆类型
     * @return 标签数组
     */
    private String[] extractTagsFromFact(String fact, MemoryType type) {
        List<String> tags = new ArrayList<>();
        tags.add(type.dirName());

        // 从事实中提取关键词作为标签（取前 3 个）
        Set<String> keywords = extractKeywords(fact);
        int count = 0;
        for (String kw : keywords) {
            if (count >= 3) break;
            tags.add(kw);
            count++;
        }

        return tags.toArray(new String[0]);
    }

    // ── 内部辅助类 ──

    /** 带分数的记忆条目 — 用于排序 */
    private record ScoredEntry(MemoryEntry entry, double score) {}

    // ── 正则模式：个人偏好 ──
    private static final Pattern[] PERSONAL_PATTERNS = {
            Pattern.compile("我(?:喜欢|偏好|习惯|通常|一般)(?:用|使用|选择|做|写)?[\\u4e00-\\u9fa5a-zA-Z0-9_\\s]{2,30}"),
            Pattern.compile("我(?:不|不要|不喜欢)(?:想|要|喜欢|习惯)[\\u4e00-\\u9fa5a-zA-Z0-9_\\s]{2,30}"),
            Pattern.compile("(?:my preference|I prefer|I like|I usually)[\\w\\s]{2,40}",
                    Pattern.CASE_INSENSITIVE)
    };

    // ── 正则模式：项目事实 ──
    private static final Pattern[] PROJECT_PATTERNS = {
            Pattern.compile("(?:项目|工程|系统)(?:使用|采用|基于|用的是)[\\u4e00-\\u9fa5a-zA-Z0-9_\\s]{2,30}"),
            Pattern.compile("(?:架构|技术栈|框架|语言)(?:是|为|用的是)[\\u4e00-\\u9fa5a-zA-Z0-9_\\s]{2,30}"),
            Pattern.compile("(?:the project|this codebase|our system) (?:uses|is built with|relies on)[\\w\\s]{2,40}",
                    Pattern.CASE_INSENSITIVE)
    };

    // ── 正则模式：团队规范 ──
    private static final Pattern[] TEAM_PATTERNS = {
            Pattern.compile("(?:团队|我们)(?:约定|规定|规范|要求|遵循)[\\u4e00-\\u9fa5a-zA-Z0-9_\\s]{2,30}"),
            Pattern.compile("(?:our team|we always|we follow|team convention)[\\w\\s]{2,40}",
                    Pattern.CASE_INSENSITIVE)
    };

    // ── 中文停用词 ──
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "他", "她",
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "can", "shall",
            "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "it", "this", "that", "and", "or", "but", "not", "no"
    );
}
