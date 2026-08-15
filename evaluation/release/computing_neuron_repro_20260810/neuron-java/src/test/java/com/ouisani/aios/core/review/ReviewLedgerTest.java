package com.ouisani.aios.core.review;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReviewLedger} 单元测试 — 镜像 {@code ProvenanceHookTest} 范式。
 * <p>
 * 覆盖：append 持久化（内存缓冲 + JSONL 文件）、listByAgent/listByTargetPath 过滤、
 * 禁用/null/异常的 best-effort 行为、JSON 转义。
 */
class ReviewLedgerTest {

    @TempDir
    Path tempDir;

    private Path reviewFile;

    @BeforeEach
    void setUp() {
        reviewFile = tempDir.resolve("review.jsonl");
        ReviewLedger.setReviewFile(reviewFile);
        ReviewLedger.setEnabled(true);
        ReviewLedger.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        ReviewLedger.resetForTesting();
        ReviewLedger.setEnabled(true);
        ReviewLedger.setReviewFile(Path.of(".aios", "review.jsonl"));
    }

    private static ReviewRecord sample(String agentId, String targetPath, String outcome) {
        return new ReviewRecord(
                targetPath, agentId, "run_1", 1000L, "annotate",
                outcome, "summary for " + outcome,
                List.of(new ReviewFinding("low", targetPath, "minor issue")),
                false
        );
    }

    @Test
    @DisplayName("append 持久化到内存缓冲 + JSONL 文件")
    void append_persistsToMemoryBufferAndJsonl() throws Exception {
        ReviewLedger.append(sample("agent_a", "/out/a.md", "CLEAN"));
        ReviewLedger.append(sample("agent_a", "/out/b.md", "FLAGGED"));

        List<ReviewRecord> records = ReviewLedger.listByAgent("agent_a");
        assertEquals(2, records.size());

        List<String> lines = Files.readAllLines(reviewFile);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"outcome\":\"CLEAN\""));
        assertTrue(lines.get(0).contains("\"agentId\":\"agent_a\""));
        assertTrue(lines.get(0).contains("\"deterministicForced\":false"));
        assertTrue(lines.get(0).contains("\"targetPath\":\"/out/a.md\""));
        assertTrue(lines.get(1).contains("\"outcome\":\"FLAGGED\""));
    }

    @Test
    @DisplayName("listByAgent 按 agentId 过滤")
    void listByAgent_filtersByAgentId() {
        ReviewLedger.append(sample("agent_A", "/out/a1.md", "CLEAN"));
        ReviewLedger.append(sample("agent_B", "/out/b1.md", "CLEAN"));

        assertEquals(1, ReviewLedger.listByAgent("agent_A").size());
        assertEquals(1, ReviewLedger.listByAgent("agent_B").size());
        assertTrue(ReviewLedger.listByAgent("agent_A").get(0).targetPath().contains("a1.md"));
    }

    @Test
    @DisplayName("listByTargetPath 按 targetPath 过滤")
    void listByTargetPath_filtersByTargetPath() {
        ReviewLedger.append(sample("agent_a", "/out/shared.md", "CLEAN"));
        ReviewLedger.append(sample("agent_b", "/out/shared.md", "BLOCKING"));
        ReviewLedger.append(sample("agent_c", "/out/other.md", "CLEAN"));

        List<ReviewRecord> shared = ReviewLedger.listByTargetPath("/out/shared.md");
        assertEquals(2, shared.size());
        assertEquals(1, ReviewLedger.listByTargetPath("/out/other.md").size());
    }

    @Test
    @DisplayName("禁用时 append 不持久化")
    void append_disabled_doesNotPersist() {
        ReviewLedger.setEnabled(false);
        ReviewLedger.append(sample("agent_x", "/out/x.md", "CLEAN"));

        assertTrue(ReviewLedger.listByAgent("agent_x").isEmpty());
        assertFalse(Files.exists(reviewFile));
    }

    @Test
    @DisplayName("append null 记录不抛出")
    void append_nullRecord_doesNotThrow() {
        assertDoesNotThrow(() -> ReviewLedger.append(null));
        assertTrue(ReviewLedger.listByAgent("anyone").isEmpty());
    }

    @Test
    @DisplayName("best-effort: 文件写入异常不抛出")
    void append_fileException_doesNotThrow() {
        ReviewLedger.setReviewFile(Path.of("/nonexistent-root-dir/cannot-create/review.jsonl"));
        // 内存缓冲仍写入（appendRecord 先写缓冲后写文件，文件失败被 catch）
        assertDoesNotThrow(() -> ReviewLedger.append(sample("agent_y", "/out/y.md", "CLEAN")));
        // 缓冲里有记录（appendRecord 缓冲写在文件写之前）
        assertEquals(1, ReviewLedger.listByAgent("agent_y").size());
    }

    @Test
    @DisplayName("ReviewRecord.toJsonLine 转义特殊字符 + 单行")
    void reviewRecord_toJsonLine_escapesSpecialChars() {
        ReviewRecord r = new ReviewRecord(
                "/out/quote.md", "agent_q", "run_q", 2000L, "soft", "BLOCKING",
                "summary with \"quote\" and \n newline \\ backslash",
                List.of(new ReviewFinding("high", null, "msg with \"quotes\"")),
                true
        );
        String json = r.toJsonLine();
        assertTrue(json.contains("\\\"quote\\\""), "双引号应被转义");
        assertTrue(json.contains("\\n"), "换行应被转义");
        assertTrue(json.contains("\\\\"), "反斜杠应被转义");
        assertFalse(json.contains("\n"), "JSONL 必须单行，无裸换行");
        assertTrue(json.contains("\"deterministicForced\":true"));
    }

    @Test
    @DisplayName("ReviewRecord null 字段处理")
    void reviewRecord_nullFields_handled() {
        ReviewRecord r = new ReviewRecord(
                null, null, null, 0L, null, null, null, null, false
        );
        assertEquals("", r.targetPath());
        assertEquals("", r.agentId());
        assertEquals("annotate", r.level());
        assertEquals("INCONCLUSIVE", r.outcome());
        assertTrue(r.findings().isEmpty());
        assertEquals("", r.summary());
        String json = r.toJsonLine();
        assertTrue(json.contains("\"findings\":[]"));
        assertTrue(json.contains("\"summary\":\"\""));
        assertTrue(json.contains("\"targetPath\":\"\""));
    }

    @Test
    @DisplayName("内存缓冲 FIFO 淘汰 — 超容量后旧记录被清除")
    void buffer_fifo_eviction() {
        // 写入超过 1024 条，验证旧记录被淘汰、新记录保留
        for (int i = 0; i < 1100; i++) {
            ReviewLedger.append(sample("agent_fifo", "/out/" + i + ".md", "CLEAN"));
        }
        List<ReviewRecord> records = ReviewLedger.listByAgent("agent_fifo");
        assertTrue(records.size() <= 1024, "缓冲不应超过容量");
        assertTrue(records.size() > 700, "应保留大部分较新记录");
        // 最早写入的应被淘汰
        assertEquals(0, ReviewLedger.listByTargetPath("/out/0.md").size());
        // 最新的应保留
        assertTrue(ReviewLedger.listByTargetPath("/out/1099.md").size() == 1);
    }
}
