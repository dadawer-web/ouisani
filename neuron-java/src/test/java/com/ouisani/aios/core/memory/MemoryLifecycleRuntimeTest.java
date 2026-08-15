package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.LifecycleResult;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLifecycleRuntimeTest {

    @AfterEach
    void resetRuntime() {
        VersionedMemoryStore.setPrimaryStore(null);
        MemoryLifecycleRuntime.shutdown();
    }

    @Test
    void agentTurnBridgeOrdersCaptureBeforeSemanticStages() throws Exception {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        VersionedMemoryStore.setPrimaryStore(store);

        MemoryLifecycleRuntime.turnStarted(
                "tenant", "workflow", "session", "agent-runtime", "run-1",
                "[fact] 用户希望答案简洁。")
                .get(5, TimeUnit.SECONDS);
        assertEquals(0, store.listByLayer("agent-runtime", MemoryLayer.L0).size(),
                "L0 is captured at turn completion, not at turn start");
        LifecycleResult result = MemoryLifecycleRuntime.turnCompleted(
                "tenant", "workflow", "session", "agent-runtime", "run-1",
                "[fact] 用户希望答案简洁。", "好的。", List.of())
                .get(5, TimeUnit.SECONDS);

        assertEquals(MemoryLayer.L0, result.l0().layer());
        assertTrue(store.current("agent-runtime", result.l0().key()).content().contains("assistant:"));
        assertTrue(store.listByLayer("agent-runtime", MemoryLayer.L1).size() >= 1);
        assertTrue(MemoryLifecycleRuntime.stats().completed() >= 1);
    }

    private static final class FakeProvider implements MemoryProvider {
        private final Map<String, List<MemoryRecord>> data = new ConcurrentHashMap<>();

        @Override
        public boolean store(String agentId, MemoryRecord record) {
            data.computeIfAbsent(agentId, ignored -> new ArrayList<>()).add(record);
            return true;
        }

        @Override
        public String retrieve(String agentId, String query) {
            return data.getOrDefault(agentId, List.of()).stream()
                    .map(MemoryRecord::content)
                    .filter(value -> value.contains(query))
                    .findFirst().orElse("");
        }

        @Override
        public void clear(String agentId) {
            data.remove(agentId);
        }

        @Override
        public String providerName() {
            return "runtime-fake";
        }
    }
}
