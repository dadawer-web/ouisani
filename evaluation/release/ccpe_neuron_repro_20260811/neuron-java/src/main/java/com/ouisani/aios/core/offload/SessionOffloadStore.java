package com.ouisani.aios.core.offload;

import com.ouisani.aios.core.config.AiosPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话级 offload 存储 — 跨会话检索的轻量方案。
 * <p>
 * 对标 AgentScope {@code workspace/_base.py::offload_context} (L413-L470) 与
 * {@code offload_tool_result} (L472-L526)，提供两个核心方法：
 * <ul>
 *   <li>{@link #offloadContext} — 追加消息到 {@code sessions/<sid>/context.jsonl}，
 *       base64 DataBlock 自动 offload 到 {@code data/<sha256>.bin} 并改写为
 *       {@code file://} URL，保证 JSONL 行大小有界。</li>
 *   <li>{@link #offloadToolResult} — 把大工具结果写到
 *       {@code sessions/<sid>/tool_result-<id>.txt}，重名自动加 {@code (1)(2)} 后缀。</li>
 * </ul>
 * <p>
 * <b>与现有持久化的分工</b>：
 * <ul>
 *   <li>{@code UpstreamMetaHook} / {@code ProvenanceHook} — 固定字段 JSONL，行大小天然有界，
 *       不需要本类；</li>
 *   <li>{@code HistoryCompressor} — 纯内存压缩，未来若要保留被截断内容可调用本类 offload；</li>
 *   <li>{@code MemoryRecord} — 未来若存图像/音频，{@code store()} 路径可用本类 offload base64。</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：{@code context.jsonl} 用 {@link FileChannel} 原子 append（与 UpstreamMetaHook 同范式）；
 * {@code tool_result-<id>.txt} 用 {@code CREATE_NEW} 原子创建，并发写同一 id 时后者触发碰撞后缀。
 * <p>
 * <b>Best-effort</b>：所有 IO 异常 catch，返回 {@code null} 表示失败，永不抛出 — 调用方主流程优先。
 *
 * @see DataBlockOffloader
 */
public final class SessionOffloadStore {

    private static final Logger log = LoggerFactory.getLogger(SessionOffloadStore.class);

    /** context.jsonl 文件名 — 会话级上下文追加日志。 */
    public static final String CONTEXT_FILE = "context.jsonl";
    /** tool result 文件名前缀。 */
    public static final String TOOL_RESULT_PREFIX = "tool_result-";
    /** tool result 文件名后缀。 */
    public static final String TOOL_RESULT_SUFFIX = ".txt";

    private final Path sessionsRoot;
    private final DataBlockOffloader offloader;

    /**
     * 创建会话 offload store。
     *
     * @param sessionsRoot  会话根目录（{@link AiosPaths#sessionsDir()}）
     * @param dataDir       DataBlock offload 目录（{@link AiosPaths#dataDir()}）
     */
    public SessionOffloadStore(Path sessionsRoot, Path dataDir) {
        this.sessionsRoot = sessionsRoot == null ? Paths.get(AiosPaths.sessionsDir()) : sessionsRoot;
        this.offloader = new DataBlockOffloader(dataDir == null ? Paths.get(AiosPaths.dataDir()) : dataDir);
    }

    /** 使用 {@link AiosPaths} 默认路径创建。 */
    public SessionOffloadStore() {
        this(Paths.get(AiosPaths.sessionsDir()), Paths.get(AiosPaths.dataDir()));
    }

    // ════════════════════════════════════════════════════════════════
    //  offload_context
    // ════════════════════════════════════════════════════════════════

    /**
     * 追加消息到会话上下文日志 — base64 DataBlock 自动 offload 保证行有界。
     * <p>
     * 借鉴 AgentScope {@code offload_context(session_id, msgs)}：
     * <ol>
     *   <li>对每条 message，调用 {@link DataBlockOffloader#offload} 提取 base64 DataBlock；</li>
     *   <li>序列化为 JSONL 行（{@code {"role":"...","content":"...","ts":...}}）；</li>
     *   <li>{@link FileChannel} 原子 append 到 {@code sessions/<sid>/context.jsonl}。</li>
     * </ol>
     *
     * @param sessionId 会话 ID（用作子目录名，{@code null} 或空则拒绝）
     * @param role      消息角色（{@code "user"} / {@code "assistant"} / {@code "system"}）
     * @param content   消息正文（可能含 base64 DataBlock）
     * @return 写入的 JSONL 行数（0 表示失败）；行内的 base64 已被替换为 {@code file://} URL
     */
    public int offloadContext(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[SessionOffloadStore] offloadContext 拒绝空 sessionId");
            return 0;
        }
        if (role == null) role = "unknown";
        if (content == null) content = "";
        try {
            // 1. offload base64 DataBlock → file:// URL
            String offloaded = offloader.offload(content);
            // 2. 序列化为 JSONL 行
            String line = toJsonLine(role, offloaded, System.currentTimeMillis()) + "\n";
            // 3. 原子 append
            Path file = sessionDir(sessionId).resolve(CONTEXT_FILE);
            Files.createDirectories(file.getParent());
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                ch.write(java.nio.ByteBuffer.wrap(bytes));
            }
            log.debug("[SessionOffloadStore] offloadContext → {} ({} bytes)", file, bytes.length);
            return 1;
        } catch (Exception e) {
            log.warn("[SessionOffloadStore] offloadContext 失败 session={}: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 读取会话上下文日志（原始 JSONL 行，含 {@code file://} URL，未 inline）。
     * <p>
     * 调用方如需还原 base64，对每行调用 {@link DataBlockOffloader#inline}。
     *
     * @param sessionId 会话 ID
     * @return JSONL 行列表；会话不存在返回空列表
     */
    public List<String> readContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        try {
            Path file = sessionDir(sessionId).resolve(CONTEXT_FILE);
            if (!Files.exists(file)) return List.of();
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[SessionOffloadStore] readContext 失败 session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  offload_tool_result
    // ════════════════════════════════════════════════════════════════

    /**
     * 把大工具结果写到独立文件 — 重名自动加 {@code (1)(2)} 后缀。
     * <p>
     * 借鉴 AgentScope {@code offload_tool_result(session_id, tool_result)}：
     * 写到 {@code sessions/<sid>/tool_result-<id>.txt}，若已存在则尝试
     * {@code tool_result-<id>(1).txt}、{@code tool_result-<id>(2).txt}……
     * 直到找到不存在的文件名（最多 1000 次尝试避免无限循环）。
     *
     * @param sessionId  会话 ID
     * @param toolCallId 工具调用 ID（用作文件名一部分，{@code null} 时用时间戳）
     * @param result     工具结果文本（可能含 base64 DataBlock，会自动 offload）
     * @return 写入的文件绝对路径；失败返回 {@code null}
     */
    public Path offloadToolResult(String sessionId, String toolCallId, String result) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[SessionOffloadStore] offloadToolResult 拒绝空 sessionId");
            return null;
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            toolCallId = "ts_" + System.currentTimeMillis();
        }
        if (result == null) result = "";
        try {
            // 1. offload base64 DataBlock → file:// URL
            String offloaded = offloader.offload(result);
            byte[] bytes = offloaded.getBytes(StandardCharsets.UTF_8);

            // 2. 找到不存在的文件名（碰撞加 (1)(2) 后缀）
            Path sessionDir = sessionDir(sessionId);
            Files.createDirectories(sessionDir);
            Path target = resolveNonCollidingPath(sessionDir, toolCallId);
            if (target == null) {
                log.warn("[SessionOffloadStore] offloadToolResult 碰撞次数超限 session={} id={}", sessionId, toolCallId);
                return null;
            }

            // 3. 原子创建（CREATE_NEW 防并发竞争）
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            log.debug("[SessionOffloadStore] offloadToolResult → {} ({} bytes)", target, bytes.length);
            return target;
        } catch (Exception e) {
            log.warn("[SessionOffloadStore] offloadToolResult 失败 session={} id={}: {}",
                    sessionId, toolCallId, e.getMessage());
            return null;
        }
    }

    /**
     * 读取工具结果文件（原始内容，含 {@code file://} URL，未 inline）。
     *
     * @param path {@link #offloadToolResult} 返回的路径
     * @return 文件文本内容；文件不存在返回 {@code null}
     */
    public String readToolResult(Path path) {
        if (path == null) return null;
        try {
            if (!Files.exists(path)) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[SessionOffloadStore] readToolResult 失败 path={}: {}", path, e.getMessage());
            return null;
        }
    }

    /** 便捷重载 — 通过 sessionId + toolCallId 读取。 */
    public String readToolResult(String sessionId, String toolCallId) {
        if (sessionId == null || toolCallId == null) return null;
        Path sessionDir = sessionDir(sessionId);
        // 尝试无后缀 + (1)(2)... 后缀，返回第一个存在的
        Path candidate = sessionDir.resolve(TOOL_RESULT_PREFIX + sanitizeFileName(toolCallId) + TOOL_RESULT_SUFFIX);
        if (Files.exists(candidate)) return readToolResult(candidate);
        for (int i = 1; i <= 1000; i++) {
            candidate = sessionDir.resolve(TOOL_RESULT_PREFIX + sanitizeFileName(toolCallId)
                    + "(" + i + ")" + TOOL_RESULT_SUFFIX);
            if (Files.exists(candidate)) return readToolResult(candidate);
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部
    // ════════════════════════════════════════════════════════════════

    private Path sessionDir(String sessionId) {
        return sessionsRoot.resolve(sanitizeFileName(sessionId));
    }

    /**
     * 找到不存在的文件名 — 碰撞时加 {@code (1)(2)} 后缀。
     * <p>
     * 对标 AgentScope {@code offload_tool_result} 的重名处理逻辑。
     */
    private Path resolveNonCollidingPath(Path sessionDir, String toolCallId) {
        String base = TOOL_RESULT_PREFIX + sanitizeFileName(toolCallId) + TOOL_RESULT_SUFFIX;
        Path candidate = sessionDir.resolve(base);
        if (!Files.exists(candidate)) return candidate;
        for (int i = 1; i <= 1000; i++) {
            candidate = sessionDir.resolve(TOOL_RESULT_PREFIX + sanitizeFileName(toolCallId)
                    + "(" + i + ")" + TOOL_RESULT_SUFFIX);
            if (!Files.exists(candidate)) return candidate;
        }
        return null;
    }

    /**
     * 文件名净化 — 去除路径分隔符与危险字符，防止路径逃逸。
     */
    private static String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        // 去除路径分隔符、..、控制字符
        String safe = name.replaceAll("[/\\\\]", "_").replaceAll("\\.\\.", "_");
        safe = safe.replaceAll("[\\x00-\\x1f]", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    /**
     * 手工序列化 JSONL 行 — 避免引入 Gson 反射依赖（与 ProvenanceQuery 同范式）。
     * <p>
     * 格式：{@code {"role":"...","content":"...","ts":<millis>}}
     */
    private static String toJsonLine(String role, String content, long ts) {
        StringBuilder sb = new StringBuilder(content.length() + 64);
        sb.append("{\"role\":\"").append(escapeJson(role)).append("\"");
        sb.append(",\"content\":\"").append(escapeJson(content)).append("\"");
        sb.append(",\"ts\":").append(ts).append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  访问器
    // ════════════════════════════════════════════════════════════════

    public Path sessionsRoot() { return sessionsRoot; }
    public DataBlockOffloader offloader() { return offloader; }

    /**
     * 列出某会话的所有 tool_result 文件路径 — 供跨会话检索使用。
     *
     * @param sessionId 会话 ID
     * @return 文件路径列表（按文件名排序）；会话不存在返回空列表
     */
    public List<Path> listToolResults(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        Path dir = sessionDir(sessionId);
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith(TOOL_RESULT_PREFIX)
                            && p.getFileName().toString().endsWith(TOOL_RESULT_SUFFIX))
                  .sorted()
                  .forEach(files::add);
        } catch (IOException e) {
            log.warn("[SessionOffloadStore] listToolResults 失败 session={}: {}", sessionId, e.getMessage());
        }
        return files;
    }
}
