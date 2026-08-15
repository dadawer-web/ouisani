package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryIsolationTest {

    @Test
    void strictContextRejectsMissingFieldsInsteadOfFillingDefaults() {
        MemoryIsolation.IsolationError error = assertThrows(MemoryIsolation.IsolationError.class,
                () -> MemoryIsolation.assertIsolation(
                        new MemoryIsolation.Context("agent-a", null, "wf-a", null),
                        true, false));

        assertEquals(Set.of("tenantId", "sessionId"), Set.copyOf(error.missingFields()));
        MemoryIsolation.Context legacy = MemoryIsolation.assertIsolation(
                new MemoryIsolation.Context("agent-a", null, "wf-a", null), true, true);
        assertEquals(MemoryIsolation.LEGACY_PLACEHOLDER, legacy.tenantId());
        assertEquals(MemoryIsolation.LEGACY_PLACEHOLDER, legacy.sessionId());
    }

    @Test
    void strictRowCheckRejectsMissingWorkflowButPermissiveCompatibilityDoesNot() {
        MemoryRecord row = MemoryRecord.atomic("fact", "value",
                "lifecycle:l1-extraction;tenant=tenant-a",
                System.currentTimeMillis(), 0.9, MemoryDomain.USER);
        MemoryIsolation.Filter strict = new MemoryIsolation.Filter(
                "tenant-a", "agent-a", "workflow-a", "session-a", true);
        MemoryIsolation.Filter permissive = MemoryIsolation.Filter.permissive(
                "tenant-a", "agent-a", "workflow-a", "session-a");

        assertFalse(MemoryIsolation.rowMatchesIsolation(row, "agent-a", strict));
        assertTrue(MemoryIsolation.rowMatchesIsolation(row, "agent-a", permissive));
    }

    @Test
    void recallRechecksRowsAfterALeakyBackendReturnsCrossTenantCandidates() {
        MemoryRecord leaked = MemoryRecord.atomic("leaked", "secret",
                "lifecycle:l1-extraction;agent=other-agent;tenant=tenant-b;workflow=wf;session=s",
                System.currentTimeMillis(), 0.99, MemoryDomain.USER);
        VersionedMemoryStore store = new LeakyStore(leaked);
        MemoryRecallHook.RecallResult result = new MemoryRecallHook(store).recall(
                new MemoryRecallHook.RecallRequest("agent-a", "tenant-a", "wf", "s",
                        "secret", 4, 1_000));

        assertTrue(result.authorized());
        assertTrue(result.records().isEmpty());
        assertTrue(result.permissionFilteredCount() >= 1);
    }

    private static final class LeakyStore extends VersionedMemoryStore {
        private final MemoryRecord leaked;

        private LeakyStore(MemoryRecord leaked) {
            super(new EmptyProvider());
            this.leaked = leaked;
        }

        @Override
        public MemoryIsolation.QueryResult queryByLayer(String agentId, MemoryLayer layer,
                                                        MemoryIsolation.Filter ignored) {
            return new MemoryIsolation.QueryResult(
                    layer == MemoryLayer.L1 ? List.of(leaked) : List.of(), 0);
        }
    }

    private static final class EmptyProvider implements MemoryProvider {
        private final Map<String, List<MemoryRecord>> rows = new ConcurrentHashMap<>();

        @Override
        public boolean store(String agentId, MemoryRecord record) {
            rows.computeIfAbsent(agentId, ignored -> new ArrayList<>()).add(record);
            return true;
        }

        @Override public String retrieve(String agentId, String query) { return ""; }

        @Override public void clear(String agentId) { rows.remove(agentId); }

        @Override public String providerName() { return "empty"; }
    }
}
