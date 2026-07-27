package com.ouisani.aios.core.observability;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UpstreamMetaQuery} 单元测试 — 跨 session 磁盘回读 + 合并去重 + 聚合统计。
 * <p>
 * 仿 {@code ProvenanceQueryTest} 范式：用显式文件路径重载
 * （{@link UpstreamMetaQuery#listByUpstream(String, Path)}）模拟"新 session 无内存缓冲"
 * 的跨 session 场景，避免静态全局状态干扰。
 *
 * <h3>核心验证矩阵</h3>
 * <ul>
 *   <li>跨 session 磁盘回读（4 个维度：upstream / agent / session / timeWindow）</li>
 *   <li>合并去重（内存缓冲 ∪ 磁盘，同记录不重复）</li>
 *   <li>聚合统计（avg/p50/p99/errorRate/bytes）</li>
 *   <li>Best-effort：文件不存在 / 坏行 / 空输入 不抛</li>
 * </ul>
 */
class UpstreamMetaQueryTest {

    @TempDir
    Path tempDir;

    private Path upstreamMetaFile;

    @BeforeEach
    void setUp() {
        upstreamMetaFile = tempDir.resolve("upstream_meta.jsonl");
        // 同步设置静态全局状态（供无参重载测试用）
        UpstreamMetaHook.setUpstreamMetaFile(upstreamMetaFile);
        UpstreamMetaHook.setEnabled(true);
        UpstreamMetaHook.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        UpstreamMetaHook.resetForTesting();
        UpstreamMetaHook.setEnabled(true);
        UpstreamMetaHook.setUpstreamMetaFile(Path.of(".aios", "upstream_meta.jsonl"));
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助 — 构造 UpstreamMeta（避免每个测试都写 9 个字段）
    // ════════════════════════════════════════════════════════════════

    private static UpstreamMeta call(String name, long latency, int status, long bytes,
                                     String errCode, long ts, String agentId, String sessionId) {
        return new UpstreamMeta(name, latency, status, null, bytes, errCode, ts, agentId, sessionId);
    }

    // ════════════════════════════════════════════════════════════════
    //  跨 session 磁盘回读（显式文件路径重载 — 模拟新 session 无缓冲）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listByUpstream 跨 session：写 jsonl → 新实例读回 → filter by upstream_name")
    void listByUpstream_diskRead_filtersByUpstream() {
        // 写两条 llm.think + 一条 tool.web_search（模拟上个 session 落盘）
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 842, 200, 1536, null, 1000L, "agent_5", "sess_old"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 920, 200, 1600, null, 2000L, "agent_5", "sess_old"));
        UpstreamMetaHook.onUpstreamCall(call("tool.web_search", 50, 200, 4096, null, 3000L, "agent_5", "sess_old"));

        // 清空内存缓冲 → 模拟新 session（只能从磁盘读）
        UpstreamMetaHook.resetForTesting();

        List<UpstreamMeta> calls = UpstreamMetaQuery.listByUpstream("llm.think", upstreamMetaFile);

        assertEquals(2, calls.size(), "应读回 2 条 llm.think 调用");
        // 按 ts 升序
        assertEquals(1000L, calls.get(0).ts());
        assertEquals(2000L, calls.get(1).ts());
        assertEquals("agent_5", calls.get(0).agentId());
        assertEquals(842, calls.get(0).upstreamDurationMs());
    }

    @Test
    @DisplayName("listByAgent 跨 session：按 agentId 读回调用记录")
    void listByAgent_diskRead_filtersByAgent() {
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 800, 200, 1024, null, 100L, "agent_A", "s1"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 700, 200, 1024, null, 200L, "agent_B", "s1"));

        UpstreamMetaHook.resetForTesting();

        List<UpstreamMeta> calls = UpstreamMetaQuery.listByAgent("agent_A", upstreamMetaFile);

        assertEquals(1, calls.size());
        assertEquals("agent_A", calls.get(0).agentId());
        assertEquals(100L, calls.get(0).ts());
    }

    @Test
    @DisplayName("listBySession 跨 session：按 sessionId 读回调用记录")
    void listBySession_diskRead_filtersBySession() {
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 800, 200, 1024, null, 100L, "agent_5", "sess_x"));
        UpstreamMetaHook.onUpstreamCall(call("tool.search", 50, 200, 1024, null, 200L, "agent_5", "sess_y"));

        UpstreamMetaHook.resetForTesting();

        List<UpstreamMeta> calls = UpstreamMetaQuery.listBySession("sess_x", upstreamMetaFile);

        assertEquals(1, calls.size());
        assertEquals("sess_x", calls.get(0).sessionId());
        assertEquals("llm.think", calls.get(0).upstreamName());
    }

    @Test
    @DisplayName("listByTimeWindow 跨 session：按时间窗口读回调用记录")
    void listByTimeWindow_diskRead_filtersByTimeWindow() {
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 800, 200, 1024, null, 1000L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 800, 200, 1024, null, 1500L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 800, 200, 1024, null, 2500L, "a", "s"));

        UpstreamMetaHook.resetForTesting();

        // 窗口 [1000, 2000) — 应包含 ts=1000 和 ts=1500，不含 ts=2500
        List<UpstreamMeta> calls = UpstreamMetaQuery.listByTimeWindow(1000L, 2000L, upstreamMetaFile);

        assertEquals(2, calls.size());
        assertEquals(1000L, calls.get(0).ts());
        assertEquals(1500L, calls.get(1).ts());
    }

    // ════════════════════════════════════════════════════════════════
    //  Best-effort：缺文件 / 坏行 / 空输入
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("文件不存在 → 返回空列表（不抛）")
    void listByUpstream_missingFile_returnsEmpty() {
        Path missing = tempDir.resolve("nonexistent.jsonl");
        List<UpstreamMeta> calls = UpstreamMetaQuery.listByUpstream("llm.think", missing);
        assertTrue(calls.isEmpty());
        UpstreamStats stats = UpstreamMetaQuery.statsByUpstream("llm.think", missing);
        assertEquals(0L, stats.callCount());
        assertEquals(0.0, stats.errorRate());
    }

    @Test
    @DisplayName("不可解析行 → 跳过，继续处理后续行（best-effort）")
    void listByUpstream_unparseableLines_skipped() throws Exception {
        // 手写混合 jsonl：好行 + 坏行 + 好行
        Files.writeString(upstreamMetaFile,
                "{\"upstream_name\":\"llm.think\",\"upstream_duration_ms\":842,\"upstream_status_code\":200,"
                        + "\"upstream_cost_units\":null,\"upstream_bytes\":1536,\"error_code\":null,"
                        + "\"ts\":1000,\"agentId\":\"agent_5\",\"sessionId\":\"sess\"}\n"
                        + "{this is not valid json}\n"
                        + "{\"upstream_name\":\"llm.think\",\"upstream_duration_ms\":920,\"upstream_status_code\":200,"
                        + "\"upstream_cost_units\":null,\"upstream_bytes\":1600,\"error_code\":null,"
                        + "\"ts\":2000,\"agentId\":\"agent_5\",\"sessionId\":\"sess\"}\n");

        List<UpstreamMeta> calls = UpstreamMetaQuery.listByUpstream("llm.think", upstreamMetaFile);

        assertEquals(2, calls.size(), "坏行被跳过，2 个好行读回");
        assertEquals(1000L, calls.get(0).ts());
        assertEquals(2000L, calls.get(1).ts());
    }

    @Test
    @DisplayName("空/null 输入 → 返回空列表")
    void listByUpstream_emptyOrNullInput_returnsEmpty() {
        assertTrue(UpstreamMetaQuery.listByUpstream("", upstreamMetaFile).isEmpty());
        assertTrue(UpstreamMetaQuery.listByUpstream(null, upstreamMetaFile).isEmpty());
        assertTrue(UpstreamMetaQuery.listByAgent("", upstreamMetaFile).isEmpty());
        assertTrue(UpstreamMetaQuery.listBySession(null, upstreamMetaFile).isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  合并 + 去重（无参重载：内存缓冲 + 磁盘）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无参 listByUpstream：内存缓冲 + 磁盘合并去重（同记录不重复）")
    void listByUpstream_mergesBufferAndDisk_dedups() {
        UpstreamMeta m = call("llm.think", 842, 200, 1536, null, 1000L, "agent_dup", "sess_dup");
        UpstreamMetaHook.onUpstreamCall(m); // 同时进缓冲 + 磁盘

        // 无参重载：合并缓冲 + 磁盘 → 去重
        List<UpstreamMeta> calls = UpstreamMetaQuery.listByUpstream("llm.think");

        assertEquals(1, calls.size(), "缓冲+磁盘同一记录应去重为 1");
    }

    @Test
    @DisplayName("合并后按 ts 升序排序")
    void merge_sortsByTsAscending() {
        // 故意乱序写入（ts 递减）
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 100, 200, 1, null, 3000L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 100, 200, 1, null, 1000L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 100, 200, 1, null, 2000L, "a", "s"));

        List<UpstreamMeta> calls = UpstreamMetaQuery.listByUpstream("llm.think");

        assertEquals(3, calls.size());
        long prev = -1;
        for (UpstreamMeta m : calls) {
            assertTrue(m.ts() >= prev, "应按 ts 升序");
            prev = m.ts();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  聚合统计 UpstreamStats
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("statsByUpstream：avg/min/max/p50/p99/errorRate/bytes 聚合")
    void statsByUpstream_aggregatesCorrectly() {
        // 5 次成功 + 2 次失败 = 7 次调用
        // latency 序列：[100, 200, 300, 400, 500, 600, 700]
        // bytes 序列：[1000, 2000, 3000, 4000, 5000, 0, 0]（失败时 bytes=0）
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 100, 200, 1000, null, 1000L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 200, 200, 2000, null, 1100L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 300, 200, 3000, null, 1200L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 400, 200, 4000, null, 1300L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 500, 200, 5000, null, 1400L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 600, 500, 0, "ERR:FAIL", 1500L, "a", "s"));
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 700, 500, 0, "ERR:FAIL", 1600L, "a", "s"));

        UpstreamStats stats = UpstreamMetaQuery.statsByUpstream("llm.think");

        assertEquals("llm.think", stats.upstreamName());
        assertEquals(7L, stats.callCount());
        assertEquals(5L, stats.successCount());
        assertEquals(2L, stats.errorCount());
        assertEquals(2.0 / 7.0, stats.errorRate(), 1e-9);

        // avg = (100+200+300+400+500+600+700) / 7 = 2800/7 = 400
        assertEquals(400L, stats.avgLatencyMs());
        assertEquals(100L, stats.minLatencyMs());
        assertEquals(700L, stats.maxLatencyMs());

        // p50：nearest-rank @ 0.50 → idx = ceil(0.50*7) - 1 = ceil(3.5) - 1 = 4 - 1 = 3
        //     排序后 [100,200,300,400,500,600,700] → idx 3 = 400
        assertEquals(400L, stats.p50LatencyMs());
        // p99：nearest-rank @ 0.99 → idx = ceil(0.99*7) - 1 = ceil(6.93) - 1 = 7 - 1 = 6
        //     → idx 6 = 700
        assertEquals(700L, stats.p99LatencyMs());

        // totalBytes = 1000+2000+3000+4000+5000 = 15000
        assertEquals(15000L, stats.totalBytes());
    }

    @Test
    @DisplayName("statsByUpstream 空输入 → empty() 不抛")
    void statsByUpstream_emptyInput_returnsEmpty() {
        UpstreamStats stats = UpstreamMetaQuery.statsByUpstream("llm.think", upstreamMetaFile);
        assertEquals("llm.think", stats.upstreamName());
        assertEquals(0L, stats.callCount());
        assertEquals(0L, stats.successCount());
        assertEquals(0L, stats.errorCount());
        assertEquals(0.0, stats.errorRate());
        assertEquals(0L, stats.avgLatencyMs());
        assertEquals(0L, stats.p50LatencyMs());
        assertEquals(0L, stats.p99LatencyMs());
        assertEquals(0L, stats.totalBytes());
    }

    @Test
    @DisplayName("UpstreamStats.from(null) → empty()，不抛 NPE")
    void upstreamStats_fromNull_returnsEmpty() {
        UpstreamStats s1 = UpstreamStats.from(null, "x");
        UpstreamStats s2 = UpstreamStats.from(List.of(), "x");
        assertEquals(0L, s1.callCount());
        assertEquals(0L, s2.callCount());
        assertEquals("x", s1.upstreamName());
    }

    @Test
    @DisplayName("UpstreamStats.from 单条记录 → min=max=avg=p50=p99")
    void upstreamStats_singleRecord_percentilesEqual() {
        UpstreamStats stats = UpstreamStats.from(List.of(
                call("llm.think", 500, 200, 100, null, 1L, "a", "s")
        ), "llm.think");

        assertEquals(1L, stats.callCount());
        assertEquals(500L, stats.minLatencyMs());
        assertEquals(500L, stats.maxLatencyMs());
        assertEquals(500L, stats.avgLatencyMs());
        assertEquals(500L, stats.p50LatencyMs());
        assertEquals(500L, stats.p99LatencyMs());
        assertEquals(0.0, stats.errorRate(), 1e-9);
    }

    // ════════════════════════════════════════════════════════════════
    //  DAG 联合查询（与 ProvenanceQuery 互补）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listByAgent + ProvenanceQuery.traceByAgent 可联合（同 agentId + ts 域）")
    void listByAgent_canJoinWithProvenanceQuery() {
        // 模拟 agent_5 一次 LLM 调用 + 一次 artifact 写入
        UpstreamMetaHook.onUpstreamCall(call("llm.think", 842, 200, 1536, null, 1000L, "agent_5", "sess_x"));

        List<UpstreamMeta> calls = UpstreamMetaQuery.listByAgent("agent_5");

        assertEquals(1, calls.size());
        assertEquals("agent_5", calls.get(0).agentId());
        // 验证可联合的键：agentId + sessionId + ts 都暴露
        assertNotNull(calls.get(0).sessionId());
        assertTrue(calls.get(0).ts() > 0);
    }
}
