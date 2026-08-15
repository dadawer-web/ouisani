package com.ouisani.aios.core.observability;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpstreamMetaHook 单元测试 — 验证落盘 + 内存缓冲 + 查询。
 * <p>
 * 仿 {@code ProvenanceHookTest} 范式：@TempDir 重定向 + resetForTesting
 * 清静态缓冲 + setEnabled 控制。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>onUpstreamCall 追加 jsonl + 调用计数递增</li>
 *   <li>禁用时不记录</li>
 *   <li>null meta 不记录（best-effort 不抛）</li>
 *   <li>listByUpstream / listByAgent / listByTimeWindow 查询</li>
 *   <li>JSONL 文件持久化 + 格式正确</li>
 *   <li>best-effort：异常不抛出</li>
 *   <li>resetForTesting 清缓冲但不清磁盘</li>
 *   <li>FIFO 淘汰（buffer 满 1024 后移除最旧 1/4）</li>
 * </ul>
 */
class UpstreamMetaHookTest {

    @TempDir
    Path tempDir;

    private Path metaFile;

    @BeforeEach
    void setUp() {
        metaFile = tempDir.resolve("upstream_meta.jsonl");
        UpstreamMetaHook.setUpstreamMetaFile(metaFile);
        UpstreamMetaHook.setEnabled(true);
        UpstreamMetaHook.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        UpstreamMetaHook.resetForTesting();
        UpstreamMetaHook.setEnabled(true);
    }

    private UpstreamMeta sampleMeta(String name, long ts, String agentId) {
        return new UpstreamMeta(name, 100L, 200, null, 1024L, null, ts, agentId, "sess_test");
    }

    @Test
    @DisplayName("onUpstreamCall 追加记录 + 调用计数递增")
    void onUpstreamCall_appendsRecordAndIncrementsCounter() throws Exception {
        UpstreamMeta m1 = sampleMeta("llm.think", 1000L, "agent_5");
        UpstreamMeta m2 = sampleMeta("llm.think", 2000L, "agent_5");

        UpstreamMetaHook.onUpstreamCall(m1);
        UpstreamMetaHook.onUpstreamCall(m2);

        List<UpstreamMeta> calls = UpstreamMetaHook.listByUpstream("llm.think");
        assertEquals(2, calls.size());
        assertEquals(2, UpstreamMetaHook.callCount("llm.think"));
        assertEquals(1000L, calls.get(0).ts());
        assertEquals(2000L, calls.get(1).ts());
    }

    @Test
    @DisplayName("onUpstreamCall 同时持久化到 JSONL 文件")
    void onUpstreamCall_persistsToJsonlFile() throws Exception {
        UpstreamMeta m = new UpstreamMeta(
                "storage.write", 5L, 500, null, 0L, "ERR:FAIL",
                1784592000000L, "agent_test", "sess_test"
        );

        UpstreamMetaHook.onUpstreamCall(m);

        assertTrue(Files.exists(metaFile));
        List<String> lines = Files.readAllLines(metaFile);
        assertEquals(1, lines.size());

        UpstreamMeta restored = UpstreamMeta.fromJsonLine(lines.get(0));
        assertNotNull(restored);
        assertEquals("storage.write", restored.upstreamName());
        assertEquals(500, restored.upstreamStatusCode());
        assertEquals("ERR:FAIL", restored.errorCode());
    }

    @Test
    @DisplayName("禁用时 onUpstreamCall 不记录")
    void onUpstreamCall_disabled_doesNotRecord() {
        UpstreamMetaHook.setEnabled(false);
        UpstreamMeta m = sampleMeta("disabled.test", 1000L, "agent_test");

        UpstreamMetaHook.onUpstreamCall(m);

        assertTrue(UpstreamMetaHook.listByUpstream("disabled.test").isEmpty());
        assertEquals(0, UpstreamMetaHook.callCount("disabled.test"));
    }

    @Test
    @DisplayName("null meta 不记录（best-effort 不抛）")
    void onUpstreamCall_nullMeta_doesNotRecord() {
        UpstreamMetaHook.onUpstreamCall(null);

        // 无异常抛出即通过
        // 验证：内存缓冲仍为空
        assertTrue(UpstreamMetaHook.listByAgent("any").isEmpty());
    }

    @Test
    @DisplayName("listByAgent 按 agentId 查询")
    void listByAgent_filtersByAgentId() {
        UpstreamMeta m1 = sampleMeta("llm.think", 1000L, "agent_A");
        UpstreamMeta m2 = sampleMeta("storage.write", 2000L, "agent_B");
        UpstreamMeta m3 = sampleMeta("llm.think", 3000L, "agent_A");

        UpstreamMetaHook.onUpstreamCall(m1);
        UpstreamMetaHook.onUpstreamCall(m2);
        UpstreamMetaHook.onUpstreamCall(m3);

        List<UpstreamMeta> agentACalls = UpstreamMetaHook.listByAgent("agent_A");
        assertEquals(2, agentACalls.size());
        assertEquals("llm.think", agentACalls.get(0).upstreamName());
        assertEquals("llm.think", agentACalls.get(1).upstreamName());

        List<UpstreamMeta> agentBCalls = UpstreamMetaHook.listByAgent("agent_B");
        assertEquals(1, agentBCalls.size());
        assertEquals("storage.write", agentBCalls.get(0).upstreamName());
    }

