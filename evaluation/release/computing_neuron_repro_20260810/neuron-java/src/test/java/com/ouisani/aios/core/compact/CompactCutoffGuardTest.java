package com.ouisani.aios.core.compact;

import com.ouisani.aios.core.AgentTask.TokenRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompactCutoffGuard 单测 — 验证 jcode safe_compaction_cutoff 借鉴实现。
 * <p>
 * 覆盖三个设计修复点：
 * <ul>
 *   <li>修复 A — 默认模式不强制话题边界（用例 7 验证纯文本对话不再被过度拒绝）</li>
 *   <li>修复 B — findNearestBoundaryBackward 向后搜索（用例 8/9 验证 SEMANTIC 模式）</li>
 *   <li>修复 C — safeCompactionCutoff 步骤 3 调用更新</li>
 * </ul>
 * <p>
 * 测试输入格式约定（避免在源码中写入 XML 形式标签字面量）：
 * <ul>
 *   <li>ToolUse 记录：content 含子串 "tool_call"（命中 isToolCallRequest）和 toolName（命中配对启发式）</li>
 *   <li>ToolResult 记录：content 含子串 tool_result name="toolName"（命中 isCompactableToolResult/isToolResult/extractToolName）</li>
 *   <li>纯文本：role=assistant/user + 任意不含上述子串的 content</li>
 * </ul>
 */
class CompactCutoffGuardTest {

    /** 每个测试首行复位全局静态状态，避免测试间污染 */
    private static void resetState() {
        CompactCutoffGuard.setCompactionMode(CompactionMode.REACTIVE);
        CompactCutoffGuard.setBoundaryDetector(null);
    }

    private static TokenRecord toolUse(String toolName) {
        return new TokenRecord("assistant",
                "tool_call name=\"" + toolName + "\" query end",
                System.currentTimeMillis());
    }

    private static TokenRecord toolResult(String toolName) {
        return new TokenRecord("tool",
                "tool_result name=\"" + toolName + "\" content here",
                System.currentTimeMillis());
    }

    private static TokenRecord text(String role, String content) {
        return new TokenRecord(role, content, System.currentTimeMillis());
    }

    @Test
    void safeCompactionCutoff_emptyContext_returnsZero() {
        resetState();
        assertEquals(0, CompactCutoffGuard.safeCompactionCutoff(List.of(), 0));
    }

    @Test
    void safeCompactionCutoff_noToolResultsInRetention_returnsCutoff() {
        resetState();
        List<TokenRecord> ctx = List.of(
                text("user", "q1"),
                text("assistant", "a1"),
                text("user", "q2"),
                text("assistant", "a2")
        );
        assertEquals(2, CompactCutoffGuard.safeCompactionCutoff(ctx, 2));
    }

    @Test
    void safeCompactionCutoff_allPairedAtBoundary_returnsCutoff() {
        resetState();
        List<TokenRecord> ctx = List.of(
                toolUse("file_read"),
                toolResult("file_read"),
                toolUse("bash"),
                toolResult("bash")
        );
        assertEquals(2, CompactCutoffGuard.safeCompactionCutoff(ctx, 2));
    }

    @Test
    void safeCompactionCutoff_orphanBacktrackAdjustsCutoff() {
        resetState();
        List<TokenRecord> ctx = List.of(
                text("user", "question"),
                toolUse("file_read"),
                text("assistant", "intermediate"),
                toolResult("file_read")
        );
        assertEquals(1, CompactCutoffGuard.safeCompactionCutoff(ctx, 3));
    }

    @Test
    void safeCompactionCutoff_orphanNoToolUseReturnsZero() {
        resetState();
        List<TokenRecord> ctx = List.of(
                text("user", "q1"),
                text("assistant", "a1"),
                toolResult("file_read")
        );
        assertEquals(0, CompactCutoffGuard.safeCompactionCutoff(ctx, 2));
    }

    @Test
    void safeCompactionCutoff_midToolBlockAdjustsBackward() {
        resetState();
        List<TokenRecord> ctx = List.of(
                text("user", "question"),
                toolUse("file_read"),
                toolResult("file_read")
        );
        assertEquals(1, CompactCutoffGuard.safeCompactionCutoff(ctx, 2));
    }

    @Test
    void safeCompactionCutoff_plainTextDefaultModeReturnsCutoff() {
        resetState();
        List<TokenRecord> ctx = List.of(
                text("user", "user input"),
                text("assistant", "reply 1"),
                text("assistant", "reply 2"),
                text("assistant", "reply 3")
        );
        assertEquals(2, CompactCutoffGuard.safeCompactionCutoff(ctx, 2));
    }

    @Test
    void safeCompactionCutoff_semanticModeStrictRejects() {
        resetState();
        CompactCutoffGuard.setCompactionMode(CompactionMode.SEMANTIC);
        List<TokenRecord> ctx = List.of(
                text("assistant", "reply 1"),
                text("assistant", "reply 2"),
                text("assistant", "reply 3"),
                text("assistant", "reply 4")
        );
        assertEquals(0, CompactCutoffGuard.safeCompactionCutoff(ctx, 2));
    }

    @Test
    void safeCompactionCutoff_semanticModeWithDetectorBackwardSnaps() {
        resetState();
        CompactCutoffGuard.setCompactionMode(CompactionMode.SEMANTIC);
        CompactCutoffGuard.setBoundaryDetector((ctx, idx) -> idx == 1);
        List<TokenRecord> ctx = List.of(
                text("assistant", "reply 1"),
                text("assistant", "reply 2"),
                text("assistant", "reply 3"),
                text("assistant", "reply 4")
        );
        assertEquals(1, CompactCutoffGuard.safeCompactionCutoff(ctx, 2));
    }

    @Test
    void splitMessagesProtectedStrict_unpairableRejects() {
        resetState();
        List<TokenRecord> ctx = List.of(
                text("user", "q1"),
                text("assistant", "a1"),
                toolResult("file_read")
        );
        CompactService.SplitResult result =
                CompactService.splitMessagesProtectedStrict(ctx, 1);
        assertTrue(result.toSummarize().isEmpty(), "toSummarize should be empty (rejected)");
        assertEquals(3, result.recentMessages().size(), "all 3 records should be retained");
    }

    @Test
    void estimateTokensWithImageCost_plainTextNoRegression() {
        resetState();
        String text = "hello world";
        assertEquals(14, CompactCutoffGuard.estimateTokens(text));
    }

    @Test
    void estimateTokensWithImageCost_base64ImageUsesFixedCost() {
        resetState();
        String input = "text" + "data:image/png;base64,iVBORw0KGgoAAAANS";
        assertEquals(1605, CompactCutoffGuard.estimateTokens(input));
    }

    @Test
    void compactionMode_thresholdForMode() {
        resetState();
        assertEquals(100000, CompactionMode.thresholdForMode(CompactionMode.REACTIVE, 100000));
        assertEquals(70000, CompactionMode.thresholdForMode(CompactionMode.PROACTIVE, 100000));
        assertEquals(70000, CompactionMode.thresholdForMode(CompactionMode.SEMANTIC, 100000));
        assertEquals(100000, CompactionMode.thresholdForMode(null, 100000));
    }
}
