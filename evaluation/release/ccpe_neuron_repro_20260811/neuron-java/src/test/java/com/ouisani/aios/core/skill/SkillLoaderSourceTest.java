package com.ouisani.aios.core.skill;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Skill Catalog 多源加载测试 — first-wins shadow / .claude legacy / LEARNED 懒加载 /
 * category+tags 解析 / body() 向后兼容。
 * <p>
 * 借鉴 OpenScience skill.ts：早源 shadow 晚源（PROJECT>BUNDLED>USER>LEARNED），
 * LEARNED 源懒加载（catalog 只索引 frontmatter，body 首次使用才读）。
 */
class SkillLoaderSourceTest {

    @AfterEach
    void cleanupProps() {
        System.clearProperty("aios.learned.skills.dir");
    }

    /** 在 skillsRoot/name/ 下写入 SKILL.md（frontmatter + body）。 */
    private static Path writeSkillMd(Path skillsRoot, String name, String frontmatter, String body)
            throws IOException {
        Path dir = skillsRoot.resolve(name);
        Files.createDirectories(dir);
        String content = "---\n" + frontmatter + "---\n" + body;
        Files.writeString(dir.resolve("SKILL.md"), content);
        return dir;
    }

    @Test
    @DisplayName("first-wins: PROJECT(.aios/skills) shadow BUNDLED 同名技能")
    void testProjectShadowsBundled(@TempDir Path workDir) throws IOException {
        // 在 workingDir/.aios/skills/verify 放一个 PROJECT skill（与 BUNDLED verify 同名）
        Path projectSkills = workDir.resolve(".aios").resolve("skills");
        writeSkillMd(projectSkills, "verify",
                "name: verify\ndescription: PROJECT shadow version\n",
                "This is the PROJECT body for verify.");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(workDir.toString());
        SkillLoader.SkillDef verify = skills.get("verify");
        assertNotNull(verify, "verify skill should be loaded");
        assertEquals(SkillLoader.SkillSource.PROJECT, verify.source(),
                "PROJECT should shadow BUNDLED (first-wins via putIfAbsent)");
        assertEquals("PROJECT shadow version", verify.description());
    }

    @Test
    @DisplayName(".claude/skills legacy 回退：加载为 PROJECT 源")
    void testClaudeLegacyFallback(@TempDir Path workDir) throws IOException {
        Path claudeSkills = workDir.resolve(".claude").resolve("skills");
        writeSkillMd(claudeSkills, "legacy-skill",
                "name: legacy-skill\ndescription: legacy via .claude/skills\n",
                "Legacy body.");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(workDir.toString());
        SkillLoader.SkillDef legacy = skills.get("legacy-skill");
        assertNotNull(legacy);
        assertEquals(SkillLoader.SkillSource.PROJECT, legacy.source(),
                ".claude/skills loaded as PROJECT source (legacy fallback)");
    }

    @Test
    @DisplayName("LEARNED 懒加载：content==null，body() 首次使用从 path 读取并剥离 frontmatter")
    void testLearnedLazyLoad(@TempDir Path workDir, @TempDir Path learnedDir) throws IOException {
        System.setProperty("aios.learned.skills.dir", learnedDir.toString());
        writeSkillMd(learnedDir, "foo",
                "name: foo\ndescription: learned skill\ncategory: learned\ntags: a, b, c\n",
                "This is the LEARNED body. It should not be in content.");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(workDir.toString());
        SkillLoader.SkillDef foo = skills.get("foo");
        assertNotNull(foo, "LEARNED skill foo should be loaded");
        assertEquals(SkillLoader.SkillSource.LEARNED, foo.source());
        assertNull(foo.content(), "LEARNED content must be null (lazy: only frontmatter indexed)");
        assertEquals("learned", foo.category());
        assertEquals(List.of("a", "b", "c"), foo.tags());

        String body = foo.body();
        assertTrue(body.contains("LEARNED body"), "body() should resolve lazily from path");
        assertFalse(body.contains("---"), "body() should strip frontmatter");
        assertFalse(body.contains("description:"), "body() should not contain frontmatter fields");
    }

    @Test
    @DisplayName("category/tags 解析：eager 源也支持 frontmatter category+tags")
    void testCategoryTagsParsing(@TempDir Path workDir) throws IOException {
        Path projectSkills = workDir.resolve(".aios").resolve("skills");
        writeSkillMd(projectSkills, "categorized",
                "name: categorized\ndescription: has meta\ncategory: testing\ntags: unit, fast\n",
                "Body here.");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(workDir.toString());
        SkillLoader.SkillDef c = skills.get("categorized");
        assertNotNull(c);
        assertEquals("testing", c.category());
        assertEquals(List.of("unit", "fast"), c.tags());
    }

    @Test
    @DisplayName("body() 向后兼容：eager 源 content 非 null → body() 不读 path")
    void testEagerBodyBackwardCompat(@TempDir Path workDir) throws IOException {
        Path projectSkills = workDir.resolve(".aios").resolve("skills");
        Path skillDir = writeSkillMd(projectSkills, "eager-skill",
                "name: eager-skill\ndescription: eager\n",
                "Eager body content.");

        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(workDir.toString());
        SkillLoader.SkillDef e = skills.get("eager-skill");
        assertNotNull(e.content(), "eager source content must be non-null");
        // 删除文件后 body() 仍应返回正确内容（证明 eager 源不读 path，仅用 content）
        Files.delete(skillDir.resolve("SKILL.md"));
        String body = e.body();
        assertEquals("Eager body content.", body);
    }

    @Test
    @DisplayName("BUNDLED 仍可发现（不回归现有 6 skill）")
    void testBundledStillDiscovered(@TempDir Path workDir) {
        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(workDir.toString());
        // BUNDLED classpath skills 应仍在（空 workDir 不 shadow 它们）
        assertTrue(skills.containsKey("batch"));
        assertTrue(skills.containsKey("karpathy-guidelines"));
        assertTrue(skills.containsKey("verify"));
    }
}
