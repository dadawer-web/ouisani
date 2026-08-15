package com.ouisani.aios.core.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SubagentResultFormatter} 单元测试 —— 纯字符串变换，无 VFS/syscall/SDK 依赖。
 *
 * <p>覆盖：短结果（原文入 body）/ 长结果（截断 head+tail+省略计数）/ null / 空 /
 * task 属性存在与省略 / 引号转义 / 边界长度。
 */
class SubagentResultFormatterTest {

    @Test
    void shortResult_wrappedUntruncated() {
        String out = SubagentResultFormatter.format("sub_123", "summarize file", "hello world");
        assertTrue(out.startsWith("<task_result agent=\"sub_123\" task=\"summarize file\">\n"), "open tag with agent+task attrs");
        assertTrue(out.endsWith("\n</task_result>"), "closing tag");
        assertTrue(out.contains("hello world"), "body preserved verbatim");
        assertFalse(out.contains("chars omitted"), "no truncation marker for short result");
    }

    @Test
    void longResult_truncatedToHeadAndTail() {
        // 构造一个明显超阈值的长结果（HEAD+TAIL+大量中间字符）
        String head = "H".repeat(SubagentResultFormatter.HEAD);
        String middle = "M".repeat(5000);
        String tail = "T".repeat(SubagentResultFormatter.TAIL);
        String result = head + middle + tail;

        String out = SubagentResultFormatter.format("sub_long", "big task", result);

        // head 完整保留
        assertTrue(out.contains(head), "head preserved");
        // tail 完整保留
        assertTrue(out.contains(tail), "tail preserved");
        // 中间被省略
        assertFalse(out.contains("M".repeat(100)), "middle bulk removed");
        // 省略计数标记
        int expectedOmitted = result.length() - SubagentResultFormatter.HEAD - SubagentResultFormatter.TAIL;
        assertTrue(out.contains("[... " + expectedOmitted + " chars omitted"), "omitted-count marker present");
        assertTrue(out.contains("prevent parent context pollution"), "rationale in marker");
    }

    @Test
    void nullResult_emitsNoOutput() {
        String out = SubagentResultFormatter.format("sub_null", "do nothing", null);
        assertTrue(out.contains("[no output]"), "null result -> [no output]");
        assertTrue(out.startsWith("<task_result agent=\"sub_null\""), "still wrapped");
    }

    @Test
    void emptyResult_emitsNoOutput() {
        String out = SubagentResultFormatter.format("sub_empty", "", "");
        // 空 description -> task 属性省略
        assertFalse(out.contains("task="), "blank description omits task attr");
        assertTrue(out.contains("[no output]"), "empty result -> [no output]");
    }

    @Test
    void blankDescription_omitsTaskAttribute() {
        String out = SubagentResultFormatter.format("sub_x", "   ", "ok");
        assertFalse(out.contains("task="), "whitespace-only description omits task attr");
        assertTrue(out.contains("ok"));
    }

    @Test
    void quotesInAgentIdAndDescription_escaped() {
        String out = SubagentResultFormatter.format("sub_\"weird\"", "task with \"quotes\" and \\backslash", "body");
        // agent 属性值中的引号被转义，不会提前闭合属性
        assertTrue(out.contains("agent=\"sub_\\\"weird\\\"\""), "agent id quotes escaped");
        assertTrue(out.contains("task=\"task with \\\"quotes\\\" and \\\\backslash\""), "description quotes+backslash escaped");
    }

    @Test
    void backslashInResult_notEscaped_bodyPreservedVerbatim() {
        // body 内容（result）不做转义，原样保留（与 OpenScience 一致：只截断不转义正文）
        String out = SubagentResultFormatter.format("sub_b", "t", "line\\with\\backslash and \"quotes\"");
        assertTrue(out.contains("line\\with\\backslash and \"quotes\""), "body backslash/quotes preserved verbatim");
    }

    @Test
    void boundaryLength_atThreshold_notTruncated() {
        // 长度 == THRESHOLD 应原文保留（边界：<= THRESHOLD 不截断）
        String result = "A".repeat(SubagentResultFormatter.THRESHOLD);
        String out = SubagentResultFormatter.format("sub_boundary", "edge", result);
        assertFalse(out.contains("chars omitted"), "at-threshold not truncated");
        // body 中包含全部 A
        long aCount = out.chars().filter(c -> c == 'A').count();
        assertEquals(SubagentResultFormatter.THRESHOLD, aCount, "all chars preserved at boundary");
    }

    @Test
    void boundaryLength_justOverThreshold_truncated() {
        // 长度 == THRESHOLD + 1 应截断（omitted = total - HEAD - TAIL）
        String result = "A".repeat(SubagentResultFormatter.THRESHOLD + 1);
        String out = SubagentResultFormatter.format("sub_over", "edge", result);
        assertTrue(out.contains("chars omitted"), "over-threshold truncated");
        int expectedOmitted = result.length() - SubagentResultFormatter.HEAD - SubagentResultFormatter.TAIL;
        assertTrue(out.contains("[... " + expectedOmitted + " chars omitted"),
                "omitted count = total - head - tail");
    }
}
