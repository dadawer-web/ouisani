package com.ouisani.aios.core.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 — {@link ReviewFinding} claim/evidence 富化测试。
 * <p>
 * 覆盖：解析（{@link ReviewVerdictParser} 读 claim/evidence）+ 序列化（{@link ReviewRecord#toJsonLine}
 * 写 claim/evidence）+ round-trip（{@link ReviewRecord#fromJsonLine} 字段保留）+ 向后兼容（旧格式缺字段降级 ""）。
 */
class ReviewFindingEnrichmentTest {

    @Test
    @DisplayName("ReviewVerdictParser 解析 claim + evidence 字段")
    void parser_readsClaimAndEvidence() {
        String resp = "```review\n" +
                "{\"verdict\":\"FLAGGED\",\"summary\":\"trace issue\",\"findings\":[" +
                "{\"severity\":\"high\",\"targetPath\":\"/out/a.md\",\"message\":\"number mismatch\"," +
                "\"claim\":\"final answer says 42\",\"evidence\":\"agent_5 wrote v2 via write at ts=1000\"}" +
                "]}\n```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(1, v.findings().size());
        ReviewFinding f = v.findings().get(0);
        assertEquals("final answer says 42", f.claim());
        assertEquals("agent_5 wrote v2 via write at ts=1000", f.evidence());
        assertEquals("number mismatch", f.message());
    }

    @Test
    @DisplayName("ReviewVerdictParser 旧格式（无 claim/evidence）→ 降级空串，不抛")
    void parser_oldFormat_defaultsEmpty() {
        String resp = "```review\n" +
                "{\"verdict\":\"CLEAN\",\"summary\":\"ok\",\"findings\":[" +
                "{\"severity\":\"low\",\"targetPath\":\"/out/b.md\",\"message\":\"minor\"}" +
                "]}\n```\n";
        ReviewVerdict v = ReviewVerdictParser.parse(resp);
        assertEquals(1, v.findings().size());
        ReviewFinding f = v.findings().get(0);
        assertEquals("", f.claim(), "旧格式缺 claim → 空串");
        assertEquals("", f.evidence(), "旧格式缺 evidence → 空串");
        assertEquals("minor", f.message());
    }

    @Test
    @DisplayName("ReviewRecord.toJsonLine 序列化 claim + evidence")
    void reviewRecord_toJsonLine_serializesClaimAndEvidence() {
        ReviewFinding f = new ReviewFinding("high", "/out/a.md", "mismatch",
                "claims 42", "agent_5 v2 write");
        ReviewRecord r = new ReviewRecord(
                "/out/a.md", "agent_x", "run_1", 1000L, "annotate", "FLAGGED",
                "summary", List.of(f), false);
        String json = r.toJsonLine();
        assertTrue(json.contains("\"claim\":\"claims 42\""), "claim 应被序列化");
        assertTrue(json.contains("\"evidence\":\"agent_5 v2 write\""), "evidence 应被序列化");
        assertTrue(json.contains("\"message\":\"mismatch\""));
        assertFalse(json.contains("\n"), "JSONL 必须单行");
    }

    @Test
    @DisplayName("round-trip: toJsonLine → fromJsonLine → 字段保留")
    void roundTrip_toJsonLine_fromJsonLine_preservesFields() {
        ReviewFinding original = new ReviewFinding("medium", "/out/r.md", "msg",
                "the claim", "the evidence");
        ReviewRecord r = new ReviewRecord(
                "/out/r.md", "agent_rt", "run_rt", 2000L, "soft", "BLOCKING",
                "rt summary", List.of(original), true);
        String json = r.toJsonLine();

        // fromJsonLine 反序列化（模拟 ProvenanceQuery 磁盘回读路径）
        ReviewRecord deserialized = ReviewRecord.fromJsonLine(json);
        assertNotNull(deserialized);
        assertEquals("/out/r.md", deserialized.targetPath());
        assertEquals("agent_rt", deserialized.agentId());
        assertEquals(1, deserialized.findings().size());
        ReviewFinding f = deserialized.findings().get(0);
        assertEquals("the claim", f.claim());
        assertEquals("the evidence", f.evidence());
        assertEquals("medium", f.severity());
        assertEquals("msg", f.message());
        assertTrue(deserialized.deterministicForced());
    }

    @Test
    @DisplayName("round-trip: 旧格式（无 claim/evidence）jsonl 行 → fromJsonLine → 降级空串")
    void roundTrip_oldJsonlLine_fromJsonLine_defaultsEmpty() {
        // 模拟 Phase 6 之前写入的 review.jsonl 行（无 claim/evidence 字段）
        String oldLine = "{\"targetPath\":\"/out/old.md\",\"agentId\":\"agent_old\",\"runId\":\"run_old\"," +
                "\"ts\":500,\"level\":\"annotate\",\"outcome\":\"CLEAN\",\"summary\":\"legacy\"," +
                "\"findings\":[{\"severity\":\"low\",\"targetPath\":\"/out/old.md\",\"message\":\"legacy msg\"}]," +
                "\"deterministicForced\":false}";
        ReviewRecord r = ReviewRecord.fromJsonLine(oldLine);
        assertNotNull(r);
        assertEquals(1, r.findings().size());
        ReviewFinding f = r.findings().get(0);
        assertEquals("", f.claim(), "旧 jsonl 行缺 claim → 空串");
        assertEquals("", f.evidence(), "旧 jsonl 行缺 evidence → 空串");
        assertEquals("legacy msg", f.message());
    }

    @Test
    @DisplayName("ReviewFinding 3-arg 构造向后兼容 — claim/evidence 默认空")
    void reviewFinding_3argCtor_defaultsEmpty() {
        ReviewFinding f = new ReviewFinding("low", "/out/c.md", "msg");
        assertEquals("", f.claim());
        assertEquals("", f.evidence());
        // 序列化时空串仍写出（保持 schema 一致）
        ReviewRecord r = new ReviewRecord(
                "/out/c.md", "a", "r", 1L, "annotate", "CLEAN", "s", List.of(f), false);
        String json = r.toJsonLine();
        assertTrue(json.contains("\"claim\":\"\""));
        assertTrue(json.contains("\"evidence\":\"\""));
    }

    @Test
    @DisplayName("ReviewFinding null claim/evidence → 降级空串")
    void reviewFinding_nullClaimEvidence_defaultsEmpty() {
        ReviewFinding f = new ReviewFinding("low", "/out/d.md", "msg", null, null);
        assertEquals("", f.claim());
        assertEquals("", f.evidence());
    }
}
