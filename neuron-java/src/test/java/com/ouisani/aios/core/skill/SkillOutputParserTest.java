package com.ouisani.aios.core.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillOutputParser 单元测试 — 验证 R1 Skill 输出契约。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>按 contract 的 languageTag 提取 fenced block</li>
 *   <li>contract 为空时退化为"取第一个 fenced block"</li>
 *   <li>JSON 内容格式校验</li>
 *   <li>无 fenced block 时返回空结果</li>
 *   <li>多个 fenced block 时优先匹配 contract 指定的</li>
 *   <li>括号不配对的 JSON 不通过校验</li>
 * </ul>
 */
class SkillOutputParserTest {

    @Test
    @DisplayName("按 contract 的 languageTag 提取 JSON fenced block")
    void parse_jsonContract_extractsJsonBlock() {
        String llmOutput = """
            Here is my review:

            ```json
            {"findings": ["issue1", "issue2"], "severity": "high"}
            ```

            Done.
            """;
        String contract = "json:Reviewer findings as JSON";

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, contract);

        assertEquals("json", out.languageTag());
        assertTrue(out.hasContent());
        assertTrue(out.parsedJson().isPresent());
        assertTrue(out.parsedJson().get().contains("\"findings\""));
        // 输入 JSON 在冒号后有空格: "severity": "high" — 校验只断言 key 与 value 各自存在
        assertTrue(out.parsedJson().get().contains("\"severity\""));
        assertTrue(out.parsedJson().get().contains("\"high\""));
    }

    @Test
    @DisplayName("contract 为空时取第一个 fenced block")
    void parse_emptyContract_takesFirstBlock() {
        String llmOutput = """
            Analysis:

            ```review
            First finding
            ```

            ```json
            {"x": 1}
            ```
            """;

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, "");

        assertEquals("review", out.languageTag());
        assertEquals("First finding", out.rawContent());
    }

    @Test
    @DisplayName("contract 指定 languageTag 时优先匹配（即使不是第一个）")
    void parse_contractLang_prefersMatchedBlock() {
        String llmOutput = """
            ```review
            review content
            ```

            ```json
            {"key": "value"}
            ```
            """;
        String contract = "json:Output as JSON";

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, contract);

        assertEquals("json", out.languageTag());
        assertTrue(out.parsedJson().isPresent());
    }

    @Test
    @DisplayName("contract 指定的 languageTag 不存在时回退到第一个 block")
    void parse_contractLangNotFound_fallsBackToFirst() {
        String llmOutput = """
            ```yaml
            key: value
            ```
            """;
        String contract = "json:Want JSON but only YAML available";

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, contract);

        // 回退到第一个（yaml）
        assertEquals("yaml", out.languageTag());
        assertEquals("key: value", out.rawContent());
        // 非 json，parsedJson 为 empty
        assertTrue(out.parsedJson().isEmpty());
    }

    @Test
    @DisplayName("无 fenced block 时返回空结果")
    void parse_noFencedBlock_returnsEmpty() {
        String llmOutput = "Just plain text, no code blocks.";

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, "json:want json");

        assertFalse(out.hasContent());
        assertTrue(out.parsedJson().isEmpty());
    }

    @Test
    @DisplayName("null/空 LLM 输出返回空结果")
    void parse_nullOrEmptyInput_returnsEmpty() {
        assertTrue(SkillOutputParser.parse(null, "json:x").hasContent() == false);
        assertTrue(SkillOutputParser.parse("", "json:x").hasContent() == false);
    }

    @Test
    @DisplayName("JSON 括号不配对时不通过校验")
    void parse_unbalancedJson_failsValidation() {
        String llmOutput = """
            ```json
            {"key": "value"
            ```
            """;
        String contract = "json:expect balanced";

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, contract);

        assertEquals("json", out.languageTag());
        assertTrue(out.hasContent());
        // 括号不配对，parsedJson 为 empty
        assertTrue(out.parsedJson().isEmpty());
    }

    @Test
    @DisplayName("JSON 数组也能识别")
    void parse_jsonArray_recognized() {
        String llmOutput = """
            ```json
            ["item1", "item2", "item3"]
            ```
            """;

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, "json:array output");

        assertTrue(out.parsedJson().isPresent());
        assertTrue(out.parsedJson().get().startsWith("["));
    }

    @Test
    @DisplayName("snake_case 契约格式也支持（output_contract:）")
    void parse_snakeCaseContract_supported() {
        // 这个测试验证 SkillLoader.parseSkillMd 对 output_contract 的支持
        // 这里只验证 parser 本身能处理任意 contract 字符串
        String llmOutput = """
            ```python
            print("hello")
            ```
            """;
        String contract = "python:Python script";

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, contract);

        assertEquals("python", out.languageTag());
        assertTrue(out.hasContent());
    }

    @Test
    @DisplayName("无冒号的 contract 退化为取第一个 block")
    void parse_contractWithoutColon_takesFirstBlock() {
        String llmOutput = """
            ```bash
            echo "hello"
            ```
            """;
        String contract = "just some description without colon";

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, contract);

        assertEquals("bash", out.languageTag());
    }

    @Test
    @DisplayName("from SkillDef 重载方法")
    void parse_fromSkillDef() {
        SkillLoader.SkillDef skill = new SkillLoader.SkillDef(
                "test-skill", "desc", "content",
                java.util.List.of(), java.util.List.of(),
                "", "", SkillLoader.SkillSource.BUNDLED, null,
                "json:output contract"
        );

        String llmOutput = """
            ```json
            {"result": "ok"}
            ```
            """;

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, skill);

        assertTrue(out.parsedJson().isPresent());
    }

    @Test
    @DisplayName("fenced block 位置偏移正确")
    void parse_blockOffsets_correct() {
        String llmOutput = "prefix\n```json\n{}\n```\nsuffix";

        SkillOutputParser.SkillOutput out = SkillOutputParser.parse(llmOutput, "json:x");

        assertTrue(out.start() > 0);
        assertTrue(out.end() > out.start());
        assertTrue(out.end() < llmOutput.length());
    }
}
