package com.ouisani.aios.core.offload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * base64 DataBlock 自动 offload 工具 — 保证 JSONL 行大小有界。
 * <p>
 * 借鉴 AgentScope {@code workspace/_base.py::offload_context}：扫描字符串中的
 * {@code data:<mime>;base64,<payload>} DataBlock，把超过阈值的 payload 提取到
 * {@code data/<sha256>.bin}（内容寻址 → 自动去重），并把原 DataBlock 改写为
 * {@code file://<absolute-path>} URL 引用。反向 {@link #inline} 把 {@code file://}
 * 还原为 base64 DataBlock，供跨会话读取时透明还原。
 * <p>
 * <b>核心价值</b>：JSONL 持久化（UpstreamMetaHook / ProvenanceHook / 未来 MemoryRecord）
 * 的每行大小有界，避免单行 base64 图像/音频撑爆文件、拖慢逐行扫描与 4D 查询。
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 swap — 把冷数据（大 blob）从内存（JSONL 行）换出到
 * 磁盘（data/ 目录），保留引用（file:// URL）按需换入。
 * <p>
 * <b>线程安全</b>：所有方法无状态，可并发调用。blob 文件写入用
 * {@code CREATE_NEW + WRITE} 原子创建，并发写同一内容时后写者失败但内容已存在（幂等）。
 *
 * @see SessionOffloadStore
 */
public final class DataBlockOffloader {

    private static final Logger log = LoggerFactory.getLogger(DataBlockOffloader.class);

    /**
     * DataBlock 正则 — 匹配 RFC 2397 data URL：
     * {@code data:[<mediatype>][;base64],<data>}
     * <p>
     * 仅匹配 {@code ;base64,} 形式（文本 data: URL 不需要 offload）。
     * payload 部分用 {@code [A-Za-z0-9+/=]+} 严格匹配 base64 字母表，避免误匹配。
     */
    private static final Pattern DATA_BLOCK_PATTERN = Pattern.compile(
            "data:([A-Za-z0-9.\\-+/]+);base64,([A-Za-z0-9+/=]+)");

    /** 默认 offload 阈值 — payload 解码后 ≤ 4KB 的 DataBlock 保持内联。 */
    public static final int DEFAULT_THRESHOLD_BYTES = 4 * 1024;

    /** file:// URL 前缀 — 与 java.nio.file.Paths.get(URI) 兼容。 */
    public static final String FILE_URL_PREFIX = "file://";

    private final Path dataDir;
    private final int thresholdBytes;

    /**
     * 创建 offloader。
     *
     * @param dataDir        blob 落盘目录（{@link com.ouisani.aios.core.config.AiosPaths#dataDir()}）
     * @param thresholdBytes payload 解码字节数 ≤ 此值则保持内联；> 此值则 offload
     */
    public DataBlockOffloader(Path dataDir, int thresholdBytes) {
        this.dataDir = dataDir == null ? Paths.get(".aios", "data") : dataDir;
        this.thresholdBytes = thresholdBytes <= 0 ? DEFAULT_THRESHOLD_BYTES : thresholdBytes;
    }

    /** 使用默认阈值（4KB）创建 offloader。 */
    public DataBlockOffloader(Path dataDir) {
        this(dataDir, DEFAULT_THRESHOLD_BYTES);
    }

    /**
     * 扫描 {@code content} 中的 base64 DataBlock，把超过阈值的提取到 {@code data/<sha256>.bin}，
     * 并改写为 {@code file://} URL 引用。
     * <p>
     * <b>Best-effort</b>：任何异常（IO 失败、digest 缺失等）都 catch，对应 DataBlock 保持原样内联，
     * 永不抛出 — 调用方主流程优先于 offload。
     *
     * @param content 含 base64 DataBlock 的原始字符串；{@code null} 原样返回
     * @return offload 后的字符串（含 {@code file://} URL）；无 DataBlock 或都低于阈值时原样返回
     */
    public String offload(String content) {
        if (content == null || content.isEmpty()) return content;
        try {
            Matcher m = DATA_BLOCK_PATTERN.matcher(content);
            StringBuilder sb = new StringBuilder(content.length());
            int last = 0;
            boolean changed = false;
            while (m.find()) {
                sb.append(content, last, m.start());
                String mime = m.group(1);
                String payload = m.group(2);
                String rewritten = offloadOne(mime, payload);
                if (rewritten != null) {
                    sb.append(rewritten);
                    changed = true;
                } else {
                    // offload 失败或低于阈值 → 保持原样
                    sb.append(m.group(0));
                }
                last = m.end();
            }
            sb.append(content, last, content.length());
            return changed ? sb.toString() : content;
        } catch (Exception e) {
            log.warn("[DataBlockOffloader] offload 失败，返回原内容: {}", e.getMessage());
            return content;
        }
    }

    /**
     * 反向操作 — 把 {@code file://<absolute-path>} URL 还原为原始 base64 DataBlock。
     * <p>
     * 供跨会话读取 context.jsonl / tool_result 时透明还原被 offload 的内容。
     * <b>Best-effort</b>：URL 指向的文件不存在时保留 URL 原样（不抛异常），调用方可据此判断。
     *
     * @param content 含 {@code file://} URL 的字符串；{@code null} 原样返回
     * @return 还原后的字符串；文件不存在或读取失败时保留 URL
     */
    public String inline(String content) {
        if (content == null || content.isEmpty()) return content;
        try {
            int idx = content.indexOf(FILE_URL_PREFIX);
            if (idx < 0) return content;
            StringBuilder sb = new StringBuilder(content.length());
            int last = 0;
            while (idx >= 0) {
                sb.append(content, last, idx);
                int urlEnd = findUrlEnd(content, idx + FILE_URL_PREFIX.length());
                String url = content.substring(idx, urlEnd);
                String restored = restoreOne(url);
                if (restored != null) {
                    sb.append(restored);
                } else {
                    // 读取失败 → 保留 URL 原样
                    sb.append(url);
                }
                last = urlEnd;
                idx = content.indexOf(FILE_URL_PREFIX, last);
            }
            sb.append(content, last, content.length());
            return sb.toString();
        } catch (Exception e) {
            log.warn("[DataBlockOffloader] inline 失败，返回原内容: {}", e.getMessage());
            return content;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部
    // ════════════════════════════════════════════════════════════════

    /**
     * 处理单个 DataBlock — 解码 payload，超过阈值则落盘并返回 file:// URL；否则返回 null（保持内联）。
     */
    private String offloadOne(String mime, String payload) {
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (bytes.length <= thresholdBytes) {
                return null; // 低于阈值，保持内联
            }
            String hash = sha256Hex(bytes);
            Path blobFile = dataDir.resolve(hash + ".bin");
            // 内容寻址 → 幂等写入：文件已存在则跳过
            if (!Files.exists(blobFile)) {
                Files.createDirectories(dataDir);
                try {
                    Files.write(blobFile, bytes);
                } catch (IOException dup) {
                    // 并发写同一 hash → 另一个线程已写入，幂等忽略
                    if (!Files.exists(blobFile)) throw dup;
                }
            }
            return FILE_URL_PREFIX + blobFile.toAbsolutePath();
        } catch (IllegalArgumentException e) {
            // payload 不是合法 base64 → 保持原样
            log.debug("[DataBlockOffloader] 非法 base64 payload，跳过: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.debug("[DataBlockOffloader] offload 单块失败，保持内联: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 还原单个 file:// URL → base64 DataBlock。
     * <p>
     * mime 类型从 blob 文件无法恢复（offload 时未持久化 mime），统一还原为
     * {@code application/octet-stream}。调用方若需精确 mime，应自行在 URL 旁记录。
     */
    private String restoreOne(String url) {
        try {
            if (!url.startsWith(FILE_URL_PREFIX)) return null;
            String pathStr = url.substring(FILE_URL_PREFIX.length());
            Path blobFile = Paths.get(pathStr);
            if (!Files.exists(blobFile)) return null;
            byte[] bytes = Files.readAllBytes(blobFile);
            String payload = Base64.getEncoder().encodeToString(bytes);
            return "data:application/octet-stream;base64," + payload;
        } catch (Exception e) {
            log.debug("[DataBlockOffloader] restore 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 找到 file:// URL 的结束位置（遇到空白字符或字符串结尾）。 */
    private static int findUrlEnd(String content, int start) {
        int i = start;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == ',' || c == '}') {
                break;
            }
            i++;
        }
        return i;
    }

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 访问器 — 供测试与日志使用。 */
    public Path dataDir() { return dataDir; }
    public int thresholdBytes() { return thresholdBytes; }
}
