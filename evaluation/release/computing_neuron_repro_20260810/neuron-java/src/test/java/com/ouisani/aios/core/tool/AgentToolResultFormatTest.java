package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import com.ouisani.aios.core.review.ReviewGateConfig;
import com.ouisani.aios.core.review.ReviewGateLevel;
import com.ouisani.aios.core.review.ReviewLedger;
import com.ouisani.aios.core.review.ReviewerRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentTool} 同步路径结果回流格式化集成测试。
 * <p>
 * 验证 AgentTool fork 出的 fresh-child {@link QueryEngine} 结果回流走
 * {@code <task_result>} 压缩协议（{@link SubagentResultFormatter}）：
 * <ul>
 *   <li>短结果：原文包装在 {@code <task_result>} 块中</li>
 *   <li>长结果：截断为 head+tail+省略计数，防父 Agent 上下文污染</li>
 * </ul>
 * 同时隐式验证 fresh-context 契约 —— 子 QueryEngine 是 {@code new QueryEngine(...)} 全新实例，
 * 不继承父 compact 历史（HistoryCompressor 为 per-instance 字段）。
 * <p>
 * setUp 镜像 {@link com.ouisani.aios.core.review.ReviewGateIntegrationTest}：
 * ReviewGate 置 ANNOTATE + reviewer override CLEAN。子 QueryEngine.query() 不写 artifact
 * （StubSdk.writeFile no-op + 未设 ProvenanceHook.CURRENT_AGENT_ID）→ gate SKIP → 返回 StubSdk 原文。
 */
class AgentToolResultFormatTest {

    private static final String CLEAN_REVIEW_BLOCK =
            "```review\n{\"verdict\":\"CLEAN\",\"summary\":\"ok\",\"findings\":[]}\n```";
    private static final String PARENT = "parent_agent";

    @TempDir
    Path tempDir;

    private ConfigurableStubSdk stubSdk;

    @BeforeEach
    void setUp() {
        stubSdk = new ConfigurableStubSdk();
        ReviewGateConfig.setLevelForTesting(ReviewGateLevel.ANNOTATE);
        ReviewLedger.setReviewFile(tempDir.resolve("review.jsonl"));
        ReviewLedger.setEnabled(true);
        ReviewLedger.resetForTesting();
        ProvenanceHook.setProvenanceFile(tempDir.resolve("provenance.jsonl"));
        ProvenanceHook.setEnabled(true);
        ProvenanceHook.resetForTesting();
        VfsManager.instance().init();
        ReviewerRunner.setOverrideForTesting(
                (sdk, parent, wd, prompt, timeout) -> CLEAN_REVIEW_BLOCK);
    }

    @AfterEach
    void tearDown() {
        ReviewerRunner.clearOverrideForTesting();
        ReviewGateConfig.clearAllForTesting();
        ReviewLedger.resetForTesting();
        ReviewLedger.setEnabled(true);
        ProvenanceHook.CURRENT_AGENT_ID.remove();
        ProvenanceHook.resetForTesting();
        ProvenanceHook.setEnabled(true);
    }

    @Test
    @DisplayName("同步调用 — 短结果包装在 <task_result> 块中（fresh-child 回流协议）")
    void syncCall_shortResult_wrappedInTaskResult() {
        stubSdk.response = "I created the report.";
        AgentTool tool = new AgentTool();
        ToolContext ctx = new ToolContext(PARENT, stubSdk, tempDir.toString());

        ToolOutput out = tool.call(
                new AgentTool.Input("summarize the file", "", false, "summarize"), ctx);

        assertTrue(out.success(), "tool should succeed");
        String text = out.toText();
        assertTrue(text.startsWith("<task_result agent=\"sub_"), "open tag with agent attr");
        assertTrue(text.contains("task=\"summarize\""), "task attr carries description");
        assertTrue(text.endsWith("\n</task_result>"), "closing tag");
        assertTrue(text.contains("I created the report."), "StubSdk response preserved in body");
        assertFalse(text.contains("chars omitted"), "short result not truncated");
    }

    @Test
    @DisplayName("同步调用 — 长结果截断为 head+tail+省略计数（防父上下文污染）")
    void syncCall_longResult_truncatedToHeadAndTail() {
        // 构造明显超阈值的长响应
        String head = "H".repeat(SubagentResultFormatter.HEAD);
        String middle = "M".repeat(5000);
        String tail = "T".repeat(SubagentResultFormatter.TAIL);
        stubSdk.response = head + middle + tail;

        AgentTool tool = new AgentTool();
        ToolContext ctx = new ToolContext(PARENT, stubSdk, tempDir.toString());

        ToolOutput out = tool.call(
                new AgentTool.Input("big task", "", false, "big"), ctx);

        assertTrue(out.success(), "tool should succeed despite long result");
        String text = out.toText();
        assertTrue(text.startsWith("<task_result"), "still wrapped in task_result");
        assertTrue(text.contains(head), "head preserved verbatim");
        assertFalse(text.contains("M".repeat(100)), "middle bulk removed");
        int expectedOmitted = stubSdk.response.length()
                - SubagentResultFormatter.HEAD - SubagentResultFormatter.TAIL;
        assertTrue(text.contains("[... " + expectedOmitted + " chars omitted"),
                "omitted-count marker present");
        // tail 主体保留（用较短子串断言，容忍 gate footer 边界）
        assertTrue(text.contains("T".repeat(50)), "tail region preserved");
        assertTrue(text.endsWith("\n</task_result>"), "closing tag");
    }

    // ── 可配置 StubSdk：think/thinkStream 返回预设响应（无工具调用 → 命中 gate finalize） ──

    private static class ConfigurableStubSdk implements ToolSdk {
        String response = "ok";

        @Override
        public String think(String agentId, String prompt) {
            return response;
        }

        @Override
        public String thinkStream(String agentId, String prompt, Consumer<String> onDelta) {
            return response;
        }

        @Override
        public void writeFile(String agentId, String path, String data) {
            // no-op
        }
    }
}
