package com.ouisani.aios.core.audit;

import com.ouisani.aios.core.ipc.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UnifiedAuditLog} 单元测试 — 验证跨层联合审计链的核心能力：
 * <ul>
 *   <li>三层决策（cgroup/sandbox/permission）按 traceId 聚合到统一 sink</li>
 *   <li>traceId 自动从 {@link TraceContext} 的 InheritableThreadLocal 取</li>
 *   <li>JSONL 持久化 + 跨 session 磁盘回读 + 内存/磁盘去重</li>
 *   <li>{@code listByTraceId} 把同一次攻击的三层响应按时间序重放</li>
 * </ul>
 * <p>
 * 这是论文"联合治理 vs 各自为战"差异化论点的代码级验证：三层决策可被一个 traceId 串联。
 * 对齐项目记忆约束：best-effort、JSONL 跨 session 可读、同 ProvenanceHook/PermissionDenialLedger 范式。
 */
class UnifiedAuditLogTest {

    private Path tempFile;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("unified_audit_test", ".jsonl");
        UnifiedAuditLog.setAuditFile(tempFile);
        UnifiedAuditLog.setEnabled(true);
        UnifiedAuditLog.resetForTesting();
        // 清理可能残留的 TraceContext（线程池复用防护）
        TraceContext.setCurrentTraceId(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        TraceContext.setCurrentTraceId(null);
        UnifiedAuditLog.resetForTesting();
        UnifiedAuditLog.setEnabled(true);
        Files.deleteIfExists(tempFile);
    }

    // ════════════════════════════════════════════════════════════════
    //  traceId 自动注入 + 单层 append
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("append 自动从 TraceContext 取 traceId 注入到记录")
    void append_autoFetchesTraceIdFromContext() {
        TraceContext.setCurrentTraceId("trace-abc123");
        UnifiedAuditLog.append(
                UnifiedAuditLog.LAYER_PERMISSION, "DENY", "agent_1", "bash", "rm -rf /");

        List<UnifiedAuditLog.AuditEntry> chain = UnifiedAuditLog.listByTraceId("trace-abc123");
        assertEquals(1, chain.size());
        assertEquals("trace-abc123", chain.get(0).traceId());
        assertEquals(UnifiedAuditLog.LAYER_PERMISSION, chain.get(0).layer());
        assertEquals("DENY", chain.get(0).decision());
        assertEquals("agent_1", chain.get(0).agentId());
        assertEquals("bash", chain.get(0).target());
        assertEquals("rm -rf /", chain.get(0).reason());
    }

    @Test
    @DisplayName("无 TraceContext 时 traceId=null 仍记录（层内事件）")
    void append_withoutTraceId_recordsWithNullTraceId() {
        // 不设置 TraceContext
        UnifiedAuditLog.append(
                UnifiedAuditLog.LAYER_CGROUP, "OOM_KILL", "agent_2", "agent_2", "quota exceeded");

        // listByTraceId(null) 返回空（按约定）
        assertTrue(UnifiedAuditLog.listByTraceId(null).isEmpty());
        // 但 listTraceIds 不包含 null
        assertFalse(UnifiedAuditLog.listTraceIds().contains(null));
    }

