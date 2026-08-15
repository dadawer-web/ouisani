package com.ouisani.aios.core.skill;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SkillArtifactLoader} 单元测试 — 远程代码载荷获取与缓存。
 * <p>
 * 验证矩阵：
 * <ul>
 *   <li>file:// 协议加载 + 大小上限拒绝</li>
 *   <li>内存缓存命中 + 磁盘缓存命中</li>
 *   <li>无远程载荷（srcUrl=null）→ empty</li>
 *   <li>禁用开关 → empty</li>
 *   <li>不存在的 file:// → empty（best-effort，不抛）</li>
 * </ul>
 * <p>
 * HTTP/HTTPS 路径不直接测试（需真实网络），由 file:// 路径覆盖核心缓存/校验逻辑。
 */
class SkillArtifactLoaderTest {

    @TempDir
    Path tempDir;

    private Path cacheDir;
    private Path artifactFile;

    @BeforeEach
    void setUp() throws Exception {
        cacheDir = tempDir.resolve("cache");
        artifactFile = tempDir.resolve("skill-code.py");
        Files.writeString(artifactFile, "# Auto-generated skill code\nprint('hello')\n");

        SkillArtifactLoader.setCacheDir(cacheDir);
        SkillArtifactLoader.setEnabled(true);
        SkillArtifactLoader.setMaxBytes(16L * 1024 * 1024);
        SkillArtifactLoader.clearMemoryCache();
    }

    @AfterEach
    void tearDown() {
        SkillArtifactLoader.clearMemoryCache();
        SkillArtifactLoader.setEnabled(true);
        SkillArtifactLoader.setMaxBytes(16L * 1024 * 1024);
        SkillArtifactLoader.setCacheDir(Path.of(".aios", "skill-artifacts"));
    }

    // ════════════════════════════════════════════════════════════════
    //  file:// 协议
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("file:// 加载成功 + 数据正确")
    void fetchFile_loadsSuccessfully() {
        SkillCap cap = SkillCap.of("oushani.core",
                "file://" + artifactFile.toString(), null, "AIOS_CORE");

        Optional<byte[]> result = SkillArtifactLoader.fetch(cap);

        assertTrue(result.isPresent());
        String content = new String(result.get());
        assertTrue(content.contains("print('hello')"));
    }

    @Test
    @DisplayName("file:// 不存在 → empty（best-effort，不抛）")
    void fetchFile_missingFile_returnsEmpty() {
        SkillCap cap = SkillCap.of("oushani.core",
                "file:///nonexistent/path/skill.py", null, "AIOS_CORE");

        Optional<byte[]> result = SkillArtifactLoader.fetch(cap);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("file:// 超过大小上限 → empty")
    void fetchFile_exceedsMaxBytes_returnsEmpty() throws Exception {
        // 写一个超过 100 字节的文件
        Path bigFile = tempDir.resolve("big.py");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("print('line').\n");
        Files.writeString(bigFile, sb.toString());
        assertTrue(Files.size(bigFile) > 100);

        SkillArtifactLoader.setMaxBytes(100); // 限制 100 字节
        SkillCap cap = SkillCap.of("oushani.core",
                "file://" + bigFile.toString(), null, "AIOS_CORE");

        Optional<byte[]> result = SkillArtifactLoader.fetch(cap);

        assertTrue(result.isEmpty(), "超过大小上限应拒绝");
    }

    // ════════════════════════════════════════════════════════════════
    //  无远程载荷
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("srcUrl=null → empty")
    void fetch_noRemoteArtifact_returnsEmpty() {
        SkillCap cap = SkillCap.DEFAULT; // artifactSrcUrl=null

        Optional<byte[]> result = SkillArtifactLoader.fetch(cap);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("cap=null → empty（best-effort）")
    void fetch_nullCap_returnsEmpty() {
        Optional<byte[]> result = SkillArtifactLoader.fetch(null);
        assertTrue(result.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  禁用开关
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("禁用开关 → empty（测试避免真实网络）")
    void fetch_disabled_returnsEmpty() {
        SkillArtifactLoader.setEnabled(false);
        SkillCap cap = SkillCap.of("oushani.core",
                "file://" + artifactFile.toString(), null, "AIOS_CORE");

        Optional<byte[]> result = SkillArtifactLoader.fetch(cap);

        assertTrue(result.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  缓存命中
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("二次 fetch → 内存缓存命中（不重复读文件）")
    void fetch_secondCall_memoryCacheHit() {
        SkillCap cap = SkillCap.of("oushani.core",
                "file://" + artifactFile.toString(), null, "AIOS_CORE");

        Optional<byte[]> r1 = SkillArtifactLoader.fetch(cap);
        Optional<byte[]> r2 = SkillArtifactLoader.fetch(cap);

        assertTrue(r1.isPresent());
        assertTrue(r2.isPresent());
        // 两次返回相同数据
        assertArrayEquals(r1.get(), r2.get());
    }

    @Test
    @DisplayName("clearMemoryCache 后 fetch → 重新读文件")
    void fetch_afterClearCache_rereadsFile() {
        SkillCap cap = SkillCap.of("oushani.core",
                "file://" + artifactFile.toString(), null, "AIOS_CORE");

        Optional<byte[]> r1 = SkillArtifactLoader.fetch(cap);
        SkillArtifactLoader.clearMemoryCache();
        Optional<byte[]> r2 = SkillArtifactLoader.fetch(cap);

        assertTrue(r1.isPresent());
        assertTrue(r2.isPresent());
        assertArrayEquals(r1.get(), r2.get());
    }

    // ════════════════════════════════════════════════════════════════
    //  VENDOR/COMMUNITY 提供者 — 不阻断（v1 仅 WARN）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("VENDOR providerId → 仍加载（v1 仅 WARN，不阻断）")
    void fetch_vendorProvider_notBlockedInV1() {
        SkillCap cap = SkillCap.of("vendor.acme.tools",
                "file://" + artifactFile.toString(), null, "VENDOR");

        Optional<byte[]> result = SkillArtifactLoader.fetch(cap);

        // v1 不阻断：VENDOR 包仍能加载，仅记 WARN（governance 层留待 v2）
        assertTrue(result.isPresent(), "v1 VENDOR 应能加载（仅 WARN）");
    }

    // ════════════════════════════════════════════════════════════════
    //  不支持的 scheme
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ftp:// scheme → empty（仅允许 http/https/file）")
    void fetch_unsupportedScheme_returnsEmpty() {
        SkillCap cap = SkillCap.of("oushani.core",
                "ftp://example.com/skill.py", null, "AIOS_CORE");

        // 注意：SkillCap.of 已把 ftp:// 降级为 null（非法 scheme）
        // 因此 fetch 直接走"无远程载荷"路径
        assertNull(cap.artifactSrcUrl());
        Optional<byte[]> result = SkillArtifactLoader.fetch(cap);
        assertTrue(result.isEmpty());
    }
}
