package com.ouisani.aios.operator.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 — 对标 OpenClaw 的 SessionManager。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>JSONL 文件持久化（延迟写入策略）</li>
 *   <li>树形会话结构（id + parentId）</li>
 *   <li>分支（branch）与重置（resetLeaf）</li>
 *   <li>压缩（compaction）摘要注入</li>
 *   <li>构建 LLM 上下文（buildSessionContext）</li>
 * </ul>
 * <p>
 * OS 类比：相当于文件系统的 journal — 每次操作追加写入，
 * 通过树形结构支持分支，通过 compaction 回收空间。
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    // ── 实例字段 ──
    private final String cwd;
    private final Path sessionDir;
    private final boolean shouldPersist;

    private SessionHeader header;
    private Path sessionFile;
    private boolean flushed = false;

    /** 所有条目（含 header） */
    private final List<Object> fileEntries = new ArrayList<>();

    /** id -> SessionEntry 索引 */
    private final Map<String, SessionEntry> byId = new LinkedHashMap<>();

    /** targetId -> label */
    private final Map<String, String> labelsById = new HashMap<>();

    /** 当前叶子节点 ID */
    private String leafId = null;

    /** ID 生成器 — 8 位 hex 短 ID + 碰撞检测 */
    private final Set<String> usedIds = new HashSet<>();

    // ════════════════════════════════════════════════════════════════
    //  静态工厂方法
    // ════════════════════════════════════════════════════════════════

    /** 创建新会话 */
    public static SessionManager create(String cwd, Path sessionDir) {
        String sessionId = generateSessionId();
        SessionHeader header = new SessionHeader(sessionId, cwd);
        SessionManager sm = new SessionManager(cwd, sessionDir, true);
        sm.header = header;
        sm.fileEntries.add(header);
        return sm;
    }

    /** 创建内存会话（不持久化） */
    public static SessionManager inMemory(String cwd) {
        String sessionId = generateSessionId();
        SessionHeader header = new SessionHeader(sessionId, cwd);
        SessionManager sm = new SessionManager(cwd, null, false);
        sm.header = header;
        sm.fileEntries.add(header);
        return sm;
    }

    /** 打开已有会话文件 */
    public static SessionManager open(Path filePath, String cwd) throws IOException {
        SessionManager sm = new SessionManager(cwd, filePath.getParent(), true);
        sm.sessionFile = filePath;
        sm.loadFromJsonl(filePath);
        sm.flushed = true;
        return sm;
    }

    /** 继续最近的会话，无则新建 */
    public static SessionManager continueRecent(String cwd, Path sessionDir) throws IOException {
        if (sessionDir != null && Files.isDirectory(sessionDir)) {
            try (var stream = Files.list(sessionDir)) {
                Optional<Path> latest = stream
                        .filter(p -> p.toString().endsWith(".jsonl"))
                        .max(Comparator.comparingLong(p -> {
                            try { return Files.getLastModifiedTime(p).toMillis(); }
                            catch (IOException e) { return 0L; }
                        }));
                if (latest.isPresent()) {
                    return open(latest.get(), cwd);
                }
            }
        }
        return create(cwd, sessionDir);
    }

    // ── 构造器 ──

    private SessionManager(String cwd, Path sessionDir, boolean shouldPersist) {
        this.cwd = cwd;
        this.sessionDir = sessionDir;
        this.shouldPersist = shouldPersist;
    }

    // ════════════════════════════════════════════════════════════════
    //  追加操作 — 构建会话树
    // ════════════════════════════════════════════════════════════════

    /** 追加消息 */
    public String appendMessage(AgentMessage message) {
        String id = nextId();
        SessionEntry entry = SessionEntry.message(id, leafId, message);
        addEntry(entry);
        return id;
    }

    /** 追加思考级别变更 */
    public String appendThinkingLevelChange(String level) {
        String id = nextId();
        SessionEntry entry = SessionEntry.thinkingLevelChange(id, leafId, level);
        addEntry(entry);
        return id;
    }

    /** 追加模型变更 */
    public String appendModelChange(String provider, String modelId) {
        String id = nextId();
        SessionEntry entry = SessionEntry.modelChange(id, leafId, provider, modelId);
        addEntry(entry);
        return id;
    }

    /** 追加压缩摘要 */
    public String appendCompaction(String summary, String firstKeptEntryId,
                                   long tokensBefore, CompactionDetails details, boolean fromHook) {
        String id = nextId();
        SessionEntry entry = SessionEntry.compaction(id, leafId, summary,
                firstKeptEntryId, tokensBefore, details, fromHook);
        addEntry(entry);
        return id;
    }

    /** 追加自定义条目（不参与 LLM 上下文） */
    public String appendCustomEntry(String customType, Object data) {
        String id = nextId();
        SessionEntry entry = SessionEntry.custom(id, leafId, customType, data);
        addEntry(entry);
        return id;
    }

    /** 追加自定义消息（参与 LLM 上下文） */
    public String appendCustomMessageEntry(String customType, String content,
                                            boolean display, Object data) {
        String id = nextId();
        SessionEntry entry = SessionEntry.customMessage(id, leafId, customType, content, display, data);
        addEntry(entry);
        return id;
    }

    /** 追加标签 */
    public String appendLabelChange(String targetId, String label) {
        String id = nextId();
        SessionEntry entry = SessionEntry.label(id, leafId, targetId, label);
        if (label != null) {
            labelsById.put(targetId, label);
        } else {
            labelsById.remove(targetId);
        }
        addEntry(entry);
        return id;
    }

    /** 追加会话名称 */
    public String appendSessionInfo(String name) {
        String id = nextId();
        SessionEntry entry = SessionEntry.sessionInfo(id, leafId, name);
        addEntry(entry);
        return id;
    }

    // ════════════════════════════════════════════════════════════════
    //  分支操作
    // ════════════════════════════════════════════════════════════════

    /** 从指定 entry 创建新分支 — 仅移动 leafId */
    public void branch(String branchFromId) {
        if (!byId.containsKey(branchFromId) && branchFromId != null) {
            throw new IllegalArgumentException("Entry not found: " + branchFromId);
        }
        this.leafId = branchFromId;
    }

    /** 重置叶子为 null（回到根） */
    public void resetLeaf() {
        this.leafId = null;
    }

    /** 创建分支并附加摘要 */
    public String branchWithSummary(String branchFromId, String summary,
                                     CompactionDetails details, boolean fromHook) {
        this.leafId = branchFromId;
        String id = nextId();
        String fromId = branchFromId != null ? branchFromId : "root";
        SessionEntry entry = SessionEntry.branchSummary(id, leafId, fromId, summary, details, fromHook);
        addEntry(entry);
        return id;
    }

    /** 创建只包含指定路径的新会话文件 */
    public String createBranchedSession(String targetLeafId) throws IOException {
        List<SessionEntry> path = getBranch(targetLeafId);
        if (path.isEmpty()) return null;

        // 过滤掉 LabelEntry
        List<SessionEntry> filtered = path.stream()
                .filter(e -> e.type() != SessionEntry.Type.LABEL)
                .toList();

        // 收集 labels
        Map<String, String> collectedLabels = new LinkedHashMap<>();
        for (SessionEntry e : path) {
            if (e.type() == SessionEntry.Type.LABEL && e.label() != null) {
                collectedLabels.put(e.targetId(), e.label());
            }
        }

        // 创建新会话
        String newSessionId = generateSessionId();
        Path newFile = resolveSessionFilePath(newSessionId);
        SessionHeader newHeader = new SessionHeader(newSessionId, cwd,
                sessionFile != null ? sessionFile.toString() : null);

        // 写入新文件
        List<Object> entries = new ArrayList<>();
        entries.add(newHeader);
        entries.addAll(filtered);
        for (Map.Entry<String, String> lbl : collectedLabels.entrySet()) {
            entries.add(SessionEntry.label(nextId(), null, lbl.getKey(), lbl.getValue()));
        }
        writeJsonlEntries(newFile, entries);

        return newFile.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  查询方法
    // ════════════════════════════════════════════════════════════════

    public String getCwd() { return cwd; }
    public Path getSessionDir() { return sessionDir; }
    public String getSessionId() { return header != null ? header.id() : null; }
    public Path getSessionFile() { return sessionFile; }
    public String getLeafId() { return leafId; }

    public SessionEntry getLeafEntry() {
        return leafId != null ? byId.get(leafId) : null;
    }

    public SessionEntry getEntry(String id) {
        return byId.get(id);
    }

    public List<SessionEntry> getEntries() {
        return new ArrayList<>(byId.values());
    }

    public SessionHeader getHeader() {
        return header;
    }

    public String getLabel(String id) {
        return labelsById.get(id);
    }

    public String getSessionName() {
        String name = null;
        for (SessionEntry e : byId.values()) {
            if (e.type() == SessionEntry.Type.SESSION_INFO && e.sessionName() != null) {
                name = e.sessionName();
            }
        }
        return name;
    }

    /** 获取指定 entry 的直接子节点 */
    public List<SessionEntry> getChildren(String parentId) {
        List<SessionEntry> children = new ArrayList<>();
        for (SessionEntry e : byId.values()) {
            if (Objects.equals(e.parentId(), parentId)) {
                children.add(e);
            }
        }
        return children;
    }

    /** 从指定 entry 遍历到根，返回路径 */
    public List<SessionEntry> getBranch(String fromId) {
        List<SessionEntry> path = new ArrayList<>();
        String current = fromId != null ? fromId : leafId;
        while (current != null) {
            SessionEntry entry = byId.get(current);
            if (entry == null) break;
            path.add(entry);
            current = entry.parentId();
        }
        Collections.reverse(path);
        return path;
    }

    /** 获取从当前 leaf 到 root 的路径 */
    public List<SessionEntry> getCurrentBranch() {
        return getBranch(leafId);
    }

    // ════════════════════════════════════════════════════════════════
    //  buildSessionContext — 构建 LLM 上下文
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建发送给 LLM 的上下文。
     * <p>
     * 算法：
     * 1. 从 leaf 到 root 遍历，收集路径上的所有 entry
     * 2. 扫描状态：最新的 thinkingLevel、model、compaction 位置
     * 3. 如果有 compaction：注入 compactionSummary 消息，只保留 firstKeptEntryId 之后的 entry
     * 4. 将各类型 entry 转换为 AgentMessage
     */
    public SessionContext buildSessionContext() {
        List<SessionEntry> path = getCurrentBranch();
        if (path.isEmpty()) {
            return new SessionContext(new ArrayList<>(), "normal", null, null);
        }

        // 1. 扫描状态
        String thinkingLevel = "normal";
        String provider = null;
        String modelId = null;
        String lastCompactionId = null;
        String firstKeptEntryId = null;
        String compactionSummary = null;
        CompactionDetails compactionDetails = null;

        for (SessionEntry e : path) {
            switch (e.type()) {
                case THINKING_LEVEL_CHANGE -> thinkingLevel = e.thinkingLevel();
                case MODEL_CHANGE -> { provider = e.provider(); modelId = e.modelId(); }
                case MESSAGE -> {
                    if (e.message().role() == AgentMessage.Role.ASSISTANT) {
                        // assistant 消息可能携带 provider/model 信息
                    }
                }
                case COMPACTION -> {
                    lastCompactionId = e.id();
                    firstKeptEntryId = e.firstKeptEntryId();
                    compactionSummary = e.summary();
                    compactionDetails = e.details();
                }
            }
        }

        // 2. 构建消息列表
        List<AgentMessage> messages = new ArrayList<>();

        // 如果有 compaction，注入摘要消息
        if (compactionSummary != null) {
            String fullSummary = compactionSummary;
            if (compactionDetails != null) {
                fullSummary += compactionDetails.toSummaryAppendix();
            }
            messages.add(AgentMessage.compactionSummary(fullSummary));
        }

        // 3. 遍历路径，提取消息
        boolean pastCompaction = (firstKeptEntryId == null); // 如果没有 compaction，全部保留
        for (SessionEntry e : path) {
            // 检查是否过了 compaction 边界
            if (!pastCompaction) {
                if (e.id().equals(firstKeptEntryId)) {
                    pastCompaction = true;
                }
                // compaction 之前的 entry 跳过（已被摘要替代）
                if (!pastCompaction) continue;
            }

            switch (e.type()) {
                case MESSAGE -> messages.add(e.message());
                case CUSTOM_MESSAGE -> {
                    if (e.content() != null) {
                        messages.add(AgentMessage.custom(e.content()));
                    }
                }
                case BRANCH_SUMMARY -> {
                    if (e.summary() != null) {
                        String fullSummary = e.summary();
                        if (e.details() != null) {
                            fullSummary += e.details().toSummaryAppendix();
                        }
                        messages.add(AgentMessage.branchSummary(fullSummary));
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════
        //  OOM Killer: 7 层递进式上下文压缩防线
        //  在构建完消息列表后，检测 Token 用量是否逼近模型极限。
        //  如果超出高水位线，自动逐级压缩，确保 Agent 永不因上下文溢出而崩溃。
        // ══════════════════════════════════════════════════════════
        try {
            com.ouisani.aios.core.llm.LlmProvider oomHelperModel =
                    com.ouisani.aios.core.llm.LlmRouterHolder.getProvider("openai");
            if (oomHelperModel != null) {
                List<AgentMessage> safeMessages = com.ouisani.aios.core.memory.TokenOomKiller.ensureMemoryHealth(
                        messages, oomHelperModel);
                if (safeMessages != messages) {
                    log.info("[SessionManager] OOM Killer applied compression: {} -> {} messages",
                            messages.size(), safeMessages.size());
                    messages = safeMessages;
                }
            }
        } catch (Exception e) {
            log.warn("[SessionManager] OOM Killer check failed (non-fatal): {}", e.getMessage());
        }

        return new SessionContext(messages, thinkingLevel, provider, modelId);
    }

    // ════════════════════════════════════════════════════════════════
    //  持久化
    // ════════════════════════════════════════════════════════════════

    private void addEntry(SessionEntry entry) {
        byId.put(entry.id(), entry);
        fileEntries.add(entry);
        leafId = entry.id();
        persist(entry);
    }

    /**
     * 延迟写入策略：
     * - 首条 assistant 消息到达前，所有 entry 只保存在内存中
     * - 首条 assistant 消息到达时，一次性全量写入
     * - 之后每条新 entry 追加写入
     */
    private void persist(SessionEntry entry) {
        if (!shouldPersist) return;

        // 确保会话文件路径已确定
        if (sessionFile == null) {
            sessionFile = resolveSessionFilePath(header.id());
        }

        // 检查是否有 assistant 消息
        boolean hasAssistant = fileEntries.stream()
                .filter(e -> e instanceof SessionEntry)
                .map(e -> (SessionEntry) e)
                .anyMatch(e -> e.type() == SessionEntry.Type.MESSAGE
                        && e.message() != null
                        && e.message().role() == AgentMessage.Role.ASSISTANT);

        if (!hasAssistant) {
            flushed = false;
            return;
        }

        try {
            if (!flushed) {
                writeJsonlEntries(sessionFile, fileEntries);
                flushed = true;
            } else {
                appendJsonlEntry(sessionFile, entry);
            }
        } catch (IOException e) {
            log.error("[SessionManager] Failed to persist entry: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  JSONL 序列化/反序列化
    // ════════════════════════════════════════════════════════════════

    private void writeJsonlEntries(Path file, List<Object> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Object entry : entries) {
                writer.write(toJson(entry));
                writer.newLine();
            }
        }
    }

    private void appendJsonlEntry(Path file, Object entry) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            writer.write(toJson(entry));
            writer.newLine();
        }
    }

    private void loadFromJsonl(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            Object entry = fromJson(line);
            if (entry instanceof SessionHeader h) {
                this.header = h;
                fileEntries.add(h);
            } else if (entry instanceof SessionEntry e) {
                byId.put(e.id(), e);
                fileEntries.add(e);
                leafId = e.id();
                // 恢复 labels
                if (e.type() == SessionEntry.Type.LABEL && e.label() != null) {
                    labelsById.put(e.targetId(), e.label());
                }
            }
        }
    }

    /** 简化 JSON 序列化 — 无需引入 Jackson/Gson */
    private String toJson(Object obj) {
        if (obj instanceof SessionHeader h) {
            StringBuilder sb = new StringBuilder("{\"type\":\"session\"");
            sb.append(",\"version\":").append(h.version());
            sb.append(",\"id\":\"").append(escape(h.id())).append("\"");
            sb.append(",\"timestamp\":\"").append(escape(h.timestamp())).append("\"");
            sb.append(",\"cwd\":\"").append(escape(h.cwd())).append("\"");
            if (h.parentSession() != null) {
                sb.append(",\"parentSession\":\"").append(escape(h.parentSession())).append("\"");
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof SessionEntry e) {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"type\":\"").append(entryTypeToString(e.type())).append("\"");
            sb.append(",\"id\":\"").append(escape(e.id())).append("\"");
            sb.append(",\"parentId\":").append(e.parentId() != null ? "\"" + escape(e.parentId()) + "\"" : "null");
            sb.append(",\"timestamp\":\"").append(escape(e.timestamp())).append("\"");

            switch (e.type()) {
                case MESSAGE -> {
                    if (e.message() != null) {
                        sb.append(",\"message\":{");
                        sb.append("\"role\":\"").append(e.message().role().name().toLowerCase()).append("\"");
                        if (e.message().text() != null) {
                            sb.append(",\"text\":\"").append(escape(e.message().text())).append("\"");
                        }
                        sb.append("}");
                    }
                }
                case THINKING_LEVEL_CHANGE -> sb.append(",\"thinkingLevel\":\"").append(escape(e.thinkingLevel())).append("\"");
                case MODEL_CHANGE -> {
                    sb.append(",\"provider\":\"").append(escape(e.provider())).append("\"");
                    sb.append(",\"modelId\":\"").append(escape(e.modelId())).append("\"");
                }
                case COMPACTION -> {
                    sb.append(",\"summary\":\"").append(escape(e.summary())).append("\"");
                    sb.append(",\"firstKeptEntryId\":\"").append(escape(e.firstKeptEntryId())).append("\"");
                    sb.append(",\"tokensBefore\":").append(e.tokensBefore());
                    if (e.details() != null) {
                        sb.append(",\"details\":{");
                        sb.append("\"readFiles\":").append(stringListToJson(e.details().readFiles()));
                        sb.append(",\"modifiedFiles\":").append(stringListToJson(e.details().modifiedFiles()));
                        sb.append("}");
                    }
                    sb.append(",\"fromHook\":").append(e.fromHook());
                }
                case BRANCH_SUMMARY -> {
                    sb.append(",\"fromId\":\"").append(escape(e.fromId())).append("\"");
                    sb.append(",\"summary\":\"").append(escape(e.summary())).append("\"");
                    if (e.details() != null) {
                        sb.append(",\"details\":{");
                        sb.append("\"readFiles\":").append(stringListToJson(e.details().readFiles()));
                        sb.append(",\"modifiedFiles\":").append(stringListToJson(e.details().modifiedFiles()));
                        sb.append("}");
                    }
                    sb.append(",\"fromHook\":").append(e.fromHook());
                }
                case CUSTOM -> {
                    sb.append(",\"customType\":\"").append(escape(e.customType())).append("\"");
                }
                case CUSTOM_MESSAGE -> {
                    sb.append(",\"customType\":\"").append(escape(e.customType())).append("\"");
                    if (e.content() != null) {
                        sb.append(",\"content\":\"").append(escape(e.content())).append("\"");
                    }
                    sb.append(",\"display\":").append(e.display());
                }
                case LABEL -> {
                    sb.append(",\"targetId\":\"").append(escape(e.targetId())).append("\"");
                    sb.append(",\"label\":").append(e.label() != null ? "\"" + escape(e.label()) + "\"" : "null");
                }
                case SESSION_INFO -> {
                    if (e.sessionName() != null) {
                        sb.append(",\"name\":\"").append(escape(e.sessionName())).append("\"");
                    }
                }
            }
            sb.append("}");
            return sb.toString();
        }
        return "{}";
    }

    /** 简化 JSON 反序列化 */
    private Object fromJson(String json) {
        String typeStr = extractJsonString(json, "type");
        if ("session".equals(typeStr)) {
            int version = 3;
            String v = extractJsonString(json, "version");
            if (v != null) try { version = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
            return new SessionHeader(
                    "session",
                    version,
                    extractJsonString(json, "id"),
                    extractJsonString(json, "timestamp"),
                    extractJsonString(json, "cwd"),
                    extractJsonString(json, "parentSession")
            );
        }

        // SessionEntry
        String id = extractJsonString(json, "id");
        String parentId = extractJsonString(json, "parentId");
        SessionEntry.Type entryType = stringToEntryType(typeStr);

        return switch (entryType) {
            case MESSAGE -> {
                String roleStr = extractJsonString(json, "message.role");
                String text = extractJsonString(json, "message.text");
                AgentMessage.Role role = roleStr != null
                        ? AgentMessage.Role.valueOf(roleStr.toUpperCase()) : AgentMessage.Role.USER;
                AgentMessage msg = role == AgentMessage.Role.USER ? AgentMessage.user(text)
                        : AgentMessage.assistant(text);
                yield SessionEntry.message(id, parentId, msg);
            }
            case THINKING_LEVEL_CHANGE -> SessionEntry.thinkingLevelChange(id, parentId,
                    extractJsonString(json, "thinkingLevel"));
            case MODEL_CHANGE -> SessionEntry.modelChange(id, parentId,
                    extractJsonString(json, "provider"), extractJsonString(json, "modelId"));
            case COMPACTION -> SessionEntry.compaction(id, parentId,
                    extractJsonString(json, "summary"),
                    extractJsonString(json, "firstKeptEntryId"),
                    extractJsonNumber(json, "tokensBefore"),
                    extractCompactionDetails(json),
                    extractJsonBoolean(json, "fromHook"));
            case BRANCH_SUMMARY -> SessionEntry.branchSummary(id, parentId,
                    extractJsonString(json, "fromId"),
                    extractJsonString(json, "summary"),
                    extractCompactionDetails(json),
                    extractJsonBoolean(json, "fromHook"));
            case CUSTOM -> SessionEntry.custom(id, parentId,
                    extractJsonString(json, "customType"), null);
            case CUSTOM_MESSAGE -> SessionEntry.customMessage(id, parentId,
                    extractJsonString(json, "customType"),
                    extractJsonString(json, "content"),
                    extractJsonBoolean(json, "display"), null);
            case LABEL -> SessionEntry.label(id, parentId,
                    extractJsonString(json, "targetId"),
                    extractJsonString(json, "label"));
            case SESSION_INFO -> SessionEntry.sessionInfo(id, parentId,
                    extractJsonString(json, "name"));
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    private String nextId() {
        String id;
        do {
            id = Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        } while (usedIds.contains(id));
        usedIds.add(id);
        return id;
    }

    private Path resolveSessionFilePath(String sessionId) {
        if (sessionDir == null) return null;
        String ts = Instant.now().toString().replace(":", "-").replace(".", "-");
        return sessionDir.resolve(ts + "_" + sessionId + ".jsonl");
    }

    private static String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String extractJsonString(String json, String key) {
        // 支持嵌套 key: "message.role" → 查找 "message":{..."role":"xxx"...}
        if (key.contains(".")) {
            String[] parts = key.split("\\.", 2);
            String parentKey = parts[0];
            String childKey = parts[1];
            // 找到 parent object
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + parentKey + "\"\\s*:\\s*\\{");
            java.util.regex.Matcher m = p.matcher(json);
            if (m.find()) {
                int start = m.end() - 1;
                int depth = 0;
                int end = start;
                for (int i = start; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
                }
                return extractJsonString(json.substring(start, end), childKey);
            }
            return null;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1).replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    private static long extractJsonNumber(String json, String key) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static boolean extractJsonBoolean(String json, String key) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    private static CompactionDetails extractCompactionDetails(String json) {
        List<String> readFiles = extractJsonStringArray(json, "details.readFiles");
        List<String> modifiedFiles = extractJsonStringArray(json, "details.modifiedFiles");
        if (readFiles.isEmpty() && modifiedFiles.isEmpty()) return null;
        return new CompactionDetails(readFiles, modifiedFiles);
    }

    private static List<String> extractJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key.replace(".", "\\.") + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            String arr = m.group(1);
            java.util.regex.Pattern sp = java.util.regex.Pattern.compile("\"([^\"]*)\"");
            java.util.regex.Matcher sm = sp.matcher(arr);
            while (sm.find()) result.add(sm.group(1));
        }
        return result;
    }

    private static String stringListToJson(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(list.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String entryTypeToString(SessionEntry.Type type) {
        return switch (type) {
            case MESSAGE -> "message";
            case THINKING_LEVEL_CHANGE -> "thinking_level_change";
            case MODEL_CHANGE -> "model_change";
            case COMPACTION -> "compaction";
            case BRANCH_SUMMARY -> "branch_summary";
            case CUSTOM -> "custom";
            case CUSTOM_MESSAGE -> "custom_message";
            case LABEL -> "label";
            case SESSION_INFO -> "session_info";
        };
    }

    private static SessionEntry.Type stringToEntryType(String s) {
        if (s == null) return SessionEntry.Type.CUSTOM;
        return switch (s) {
            case "message" -> SessionEntry.Type.MESSAGE;
            case "thinking_level_change" -> SessionEntry.Type.THINKING_LEVEL_CHANGE;
            case "model_change" -> SessionEntry.Type.MODEL_CHANGE;
            case "compaction" -> SessionEntry.Type.COMPACTION;
            case "branch_summary" -> SessionEntry.Type.BRANCH_SUMMARY;
            case "custom" -> SessionEntry.Type.CUSTOM;
            case "custom_message" -> SessionEntry.Type.CUSTOM_MESSAGE;
            case "label" -> SessionEntry.Type.LABEL;
            case "session_info" -> SessionEntry.Type.SESSION_INFO;
            default -> SessionEntry.Type.CUSTOM;
        };
    }
}
