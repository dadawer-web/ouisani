package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.user.apps.omnifactory.StructuredOutputValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StructuredOutputValidator 单元测试 — 验证三级 JSON 提取 + 拓扑 schema 验证。
 */
class StructuredOutputValidatorTest {

    // ════════════════════════════════════════════════════════════════
    //  三级 fallback 提取测试
    // ════════════════════════════════════════════════════════════════

    @Test
    void extractJson_plainJson_directParse() {
        String raw = "{\"workflowName\":\"test\",\"nodes\":[]}";
        String result = StructuredOutputValidator.extractJson(raw);
        assertNotNull(result);
        assertEquals(raw, result);
    }

    @Test
    void extractJson_thinkTag_stripped() {
        String raw = "<think>让我分析一下这个任务...</think>{\"workflowName\":\"test\",\"nodes\":[]}";
        String result = StructuredOutputValidator.extractJson(raw);
        assertNotNull(result);
        assertEquals("{\"workflowName\":\"test\",\"nodes\":[]}", result);
    }

    @Test
    void extractJson_markdownFence_extracted() {
        String raw = "好的，以下是拓扑：\n```json\n{\"workflowName\":\"test\",\"nodes\":[]}\n```\n";
        String result = StructuredOutputValidator.extractJson(raw);
        assertNotNull(result);
        assertTrue(result.contains("\"workflowName\""));
    }

    @Test
    void extractJson_surroundingNoise_bracketExtract() {
        String raw = "这是结果：{\"workflowName\":\"test\",\"nodes\":[]} 完成！";
        // 注意:这个 case 会在 Case 1 就成功(去除了两边文字后整体不是合法 JSON,
        // 但 Case 3 首尾大括号会截取到合法 JSON)
        String result = StructuredOutputValidator.extractJson(raw);
        assertNotNull(result);
        assertTrue(result.startsWith("{"));
        assertTrue(result.endsWith("}"));
    }

    @Test
    void extractJson_nullInput_returnsNull() {
        assertNull(StructuredOutputValidator.extractJson(null));
    }

    @Test
    void extractJson_blankInput_returnsNull() {
        assertNull(StructuredOutputValidator.extractJson("   "));
    }

    @Test
    void extractJson_noJson_returnsNull() {
        assertNull(StructuredOutputValidator.extractJson("这不是 JSON，也没有大括号"));
    }

    @Test
    void extractJson_invalidJson_returnsNull() {
        // 首尾有括号但内容不是合法 JSON
        assertNull(StructuredOutputValidator.extractJson("{这不是合法JSON}"));
    }

    // ════════════════════════════════════════════════════════════════
    //  extractAndValidate — 正常用例
    // ════════════════════════════════════════════════════════════════

    @Test
    void extractAndValidate_validTopology_passes() {
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"instanceId\":\"n1\",\"role\":\"获取数据\",\"executor\":\"omni\",\"upstreamDependencies\":[]}," +
                "{\"instanceId\":\"n2\",\"role\":\"处理数据\",\"executor\":\"omni\",\"upstreamDependencies\":[\"n1\"]}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertTrue(result.isValid());
        assertNotNull(result.cleanedJson());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void extractAndValidate_withThinkTag_passes() {
        String raw = "<think>分析:需要两个节点</think>\n" +
                "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"instanceId\":\"n1\",\"role\":\"搜索\",\"executor\":\"omni\"}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertTrue(result.isValid());
    }

    @Test
    void extractAndValidate_externalWithSubtype_passes() {
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"instanceId\":\"n1\",\"role\":\"调用Claude\",\"executor\":\"external:claude-code\"}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertTrue(result.isValid());
    }

    @Test
    void extractAndValidate_idAliasForInstanceId_passes() {
        // 旧格式用 id 而非 instanceId
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"id\":\"n1\",\"role\":\"搜索\",\"executor\":\"omni\"}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertTrue(result.isValid());
    }

    // ════════════════════════════════════════════════════════════════
    //  extractAndValidate — 错误用例
    // ════════════════════════════════════════════════════════════════

    @Test
    void extractAndValidate_missingNodesArray_fails() {
        String raw = "{\"workflowName\":\"demo\"}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertFalse(result.isValid());
        assertTrue(result.formattedErrors().contains("nodes"));
    }

    @Test
    void extractAndValidate_emptyNodesArray_fails() {
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertFalse(result.isValid());
        assertTrue(result.formattedErrors().contains("空"));
    }

    @Test
    void extractAndValidate_missingInstanceId_fails() {
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"role\":\"搜索\",\"executor\":\"omni\"}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertFalse(result.isValid());
        assertTrue(result.formattedErrors().contains("instanceId"));
    }

    @Test
    void extractAndValidate_missingRole_fails() {
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"instanceId\":\"n1\",\"executor\":\"omni\"}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertFalse(result.isValid());
        assertTrue(result.formattedErrors().contains("role"));
    }

    @Test
    void extractAndValidate_invalidExecutor_fails() {
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"instanceId\":\"n1\",\"role\":\"搜索\",\"executor\":\"invalid_type\"}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertFalse(result.isValid());
        assertTrue(result.formattedErrors().contains("executor"));
    }

    @Test
    void extractAndValidate_iterationNodeMissingChildNodes_fails() {
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"instanceId\":\"loop1\",\"role\":\"批量\",\"executor\":\"omni\"," +
                "\"isIteration\":true,\"iteratorDataVariable\":\"{{n1.list}}\",\"iteratorItemAlias\":\"item\"}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertFalse(result.isValid());
        assertTrue(result.formattedErrors().contains("childNodes"));
    }

    @Test
    void extractAndValidate_iterationNodeComplete_passes() {
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"instanceId\":\"src\",\"role\":\"获取列表\",\"executor\":\"omni\"}," +
                "{\"instanceId\":\"loop1\",\"role\":\"批量处理\",\"executor\":\"omni\"," +
                "\"isIteration\":true,\"iteratorDataVariable\":\"{{src.list}}\",\"iteratorItemAlias\":\"item\"," +
                "\"childNodes\":[" +
                "{\"instanceId\":\"proc\",\"role\":\"处理单个\",\"executor\":\"omni\"}" +
                "]}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertTrue(result.isValid());
    }

    @Test
    void extractAndValidate_emptyInput_fails() {
        ValidationResult result = StructuredOutputValidator.extractAndValidate("");
        assertFalse(result.isValid());
        assertTrue(result.formattedErrors().contains("空"));
    }

    @Test
    void extractAndValidate_nullInput_fails() {
        ValidationResult result = StructuredOutputValidator.extractAndValidate(null);
        assertFalse(result.isValid());
    }

    @Test
    void extractAndValidate_multipleErrors_allReported() {
        // 两个节点都有问题
        String raw = "{\"workflowName\":\"demo\",\"nodes\":[" +
                "{\"executor\":\"omni\"}," +
                "{\"instanceId\":\"n2\",\"role\":\"r\",\"executor\":\"bad\"}" +
                "]}";
        ValidationResult result = StructuredOutputValidator.extractAndValidate(raw);
        assertFalse(result.isValid());
        // 至少 2 个错误: n0 缺 instanceId + role, n1 executor 非法
        assertTrue(result.errors().size() >= 2);
    }
}
