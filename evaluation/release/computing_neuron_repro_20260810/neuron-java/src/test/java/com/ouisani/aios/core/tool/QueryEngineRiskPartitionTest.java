package com.ouisani.aios.core.tool;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 风险驱动工具分流单元测试 — 验证 {@link QueryEngine#partitionByRisk} 和 {@link QueryEngine#isParallelSafe}。
 * <p>
 * 借鉴 OpenWorker engine.py:480-504 的 risk-based 分流：
 * read-only 工具（file_read/grep/glob）→ 并发执行
 * write/exec 工具（file_write/file_edit/bash）→ 严格串行
 * <p>
 * 关键测试点：
 * <ul>
 *   <li>isParallelSafe 正确识别只读 vs 写/执行工具</li>
 *   <li>partitionByRisk 保留原始顺序，只有连续的只读工具合并为一批</li>
 *   <li>read→write→read 不应把两个 read 并发化</li>
 *   <li>未知工具 fail-safe → 串行</li>
 * </ul>
 */
class QueryEngineRiskPartitionTest {

    private static QueryEngine engine;

    @BeforeAll
    static void setup() {
        // 注册 stub 工具（模拟真实工具的 readOnly 属性）
        ToolRegistry reg = ToolRegistry.instance();
        reg.register(stub("stub_read_a", true));
        reg.register(stub("stub_read_b", true));
        reg.register(stub("stub_read_c", true));
        reg.register(stub("stub_write_a", false));
        reg.register(stub("stub_write_b", false));
        reg.register(stub("stub_edit_a", false));
        reg.register(stub("stub_bash", false));
        reg.register(stub("stub_unknown", false));

        engine = new QueryEngine(null, "test_agent", "/tmp");
    }

    private static Tool<ToolInput> stub(String name, boolean readOnly) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return readOnly; }
        };
    }

    private static QueryEngine.ToolCall tc(String toolName) {
        return new QueryEngine.ToolCall(toolName, "{}");
    }

    private static List<Integer> batchSizes(List<List<QueryEngine.ToolCall>> batches) {
        return batches.stream().map(List::size).toList();
    }

    // ════════════════════════════════════════════════════════════════
    //  isParallelSafe
    // ════════════════════════════════════════════════════════════════

    @Test
    void isParallelSafe_readOnlyTool_returnsTrue() {
        assertTrue(engine.isParallelSafe("stub_read_a"));
        assertTrue(engine.isParallelSafe("stub_read_b"));
    }

    @Test
    void isParallelSafe_writeTool_returnsFalse() {
        assertFalse(engine.isParallelSafe("stub_write_a"));
        assertFalse(engine.isParallelSafe("stub_write_b"));
        assertFalse(engine.isParallelSafe("stub_edit_a"));
    }

    @Test
    void isParallelSafe_bashTool_returnsFalse() {
        assertFalse(engine.isParallelSafe("stub_bash"));
    }

    @Test
    void isParallelSafe_unknownTool_returnsFalse_failSafe() {
        assertFalse(engine.isParallelSafe("nonexistent_tool_xyz"),
                "未知工具应 fail-safe 返回 false（串行），避免对陌生工具的副作用做并发假设");
    }

    // ════════════════════════════════════════════════════════════════
    //  partitionByRisk — 基本分区
    // ════════════════════════════════════════════════════════════════

    @Test
    void partition_allReads_singleParallelBatch() {
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_read_a"), tc("stub_read_b"), tc("stub_read_c")
        ));
        assertEquals(1, batches.size(), "3 个连续只读工具应合并为 1 个并发批次");
        assertEquals(3, batches.get(0).size());
    }

    @Test
    void partition_allWrites_individualSerialBatches() {
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_write_a"), tc("stub_write_b"), tc("stub_edit_a")
        ));
        assertEquals(3, batches.size(), "3 个写工具应各成一批（串行）");
        assertEquals(List.of(1, 1, 1), batchSizes(batches));
    }

    @Test
    void partition_mixed_readWriteRead_threeBatches() {
        // read → write → read → 3 批，两个 read 不应并发化
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_read_a"), tc("stub_write_a"), tc("stub_read_b")
        ));
        assertEquals(3, batches.size(), "read→write→read 应分为 3 批");
        assertEquals(List.of(1, 1, 1), batchSizes(batches),
                "两个 read 被 write 隔开，不应合并为并发批次");
    }

    // ════════════════════════════════════════════════════════════════
    //  partitionByRisk — 顺序保持
    // ════════════════════════════════════════════════════════════════

    @Test
    void partition_preservesOrder() {
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_read_a"), tc("stub_read_b"),
                tc("stub_write_a"),
                tc("stub_read_c"),
                tc("stub_bash"),
                tc("stub_read_a"), tc("stub_read_c")
        ));
        // 预期: [read_a, read_b] | [write_a] | [read_c] | [bash] | [read_a, read_c]
        assertEquals(5, batches.size());
        assertEquals(List.of(2, 1, 1, 1, 2), batchSizes(batches));
        // 验证顺序
        assertEquals("stub_read_a", batches.get(0).get(0).toolName());
        assertEquals("stub_read_b", batches.get(0).get(1).toolName());
        assertEquals("stub_write_a", batches.get(1).get(0).toolName());
        assertEquals("stub_read_c", batches.get(2).get(0).toolName());
        assertEquals("stub_bash", batches.get(3).get(0).toolName());
        assertEquals("stub_read_a", batches.get(4).get(0).toolName());
        assertEquals("stub_read_c", batches.get(4).get(1).toolName());
    }

    @Test
    void partition_writeBetweenReads_preventsParallelization() {
        // 两个 read 被 write 隔开 → 不应并发化（第二个 read 可能依赖 write 结果）
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_read_a"), tc("stub_write_a"), tc("stub_read_a")
        ));
        assertEquals(3, batches.size(), "write 两侧的 read 不应合并");
        assertEquals(List.of(1, 1, 1), batchSizes(batches));
    }

    // ════════════════════════════════════════════════════════════════
    //  partitionByRisk — 边界情况
    // ════════════════════════════════════════════════════════════════

    @Test
    void partition_emptyList_returnsEmpty() {
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of());
        assertTrue(batches.isEmpty());
    }

    @Test
    void partition_singleRead_oneBatch() {
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_read_a")
        ));
        assertEquals(1, batches.size());
        assertEquals(1, batches.get(0).size());
    }

    @Test
    void partition_singleWrite_oneBatch() {
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_write_a")
        ));
        assertEquals(1, batches.size());
        assertEquals(1, batches.get(0).size());
    }

    @Test
    void partition_unknownTool_treatedAsSerial() {
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_read_a"), tc("nonexistent_tool"), tc("stub_read_b")
        ));
        assertEquals(3, batches.size(), "未知工具打断只读批次，各成一批");
        assertEquals(List.of(1, 1, 1), batchSizes(batches));
    }

    @Test
    void partition_leadingWriteThenReads_twoBatches() {
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_bash"), tc("stub_read_a"), tc("stub_read_b")
        ));
        assertEquals(2, batches.size());
        assertEquals(List.of(1, 2), batchSizes(batches));
    }

    @Test
    void partition_trailingWriteAfterReads_twoBatches() {
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_read_a"), tc("stub_read_b"), tc("stub_bash")
        ));
        assertEquals(2, batches.size());
        assertEquals(List.of(2, 1), batchSizes(batches));
    }

    @Test
    void partition_alternatingReadWrite_allSerial() {
        // read → write → read → write → read → write → 全部串行
        List<List<QueryEngine.ToolCall>> batches = engine.partitionByRisk(List.of(
                tc("stub_read_a"), tc("stub_write_a"),
                tc("stub_read_b"), tc("stub_write_b"),
                tc("stub_read_c"), tc("stub_edit_a")
        ));
        assertEquals(6, batches.size(), "交替读写应全部串行，无并发批次");
        assertEquals(List.of(1, 1, 1, 1, 1, 1), batchSizes(batches));
    }
}
