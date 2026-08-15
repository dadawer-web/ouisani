package com.ouisani.aios.core.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SkillCap} + {@link ProviderId} 单元测试 — Cap 模型规范化与默认值降级。
 * <p>
 * 核心验证矩阵：
 * <ul>
 *   <li>frontmatter 字段缺失 → DEFAULT 哨兵（零回归）</li>
 *   <li>supportedInputs v1 强制 ["text"]，其他值规范化</li>
 *   <li>providerId 解析大小写不敏感 + 未知值降级 AIOS_CORE</li>
 *   <li>artifactSrcUrl 仅允许 http/https/file scheme，非法 URL → null</li>
 *   <li>author/providerId 一致性校验（VENDOR 需 vendor. 前缀）</li>
 * </ul>
 */
class SkillCapTest {

    // ════════════════════════════════════════════════════════════════
    //  ProviderId.fromString
    // ════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ProviderId.fromString")
    class ProviderIdParsing {

        @Test
        @DisplayName("合法值 — 大小写不敏感 + kebab→snake 转换")
        void parsesValidValues_caseInsensitive() {
            assertEquals(ProviderId.AIOS_CORE, ProviderId.fromString("AIOS_CORE"));
            assertEquals(ProviderId.AIOS_CORE, ProviderId.fromString("aios-core"));
            assertEquals(ProviderId.AIOS_CORE, ProviderId.fromString("aios_core"));
            assertEquals(ProviderId.COMMUNITY, ProviderId.fromString("community"));
            assertEquals(ProviderId.VENDOR, ProviderId.fromString("Vendor"));
            assertEquals(ProviderId.USER, ProviderId.fromString("USER"));
        }

