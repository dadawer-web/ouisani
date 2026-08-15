package com.ouisani.aios.core.skill;

import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    @Test
    @DisplayName("loadAll 自动发现 6 个 classpath 技能")
    void testLoadAllDiscoversClasspathSkills() {
        Map<String, SkillLoader.SkillDef> skills = SkillLoader.loadAll(".");
        assertTrue(skills.containsKey("batch"));
        assertTrue(skills.containsKey("debug"));
        assertTrue(skills.containsKey("karpathy-guidelines"));
        assertTrue(skills.containsKey("remember"));
        assertTrue(skills.containsKey("stuck"));
        assertTrue(skills.containsKey("verify"));
        assertEquals("Verify code changes by reading and checking the modified files",
                skills.get("verify").description());
    }

    @Test
    @DisplayName("formatActiveSkillsAsPrompt 跳过 frontmatter-only 技能")
    void testFormatActiveSkillsSkipsFrontmatterOnly() {
        SkillLoader.loadAll(".");
        SkillLoader.activate("verify");
        String prompt = SkillLoader.formatActiveSkillsAsPrompt();
        assertFalse(prompt.contains("### Skill: verify"));
        SkillLoader.deactivate("verify");
    }

    @Test
    @DisplayName("formatActiveSkillsAsPrompt 包含有 body 的技能")
    void testFormatActiveSkillsIncludesBodySkill() {
        SkillLoader.loadAll(".");
        SkillLoader.activate("karpathy-guidelines");
        String prompt = SkillLoader.formatActiveSkillsAsPrompt();
        assertTrue(prompt.contains("### Skill: karpathy-guidelines"));
        assertTrue(prompt.contains("Karpathy Guidelines"));
        SkillLoader.deactivate("karpathy-guidelines");
    }
}