    @Test
    @DisplayName("listByTimeWindow 按时间窗口查询")
    void listByTimeWindow_filtersByTimeRange() {
        UpstreamMeta m1 = sampleMeta("call1", 1000L, "agent");
        UpstreamMeta m2 = sampleMeta("call2", 2000L, "agent");
        UpstreamMeta m3 = sampleMeta("call3", 3000L, "agent");
        UpstreamMeta m4 = sampleMeta("call4", 4000L, "agent");

        UpstreamMetaHook.onUpstreamCall(m1);
        UpstreamMetaHook.onUpstreamCall(m2);
        UpstreamMetaHook.onUpstreamCall(m3);
        UpstreamMetaHook.onUpstreamCall(m4);

        // [2000, 4000) 应包含 m2 和 m3
        List<UpstreamMeta> window = UpstreamMetaHook.listByTimeWindow(2000L, 4000L);
        assertEquals(2, window.size());
        assertEquals(2000L, window.get(0).ts());
        assertEquals(3000L, window.get(1).ts());
    }

    @Test
    @DisplayName("resetForTesting 清缓冲但不清磁盘文件")
    void resetForTesting_clearsBufferNotDisk() throws Exception {
        UpstreamMeta m = sampleMeta("persist.test", 1000L, "agent");
        UpstreamMetaHook.onUpstreamCall(m);

        // 重置前：缓冲 + 磁盘都有
        assertEquals(1, UpstreamMetaHook.listByUpstream("persist.test").size());
        assertTrue(Files.exists(metaFile));

        UpstreamMetaHook.resetForTesting();

        // 重置后：缓冲空，磁盘仍有
        assertTrue(UpstreamMetaHook.listByUpstream("persist.test").isEmpty());
        assertEquals(0, UpstreamMetaHook.callCount("persist.test"));
        assertTrue(Files.exists(metaFile));
        assertEquals(1, Files.readAllLines(metaFile).size());
    }

    @Test
    @DisplayName("FIFO 淘汰：buffer 满后移除最旧 1/4")
    void bufferOverflow_fifoEviction() {
        // 写入 1024 条 + 1 条 = 触发淘汰，移除最旧 1/4 (256 条)
        for (int i = 0; i < 1025; i++) {
            UpstreamMetaHook.onUpstreamCall(sampleMeta("call" + i, i, "agent"));
        }

        // 淘汰后应有 1025 - 256 = 769 条
        // 但淘汰会发生在第 1025 次插入时（buffer 满 1024 → 移除 256 → size=768 → 插入 → 769）
        List<UpstreamMeta> all = UpstreamMetaHook.listByAgent("agent");
        assertEquals(769, all.size());

        // 最旧的 256 条 (call0..call255) 应被淘汰
        // 验证：最早的 ts 应 >= 256
        long earliestTs = all.get(0).ts();
        assertTrue(earliestTs >= 256L,
                "earliest ts should be >= 256 after FIFO eviction, got " + earliestTs);
    }

    @Test
    @DisplayName("callCount 按 upstream_name 累计（与缓冲独立）")
    void callCount_accumulatesAcrossEvictions() {
        for (int i = 0; i < 1500; i++) {
            UpstreamMetaHook.onUpstreamCall(sampleMeta("llm.think", i, "agent"));
        }

        // 即使缓冲被 FIFO 淘汰，callCount 仍累计
        assertEquals(1500, UpstreamMetaHook.callCount("llm.think"));
    }

    @Test
    @DisplayName("多 upstream_name 并行记录")
    void onUpstreamCall_multipleUpstreamNames_independentCounters() {
        UpstreamMetaHook.onUpstreamCall(sampleMeta("llm.think", 1L, "a"));
        UpstreamMetaHook.onUpstreamCall(sampleMeta("llm.think", 2L, "a"));
        UpstreamMetaHook.onUpstreamCall(sampleMeta("storage.write", 3L, "a"));
        UpstreamMetaHook.onUpstreamCall(sampleMeta("tool.web_search", 4L, "a"));

        assertEquals(2, UpstreamMetaHook.callCount("llm.think"));
        assertEquals(1, UpstreamMetaHook.callCount("storage.write"));
        assertEquals(1, UpstreamMetaHook.callCount("tool.web_search"));
        assertEquals(0, UpstreamMetaHook.callCount("nonexistent"));
    }

    @Test
    @DisplayName("upstreamMetaFile() 暴露路径供 UpstreamMetaQuery 跨 session 回读")
    void upstreamMetaFile_exposesPath() {
        assertEquals(metaFile, UpstreamMetaHook.upstreamMetaFile());
    }

    @Test
    @DisplayName("JSONL 多行格式正确（每行一条记录，换行分隔）")
    void jsonlFile_multipleRecords_correctFormat() throws Exception {
        UpstreamMetaHook.onUpstreamCall(sampleMeta("call1", 1000L, "a"));
        UpstreamMetaHook.onUpstreamCall(sampleMeta("call2", 2000L, "b"));
        UpstreamMetaHook.onUpstreamCall(sampleMeta("call3", 3000L, "c"));

        String content = Files.readString(metaFile);
        String[] lines = content.split("\n");

        // 3 行 + 末尾可能有一个空行（split 行为）
        long nonEmptyLines = java.util.Arrays.stream(lines)
                .filter(s -> !s.isEmpty())
                .count();
        assertEquals(3, nonEmptyLines);

        // 每行都能反序列化
        for (String line : lines) {
            if (line.isEmpty()) continue;
            UpstreamMeta m = UpstreamMeta.fromJsonLine(line);
            assertNotNull(m, "Line should deserialize: " + line);
        }
    }
}
