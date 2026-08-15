package com.ouisani.aios.core.provenance;

import com.ouisani.aios.core.review.ReviewFinding;
import com.ouisani.aios.core.review.ReviewLedger;
import com.ouisani.aios.core.review.ReviewRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProvenanceQuery} 单元测试 — Phase 6 磁盘回读 + join + 去重。
 * <p>
 * 核心验证：把"数字是否可追溯"从 LLM 读文件判断变成 DAG 查询 —— jsonl 持久化后能跨 session 回读。
 * <p>
 * 测试策略：用显式文件路径重载（{@link ProvenanceQuery#traceByPath(String, Path, Path)}）模拟
 * "新 session 无内存缓冲"的跨 session 场景，避免静态全局状态干扰。
 */
class ProvenanceQueryTest {

    @TempDir
    Path tempDir;

    private Path provenanceFile;
    private Path reviewFile;

    @BeforeEach
    void setUp() {
        provenanceFile = tempDir.resolve("provenance.jsonl");
        reviewFile = tempDir.resolve("review.jsonl");
        // 同步设置静态全局状态（供 traceByPath/traceByAgent 无参重载测试用）
        ProvenanceHook.setProvenanceFile(provenanceFile);
        ProvenanceHook.setEnabled(true);
        ProvenanceHook.resetForTesting();
        ReviewLedger.setReviewFile(reviewFile);
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

    // ════════════════════════════════════════════════════════════════
    //  跨 session 磁盘回读（显式文件路径重载 — 模拟新 session 无缓冲）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("traceByPath 跨 session：写 jsonl → 新实例读回 → join by path")
    void traceByPath_diskRead_joinsByPath() {
        // 写 provenance（模拟上个 session 写入，落盘到 jsonl）
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_5");
        ProvenanceHook.CURRENT_SESSION_ID.set("sess_old");
        ProvenanceHook.onWrite("/out/survey.md", "v1 content", true);
        ProvenanceHook.onWrite("/out/survey.md", "v2 content", true);
        ProvenanceHook.CURRENT_AGENT_ID.remove();
        ProvenanceHook.CURRENT_SESSION_ID.remove();

        // 写 review（关联同一路径）
        ReviewLedger.append(new ReviewRecord(
                "/out/survey.md", "agent_5", "run_1", 1500L, "annotate", "FLAGGED",
                "number traceable", List.of(new ReviewFinding("medium", "/out/survey.md",
                        "claim mismatch", "says 42", "agent_5 v2 write")), false));

        // 清空内存缓冲 → 模拟新 session（只能从磁盘读）
        ProvenanceHook.resetForTesting();
        ReviewLedger.resetForTesting();

        TraceabilityReport report = ProvenanceQuery.traceByPath(
                "/out/survey.md", provenanceFile, reviewFile);

        assertEquals("/out/survey.md", report.key());
        assertEquals(2, report.provenance().size(), "应读回 2 个版本记录");
        // 按 ts 升序
        assertEquals(1, report.provenance().get(0).version());
        assertEquals(2, report.provenance().get(1).version());
        assertEquals("agent_5", report.provenance().get(0).agentId());

        assertEquals(1, report.reviews().size(), "应读回 1 条 review");
        ReviewRecord rev = report.reviews().get(0);
        assertEquals("FLAGGED", rev.outcome());
        assertEquals(1, rev.findings().size());
        assertEquals("says 42", rev.findings().get(0).claim());
    }

    @Test
    @DisplayName("traceByAgent 跨 session：按 agentId 读回 provenance + review")
    void traceByAgent_diskRead_filtersByAgent() {
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_A");
        ProvenanceHook.onWrite("/out/a1.md", "x", true);
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_B");
        ProvenanceHook.onWrite("/out/b1.md", "y", true);
        ProvenanceHook.CURRENT_AGENT_ID.remove();

        ReviewLedger.append(new ReviewRecord(
                "/out/a1.md", "agent_A", "run_A", 100L, "annotate", "CLEAN", "ok", List.of(), false));
        ReviewLedger.append(new ReviewRecord(
                "/out/b1.md", "agent_B", "run_B", 200L, "annotate", "CLEAN", "ok", List.of(), false));

        // 清空缓冲 → 纯磁盘读
        ProvenanceHook.resetForTesting();
        ReviewLedger.resetForTesting();

        TraceabilityReport report = ProvenanceQuery.traceByAgent(
                "agent_A", provenanceFile, reviewFile);

        assertEquals(1, report.provenance().size());
        assertEquals("/out/a1.md", report.provenance().get(0).path());
        assertEquals(1, report.reviews().size());
        assertEquals("agent_A", report.reviews().get(0).agentId());
    }

    @Test
    @DisplayName("文件不存在 → 空报告（不抛）")
    void traceByPath_missingFiles_returnsEmpty() {
        Path missingProv = tempDir.resolve("nonexistent-prov.jsonl");
        Path missingRev = tempDir.resolve("nonexistent-rev.jsonl");
        TraceabilityReport report = ProvenanceQuery.traceByPath(
                "/out/ghost.md", missingProv, missingRev);
        assertTrue(report.isEmpty());
        assertTrue(report.provenance().isEmpty());
        assertTrue(report.reviews().isEmpty());
    }

    @Test
    @DisplayName("不可解析行 → 跳过，继续处理后续行（best-effort）")
    void traceByPath_unparseableLines_skipped() throws Exception {
        // 手写混合 jsonl：好行 + 坏行 + 好行
        Files.writeString(provenanceFile,
                "{\"path\":\"/out/x.md\",\"version\":1,\"ts\":100,\"tool\":\"write\",\"content\":\"a\",\"agentId\":\"agent_x\",\"sessionId\":\"s\"}\n" +
                "{this is not valid json}\n" +
                "{\"path\":\"/out/x.md\",\"version\":2,\"ts\":200,\"tool\":\"edit\",\"content\":\"b\",\"agentId\":\"agent_x\",\"sessionId\":\"s\"}\n");
        Files.writeString(reviewFile,
                "{broken review line}\n" +
                "{\"targetPath\":\"/out/x.md\",\"agentId\":\"agent_x\",\"runId\":\"r\",\"ts\":150,\"level\":\"annotate\",\"outcome\":\"CLEAN\",\"summary\":\"ok\",\"findings\":[],\"deterministicForced\":false}\n");

        TraceabilityReport report = ProvenanceQuery.traceByPath(
                "/out/x.md", provenanceFile, reviewFile);

        assertEquals(2, report.provenance().size(), "坏行被跳过，2 个好行读回");
        assertEquals(1, report.reviews().size(), "坏 review 行被跳过，1 个好行读回");
    }

    @Test
    @DisplayName("空/null path → 空报告")
    void traceByPath_emptyPath_returnsEmpty() {
        TraceabilityReport r1 = ProvenanceQuery.traceByPath("", provenanceFile, reviewFile);
        TraceabilityReport r2 = ProvenanceQuery.traceByPath(null, provenanceFile, reviewFile);
        assertTrue(r1.isEmpty());
        assertTrue(r2.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  合并 + 去重（内存缓冲 ∪ 磁盘）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无参 traceByPath：内存缓冲 + 磁盘合并去重（同记录不重复）")
    void traceByPath_mergesBufferAndDisk_dedups() {
        // 写一条记录 → 同时进缓冲 + 磁盘
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_dup");
        ProvenanceHook.onWrite("/out/dup.md", "v1", true);
        ProvenanceHook.CURRENT_AGENT_ID.remove();

        ReviewLedger.append(new ReviewRecord(
                "/out/dup.md", "agent_dup", "run_dup", 1000L, "annotate", "CLEAN",
                "s", List.of(), false));

        // 无参重载：合并缓冲 + 磁盘 → 去重
        TraceabilityReport report = ProvenanceQuery.traceByPath("/out/dup.md");
        assertEquals(1, report.provenance().size(), "缓冲+磁盘同一记录应去重为 1");
        assertEquals(1, report.reviews().size(), "缓冲+磁盘同一 review 应去重为 1");
    }

    @Test
    @DisplayName("合并后按 ts 升序排序")
    void merge_sortsByTsAscending() {
        // 故意乱序写入 ts（通过不同 path 避免版本递增干扰）
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_sort");
        ProvenanceHook.onWrite("/out/s3.md", "third", true);
        ProvenanceHook.onWrite("/out/s1.md", "first", true);
        ProvenanceHook.onWrite("/out/s2.md", "second", true);
        ProvenanceHook.CURRENT_AGENT_ID.remove();

        TraceabilityReport report = ProvenanceQuery.traceByAgent("agent_sort");
        assertEquals(3, report.provenance().size());
        // ts 应递增
        long prev = -1;
        for (ProvenanceRecord r : report.provenance()) {
            assertTrue(r.ts() >= prev, "应按 ts 升序");
            prev = r.ts();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  空报告辅助方法
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TraceabilityReport.empty + isEmpty 语义")
    void traceabilityReport_empty_isEmpty() {
        TraceabilityReport empty = TraceabilityReport.empty("key");
        assertEquals("key", empty.key());
        assertTrue(empty.isEmpty());
        TraceabilityReport nonEmpty = new TraceabilityReport(
                "k", List.of(new ProvenanceRecord("/p", 1, 1L, "write", "c", "a", "s")), List.of());
        assertFalse(nonEmpty.isEmpty());
    }

    @Test
    @DisplayName("TraceabilityReport null 字段降级")
    void traceabilityReport_nullFields_defaults() {
        TraceabilityReport r = new TraceabilityReport(null, null, null);
        assertEquals("", r.key());
        assertTrue(r.provenance().isEmpty());
        assertTrue(r.reviews().isEmpty());
    }
}
