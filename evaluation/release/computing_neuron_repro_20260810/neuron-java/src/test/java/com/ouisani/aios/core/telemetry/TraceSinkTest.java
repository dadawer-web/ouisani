package com.ouisani.aios.core.telemetry;

import com.ouisani.aios.core.telemetry.TraceSink.CompositeTraceSink;
import com.ouisani.aios.core.telemetry.TraceSink.DiagnosticCode;
import com.ouisani.aios.core.telemetry.TraceSink.DiagnosticReporter;
import com.ouisani.aios.core.telemetry.TraceSink.FlushResult;
import com.ouisani.aios.core.telemetry.TraceSink.InMemoryTraceSink;
import com.ouisani.aios.core.telemetry.TraceSink.TraceCapturePolicy;
import com.ouisani.aios.core.telemetry.TraceSink.TraceCapturePolicy.FieldCapture;
import com.ouisani.aios.core.telemetry.TraceSink.TraceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceSink + SemanticEtw 可观测性测试 — 验证捕获策略、flush、限频、组合 sink。
 */
class TraceSinkTest {

    @BeforeEach
    void setup() {
        SemanticEtw etw = SemanticEtw.getInstance();
        etw.clear();
        etw.resetSinks();
    }

    @AfterEach
    void cleanup() {
        SemanticEtw etw = SemanticEtw.getInstance();
        etw.clear();
        etw.resetSinks();
    }

    // ════════════════════════════════════════════════════════════════
    //  TraceCapturePolicy
    // ════════════════════════════════════════════════════════════════

    @Test
    void capturePolicy_default_isRedacted() {
        assertEquals(FieldCapture.REDACTED, TraceCapturePolicy.DEFAULT.prompt());
        assertEquals(FieldCapture.REDACTED, TraceCapturePolicy.DEFAULT.completion());
    }

    @Test
    void capturePolicy_none_isAllNone() {
        assertEquals(FieldCapture.NONE, TraceCapturePolicy.NONE.prompt());
        assertEquals(FieldCapture.NONE, TraceCapturePolicy.NONE.completion());
        assertEquals(FieldCapture.NONE, TraceCapturePolicy.NONE.toolInput());
        assertEquals(FieldCapture.NONE, TraceCapturePolicy.NONE.toolOutput());
    }

    @Test
    void capturePolicy_applyNone_returnsOmitted() {
        String result = TraceCapturePolicy.NONE.apply(FieldCapture.NONE, "sensitive data");
        assertEquals("[omitted]", result);
    }

    @Test
    void capturePolicy_applyRedacted_truncatesLongContent() {
        TraceCapturePolicy policy = new TraceCapturePolicy(
                FieldCapture.REDACTED, FieldCapture.REDACTED,
                FieldCapture.REDACTED, FieldCapture.REDACTED, 10);
        String longContent = "abcdefghijklmnopqrstuvwxyz";
        String result = policy.apply(FieldCapture.REDACTED, longContent);
        assertTrue(result.startsWith("abcdefghij"));
        assertTrue(result.contains("truncated"));
    }

    @Test
    void capturePolicy_applyRedacted_keepsShortContent() {
        String result = TraceCapturePolicy.DEFAULT.apply(FieldCapture.REDACTED, "short");
        assertEquals("short", result);
    }

    @Test
    void capturePolicy_applyNull_returnsNull() {
        assertNull(TraceCapturePolicy.DEFAULT.apply(FieldCapture.REDACTED, null));
    }

    // ════════════════════════════════════════════════════════════════
    //  InMemoryTraceSink
    // ════════════════════════════════════════════════════════════════

    @Test
    void inMemorySink_emitAndFlush() {
        InMemoryTraceSink sink = new InMemoryTraceSink();
        sink.emit(TraceRecord.of("LLM", "CALL", "gpt-4o called"));
        sink.emit(TraceRecord.of("VFS", "WRITE", "/dev/http"));

        assertEquals(2, sink.size());
        FlushResult result = sink.forceFlush();
        assertEquals(FlushResult.Status.OK, result.status());
        assertEquals(2, result.exported());
    }

    @Test
    void inMemorySink_shutdownBlocksEmit() {
        InMemoryTraceSink sink = new InMemoryTraceSink();
        sink.emit(TraceRecord.of("LLM", "CALL", "before shutdown"));
        sink.shutdown();

        // shutdown 后 emit 被静默拒绝
        sink.emit(TraceRecord.of("LLM", "CALL", "after shutdown"));
        assertEquals(1, sink.size());
    }

    @Test
    void inMemorySink_stats() {
        InMemoryTraceSink sink = new InMemoryTraceSink();
        sink.emit(TraceRecord.of("LLM", "CALL", "record 1"));
        sink.emit(TraceRecord.of("VFS", "READ", "record 2"));

        TraceSink.TraceSinkStats stats = sink.getStats();
        assertEquals(2, stats.accepted());
        assertEquals(0, stats.exported());
        assertEquals(2, stats.queuedRecords());
    }

    // ════════════════════════════════════════════════════════════════
    //  CompositeTraceSink
    // ════════════════════════════════════════════════════════════════

    @Test
    void compositeSink_broadcastsToAll() {
        InMemoryTraceSink sink1 = new InMemoryTraceSink();
        InMemoryTraceSink sink2 = new InMemoryTraceSink();
        CompositeTraceSink composite = new CompositeTraceSink(sink1, sink2);

        composite.emit(TraceRecord.of("LLM", "CALL", "broadcast"));
        assertEquals(1, sink1.size());
        assertEquals(1, sink2.size());
    }

