package com.ouisani.aios.core.syscall;

import com.ouisani.aios.core.observability.UpstreamMeta;
import com.ouisani.aios.core.observability.UpstreamMetaHook;
import com.ouisani.aios.core.observability.UpstreamMetaQuery;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import com.ouisani.aios.core.syscall.schema.LlmPayload;
import com.ouisani.aios.core.trace.TraceSpan;
import com.ouisani.aios.core.trace.TracingManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SyscallDispatcher ↔ UpstreamMeta ↔ TraceSpan 端到端桥接验证（Step 7）。
 * <p>
 * 验证用户需求"UpstreamMeta 沿 EventBus 调用链透传"的最后一环：
 * 一次真实 syscall 执行后，{@code recordUpstreamMeta} 同时把元数据
 * 落盘到 {@code .aios/upstream_meta.jsonl} <b>并</b>桥接为 TraceSpan 的
 * upstream.* attributes，让 OpenTelemetry 后端可查询。
 *
 * <h3>验证矩阵</h3>
 * <ul>
 *   <li>TraceSpan 上游属性已设置：{@code upstream.name} / {@code upstream.duration_ms} /
 *       {@code upstream.status_code} / {@code upstream.bytes} / {@code upstream.error_code}</li>
 *   <li>TraceSpan status 与 response.success() 对齐（OK / ERROR）</li>
 *   <li>.aios/upstream_meta.jsonl 落盘记录可通过 UpstreamMetaQuery 跨 session 读回</li>
 *   <li>落盘记录的 6 个标准字段与 TraceSpan attributes 一致（双通道不漂移）</li>
 *   <li>agentId / sessionId 从 ProvenanceHook ThreadLocal 透传</li>
 * </ul>
 *
 * <h3>测试路径选择</h3>
 * 使用 {@code llm.think} 命名空间，未配置 LlmRouter 时 routeLlm 返回
 * {@code SyscallResponse.fail("LLM 路由器未配置")} —— 这是一条<b>可预测的失败路径</b>，
 * 不需要 mock 整个 LLM 栈即可端到端验证 recordUpstreamMeta 触发。
 * <p>
 * 预期 UpstreamMeta：
 * <ul>
 *   <li>{@code upstream_name = "llm.think"}（resolveUpstreamName 命名空间映射）</li>
 *   <li>{@code upstream_status_code = 500}（FAILED 状态映射为 HTTP 5xx）</li>
 *   <li>{@code upstream_bytes = 0}（fail 响应 data 为 null）</li>
 *   <li>{@code error_code = "LLM 路由器未配置"}（errorMessage 无 ":" 前缀，取全串）</li>
 * </ul>
 */
class SyscallUpstreamMetaE2ETest {

    @TempDir
    Path tempDir;

    private Path upstreamMetaFile;
    private boolean tracingWasDisabled;

    @BeforeEach
    void setUp() {
        upstreamMetaFile = tempDir.resolve("upstream_meta.jsonl");
        UpstreamMetaHook.setUpstreamMetaFile(upstreamMetaFile);
        UpstreamMetaHook.setEnabled(true);
        UpstreamMetaHook.resetForTesting();

        // 确保 Tracing 启用（其他测试可能禁用过）
        tracingWasDisabled = TracingManager.instance().isTracingDisabled();
        TracingManager.instance().setTracingDisabled(false);

        // 设置 agent / session 上下文（recordUpstreamMeta 从这里读）
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_e2e");
        ProvenanceHook.CURRENT_SESSION_ID.set("sess_e2e");
    }

    @AfterEach
    void tearDown() {
        ProvenanceHook.CURRENT_AGENT_ID.remove();
        ProvenanceHook.CURRENT_SESSION_ID.remove();

        UpstreamMetaHook.resetForTesting();
        UpstreamMetaHook.setEnabled(true);
        UpstreamMetaHook.setUpstreamMetaFile(Path.of(".aios", "upstream_meta.jsonl"));

        TracingManager.instance().setTracingDisabled(tracingWasDisabled);
    }

