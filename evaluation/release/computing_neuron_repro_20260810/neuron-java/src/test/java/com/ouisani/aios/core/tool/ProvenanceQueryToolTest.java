package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.provenance.ProvenanceHook;
import com.ouisani.aios.core.provenance.ProvenanceQuery;
import com.ouisani.aios.core.provenance.TraceabilityReport;
import com.ouisani.aios.core.review.ReviewFinding;
import com.ouisani.aios.core.review.ReviewLedger;
import com.ouisani.aios.core.review.ReviewRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProvenanceQueryTool} 单元测试 — Phase 6。
 * <p>
 * 覆盖：path 查询、agentId 查询、空输入报错、只读标记、报告格式化、空结果文案。
 * <p>
 * 用 {@link ProvenanceHook}/{@link ReviewLedger} 静态全局状态 + {@code setProvenanceFile/setReviewFile}
 * 指向 @TempDir，避免触碰真实 .aios/ 目录。
 */
class ProvenanceQueryToolTest {

    @TempDir
    Path tempDir;

    private ProvenanceQueryTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new ProvenanceQueryTool();
        ctx = new ToolContext("reviewer_test", null, tempDir.toString());

        ProvenanceHook.setProvenanceFile(tempDir.resolve("provenance.jsonl"));
        ProvenanceHook.setEnabled(true);
        ProvenanceHook.resetForTesting();
        ReviewLedger.setReviewFile(tempDir.resolve("review.jsonl"));
        ReviewLedger.setEnabled(true);
        ReviewLedger.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        ProvenanceHook.resetForTesting();
        ProvenanceHook.setEnabled(true);
        ProvenanceHook.setProvenanceFile(Path.of(".aios", "provenance.jsonl"));
        ReviewLedger.resetForTesting();
        ReviewLedger.setEnabled(true);
        ReviewLedger.setReviewFile(Path.of(".aios", "review.jsonl"));
    }

    @Test
    @DisplayName("工具元数据：name=provenance_query，readOnly=true，有 I/O 契约")
    void metadata_readOnlyWithContract() {
        assertEquals("provenance_query", tool.name());
        assertTrue(tool.readOnly(), "必须是只读工具（PLAN 模式可用）");
        assertFalse(tool.inputSchema().isBlank());
        assertFalse(tool.description().isBlank());
        assertFalse(tool.inputPorts().isEmpty(), "应声明 inputPorts");
        assertFalse(tool.outputPorts().isEmpty(), "应声明 outputPorts");
        assertTrue(tool.example().isPresent(), "应提供使用示例");
    }

    @Test
    @DisplayName("call with path → 格式化报告含 provenance + review")
    void callWithPath_returnsFormattedReport() {
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_p");
        ProvenanceHook.CURRENT_SESSION_ID.set("sess_p");
        ProvenanceHook.onWrite("/out/tool.md", "content", true);
        ProvenanceHook.CURRENT_AGENT_ID.remove();
        ProvenanceHook.CURRENT_SESSION_ID.remove();

        ReviewLedger.append(new ReviewRecord(
                "/out/tool.md", "agent_p", "run_p", 1000L, "annotate", "FLAGGED",
                "traceable", List.of(new ReviewFinding("medium", "/out/tool.md",
                        "mismatch", "claims 42", "agent_p v1 write")), false));

        ToolOutput out = tool.call(new ProvenanceQueryTool.Input("/out/tool.md", null), ctx);
        assertTrue(out.success());
        String text = out.toText();
        assertTrue(text.contains("Traceability Report for: /out/tool.md"));
        assertTrue(text.contains("Provenance (1 records)"));
        assertTrue(text.contains("v1 [write]"));
        assertTrue(text.contains("agent=agent_p"));
        assertTrue(text.contains("Reviews (1 records)"));
        assertTrue(text.contains("[FLAGGED]"));
        assertTrue(text.contains("claim: claims 42"), "claim 应出现在格式化输出");
        assertTrue(text.contains("evidence: agent_p v1 write"), "evidence 应出现在格式化输出");
    }

    @Test
    @DisplayName("call with agentId（无 path）→ 按 agent 追溯")
    void callWithAgentId_tracesByAgent() {
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_tool2");
        ProvenanceHook.onWrite("/out/a2.md", "x", true);
        ProvenanceHook.CURRENT_AGENT_ID.remove();

        ToolOutput out = tool.call(new ProvenanceQueryTool.Input(null, "agent_tool2"), ctx);
        assertTrue(out.success());
        assertTrue(out.toText().contains("Traceability Report for: agent_tool2"));
        assertTrue(out.toText().contains("/out/a2.md"));
    }

    @Test
    @DisplayName("path 优先于 agentId（两者都给时）")
    void callWithBoth_pathTakesPrecedence() {
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_both");
        ProvenanceHook.onWrite("/out/both.md", "x", true);
        ProvenanceHook.CURRENT_AGENT_ID.remove();

        ToolOutput out = tool.call(
                new ProvenanceQueryTool.Input("/out/both.md", "agent_both"), ctx);
        assertTrue(out.success());
        // key 应是 path，不是 agentId
        assertTrue(out.toText().contains("Traceability Report for: /out/both.md"));
    }

    @Test
    @DisplayName("path 和 agentId 都缺 → 失败")
    void callWithNeither_fails() {
        ToolOutput out = tool.call(new ProvenanceQueryTool.Input(null, null), ctx);
        assertFalse(out.success());
        assertTrue(out.toText().contains("requires at least one"));
    }

    @Test
    @DisplayName("空字符串 path/agentId 等同未提供 → 失败")
    void callWithBlank_fails() {
        ToolOutput out = tool.call(new ProvenanceQueryTool.Input("  ", "  "), ctx);
        assertFalse(out.success());
    }

    @Test
    @DisplayName("null input → 失败")
    void callWithNullInput_fails() {
        ToolOutput out = tool.call(null, ctx);
        assertFalse(out.success());
    }

    @Test
    @DisplayName("无命中 → 空报告文案")
    void callWithNoHits_emptyReportMessage() {
        ToolOutput out = tool.call(
                new ProvenanceQueryTool.Input("/out/ghost.md", null), ctx);
        assertTrue(out.success());
        assertTrue(out.toText().contains("No provenance or review records found"));
    }

    @Test
    @DisplayName("Input.toJson 序列化（含转义）")
    void inputToJson_serializes() {
        ProvenanceQueryTool.Input in = new ProvenanceQueryTool.Input("/out/x.md", "agent_y");
        String json = in.toJson();
        assertTrue(json.contains("\"path\":\"/out/x.md\""));
        assertTrue(json.contains("\"agentId\":\"agent_y\""));
    }

    @Test
    @DisplayName("Input trim 空白")
    void input_trimsWhitespace() {
        ProvenanceQueryTool.Input in = new ProvenanceQueryTool.Input("  /out/t.md  ", "  agent_t  ");
        assertEquals("/out/t.md", in.path());
        assertEquals("agent_t", in.agentId());
    }

    @Test
    @DisplayName("format(空报告) → 空报告文案")
    void format_emptyReport_message() {
        String text = ProvenanceQueryTool.format(TraceabilityReport.empty("/out/none.md"));
        assertTrue(text.contains("No provenance or review records found"));
        assertTrue(text.contains("/out/none.md"));
    }
}