    @Test
    void compositeSink_oneFailsOthersStillWork() {
        InMemoryTraceSink goodSink = new InMemoryTraceSink();
        TraceSink badSink = new TraceSink() {
            @Override
            public void emit(TraceRecord record) {
                throw new RuntimeException("intentional failure");
            }
            @Override
            public FlushResult forceFlush() { return FlushResult.empty(); }
            @Override
            public FlushResult shutdown() { return FlushResult.empty(); }
        };
        CompositeTraceSink composite = new CompositeTraceSink(badSink, goodSink);

        // badSink 抛异常,但 goodSink 应该仍然收到
        composite.emit(TraceRecord.of("LLM", "CALL", "resilient"));
        assertEquals(1, goodSink.size());

        // 诊断应该被报告
        assertTrue(composite.diagnostics().count(DiagnosticCode.SINK_EMIT_FAILED) > 0);
    }

    @Test
    void compositeSink_flushAll() {
        InMemoryTraceSink sink1 = new InMemoryTraceSink();
        InMemoryTraceSink sink2 = new InMemoryTraceSink();
        CompositeTraceSink composite = new CompositeTraceSink(sink1, sink2);

        composite.emit(TraceRecord.of("LLM", "CALL", "record 1"));
        composite.emit(TraceRecord.of("VFS", "READ", "record 2"));

        FlushResult result = composite.forceFlush();
        assertEquals(FlushResult.Status.OK, result.status());
        // 2 个 sink 各 2 条 = 总共 exported 4
        assertEquals(4, result.exported());
    }

    @Test
    void compositeSink_shutdownBlocksEmit() {
        InMemoryTraceSink sink = new InMemoryTraceSink();
        CompositeTraceSink composite = new CompositeTraceSink(sink);

        composite.emit(TraceRecord.of("LLM", "CALL", "before"));
        composite.shutdown();

        // shutdown 后 emit 被拒绝
        composite.emit(TraceRecord.of("LLM", "CALL", "after"));
        assertEquals(1, sink.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  DiagnosticReporter
    // ════════════════════════════════════════════════════════════════

    @Test
    void diagnosticReporter_countsErrors() {
        DiagnosticReporter reporter = new DiagnosticReporter();
        reporter.report(DiagnosticCode.SINK_EMIT_FAILED, "error 1");
        reporter.report(DiagnosticCode.SINK_EMIT_FAILED, "error 2");
        reporter.report(DiagnosticCode.QUEUE_FULL, "error 3");

        assertEquals(2, reporter.count(DiagnosticCode.SINK_EMIT_FAILED));
        assertEquals(1, reporter.count(DiagnosticCode.QUEUE_FULL));
        assertEquals(0, reporter.count(DiagnosticCode.EXPORT_FAILED));
    }

    @Test
    void diagnosticReporter_reset() {
        DiagnosticReporter reporter = new DiagnosticReporter();
        reporter.report(DiagnosticCode.SINK_EMIT_FAILED, "error");
        reporter.reset();
        assertEquals(0, reporter.count(DiagnosticCode.SINK_EMIT_FAILED));
    }

    // ════════════════════════════════════════════════════════════════
    //  SemanticEtw 集成
    // ════════════════════════════════════════════════════════════════

    @Test
    void semanticEtw_registerAndFlushSink() {
        SemanticEtw etw = SemanticEtw.getInstance();
        etw.clear();
        InMemoryTraceSink sink = new InMemoryTraceSink();
        etw.registerSink(sink);

        etw.logEvent("LLM", "CALL", "gpt-4o invoked");

        FlushResult result = etw.forceFlushSinks();
        assertEquals(FlushResult.Status.OK, result.status());
        assertTrue(result.exported() > 0);
        assertTrue(sink.size() > 0);

        // 记录应该包含我们写入的事件
        boolean found = sink.records().stream()
                .anyMatch(r -> "LLM".equals(r.component()) && "CALL".equals(r.type()));
        assertTrue(found);
    }

    @Test
    void semanticEtw_setCapturePolicy() {
        SemanticEtw etw = SemanticEtw.getInstance();
        etw.setCapturePolicy(TraceCapturePolicy.NONE);
        assertEquals(TraceCapturePolicy.NONE, etw.getCapturePolicy());

        // 恢复默认
        etw.setCapturePolicy(TraceCapturePolicy.DEFAULT);
        assertEquals(TraceCapturePolicy.DEFAULT, etw.getCapturePolicy());
    }

    @Test
    void semanticEtw_shutdownSinks_isIdempotent() {
        SemanticEtw etw = SemanticEtw.getInstance();
        etw.clear();
        InMemoryTraceSink sink = new InMemoryTraceSink();
        etw.registerSink(sink);

        // 第一次 shutdown
        FlushResult r1 = etw.shutdownSinks();
        assertNotNull(r1);

        // 第二次 shutdown 应该幂等返回 empty
        FlushResult r2 = etw.shutdownSinks();
        assertEquals(0, r2.exported());
    }

    @Test
    void semanticEtw_noSinks_flushReturnsEmpty() {
        SemanticEtw etw = SemanticEtw.getInstance();
        etw.clear();
        // 不注册任何 sink
        FlushResult result = etw.forceFlushSinks();
        assertEquals(FlushResult.Status.OK, result.status());
        assertEquals(0, result.exported());
    }
}