    // ════════════════════════════════════════════════════════════════
    //  端到端：syscall.execute → TraceSpan upstream.* attributes
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("E2E：llm.think 执行 → TraceSpan 桥接 upstream.* attributes + 落盘")
    void execute_llmThink_bridgesUpstreamMetaToTraceSpan_andPersists() {
        // ── 执行 ──
        // 注意：getInstance() 是单例；测试 JVM 中 configure() 未被调用，
        // llmRouter 为 null → routeLlm 返回 SyscallResponse.fail("LLM 路由器未配置")
        SyscallRequest req = new SyscallRequest("llm", "think", new LlmPayload("hello"));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute("agent_e2e", req);

        assertNotNull(resp);
        assertFalse(resp.success(), "未配置 LlmRouter 应走失败路径");

        // ── 1. 验证 TraceSpan 已桥接 upstream.* attributes ──
        TraceSpan span = findSpanByName("syscall.llm.think");
        assertNotNull(span, "应创建名为 syscall.llm.think 的 TraceSpan");

        Map<String, Object> attrs = span.attributes();
        assertTrue(attrs.containsKey("upstream.name"), "upstream.name 应已桥接到 Span");
        assertEquals("llm.think", attrs.get("upstream.name"));

        assertTrue(attrs.containsKey("upstream.duration_ms"));
        long dur = ((Number) attrs.get("upstream.duration_ms")).longValue();
        assertTrue(dur >= 0, "duration_ms 应非负");

        assertTrue(attrs.containsKey("upstream.status_code"));
        int spanStatusCode = ((Number) attrs.get("upstream.status_code")).intValue();
        assertEquals(500, spanStatusCode, "FAILED 状态 → HTTP 500");

        assertTrue(attrs.containsKey("upstream.bytes"));
        assertEquals(0L, ((Number) attrs.get("upstream.bytes")).longValue(), "fail 路径 data=null → 0 bytes");

        assertTrue(attrs.containsKey("upstream.error_code"));
        assertEquals("LLM 路由器未配置", attrs.get("upstream.error_code"));

        // cost_units v1 留 null → 不应出现在 Span attributes
        assertFalse(attrs.containsKey("upstream.cost_units"),
                "v1 cost_units=null 应跳过 span 桥接");

        // ── 2. 验证 TraceSpan status 与 response 对齐 ──
        assertEquals(TraceSpan.Status.ERROR, span.status(),
                "失败响应 → span status=ERROR");
        assertTrue(span.isEnded(), "finally 块应已结束 span");
        assertEquals(Boolean.FALSE, attrs.get("success"), "success 属性应为 false");
        assertEquals("llm", attrs.get("namespace"));
        assertEquals("think", attrs.get("action"));
        assertEquals("agent_e2e", attrs.get("agent_id"));

        // ── 3. 验证落盘记录可通过 UpstreamMetaQuery 读回 ──
        // 清空内存缓冲 → 模拟跨 session 纯磁盘读
        UpstreamMetaHook.resetForTesting();
        List<UpstreamMeta> calls = UpstreamMetaQuery.listByUpstream("llm.think", upstreamMetaFile);

        assertEquals(1, calls.size(), "应有一条落盘记录");
        UpstreamMeta meta = calls.get(0);

        // ── 4. 验证落盘记录与 Span attributes 一致（双通道不漂移） ──
        assertEquals("llm.think", meta.upstreamName());
        assertEquals(spanStatusCode, meta.upstreamStatusCode(), "status_code 双通道一致");
        assertEquals(0L, meta.upstreamBytes(), "bytes 双通道一致");
        assertEquals("LLM 路由器未配置", meta.errorCode(), "error_code 双通道一致");
        assertNull(meta.upstreamCostUnits(), "v1 cost_units 应为 null");
        assertTrue(meta.upstreamDurationMs() >= 0);

        // ── 5. 验证 agentId / sessionId 从 ThreadLocal 透传 ──
        assertEquals("agent_e2e", meta.agentId());
        assertEquals("sess_e2e", meta.sessionId());
    }

    @Test
    @DisplayName("E2E：statsByUpstream 聚合查询（双通道互补验证）")
    void execute_llmThink_statsByUpstream_aggregates() {
        // 写 3 次（全部走相同的 fail 路径）
        for (int i = 0; i < 3; i++) {
            SyscallRequest req = new SyscallRequest("llm", "think", new LlmPayload("hello-" + i));
            SyscallDispatcher.getInstance().execute("agent_e2e", req);
        }

        // 跨 session 聚合（模拟仪表盘查询）
        UpstreamMetaHook.resetForTesting();
        var stats = UpstreamMetaQuery.statsByUpstream("llm.think", upstreamMetaFile);

        assertEquals("llm.think", stats.upstreamName());
        assertEquals(3L, stats.callCount(), "应聚合 3 次调用");
        assertEquals(0L, stats.successCount(), "全部失败");
        assertEquals(3L, stats.errorCount());
        assertEquals(1.0, stats.errorRate(), 1e-9);
        assertEquals(0L, stats.totalBytes(), "失败响应无字节");
        assertTrue(stats.avgLatencyMs() >= 0);
        assertTrue(stats.maxLatencyMs() >= stats.minLatencyMs());
        assertTrue(stats.p99LatencyMs() >= stats.p50LatencyMs());
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助
    // ════════════════════════════════════════════════════════════════

    /** 在最近完成的 Span 中按 name 查找（getRecentSpans 返回倒序，取首个匹配）。 */
    private static TraceSpan findSpanByName(String name) {
        for (TraceSpan s : TracingManager.instance().getRecentSpans()) {
            if (name.equals(s.name()) && s.isEnded()) {
                return s;
            }
        }
        return null;
    }
}
