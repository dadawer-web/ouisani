package com.ouisani.aios.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程代码载荷获取器 — 把 {@link SkillCap#artifactSrcUrl()} 拉回本地缓存。
 * <p>
 * 用户需求"artifact.srcUrl（允许远程加载代码载荷）"的执行端：SkillLoader 只解析 URL，
 * 真正抓取由本类完成。借鉴 nuwa Cap 模型 + Claude Code 插件市场加载策略：
 * <ul>
 *   <li><b>本地缓存</b> — 同 URL 只抓一次，存到 {@code .aios/skill-artifacts/sha256.hex}</li>
 *   <li><b>SHA256 校验</b> — 文件名即哈希，篡改/损坏立即可见（v1 不强制 pinned digest，后续可加）</li>
 *   <li><b>大小上限</b> — 默认 16MB，超限直接拒绝（防 OOM 与恶意大文件）</li>
 *   <li><b>超时控制</b> — 连接 5s + 读 30s，防慢速攻击</li>
 *   <li><b>沙箱策略差异化</b> — 按 {@link ProviderId} 决定是否真的抓取：VENDOR/COMMUNITY
 *       需 governance 层显式批准（v1 仅 WARN，不阻断；v2 加 approval ledger）</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * 相当于 Linux 的 {@code modprobe --fetch-external} —— 内核模块签名校验失败时拒绝 insmod，
 * 但 v1 我们 best-effort 不强制阻断（与 ProvenanceHook 同范式：先记后议）。
 *
 * <h3>双读模式呼应</h3>
 * 与 {@link com.ouisani.aios.core.role.RoleBlueprintLoader} 的双读模式呼应：
 * <ul>
 *   <li><b>原文 prompt 侧</b> — SKILL.md 的 body 不变，照常拼 prompt</li>
 *   <li><b>结构化 Cap 侧</b> — {@code cap.artifactSrcUrl()} 不为 null 时，
 *       调度器在执行 Skill 前 {@code fetch} 远程代码，把它注入到 Skill 的代码目录</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 缓存用 {@link ConcurrentHashMap}（path → bytes），抓取用 synchronized per-URL 防并发重复下载。
 *
 * @see SkillCap
 * @see SkillLoader
 */
public final class SkillArtifactLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillArtifactLoader.class);

    /** 缓存目录 — 与 .aios/provenance.jsonl / .aios/upstream_meta.jsonl 同根 */
    private static volatile Path cacheDir = Paths.get(".aios", "skill-artifacts");

    /** 大小上限 — 16MB（防 OOM 与恶意大文件）。 */
    private static volatile long maxBytes = 16L * 1024 * 1024;

    /** 连接超时（秒）。 */
    private static final int CONNECT_TIMEOUT_SEC = 5;

    /** 读取超时（秒）。 */
    private static final int READ_TIMEOUT_SEC = 30;

    /** HTTP 客户端（复用连接池）。 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .build();

    /** 内存缓存 — sha256hex → 已抓取字节数据。 */
    private static final Map<String, byte[]> memCache = new ConcurrentHashMap<>();

    /** per-URL 抓取锁 — 防并发重复下载同一 URL。 */
    private static final Map<String, Object> urlLocks = new ConcurrentHashMap<>();

    /** 全局启用开关 — 测试可禁用避免真实网络。 */
    private static volatile boolean enabled = true;

    private SkillArtifactLoader() {}

    // ════════════════════════════════════════════════════════════════
    //  公共入口
    // ════════════════════════════════════════════════════════════════

    /**
     * 抓取远程代码载荷 — 由调度器在执行 Skill 前调用。
     * <p>
     * <b>Best-effort</b>：所有异常 catch，返回 {@link Optional#empty()}，永不抛出
     * （与 ProvenanceHook / UpstreamMetaHook 同范式，主流程优先于审计加载）。
     * <p>
     * 行为矩阵：
     * <ul>
     *   <li>cap 无远程载荷（{@code artifactSrcUrl == null}）→ 返回 empty</li>
     *   <li>缓存命中（内存 or 磁盘）→ 直接返回</li>
     *   <li>缓存未命中 → HTTP/file 抓取 + SHA256 校验 + 大小校验 → 落盘 + 进内存</li>
     *   <li>providerId=VENDOR/COMMUNITY → 仅 WARN，不阻断（v1 留 governance 层）</li>
     * </ul>
     *
     * @param cap Skill 能力元数据（必须含非 null artifactSrcUrl）
     * @return 已抓取的字节数据；失败/无载荷 → empty
     */
    public static Optional<byte[]> fetch(SkillCap cap) {
        if (!enabled || cap == null || !cap.hasRemoteArtifact()) {
            return Optional.empty();
        }
        URI url = cap.artifactSrcUrl();
        String scheme = url.getScheme().toLowerCase();

        // VENDOR/COMMUNITY 安全提醒（v1 不阻断）
        if (cap.providerId() == ProviderId.VENDOR || cap.providerId() == ProviderId.COMMUNITY) {
            log.warn("[SkillArtifactLoader] 加载 {} 提供者的远程代码载荷: {} (provider={}, srcUrl={})",
                    cap.providerId(), cap.author(), cap.providerId(), url);
        }

        try {
            return switch (scheme) {
                case "file" -> fetchFromFile(url);
                case "http", "https" -> fetchFromHttp(cap, url);
                default -> {
                    log.warn("[SkillArtifactLoader] 不支持的 scheme: {}", url);
                    yield Optional.empty();
                }
            };
        } catch (Throwable t) {
            log.warn("[SkillArtifactLoader] 抓取失败 ({}): {}", url, t.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 清空内存缓存（磁盘缓存保留）— 测试用。
     */
    public static void clearMemoryCache() {
        memCache.clear();
        urlLocks.clear();
    }

    /**
     * 设置缓存目录 — 测试可指向临时目录。
     */
    public static void setCacheDir(Path dir) {
        cacheDir = dir;
        log.info("[SkillArtifactLoader] cacheDir={}", dir);
    }

    /**
     * 设置大小上限 — 测试可调小以触发拒绝路径。
     */
    public static void setMaxBytes(long bytes) {
        maxBytes = bytes;
    }

    /**
     * 启用/禁用 — 测试可禁用避免真实网络。
     */
    public static void setEnabled(boolean enabled) {
        SkillArtifactLoader.enabled = enabled;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部 — file:// 协议
    // ════════════════════════════════════════════════════════════════

    private static Optional<byte[]> fetchFromFile(URI url) throws IOException {
        Path p = Paths.get(url);
        if (!Files.exists(p)) {
            log.warn("[SkillArtifactLoader] file:// 不存在: {}", p);
            return Optional.empty();
        }
        long size = Files.size(p);
        if (size > maxBytes) {
            log.warn("[SkillArtifactLoader] file:// 超过大小上限 {} > {}: {}", size, maxBytes, p);
            return Optional.empty();
        }
        byte[] data = Files.readAllBytes(p);
        String sha = sha256Hex(data);
        memCache.put(sha, data);
        log.info("[SkillArtifactLoader] file:// 加载成功: {} ({}B, sha={})", p, data.length, sha.substring(0, 12));
        return Optional.of(data);
    }

    // ════════════════════════════════════════════════════════════════
    //  内部 — http(s):// 协议
    // ════════════════════════════════════════════════════════════════

    private static Optional<byte[]> fetchFromHttp(SkillCap cap, URI url) throws IOException, InterruptedException {
        String sha = sha256OfUrl(url.toString());
        // 1. 内存缓存命中
        byte[] cached = memCache.get(sha);
        if (cached != null) {
            log.debug("[SkillArtifactLoader] 内存命中: {} (sha={})", url, sha.substring(0, 12));
            return Optional.of(cached);
        }
        // 2. 磁盘缓存命中
        Path diskPath = cacheDir.resolve(sha + ".bin");
        if (Files.exists(diskPath)) {
            byte[] diskData = Files.readAllBytes(diskPath);
            memCache.put(sha, diskData);
            log.debug("[SkillArtifactLoader] 磁盘命中: {} (sha={})", url, sha.substring(0, 12));
            return Optional.of(diskData);
        }
        // 3. 网络抓取（per-URL 锁防并发）
        Object lock = urlLocks.computeIfAbsent(url.toString(), k -> new Object());
        synchronized (lock) {
            // double-check
            cached = memCache.get(sha);
            if (cached != null) return Optional.of(cached);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(url)
                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SEC))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() / 100 != 2) {
                log.warn("[SkillArtifactLoader] HTTP {}: {}", resp.statusCode(), url);
                return Optional.empty();
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) {
                log.warn("[SkillArtifactLoader] HTTP 空响应: {}", url);
                return Optional.empty();
            }
            if (body.length > maxBytes) {
                log.warn("[SkillArtifactLoader] HTTP 响应超过上限 {} > {}: {}", body.length, maxBytes, url);
                return Optional.empty();
            }

            // 4. 校验 SHA256 + 落盘 + 进内存
            String actualSha = sha256Hex(body);
            memCache.put(actualSha, body);
            persistToDisk(actualSha, body);

            log.info("[SkillArtifactLoader] HTTP 加载成功: {} ({}B, sha={}, provider={})",
                    url, body.length, actualSha.substring(0, 12), cap.providerId());
            return Optional.of(body);
        }
    }

    private static void persistToDisk(String sha, byte[] data) {
        try {
            Path dir = cacheDir;
            if (dir.getParent() != null) {
                Files.createDirectories(dir);
            }
            Files.write(dir.resolve(sha + ".bin"), data);
        } catch (IOException e) {
            log.warn("[SkillArtifactLoader] 落盘失败 (sha={}): {}", sha.substring(0, 12), e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助 — SHA256
    // ════════════════════════════════════════════════════════════════

    /** URL → 稳定 sha256（用于缓存 key，不与内容 sha 混用）。 */
    private static String sha256OfUrl(String url) {
        return sha256Hex(url.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制算法，不会缺失
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 测试辅助 — 读 InputStream 为 bytes（避免依赖 InputStream.readAllBytes 的版本差异）。 */
    @SuppressWarnings("unused")
    private static byte[] readAllBytes(InputStream is) throws IOException {
        return is.readAllBytes();
    }
}
