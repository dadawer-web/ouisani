package com.ouisani.aios.core.audit;

import com.ouisani.aios.core.ipc.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedAuditTimelineTest {
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        file = Files.createTempFile("unified_timeline", ".jsonl");
        UnifiedAuditLog.setAuditFile(file);
        UnifiedAuditLog.resetForTesting();
        UnifiedAuditLog.setEnabled(true);
        TraceContext.setCurrentTraceId(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        UnifiedAuditLog.resetForTesting();
        TraceContext.setCurrentTraceId(null);
        Files.deleteIfExists(file);
    }

    @Test
    void queryReconstructsStructuredCrossLayerTimeline() {
        UnifiedAuditLog.AuditContext context = new UnifiedAuditLog.AuditContext(
                "tenant-a", "workflow-7", "run-3", "trace-9", "agent-child",
                "agent-parent", "del-4", "node-2", 2);
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_PERMISSION, "ACTION_GATE", "ACTION_GATE_ALLOW",
                "agent-child", "shell@echo", "approved", context));
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_PERMISSION, "SHARED_MEMORY", "MEMORY_WRITE",
                "agent-child", "task:notes", "version=2", context));

        List<UnifiedAuditLog.AuditEntry> timeline = UnifiedAuditLog.query(
                new UnifiedAuditLog.TimelineQuery("trace-9", "tenant-a", "workflow-7", "run-3",
                        "agent-child", Long.MIN_VALUE, Long.MAX_VALUE,
                        Set.of(), Set.of()));
        assertEquals(2, timeline.size());
        assertEquals("ACTION_GATE_ALLOW", timeline.get(0).decision());
        assertEquals("del-4", timeline.get(1).delegationId());
        assertTrue(UnifiedAuditLog.exportJsonLines(UnifiedAuditLog.TimelineQuery.forTraceId("trace-9"))
                .contains("\"workflowId\":\"workflow-7\""));
    }

    @Test
    void sequenceProvidesStableOrderWhenTimestampsTie() {
        UnifiedAuditLog.AuditContext context = new UnifiedAuditLog.AuditContext(
                null, null, null, "same-trace", null, null, null, null, -1);
        for (int i = 0; i < 3; i++) {
            UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                    UnifiedAuditLog.LAYER_PERMISSION, "TEST", "EVENT_" + i,
                    null, "target", "", context));
        }
        List<UnifiedAuditLog.AuditEntry> events = UnifiedAuditLog.listByTraceId("same-trace");
        assertEquals(List.of("EVENT_0", "EVENT_1", "EVENT_2"),
                events.stream().map(UnifiedAuditLog.AuditEntry::decision).toList());
        assertTrue(events.get(0).sequence() < events.get(1).sequence());
    }
}
