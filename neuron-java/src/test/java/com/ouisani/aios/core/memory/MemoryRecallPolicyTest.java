package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRecallPolicyTest {

    @Test
    void embeddingStrategyFallsBackToKeywordWithStructuredPartialFailure() {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        store.store("agent-policy", MemoryRecord.atomic("fact", "coffee preference",
                "lifecycle:l1-extraction", System.currentTimeMillis(), 0.9, MemoryDomain.USER));

        MemoryRecallHook hook = new MemoryRecallHook(store);
        MemoryRecallHook.RecallOptions options = new MemoryRecallHook.RecallOptions(
                MemoryRecallHook.RecallStrategy.EMBEDDING, 4, 0.1, 128, 800, 500);
        MemoryRecallHook.RecallResult result = hook.recall(
                MemoryRecallHook.RecallRequest.of("agent-policy", "coffee"), options);

        assertTrue(result.authorized());
        assertEquals("keyword-fallback", result.effectiveStrategy());
        assertTrue(result.partial());
        assertNotNull(result.error());
        assertEquals("embedding_unavailable", result.error().code());
        assertEquals(1, result.records().size());
    }

    @Test
    void hybridEmbeddingKeepsStableAndDynamicBlocksSeparateAndHonorsBudget() {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        store.store("agent-policy", MemoryRecord.core("profile", "likes tea",
                "lifecycle:l3-promotion", System.currentTimeMillis(), 0.9, MemoryDomain.USER));
        store.store("agent-policy", MemoryRecord.atomic("fact", "tea project",
                "lifecycle:l1-extraction", System.currentTimeMillis(), 0.9, MemoryDomain.AGENT));

        MemoryRecallHook.EmbeddingProvider embeddings = text ->
                text.toLowerCase().contains("tea") ? new double[]{1, 0} : new double[]{0, 1};
        MemoryRecallHook.RecallOptions options = new MemoryRecallHook.RecallOptions(
                MemoryRecallHook.RecallStrategy.HYBRID, 4, 0.0, 64, 900, 500,
                embeddings);
        MemoryRecallHook.RecallResult result = new MemoryRecallHook(store).recall(
                MemoryRecallHook.RecallRequest.of("agent-policy", "tea"), options);

        assertFalse(result.stableContext().isBlank());
        assertFalse(result.dynamicContext().isBlank());
        assertTrue(result.stableContext().contains("kind=\"stable\""));
        assertTrue(result.dynamicContext().contains("kind=\"dynamic\""));
        assertTrue(result.context().length() <= 900);
        assertEquals("hybrid", result.effectiveStrategy());
    }

    @Test
    void shortInputSkipsDynamicSearchButKeepsStableProfile() {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        store.store("agent-policy", MemoryRecord.core("profile", "stable profile",
                "lifecycle:l3-promotion", System.currentTimeMillis(), 0.9, MemoryDomain.USER));
        store.store("agent-policy", MemoryRecord.atomic("fact", "dynamic fact",
                "lifecycle:l1-extraction", System.currentTimeMillis(), 0.9, MemoryDomain.AGENT));

        MemoryRecallHook.RecallResult result = new MemoryRecallHook(store).recall(
                MemoryRecallHook.RecallRequest.of("agent-policy", "a"));

        assertEquals("", result.dynamicContext());
        assertTrue(result.stableContext().contains("stable profile"));
        assertTrue(result.records().stream().allMatch(record -> record.layer() == MemoryLayer.L3));
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
            return "policy-test";
        }
    }
}
