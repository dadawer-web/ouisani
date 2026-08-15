package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.LifecycleResult;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.ToolObservation;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.TurnInput;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLifecyclePipelineTest {

    @Test
    void completesL0ToL3AfterRepeatedEvidence() throws Exception {
        FakeProvider provider = new FakeProvider();
        VersionedMemoryStore store = new VersionedMemoryStore(provider);
        try (MemoryLifecyclePipeline pipeline = new MemoryLifecyclePipeline(store, 1, 8)) {
            TurnInput first = new TurnInput(
                    "tenant-a", "workflow-a", "session-a", "agent-a", "turn-1",
                    "[fact] 用户偏好简洁回答。\n[constraint] 不要编造来源。",
                    "收到，我会遵守。",
                    List.of(new ToolObservation("search", "[event] 已完成资料检索。")));
            LifecycleResult result1 = pipeline.submit(first).get(5, TimeUnit.SECONDS);

            assertEquals(MemoryLayer.L0, result1.l0().layer());
            assertEquals(4, result1.l1().size());
            assertTrue(result1.l2().isPresent());
            assertTrue(result1.l3().isEmpty(), "single-turn evidence must not be promoted");
            assertEquals(1, store.listByLayer("agent-a", MemoryLayer.L0).size());
            assertEquals(4, store.listByLayer("agent-a", MemoryLayer.L1).size());
            assertEquals(1, store.listByLayer("agent-a", MemoryLayer.L2).size());

            TurnInput second = new TurnInput(
                    "tenant-a", "workflow-a", "session-a", "agent-a", "turn-2",
                    "[fact] 用户偏好简洁回答。\n[constraint] 不要编造来源。",
                    "好的。",
                    List.of());
            LifecycleResult result2 = pipeline.submit(second).get(5, TimeUnit.SECONDS);

            assertFalse(result2.l3().isEmpty());
            String repeatedKey = result1.l1().get(0).key();
            assertTrue(store.history("agent-a", repeatedKey).size() >= 1);
            assertTrue(result2.promotedToL3());
            assertTrue(store.current("agent-a", result2.l0().key()).content().contains("assistant:"));
            assertTrue(pipeline.stats().completed() >= 2);
            assertEquals(0, pipeline.stats().failed());
        }
    }

    @Test
    void explicitStableEvidencePromotesOnFirstTurn() throws Exception {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        try (MemoryLifecyclePipeline pipeline = new MemoryLifecyclePipeline(store, 1, 4)) {
            LifecycleResult result = pipeline.submit(TurnInput.of(
                    "agent-stable", "turn-stable", "[stable] 始终使用中文。", "明白。"))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(2, result.l1().size());
            assertEquals(1, result.l3().size());
            assertEquals(MemoryLayer.L3, result.l3().get(0).layer());
        }
    }

    @Test
    void failedTurnStillCapturesOnlyL0() throws Exception {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        try (MemoryLifecyclePipeline pipeline = new MemoryLifecyclePipeline(store, 1, 4)) {
            TurnInput input = TurnInput.of("agent-failed", "turn-failed", "用户输入", "partial");
            MemoryRecord l0 = pipeline.capture(input).get(5, TimeUnit.SECONDS);

            assertNotNull(l0);
            assertEquals(MemoryLayer.L0, l0.layer());
            assertEquals(1, store.listByLayer("agent-failed", MemoryLayer.L0).size());
            assertEquals(0, store.listByLayer("agent-failed", MemoryLayer.L1).size());
            assertFalse(pipeline.isClosed());
        }
    }

    private static String fingerprint(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                    value.trim().toLowerCase(java.util.Locale.ROOT)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static final class FakeProvider implements MemoryProvider {
        private final Map<String, List<MemoryRecord>> stored = new ConcurrentHashMap<>();

        @Override
        public boolean store(String agentId, MemoryRecord record) {
            stored.computeIfAbsent(agentId, ignored -> new ArrayList<>()).add(record);
            return true;
        }

        @Override
        public String retrieve(String agentId, String query) {
            return stored.getOrDefault(agentId, List.of()).stream()
                    .map(MemoryRecord::content)
                    .filter(content -> content.contains(query))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
        }

        @Override
        public void clear(String agentId) {
            stored.remove(agentId);
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
