package com.ouisani.aios.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Partial-turn 持久化 + Notice 消息测试 —— 同包以访问 package-private
 * {@code persistPartialTurn} / {@code historySnapshot} / {@code historyText}。
 * <p>
 * 借鉴 OpenWorker {@code engine.py:331-352}：LLM 异常/中断时已流式输出的 partial response
 * 作为 assistant 消息持久化（不丢弃），error/interrupted 标记作为 display-only 的
 * {@code role:"notice"} 消息持久化（reload/快照存活，但 provider feed 剥离）。
 * <p>
 * 构造 {@code new QueryEngine(null, "test", "/tmp", List.of())} 安全：null sdk 时
 * {@code generateSummary} catch NPE 后回退截断；小测试不触发压缩（远低于 4000 字符预算）。
 */
class PartialTurnPersistenceTest {

    private static QueryEngine newEngine() {
        return new QueryEngine(null, "test", "/tmp", List.of());
    }

    /** partial 非空 → assistant 消息先入历史，notice 紧随其后（顺序契约）。 */
    @Test
    void partialTurnPersistedBeforeNotice() {
        QueryEngine engine = newEngine();
        engine.persistPartialTurn("partial text", "[notice:error] test error");

        List<HistoryCompressor.Message> messages = engine.historySnapshot();
        assertEquals(2, messages.size());
        assertEquals("assistant", messages.get(0).role());
        assertEquals("partial text", messages.get(0).content());
        assertEquals(HistoryCompressor.ROLE_NOTICE, messages.get(1).role());
        assertEquals("[notice:error] test error", messages.get(1).content());
    }

    /** partial 为空字符串 → 跳过 assistant 消息，仅持久化 notice。 */
    @Test
    void emptyPartialSkippedOnlyNoticePersisted() {
        QueryEngine engine = newEngine();
        engine.persistPartialTurn("", "[notice:interrupted] 用户中断");

        List<HistoryCompressor.Message> messages = engine.historySnapshot();
        assertEquals(1, messages.size());
        assertEquals(HistoryCompressor.ROLE_NOTICE, messages.get(0).role());
        assertEquals("[notice:interrupted] 用户中断", messages.get(0).content());
    }

    /** partial 为 null → 跳过 assistant 消息，仅持久化 notice。 */
    @Test
    void nullPartialSkippedOnlyNoticePersisted() {
        QueryEngine engine = newEngine();
        engine.persistPartialTurn(null, "[notice:error] boom");

        List<HistoryCompressor.Message> messages = engine.historySnapshot();
        assertEquals(1, messages.size());
        assertEquals(HistoryCompressor.ROLE_NOTICE, messages.get(0).role());
    }

    /** display-only 契约：notice 文本不出现在 provider feed（buildHistoryText）中。 */
    @Test
    void noticeStrippedFromHistoryText() {
        QueryEngine engine = newEngine();
        engine.persistPartialTurn("partial body", "[notice:error] SECRET_MARKER_DO_NOT_LEAK");

        String feed = engine.historyText();
        assertFalse(feed.contains("SECRET_MARKER_DO_NOT_LEAK"),
                "notice 必须从 provider feed 剥离");
        assertFalse(feed.contains("[notice:error]"),
                "notice 角色标记不得出现在 provider feed");
    }

    /** partial 作为 assistant 消息保留在 provider feed 中（未被剥离）。 */
    @Test
    void partialSurvivesInHistoryText() {
        QueryEngine engine = newEngine();
        engine.persistPartialTurn("KEEP_THIS_PARTIAL", "[notice:interrupted] stopped");

        String feed = engine.historyText();
        assertTrue(feed.contains("KEEP_THIS_PARTIAL"),
                "partial assistant 文本应保留在 provider feed");
        assertTrue(feed.contains("assistant"),
                "assistant 角色应出现在 provider feed");
    }
}