    // ════════════════════════════════════════════════════════════════
    //  跨层串联 — 论文核心论点的验证
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("同 traceId 下三层决策可按时间序重放（联合治理 vs 各自为战的关键证据）")
    void listByTraceId_chainsThreeLayerDecisionsInOrder() throws Exception {
        TraceContext.setCurrentTraceId("trace-attack-001");

        // 模拟一次复合攻击的三层响应：
        // 1. cgroup 软限触发（资源压力）
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_CGROUP, "SOFT_OOM",
                "agent_5", "agent_5", "consumed=42000/50000");
        Thread.sleep(2);
        // 2. permission 拒绝越权（攻击者利用资源窗口期发起越权）
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_PERMISSION, "DENY",
                "agent_5", "file_read", "cross-tenant access blocked");
        Thread.sleep(2);
        // 3. sandbox 故障（攻击者转而尝试代码执行逃逸）
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_SANDBOX, "FAULT_INVALID_ACCESS",
                "agent_5", "sandbox-789", "unauthorized host import");

        List<UnifiedAuditLog.AuditEntry> chain = UnifiedAuditLog.listByTraceId("trace-attack-001");

        // 三层决策全部被串到同一个 traceId 下
        assertEquals(3, chain.size());
        // 按时间升序：cgroup → permission → sandbox
        assertEquals(UnifiedAuditLog.LAYER_CGROUP, chain.get(0).layer());
        assertEquals(UnifiedAuditLog.LAYER_PERMISSION, chain.get(1).layer());
        assertEquals(UnifiedAuditLog.LAYER_SANDBOX, chain.get(2).layer());
        // 同一 traceId 贯穿
        for (UnifiedAuditLog.AuditEntry e : chain) {
            assertEquals("trace-attack-001", e.traceId());
            assertEquals("agent_5", e.agentId());
        }
    }

    @Test
    @DisplayName("不同 traceId 的决策互不混淆（隔离性）")
    void listByTraceId_isolatesDifferentTraces() {
        TraceContext.setCurrentTraceId("trace-A");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_CGROUP, "OOM_KILL", "a1", "a1", "r");
        TraceContext.setCurrentTraceId("trace-B");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_PERMISSION, "DENY", "b1", "bash", "r");

        assertEquals(1, UnifiedAuditLog.listByTraceId("trace-A").size());
        assertEquals(1, UnifiedAuditLog.listByTraceId("trace-B").size());
        assertEquals("trace-A", UnifiedAuditLog.listByTraceId("trace-A").get(0).traceId());
        assertEquals("trace-B", UnifiedAuditLog.listByTraceId("trace-B").get(0).traceId());
    }

    // ════════════════════════════════════════════════════════════════
    //  JSONL 持久化 + 跨 session 磁盘回读
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("JSONL 持久化后跨 session 磁盘回读（内存缓冲清空后仍可查）")
    void listByTraceId_readsFromDiskAfterBufferCleared() {
        TraceContext.setCurrentTraceId("trace-persist");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_SANDBOX, "FAULT_OOM",
                "agent_9", "sandbox-xyz", "memory limit exceeded");
        // 清空内存缓冲，强制走磁盘回读
        UnifiedAuditLog.resetForTesting();

        List<UnifiedAuditLog.AuditEntry> chain = UnifiedAuditLog.listByTraceId("trace-persist");
        assertEquals(1, chain.size());
        assertEquals(UnifiedAuditLog.LAYER_SANDBOX, chain.get(0).layer());
        assertEquals("FAULT_OOM", chain.get(0).decision());
        assertEquals("trace-persist", chain.get(0).traceId());
    }

    @Test
    @DisplayName("内存缓冲 + 磁盘合并去重（LinkedHashSet 按 AuditEntry 结构化 equals 去重）")
    void listByTraceId_dedupesMemoryAndDisk() {
        TraceContext.setCurrentTraceId("trace-dedup");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_CGROUP, "OOM_KILL", "a", "a", "r");

        // 不清缓冲 → 同一条记录在内存和磁盘都有 → 去重后只返回 1 条
        List<UnifiedAuditLog.AuditEntry> chain = UnifiedAuditLog.listByTraceId("trace-dedup");
        assertEquals(1, chain.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  AuditEntry JSON 往返
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AuditEntry JSON 往返一致（含 null traceId）")
    void auditEntry_jsonRoundTrip() {
        UnifiedAuditLog.AuditEntry original = new UnifiedAuditLog.AuditEntry(
                1700000000L, "trace-x", UnifiedAuditLog.LAYER_PERMISSION,
                "DENY", "agent_1", "file_write:/etc/passwd", "cross-tenant");
        String json = original.toJsonLine();

        UnifiedAuditLog.AuditEntry parsed = UnifiedAuditLog.AuditEntry.fromJsonLine(json);
        assertNotNull(parsed);
        assertEquals(1700000000L, parsed.ts());
        assertEquals("trace-x", parsed.traceId());
        assertEquals(UnifiedAuditLog.LAYER_PERMISSION, parsed.layer());
        assertEquals("DENY", parsed.decision());
        assertEquals("agent_1", parsed.agentId());
        assertEquals("file_write:/etc/passwd", parsed.target());
        assertEquals("cross-tenant", parsed.reason());
    }

    @Test
    @DisplayName("AuditEntry fromJsonLine 容错：空/非法输入返回 null")
    void auditEntry_fromJsonLineNullReturnsNull() {
        assertNull(UnifiedAuditLog.AuditEntry.fromJsonLine(null));
        assertNull(UnifiedAuditLog.AuditEntry.fromJsonLine(""));
        assertNull(UnifiedAuditLog.AuditEntry.fromJsonLine("NOT JSON"));
    }

    @Test
    @DisplayName("AuditEntry fromJsonLine 容错：缺 traceId 字段时返回 null（向后兼容旧记录）")
    void auditEntry_fromJsonLineMissingTraceId() {
        // 旧格式记录（无 traceId 字段）— 应能解析，traceId 为 null
        String legacyJson = "{\"ts\":100,\"layer\":\"CGROUP\",\"decision\":\"OOM_KILL\","
                + "\"agentId\":\"a\",\"target\":\"a\",\"reason\":\"r\"}";
        UnifiedAuditLog.AuditEntry parsed = UnifiedAuditLog.AuditEntry.fromJsonLine(legacyJson);
        assertNotNull(parsed);
        assertEquals("CGROUP", parsed.layer());
        assertNull(parsed.traceId());
    }

    // ════════════════════════════════════════════════════════════════
    //  统计辅助
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listTraceIds 枚举所有 traceId（评测脚本枚举攻击样本用）")
    void listTraceIds_enumeratesAll() {
        TraceContext.setCurrentTraceId("trace-1");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_CGROUP, "OOM_KILL", "a", "a", "r");
        TraceContext.setCurrentTraceId("trace-2");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_PERMISSION, "DENY", "b", "bash", "r");
        TraceContext.setCurrentTraceId("trace-1"); // 同 traceId 再记一条
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_SANDBOX, "FAULT_OOM", "a", "s", "r");

        Set<String> ids = UnifiedAuditLog.listTraceIds();
        assertTrue(ids.contains("trace-1"));
        assertTrue(ids.contains("trace-2"));
        assertEquals(2, ids.size());
    }

    @Test
    @DisplayName("countByLayer 按层统计（sanity check）")
    void countByLayer_groupsByLayer() {
        TraceContext.setCurrentTraceId("trace-stats");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_CGROUP, "OOM_KILL", "a", "a", "r");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_CGROUP, "SOFT_OOM", "a", "a", "r");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_PERMISSION, "DENY", "a", "bash", "r");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_SANDBOX, "FAULT_OOM", "a", "s", "r");

        var stats = UnifiedAuditLog.countByLayer();
        assertEquals(2L, stats.get(UnifiedAuditLog.LAYER_CGROUP));
        assertEquals(1L, stats.get(UnifiedAuditLog.LAYER_PERMISSION));
        assertEquals(1L, stats.get(UnifiedAuditLog.LAYER_SANDBOX));
    }

    // ════════════════════════════════════════════════════════════════
    //  reason 截断 + best-effort
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reason 超长被截断到 512 字符（防日志爆炸）")
    void append_truncatesLongReason() {
        TraceContext.setCurrentTraceId("trace-trunc");
        String longReason = "x".repeat(1000);
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_PERMISSION, "DENY", "a", "bash", longReason);

        List<UnifiedAuditLog.AuditEntry> chain = UnifiedAuditLog.listByTraceId("trace-trunc");
        assertEquals(1, chain.size());
        String reason = chain.get(0).reason();
        assertTrue(reason.length() <= 512 + "...".length());
        assertTrue(reason.endsWith("..."));
    }

    @Test
    @DisplayName("enabled=false 时 append 不记录")
    void append_disabledDoesNothing() {
        UnifiedAuditLog.setEnabled(false);
        TraceContext.setCurrentTraceId("trace-off");
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_CGROUP, "OOM_KILL", "a", "a", "r");
        UnifiedAuditLog.setEnabled(true);

        assertTrue(UnifiedAuditLog.listByTraceId("trace-off").isEmpty());
    }
}
