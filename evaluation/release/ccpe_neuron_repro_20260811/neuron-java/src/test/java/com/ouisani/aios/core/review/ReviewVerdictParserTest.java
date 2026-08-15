package com.ouisani.aios.core.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReviewVerdictParser} 单元测试。
 * <p>
 * 覆盖：```review block 解析、```json 回退、findings 解析、无 block/坏 JSON/未知 verdict
 * 降级 INCONCLUSIVE（best-effort 永不抛出）。
 */
class ReviewVerdictParserTest {

    @Test
    @DisplayName("解析 ```review block 的 CLEAN verdict")
    void parse_reviewBlock_cleanVerdict() {
        String resp = "Here is my review.\n```review\n" +
                "{\"verdict\":\"CLEAN\",\"summary\":\"all good\",\"findings\":[]}\n" +
                "```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(ReviewVerdict.Outcome.CLEAN, v.outcome());
        assertEquals("all good", v.summary());
        assertTrue(v.findings().isEmpty());
        assertFalse(v.isBlocking());
    }

    @Test
    @DisplayName("无 ```review 时回退首个 ```json block")
    void parse_jsonBlock_fallback() {
        String resp = "Review below.\n```json\n" +
                "{\"verdict\":\"CLEAN\",\"summary\":\"fallback ok\",\"findings\":[]}\n" +
                "```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(ReviewVerdict.Outcome.CLEAN, v.outcome());
        assertEquals("fallback ok", v.summary());
    }

    @Test
    @DisplayName("findings 解析 — low/medium/high，high 视为阻断性")
    void parse_findingsParsed() {
        String resp = "```review\n" +
                "{\"verdict\":\"FLAGGED\",\"summary\":\"issues\",\"findings\":[" +
                "{\"severity\":\"low\",\"targetPath\":\"/a\",\"message\":\"m1\"}," +
                "{\"severity\":\"medium\",\"targetPath\":\"/b\",\"message\":\"m2\"}," +
                "{\"severity\":\"high\",\"targetPath\":\"/c\",\"message\":\"m3\"}" +
                "]}\n```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(ReviewVerdict.Outcome.FLAGGED, v.outcome());
        assertEquals(3, v.findings().size());
        assertEquals("low", v.findings().get(0).severity());
        assertEquals("/b", v.findings().get(1).targetPath());
        // high finding → isBlocking
        assertTrue(v.findings().get(2).isBlocking());
        assertTrue(v.isBlocking(), "含 high finding → 整体阻断性");
        assertFalse(v.findings().get(0).isBlocking());
    }

    @Test
    @DisplayName("findings 缺省字段 — severity 默认 low，targetPath 默认 null，message 默认空")
    void parse_findingsDefaultFields() {
        String resp = "```review\n" +
                "{\"verdict\":\"BLOCKING\",\"summary\":\"s\",\"findings\":[{\"severity\":\"high\"}]}\n```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(1, v.findings().size());
        ReviewFinding f = v.findings().get(0);
        assertEquals("high", f.severity());
        assertNull(f.targetPath());
        assertEquals("", f.message());
    }

    @Test
    @DisplayName("无 fenced block → INCONCLUSIVE")
    void parse_noBlock_returnsInconclusive() {
        String resp = "This is just plain text with no fenced block at all.";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(ReviewVerdict.Outcome.INCONCLUSIVE, v.outcome());
        assertTrue(v.findings().isEmpty());
    }

    @Test
    @DisplayName("null/空输入 → INCONCLUSIVE")
    void parse_blankInput_returnsInconclusive() {
        assertEquals(ReviewVerdict.Outcome.INCONCLUSIVE, ReviewVerdictParser.parse(null).outcome());
        assertEquals(ReviewVerdict.Outcome.INCONCLUSIVE, ReviewVerdictParser.parse("").outcome());
        assertEquals(ReviewVerdict.Outcome.INCONCLUSIVE, ReviewVerdictParser.parse("   ").outcome());
    }

    @Test
    @DisplayName("坏 JSON → 降级 INCONCLUSIVE，不抛出")
    void parse_badJson_returnsInconclusive() {
        String resp = "```review\n{not valid json at all}\n```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(ReviewVerdict.Outcome.INCONCLUSIVE, v.outcome());
    }

    @Test
    @DisplayName("未知 verdict 字符串 → INCONCLUSIVE")
    void parse_unknownVerdictString_returnsInconclusive() {
        String resp = "```review\n{\"verdict\":\"MAYBE\",\"summary\":\"x\",\"findings\":[]}\n```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(ReviewVerdict.Outcome.INCONCLUSIVE, v.outcome());
    }

    @Test
    @DisplayName("空 JSON 对象 → 字段缺省")
    void parse_missingFields_defaults() {
        String resp = "```review\n{}\n```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(ReviewVerdict.Outcome.INCONCLUSIVE, v.outcome());
        assertTrue(v.findings().isEmpty());
        assertEquals("", v.summary());
    }

    @Test
    @DisplayName("BLOCKING verdict → isBlocking true")
    void parse_blockingVerdict_isBlocking() {
        String resp = "```review\n{\"verdict\":\"BLOCKING\",\"summary\":\"must fix\",\"findings\":[]}\n```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(ReviewVerdict.Outcome.BLOCKING, v.outcome());
        assertTrue(v.isBlocking());
    }

    @Test
    @DisplayName("verdict 大小写不敏感（CLEAN/clean/Clean）")
    void parse_verdictCaseInsensitive() {
        String resp = "```review\n{\"verdict\":\"clean\",\"summary\":\"s\",\"findings\":[]}\n```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(ReviewVerdict.Outcome.CLEAN, v.outcome());
    }
}