        @Test
        @DisplayName("null/空/未知值 → 降级 AIOS_CORE（best-effort，零回归）")
        void defaultsToAiosCoreForUnknown() {
            assertEquals(ProviderId.AIOS_CORE, ProviderId.fromString(null));
            assertEquals(ProviderId.AIOS_CORE, ProviderId.fromString(""));
            assertEquals(ProviderId.AIOS_CORE, ProviderId.fromString("   "));
            assertEquals(ProviderId.AIOS_CORE, ProviderId.fromString("unknown-provider"));
            assertEquals(ProviderId.AIOS_CORE, ProviderId.fromString("FOO_BAR"));
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  SkillCap 紧凑构造器 — 规范化
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DEFAULT 哨兵 — author=unknown / srcUrl=null / inputs=[text] / provider=AIOS_CORE")
    void defaultSentinel_hasAllDefaults() {
        SkillCap d = SkillCap.DEFAULT;
        assertEquals("unknown", d.author());
        assertNull(d.artifactSrcUrl());
        assertEquals(List.of("text"), d.supportedInputs());
        assertEquals(ProviderId.AIOS_CORE, d.providerId());
        assertFalse(d.hasRemoteArtifact());
    }

    @Test
    @DisplayName("author null/blank → 'unknown'（best-effort）")
    void author_nullOrBlank_defaultsToUnknown() {
        assertEquals("unknown", new SkillCap(null, null, null, null).author());
        assertEquals("unknown", new SkillCap("", null, null, null).author());
        assertEquals("unknown", new SkillCap("   ", null, null, null).author());
        assertEquals("oushani.core", new SkillCap("oushani.core", null, null, null).author());
    }

    @Test
    @DisplayName("supportedInputs v1 强制 ['text'] — 其他值规范化")
    void supportedInputs_v1ForcesTextOnly() {
        // null → ["text"]
        assertEquals(List.of("text"), new SkillCap("a", null, null, null).supportedInputs());
        // 空 → ["text"]
        assertEquals(List.of("text"), new SkillCap("a", null, List.of(), null).supportedInputs());
        // 非 ["text"] → 规范化为 ["text"]（v1 只接受 text 模态）
        assertEquals(List.of("text"),
                new SkillCap("a", null, List.of("image"), null).supportedInputs());
        assertEquals(List.of("text"),
                new SkillCap("a", null, List.of("text", "image"), null).supportedInputs());
        // 正确值保留
        assertEquals(List.of("text"),
                new SkillCap("a", null, List.of("text"), null).supportedInputs());
    }

    @Test
    @DisplayName("providerId null → AIOS_CORE")
    void providerId_nullDefaultsToAiosCore() {
        assertEquals(ProviderId.AIOS_CORE, new SkillCap("a", null, null, null).providerId());
    }

    @Test
    @DisplayName("supportedInputs 返回不可变 List")
    void supportedInputs_isImmutable() {
        SkillCap c = new SkillCap("a", null, List.of("text"), null);
        assertThrows(UnsupportedOperationException.class,
                () -> c.supportedInputs().add("image"));
    }

    // ════════════════════════════════════════════════════════════════
    //  SkillCap.of 工厂 — URL 解析 best-effort
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("of() — 合法 http/https/file URL 解析")
    void of_parsesValidUrls() {
        SkillCap http = SkillCap.of("a", "http://example.com/skill.py", null, null);
        assertEquals(URI.create("http://example.com/skill.py"), http.artifactSrcUrl());
        assertTrue(http.hasRemoteArtifact());

        SkillCap https = SkillCap.of("a", "https://raw.githubusercontent.com/x/y/main/skill.py", null, null);
        assertNotNull(https.artifactSrcUrl());

        SkillCap file = SkillCap.of("a", "file:///tmp/skill.py", null, null);
        assertEquals(URI.create("file:///tmp/skill.py"), file.artifactSrcUrl());
    }

    @Test
    @DisplayName("of() — 非法/空 URL → null（best-effort）")
    void of_invalidUrlBecomesNull() {
        assertNull(SkillCap.of("a", null, null, null).artifactSrcUrl());
        assertNull(SkillCap.of("a", "", null, null).artifactSrcUrl());
        assertNull(SkillCap.of("a", "   ", null, null).artifactSrcUrl());
        assertNull(SkillCap.of("a", "not-a-url", null, null).artifactSrcUrl());
    }

    @Test
    @DisplayName("of() — 非 http/https/file scheme 拒绝（防 ftp/gopher 等攻击面）")
    void of_rejectsUnsupportedSchemes() {
        assertNull(SkillCap.of("a", "ftp://example.com/x", null, null).artifactSrcUrl());
        assertNull(SkillCap.of("a", "gopher://example.com", null, null).artifactSrcUrl());
        assertNull(SkillCap.of("a", "javascript:alert(1)", null, null).artifactSrcUrl());
    }

    @Test
    @DisplayName("of() — providerId 字符串解析")
    void of_parsesProviderId() {
        assertEquals(ProviderId.COMMUNITY, SkillCap.of("a", null, null, "community").providerId());
        assertEquals(ProviderId.VENDOR, SkillCap.of("vendor.x", null, null, "VENDOR").providerId());
        assertEquals(ProviderId.AIOS_CORE, SkillCap.of("a", null, null, "aios-core").providerId());
        // 未知值降级
        assertEquals(ProviderId.AIOS_CORE, SkillCap.of("a", null, null, "unknown").providerId());
    }

    // ════════════════════════════════════════════════════════════════
    //  hasRemoteArtifact
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("hasRemoteArtifact — srcUrl null → false")
    void hasRemoteArtifact_falseWhenNoUrl() {
        assertFalse(SkillCap.DEFAULT.hasRemoteArtifact());
        assertFalse(new SkillCap("a", null, null, null).hasRemoteArtifact());
    }

    @Test
    @DisplayName("hasRemoteArtifact — srcUrl 非 null → true")
    void hasRemoteArtifact_trueWhenUrlPresent() {
        assertTrue(SkillCap.of("a", "http://x.com/s.py", null, null).hasRemoteArtifact());
        assertTrue(SkillCap.of("a", "file:///tmp/x.py", null, null).hasRemoteArtifact());
    }

    // ════════════════════════════════════════════════════════════════
    //  isAuthorConsistentWithProvider
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("VENDOR provider 需 'vendor.' 前缀")
    void vendorProvider_requiresVendorPrefix() {
        // 一致
        assertTrue(new SkillCap("vendor.acme.tools", null, null, ProviderId.VENDOR)
                .isAuthorConsistentWithProvider());
        // 不一致
        assertFalse(new SkillCap("acme.tools", null, null, ProviderId.VENDOR)
                .isAuthorConsistentWithProvider());
        // unknown author 视为一致（best-effort，加载阶段不阻断）
        assertTrue(new SkillCap("unknown", null, null, ProviderId.VENDOR)
                .isAuthorConsistentWithProvider());
    }

    @Test
    @DisplayName("AIOS_CORE provider 需 'oushani.' 前缀或 unknown")
    void aiosCoreProvider_requiresOushaniPrefix() {
        assertTrue(new SkillCap("oushani.core", null, null, ProviderId.AIOS_CORE)
                .isAuthorConsistentWithProvider());
        assertTrue(new SkillCap("unknown", null, null, ProviderId.AIOS_CORE)
                .isAuthorConsistentWithProvider());
        assertFalse(new SkillCap("vendor.x", null, null, ProviderId.AIOS_CORE)
                .isAuthorConsistentWithProvider());
    }

    @Test
    @DisplayName("COMMUNITY / USER 无前缀约束")
    void communityAndUserProviders_noPrefixConstraint() {
        assertTrue(new SkillCap("alice", null, null, ProviderId.USER)
                .isAuthorConsistentWithProvider());
        assertTrue(new SkillCap("open-source-fork", null, null, ProviderId.COMMUNITY)
                .isAuthorConsistentWithProvider());
    }

    // ════════════════════════════════════════════════════════════════
    //  equals / hashCode
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("equals/hashCode — 全字段相同才相等")
    void equalsHashCode_fullFieldComparison() {
        SkillCap a = SkillCap.of("oushani.core", "http://x.com/s.py", List.of("text"), "AIOS_CORE");
        SkillCap b = SkillCap.of("oushani.core", "http://x.com/s.py", List.of("text"), "AIOS_CORE");
        SkillCap c = SkillCap.of("vendor.x", "http://x.com/s.py", List.of("text"), "VENDOR");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
