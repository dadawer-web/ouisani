package com.ouisani.aios.core.skill;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SkillLoader#parseSkillMd} Cap 字段解析集成测试。
 * <p>
 * 通过 {@link SkillLoader#loadAll} 间接验证（parseSkillMd 是 private）：
 * <ul>
 *   <li>缺新字段的存量 SKILL.md → cap=DEFAULT（零回归）</li>
 *   <li>含 Cap 字段的 SKILL.md → 4 字段正确解析</li>
 *   <li>支持 dotted/snake/kebab 多种 frontmatter 键写法</li>
 *   <li>真实 classpath 中的 integrity-auditor SKILL.md 已升级为 Cap 模型</li>
 * </ul>
 */
class SkillCapParsingTest {

    @TempDir
    Path tempDir;

    private Path skillsDir;

    @BeforeEach
    void setUp() throws Exception {
        skillsDir = tempDir.resolve(".aios").resolve("skills");
        Files.createDirectories(skillsDir);
    }

    @AfterEach
    void tearDown() {
        // SkillLoader.loadAll 每次调用都 clear + 重建 skillCache，无需显式重置
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助
    // ════════════════════════════════════════════════════════════════

    private void writeSkill(String name, String frontmatter, String body) throws Exception {
        Path skillDir = skillsDir.resolve(name);
        Files.createDirectories(skillDir);
        String content = "---\n" + frontmatter + "\n---\n\n" + body;
        Files.writeString(skillDir.resolve("SKILL.md"), content);
    }

    // ════════════════════════════════════════════════════════════════
    //  零回归：缺 Cap 字段的存量 SKILL.md
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("存量 SKILL.md（无 Cap 字段）→ cap=DEFAULT 零回归")
    void legacySkillWithoutCapFields_defaultsToDefault() throws Exception {
        writeSkill("legacy-skill",
                """
                name: legacy-skill
                description: a legacy skill without Cap fields
                allowed-tools: file_read, grep
                """,
                "# Legacy Skill\n\nJust a body.");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(tempDir.toString());
        SkillLoader.SkillDef skill = skills.get("legacy-skill");

        assertNotNull(skill);
        // 关键：cap 降级为 DEFAULT，调用方完全无感知
        assertEquals(SkillCap.DEFAULT, skill.cap());
        assertEquals("unknown", skill.cap().author());
        assertNull(skill.cap().artifactSrcUrl());
        assertEquals(java.util.List.of("text"), skill.cap().supportedInputs());
        assertEquals(ProviderId.AIOS_CORE, skill.cap().providerId());
    }

    // ════════════════════════════════════════════════════════════════
    //  完整 Cap 字段解析
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("完整 Cap 字段 frontmatter → 4 字段正确解析")
    void fullCapFields_allParsed() throws Exception {
        writeSkill("cap-skill",
                """
                name: cap-skill
                description: skill with full Cap fields
                author: oushani.core.research
                artifact.srcUrl: https://example.com/skill.py
                supported-inputs: text
                provider-id: AIOS_CORE
                """,
                "# Cap Skill");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(tempDir.toString());
        SkillLoader.SkillDef skill = skills.get("cap-skill");

        assertNotNull(skill);
        SkillCap cap = skill.cap();
        assertEquals("oushani.core.research", cap.author());
        assertEquals(java.net.URI.create("https://example.com/skill.py"), cap.artifactSrcUrl());
        assertEquals(java.util.List.of("text"), cap.supportedInputs());
        assertEquals(ProviderId.AIOS_CORE, cap.providerId());
        assertTrue(cap.hasRemoteArtifact());
        assertTrue(cap.isAuthorConsistentWithProvider());
    }

    @Test
    @DisplayName("artifact.srcUrl=null → 无远程载荷")
    void srcUrlNull_noRemoteArtifact() throws Exception {
        writeSkill("no-artifact",
                """
                name: no-artifact
                description: skill with null srcUrl
                author: oushani.core
                artifact.srcUrl: null
                provider-id: AIOS_CORE
                """,
                "# No Artifact");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(tempDir.toString());
        SkillLoader.SkillDef skill = skills.get("no-artifact");

        assertNotNull(skill);
        // "null" 字符串 → 解析为 URI 失败 → 降级 null
        assertNull(skill.cap().artifactSrcUrl());
        assertFalse(skill.cap().hasRemoteArtifact());
    }

    // ════════════════════════════════════════════════════════════════
    //  frontmatter 键多写法兼容
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("snake_case 写法兼容（artifact_src_url / supported_inputs / provider_id）")
    void snakeCaseKeys_accepted() throws Exception {
        writeSkill("snake-skill",
                """
                name: snake-skill
                description: snake_case frontmatter
                author: vendor.acme.tools
                artifact_src_url: https://example.com/x.py
                supported_inputs: text
                provider_id: VENDOR
                """,
                "# Snake Skill");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(tempDir.toString());
        SkillLoader.SkillDef skill = skills.get("snake-skill");

        assertNotNull(skill);
        SkillCap cap = skill.cap();
        assertEquals("vendor.acme.tools", cap.author());
        assertEquals(java.net.URI.create("https://example.com/x.py"), cap.artifactSrcUrl());
        assertEquals(ProviderId.VENDOR, cap.providerId());
        assertTrue(cap.isAuthorConsistentWithProvider());
    }

    @Test
    @DisplayName("kebab-case 写法兼容（artifact-src-url / supported-inputs / provider-id）")
    void kebabCaseKeys_accepted() throws Exception {
        writeSkill("kebab-skill",
                """
                name: kebab-skill
                description: kebab-case frontmatter
                author: alice
                artifact-src-url: file:///tmp/x.py
                supported-inputs: text
                provider-id: USER
                """,
                "# Kebab Skill");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(tempDir.toString());
        SkillLoader.SkillDef skill = skills.get("kebab-skill");

        assertNotNull(skill);
        SkillCap cap = skill.cap();
        assertEquals("alice", cap.author());
        assertEquals(java.net.URI.create("file:///tmp/x.py"), cap.artifactSrcUrl());
        assertEquals(ProviderId.USER, cap.providerId());
        assertTrue(cap.hasRemoteArtifact());
    }

    // ════════════════════════════════════════════════════════════════
    //  规范化降级
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("supportedInputs=image → v1 强制降级为 ['text']")
    void supportedInputsImage_normalizesToText() throws Exception {
        writeSkill("image-skill",
                """
                name: image-skill
                description: skill claiming image input
                author: oushani.core
                supported-inputs: image
                provider-id: AIOS_CORE
                """,
                "# Image Skill");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(tempDir.toString());
        SkillLoader.SkillDef skill = skills.get("image-skill");

        assertNotNull(skill);
        // v1 强制 ["text"]
        assertEquals(java.util.List.of("text"), skill.cap().supportedInputs());
    }

    @Test
    @DisplayName("provider-id=未知值 → 降级 AIOS_CORE")
    void unknownProviderId_defaultsToAiosCore() throws Exception {
        writeSkill("unknown-prov",
                """
                name: unknown-prov
                description: skill with unknown provider
                author: oushani.core
                provider-id: SOME_FUTURE_PROVIDER
                """,
                "# Unknown Provider");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(tempDir.toString());
        SkillLoader.SkillDef skill = skills.get("unknown-prov");

        assertNotNull(skill);
        assertEquals(ProviderId.AIOS_CORE, skill.cap().providerId());
    }

    @Test
    @DisplayName("artifact.srcUrl 非法 URL → 降级 null")
    void invalidSrcUrl_becomesNull() throws Exception {
        writeSkill("bad-url",
                """
                name: bad-url
                description: skill with bad URL
                author: oushani.core
                artifact.srcUrl: not-a-valid-url
                provider-id: AIOS_CORE
                """,
                "# Bad URL");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(tempDir.toString());
        SkillLoader.SkillDef skill = skills.get("bad-url");

        assertNotNull(skill);
        assertNull(skill.cap().artifactSrcUrl());
        assertFalse(skill.cap().hasRemoteArtifact());
    }

    // ════════════════════════════════════════════════════════════════
    //  双读模式 — 原文 prompt 与 Cap 共存
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("双读模式：body（原文 prompt）与 cap（结构化字段）同时可用")
    void dualRead_bodyAndCapCoexist() throws Exception {
        writeSkill("dual-read",
                """
                name: dual-read
                description: skill demonstrating dual-read mode
                author: oushani.core
                supported-inputs: text
                provider-id: AIOS_CORE
                """,
                "# Dual Read Skill\n\nThis body should remain accessible alongside cap.");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(tempDir.toString());
        SkillLoader.SkillDef skill = skills.get("dual-read");

        assertNotNull(skill);
        // 原文 prompt 侧 — body 仍可读
        String body = skill.body();
        assertTrue(body.contains("# Dual Read Skill"));
        assertTrue(body.contains("should remain accessible"));
        // 结构化 Cap 侧 — 4 字段已解析
        assertEquals("oushani.core", skill.cap().author());
        assertEquals(ProviderId.AIOS_CORE, skill.cap().providerId());
    }

    // ════════════════════════════════════════════════════════════════
    //  真实 classpath skill — integrity-auditor（已升级为 Cap 模型）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("integrity-auditor SKILL.md 真实加载 → Cap 字段已升级")
    void integrityAuditor_realClasspathSkill_hasCapFields() {
        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(".");
        SkillLoader.SkillDef skill = skills.get("integrity-auditor");

        assertNotNull(skill, "integrity-auditor 应被 SkillLoader 自动发现");
        SkillCap cap = skill.cap();
        // 已升级的 frontmatter 字段
        assertEquals("oushani.core.research", cap.author());
        assertEquals(ProviderId.AIOS_CORE, cap.providerId());
        assertEquals(java.util.List.of("text"), cap.supportedInputs());
        // artifact.srcUrl=null → 无远程载荷
        assertNull(cap.artifactSrcUrl());
        assertFalse(cap.hasRemoteArtifact());
        // author 与 providerId 一致
        assertTrue(cap.isAuthorConsistentWithProvider());
        // 原文 prompt 侧 — body 仍可读（双读模式）
        String body = skill.body();
        assertNotNull(body);
        assertFalse(body.isBlank(), "integrity-auditor 的 body 应非空（双读模式保留原文）");
    }
}
