package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.ipc.MemoryScope;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.LifecycleResult;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.TurnInput;
import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedMemoryBridgeTest {

    private MemoryLifecyclePipeline pipeline;
    private MemoryAssetRegistry assets;

    @AfterEach
    void closePipeline() {
        if (pipeline != null) pipeline.close();
        if (assets != null) assets.clearForTest();
    }

    @Test
    void bridgeKeepsExecutionAndExperiencePlanesDistinctAndQueuesCapture() throws Exception {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        pipeline = new MemoryLifecyclePipeline(store, 1, 8);
        assets = new MemoryAssetRegistry();
        GovernedMemoryBridge bridge = new GovernedMemoryBridge(
                new ExecutionMemoryPlane(),
                new ExperienceMemoryPlane(new MemoryCaptureHook(pipeline),
                        new MemoryRecallHook(store, MemoryRecallHook.RecallOptions.defaults(), assets)),
                assets);

        assertEquals(MemoryPlane.EXECUTION, bridge.executionPlane().plane());
        assertEquals(MemoryPlane.EXPERIENCE_SIDECAR, bridge.experiencePlane().plane());
        MemoryAccessContext context = MemoryAccessContext.of(
                "bridge-agent", "tenant-a", "workflow-a", null);
        TurnInput input = new TurnInput("tenant-a", "workflow-a", "session-a",
                "bridge-agent", "turn-a", "[fact] prefers concise output", "ack", List.of());

        var accepted = bridge.captureCompleted(input, context).get(5, TimeUnit.SECONDS);
        assertTrue(accepted.allowed());
        LifecycleResult lifecycle = accepted.value();
        assertNotNull(lifecycle);
        assertEquals(MemoryLayer.L0, lifecycle.l0().layer());
        assertTrue(store.listByLayer("bridge-agent", MemoryLayer.L1).size() >= 1);
    }

    @Test
    void bridgeDeniesCrossAgentCaptureBeforeQueueAdmission() throws Exception {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        pipeline = new MemoryLifecyclePipeline(store, 1, 8);
        assets = new MemoryAssetRegistry();
        GovernedMemoryBridge bridge = new GovernedMemoryBridge(
                new MemoryCaptureHook(pipeline), new MemoryRecallHook(store), assets);
        TurnInput input = TurnInput.of("bridge-agent", "turn-deny", "hello", "reply");

        var denied = bridge.captureCompleted(input,
                MemoryAccessContext.of("different-agent", null, null, null))
                .get(5, TimeUnit.SECONDS);
        assertFalse(denied.allowed());
        assertEquals("agent_identity_mismatch", denied.decision().reason());
        assertEquals(0, pipeline.stats().submitted());
    }

    @Test
    void explicitExecutionEvidenceCrossesOnlyThroughBridgeAndRecallIsLowTrust() {
        VersionedMemoryStore store = new VersionedMemoryStore(new FakeProvider());
        pipeline = new MemoryLifecyclePipeline(store, 1, 8);
        assets = new MemoryAssetRegistry();
        GovernedMemoryBridge bridge = new GovernedMemoryBridge(
                new MemoryCaptureHook(pipeline), new MemoryRecallHook(store), assets);
        MemoryAccessContext context = MemoryAccessContext.of(
                "bridge-agent", "tenant-a", "workflow-a", null);

        String namespace = "bridge-execution-" + System.nanoTime();
        com.ouisani.aios.core.ipc.MemoryRecord executionRecord =
                com.ouisani.aios.core.ipc.MemoryRecord.draft(
                        namespace, "state-1", MemoryScope.TASK, "bridge-agent",
                        "workflow-a", "tenant-a", MemoryLayer.L1,
                        "execution checkpoint", "test;agent=bridge-agent;tenant=tenant-a");
        bridge.executionPlane().write(executionRecord, context);

        TurnInput input = new TurnInput("tenant-a", "workflow-a", "session-a",
                "bridge-agent", "turn-evidence", "summarize state", "done", List.of());
        var captured = bridge.captureExecutionEvidence(input, Set.of(namespace), context)
                .join();
        assertTrue(captured.allowed());
        assertTrue(captured.value().l0().content().contains("execution-memory:"));

        String source = "test;agent=bridge-agent;tenant=tenant-a;workflow=workflow-a;session=session-a";
        store.store("bridge-agent", MemoryRecord.atomic(
                "recall-1", "durable preference", source, System.currentTimeMillis(),
                0.9, MemoryDomain.USER));
        var recalled = bridge.recallForPrompt(
                new MemoryRecallHook.RecallRequest("bridge-agent", "tenant-a",
                        "workflow-a", "session-a", "durable preference", 4, 2_000),
                context);
        assertTrue(recalled.allowed());
        assertTrue(recalled.value().context().contains("<external_memory"));
        assertTrue(recalled.value().context().contains("instruction=\"none\""));
        assertEquals(1, bridge.executionPlane().list(namespace, context).size(),
                "recall never writes experience context into execution memory");
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

        @Override public void clear(String agentId) { data.remove(agentId); }

        @Override public String providerName() { return "bridge-fake"; }
    }
}
