package com.ouisani.aios.core.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetaSkill 数据模型单元测试 — 验证 R2 Meta-skill 编排规格。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>record 构造 + 字段访问</li>
 *   <li>compact constructor 校验（name/steps 必填，outputBasePath 默认值）</li>
 *   <li>便利构造器（无 defaults / 简化 SkillStep）</li>
 *   <li>SkillStep 默认值（outputDir 默认 = skillName）</li>
 *   <li>List 不可变性</li>
 *   <li>MetaSkills.ai4sAgent() 工厂与 SKILL.md 对齐</li>
 * </ul>
 */
class MetaSkillTest {

    @Test
    @DisplayName("基本构造与字段访问")
    void basicConstruction() {
        MetaSkill meta = new MetaSkill(
                "test-meta",
                "Test description",
                List.of(new MetaSkill.SkillStep("step-a", "${input}", "dir-a", false, "desc-a")),
                "/output/test",
                Map.of("key", "val")
        );

        assertEquals("test-meta", meta.name());
        assertEquals("Test description", meta.description());
        assertEquals("/output/test", meta.outputBasePath());
        assertEquals(1, meta.stepCount());
        assertEquals("val", meta.defaults().get("key"));
        assertEquals("step-a", meta.step(0).skillName());
    }

    @Test
    @DisplayName("outputBasePath 为空时默认 /output/{name}")
    void defaultOutputBasePath() {
        MetaSkill meta = new MetaSkill(
                "my-meta",
                "desc",
                List.of(new MetaSkill.SkillStep("s1")),
                null
        );
        assertEquals("/output/my-meta", meta.outputBasePath());
    }

    @Test
    @DisplayName("name 为空抛 IllegalArgumentException")
    void blankName_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new MetaSkill("", "d", List.of(new MetaSkill.SkillStep("s")), "/o"));
        assertThrows(IllegalArgumentException.class, () ->
                new MetaSkill(null, "d", List.of(new MetaSkill.SkillStep("s")), "/o"));
    }

    @Test
    @DisplayName("steps 为空或 null 抛 IllegalArgumentException")
    void emptySteps_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new MetaSkill("m", "d", List.of(), "/o"));
        assertThrows(IllegalArgumentException.class, () ->
                new MetaSkill("m", "d", null, "/o"));
    }

    @Test
    @DisplayName("SkillStep outputDir 默认 = skillName")
    void skillStep_defaultOutputDir() {
        MetaSkill.SkillStep step = new MetaSkill.SkillStep("my-skill", "${input}", null, false, "");
        assertEquals("my-skill", step.outputDir());
    }

    @Test
    @DisplayName("SkillStep skillName 为空抛 IllegalArgumentException")
    void skillStep_blankName_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new MetaSkill.SkillStep("", "${input}", "dir", false, ""));
    }

    @Test
    @DisplayName("SkillStep 便利构造器（4 参数 — optional 默认 false）")
    void skillStep_convenience4() {
        MetaSkill.SkillStep step = new MetaSkill.SkillStep("s1", "${input}", "d1", "desc");
        assertFalse(step.optional());
        assertEquals("desc", step.description());
    }

    @Test
    @DisplayName("SkillStep 单参数便利构造器")
    void skillStep_convenience1() {
        MetaSkill.SkillStep step = new MetaSkill.SkillStep("solo");
        assertEquals("solo", step.skillName());
        assertEquals("${input}", step.argsTemplate());
        assertEquals("solo", step.outputDir());
        assertFalse(step.optional());
    }

    @Test
    @DisplayName("steps 列表不可变")
    void stepsListIsImmutable() {
        MetaSkill meta = new MetaSkill(
                "m", "d",
                List.of(new MetaSkill.SkillStep("s1")),
                "/o"
        );
        assertThrows(UnsupportedOperationException.class, () ->
                meta.steps().add(new MetaSkill.SkillStep("s2")));
    }

    @Test
    @DisplayName("defaults 为 null 时退化为空 Map")
    void defaultsNull_becomesEmpty() {
        MetaSkill meta = new MetaSkill(
                "m", "d",
                List.of(new MetaSkill.SkillStep("s1")),
                "/o",
                null
        );
        assertNotNull(meta.defaults());
        assertTrue(meta.defaults().isEmpty());
    }

    @Test
    @DisplayName("MetaSkills.ai4sAgent() 工厂：4 步骤链，research-explorer 是 optional")
    void factory_ai4sAgent() {
        MetaSkill ai4s = MetaSkills.ai4sAgent();

        assertEquals("ai4s-agent", ai4s.name());
        assertEquals(4, ai4s.stepCount());
        assertEquals("/output/ai4s-agent", ai4s.outputBasePath());

        // 第一步 research-explorer 是 optional（用户直接给 topic 时可跳过）
        assertTrue(ai4s.step(0).optional(), "research-explorer 应该是 optional");
        assertEquals("research-explorer", ai4s.step(0).skillName());

        // 后三步是必需的
        assertFalse(ai4s.step(1).optional(), "literature-survey 应该是 required");
        assertFalse(ai4s.step(2).optional(), "experiment-suite 应该是 required");
        assertFalse(ai4s.step(3).optional(), "paper-writer 应该是 required");

        // 验证链顺序与 SKILL.md 描述对齐
        assertEquals("literature-survey", ai4s.step(1).skillName());
        assertEquals("experiment-suite", ai4s.step(2).skillName());
        assertEquals("paper-writer", ai4s.step(3).skillName());
    }

    @Test
    @DisplayName("argsTemplate 含 ${prev.outputPath} 引用上一步输出")
    void argsTemplate_canReferencePrevOutput() {
        MetaSkill ai4s = MetaSkills.ai4sAgent();

        // paper-writer 步骤应该引用上一步的输出路径
        assertTrue(ai4s.step(3).argsTemplate().contains("${prev.outputPath}")
                || ai4s.step(2).argsTemplate().contains("${prev.outputPath"),
                "至少一个后续步骤应该引用 ${prev.outputPath}");
    }

    @Test
    @DisplayName("MetaSkillRegistry 单例 + 内置 ai4s-agent")
    void registry_builtinAi4sAgent() {
        MetaSkillRegistry registry = MetaSkillRegistry.instance();

        // 内置 ai4s-agent 应已注册
        assertTrue(registry.get("ai4s-agent").isPresent());
        assertEquals(4, registry.get("ai4s-agent").get().stepCount());

        // 不存在的 meta-skill 返回 empty
        assertTrue(registry.get("nonexistent").isEmpty());
    }

    @Test
    @DisplayName("MetaSkillRegistry register/unregister")
    void registry_registerUnregister() {
        MetaSkillRegistry registry = MetaSkillRegistry.instance();
        int initialSize = registry.size();

        MetaSkill custom = new MetaSkill(
                "custom-meta", "d",
                List.of(new MetaSkill.SkillStep("s1")),
                "/output/custom"
        );
        registry.register(custom);
        assertEquals(initialSize + 1, registry.size());
        assertTrue(registry.get("custom-meta").isPresent());

        // 同名覆盖
        MetaSkill customV2 = new MetaSkill(
                "custom-meta", "v2",
                List.of(new MetaSkill.SkillStep("s1"), new MetaSkill.SkillStep("s2")),
                "/output/custom-v2"
        );
        registry.register(customV2);
        assertEquals(initialSize + 1, registry.size(), "覆盖不应增加数量");
        assertEquals(2, registry.get("custom-meta").get().stepCount());

        // 注销
        registry.unregister("custom-meta");
        assertFalse(registry.get("custom-meta").isPresent());
        assertEquals(initialSize, registry.size());
    }
}
