package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import com.ouisani.aios.core.telemetry.TelemetryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRecallMetricsTest {

    @BeforeEach
    void resetMetrics() {
        MemoryRecallMetrics.resetOutcomeBaselines();
        TelemetryService.instance().drainEvents();
    }

    @AfterEach
    void clearMetrics() {
        MemoryRecallMetrics.resetOutcomeBaselines();
        TelemetryService.instance().drainEvents();
    }

    @Test
    void recallEmitsQualityMetrics() {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        store.store("metrics-agent", MemoryRecord.atomic("coffee", "coffee preference",
                "lifecycle:l1-extraction;tenant=tenant-a", System.currentTimeMillis(),
                0.9, MemoryDomain.USER));
        store.store("metrics-agent", MemoryRecord.atomic("other", "other tenant memory",
                "lifecycle:l1-extraction;tenant=tenant-b", System.currentTimeMillis(),
                0.9, MemoryDomain.USER));
        store.store("metrics-agent", MemoryRecord.raw("raw", "raw evidence",
                "lifecycle:l0-capture;tenant=tenant-a", System.currentTimeMillis(),
                MemoryDomain.USER));

        MemoryRecallHook.RecallResult result = new MemoryRecallHook(store).recall(
                new MemoryRecallHook.RecallRequest("metrics-agent", "tenant-a", null,
                        null, "coffee", 4, 1_000));

        assertEquals(1, result.records().size());
        assertTrue(result.topScore() > 0.0);
        assertTrue(result.permissionFilteredCount() >= 1);
        assertTrue(result.untrustedRejectedCount() >= 1);

        TelemetryService.TelemetryEvent event = TelemetryService.instance().drainEvents().stream()
                .filter(candidate -> MemoryRecallMetrics.RECALL_EVENT.equals(candidate.name()))
                .findFirst().orElseThrow();
        assertEquals(1, event.metadata().get("recall_hit_count"));
        assertTrue(((Number) event.metadata().get("recall_latency_ms")).longValue() >= 0);
        assertTrue(((Number) event.metadata().get("recall_injected_token_count")).longValue() > 0);
        assertEquals("keyword", event.metadata().get("recall_strategy"));
    }

    @Test
    void outcomeMetricsMeasureCitationUnusedRecallAndSuccessLift() {
        MemoryRecallHook.RecallResult noRecall = MemoryRecallHook.RecallResult.unavailable(
                "none", "test", "no memory");
        MemoryRecallMetrics.recordOutcome("metrics-agent", "turn-1", noRecall,
                "could not complete", false);

        MemoryRecallHook.RecallResult recalled = recalledResult();
        MemoryRecallMetrics.OutcomeSnapshot outcome = MemoryRecallMetrics.recordOutcome(
                "metrics-agent", "turn-2", recalled, "The coffee preference was applied", true);

        assertEquals(1.0, outcome.citationRate(), 0.001);
        assertEquals(0.0, outcome.uselessRecallRate(), 0.001);
        assertTrue(outcome.baselineAvailable());
        assertTrue(outcome.improvedTaskSuccess());
        assertTrue(outcome.taskSuccessLift() > 0.0);

        assertFalse(TelemetryService.instance().drainEvents().stream()
                .filter(event -> MemoryRecallMetrics.OUTCOME_EVENT.equals(event.name()))
                .toList().isEmpty());
    }

    private static MemoryRecallHook.RecallResult recalledResult() {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        store.store("metrics-agent", MemoryRecord.atomic("coffee", "coffee preference",
                "lifecycle:l1-extraction", System.currentTimeMillis(), 0.9,
                MemoryDomain.USER));
        return new MemoryRecallHook(store).recall("metrics-agent", null, null, null, "coffee");
    }

    private static final class FakeProvider implements MemoryProvider {
        private final Map<String, List<MemoryRecord>> records = new ConcurrentHashMap<>();

        @Override
        public boolean store(String agentId, MemoryRecord record) {
            records.computeIfAbsent(agentId, ignored -> new ArrayList<>()).add(record);
            return true;
        }

        @Override
        public String retrieve(String agentId, String query) {
            return records.getOrDefault(agentId, List.of()).stream()
                    .map(MemoryRecord::content).filter(content -> content.contains(query))
                    .findFirst().orElse("");
        }

        @Override
        public void clear(String agentId) {
            records.remove(agentId);
        }

        @Override
        public String providerName() {
            return "metrics-test";
        }
    }
}
