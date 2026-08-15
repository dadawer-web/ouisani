package com.ouisani.aios.core.memory;

import com.google.gson.JsonObject;
import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.LifecycleResult;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.QueueStats;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.ToolObservation;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.TurnInput;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import com.ouisani.aios.core.network.EventBus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-level bridge between Agent turn events and the configured memory
 * lifecycle pipeline. It also closes the recall-to-turn loop so citation,
 * unused-recall, and task-success lift metrics can be recorded after the
 * assistant response. It is intentionally best-effort: memory persistence
 * and observability must never turn a successful Agent response into a failed response.
 */
public final class MemoryLifecycleRuntime {

    public static final String TURN_STARTED_CHANNEL = "agent.turn.started";
    public static final String TURN_COMPLETED_CHANNEL = "agent.turn.completed";
    public static final String TURN_FAILED_CHANNEL = "agent.turn.failed";

    private static final Object LOCK = new Object();
    private static volatile VersionedMemoryStore configuredStore;
    private static volatile MemoryLifecyclePipeline pipeline;
    private static volatile MemoryCaptureHook captureHook;
    private static volatile MemoryRecallHook recallHook;
    private static volatile GovernedMemoryBridge bridge;
    private static volatile MemoryRecallHook.RecallOptions recallOptions =
            MemoryRecallHook.RecallOptions.defaults();
    private static final ConcurrentMap<String, MemoryRecallHook.RecallResult> ACTIVE_RECALLS =
            new ConcurrentHashMap<>();

    private MemoryLifecycleRuntime() {
    }

    /** Configure the runtime from the application's primary versioned store. */
    public static void configure(VersionedMemoryStore store) {
        configure(store, recallOptions);
    }

    /** Configure the runtime and its bounded recall policy. */
    public static void configure(VersionedMemoryStore store,
                                 MemoryRecallHook.RecallOptions options) {
        MemoryRecallHook.RecallOptions effectiveOptions = options == null
                ? MemoryRecallHook.RecallOptions.defaults() : options;
        synchronized (LOCK) {
            if (configuredStore == store && java.util.Objects.equals(recallOptions, effectiveOptions)
                    && (bridge != null || store == null)) return;
            MemoryLifecyclePipeline previous = pipeline;
            configuredStore = store;
            recallOptions = effectiveOptions;
            pipeline = store == null ? null : new MemoryLifecyclePipeline(store);
            captureHook = pipeline == null ? null : new MemoryCaptureHook(pipeline);
            recallHook = store != null
                    ? new MemoryRecallHook(store, effectiveOptions) : null;
            ExperienceMemoryPlane experience = captureHook == null || recallHook == null ? null
                    : new ExperienceMemoryPlane(captureHook, recallHook);
            bridge = experience == null ? null
                    : new GovernedMemoryBridge(new ExecutionMemoryPlane(), experience,
                    MemoryAssetRegistry.global());
            if (previous != null) previous.close();
        }
    }

    /** Current pipeline, primarily useful for observability and tests. */
    public static MemoryLifecyclePipeline pipeline() {
        return pipeline;
    }

    public static QueueStats stats() {
        MemoryLifecyclePipeline current = pipeline;
        return current == null ? new QueueStats(0, 0, 0, 0, 0, 0) : current.stats();
    }

    /** The governed bridge used by Agent turn/tool adapters. */
    public static GovernedMemoryBridge bridge() {
        return bridge;
    }

    /**
     * Publish the beginning of a turn.  Capture intentionally happens at the
     * completed/failed boundary so the L0 record contains the assistant and
     * tool evidence for the whole turn rather than a user-only placeholder.
     * The return type is retained for source compatibility with integrations
     * that used to await the old start-time capture.
     */
    public static CompletableFuture<MemoryRecord> turnStarted(
            String tenantId,
            String workflowId,
            String sessionId,
            String agentId,
            String turnId,
            String userMessage) {
        MemoryAccessContext context = contextFor(agentId, tenantId, workflowId);
        TurnInput input = new TurnInput(effective(tenantId, context.effectiveTenantId()),
                effective(workflowId, context.effectiveWorkflowId()), sessionId, agentId,
                turnId, userMessage, "", List.of());
        MemoryAssetRegistry.global().ensureChatMemory(agentId, tenantId);
        emitTurn(TURN_STARTED_CHANNEL, input, null);
        ensurePipeline();
        return CompletableFuture.completedFuture(null);
    }

    /** Capture a completed Agent turn, then asynchronously run L1-L3. */
    public static CompletableFuture<LifecycleResult> turnCompleted(
            String tenantId,
            String workflowId,
            String sessionId,
            String agentId,
            String turnId,
            String userMessage,
            String assistantResponse,
            List<ToolObservation> toolObservations) {
        MemoryAccessContext context = contextFor(agentId, tenantId, workflowId);
        TurnInput input = new TurnInput(effective(tenantId, context.effectiveTenantId()),
                effective(workflowId, context.effectiveWorkflowId()), sessionId, agentId,
                turnId, userMessage, assistantResponse, toolObservations);
        emitTurn(TURN_COMPLETED_CHANNEL, input, null);
        finishRecall(agentId, turnId, assistantResponse, true);
        ensurePipeline();
        GovernedMemoryBridge activeBridge = bridge;
        return activeBridge == null
                ? CompletableFuture.completedFuture(null)
                : activeBridge.captureCompleted(input, context)
                .thenApply(MemoryBridgeResult::value);
    }

    /** Report a failed/interrupted turn without attempting semantic promotion. */
    public static void turnFailed(
            String tenantId,
            String workflowId,
            String sessionId,
            String agentId,
            String turnId,
            String userMessage,
            String partialResponse,
            Throwable failure) {
        MemoryAccessContext context = contextFor(agentId, tenantId, workflowId);
        TurnInput input = new TurnInput(effective(tenantId, context.effectiveTenantId()),
                effective(workflowId, context.effectiveWorkflowId()), sessionId, agentId,
                turnId, userMessage, partialResponse, List.of());
        emitTurn(TURN_FAILED_CHANNEL, input,
                failure == null ? "unknown" : failure.getMessage());
        finishRecall(agentId, turnId, partialResponse, false);
        // A failed turn is still useful raw evidence.  Do not run L1-L3.
        ensurePipeline();
        GovernedMemoryBridge activeBridge = bridge;
        if (activeBridge != null) {
            activeBridge.capturePartial(input, context);
        }
    }

    /**
     * Recall related L1-L3 records before prompt construction. A missing
     * store, denied delegation, timeout, or malformed provider result returns
     * a structured degraded result and never fails the Agent turn.
     */
    public static MemoryRecallHook.RecallResult recall(
            String tenantId, String workflowId, String sessionId,
            String agentId, String query) {
        return recall(tenantId, workflowId, sessionId, agentId, query, null);
    }

    /** Recall variant associated with an Agent turn for outcome metrics. */
    public static MemoryRecallHook.RecallResult recall(
            String tenantId, String workflowId, String sessionId,
            String agentId, String query, String turnId) {
        return recall(tenantId, workflowId, sessionId, agentId, query, turnId, null);
    }

    /** Recall with an explicit loadout for adapters that are not running under a child token. */
    public static MemoryRecallHook.RecallResult recall(
            String tenantId, String workflowId, String sessionId,
            String agentId, String query, String turnId,
            MemoryAssetLoadout loadout) {
        MemoryRecallHook.RecallResult result;
        try {
            MemoryAssetRegistry.global().ensureChatMemory(agentId, tenantId);
            ensurePipeline();
            GovernedMemoryBridge activeBridge = bridge;
            if (activeBridge == null) {
                result = MemoryRecallHook.RecallResult.unavailable(
                        "memory_store_unavailable", "runtime", "memory recall is not configured");
            } else if (loadout == null) {
                MemoryRecallHook.RecallRequest request = new MemoryRecallHook.RecallRequest(
                        agentId, tenantId, workflowId, sessionId, query, 8, 6_000);
                MemoryBridgeResult<MemoryRecallHook.RecallResult> bridgeResult =
                        activeBridge.recallForPrompt(request,
                                contextFor(agentId, tenantId, workflowId));
                result = bridgeResult.value() == null
                        ? deniedRecall(bridgeResult) : bridgeResult.value();
            } else {
                MemoryBridgeResult<MemoryRecallHook.RecallResult> bridgeResult =
                        activeBridge.recallForPrompt(agentId, tenantId, workflowId, sessionId,
                                query, loadout, contextFor(agentId, tenantId, workflowId));
                result = bridgeResult.value() == null
                        ? deniedRecall(bridgeResult) : bridgeResult.value();
            }
        } catch (RuntimeException error) {
            // Recall is context enrichment, never a hard dependency of the
            // prompt or Action Gate.
            result = MemoryRecallHook.RecallResult.unavailable(
                    "memory_recall_runtime_failed", "runtime",
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        if (turnId != null && !turnId.isBlank()) trackRecall(turnId, result);
        return result;
    }

    /** Convenience projection used by QueryEngine's ephemeral context path. */
    public static String recallContext(String tenantId, String workflowId,
                                       String sessionId, String agentId, String query) {
        return recall(tenantId, workflowId, sessionId, agentId, query).context();
    }

    /** Process-wide governed asset registry used by lifecycle and recall hooks. */
    public static MemoryAssetRegistry assetRegistry() {
        return MemoryAssetRegistry.global();
    }

    /** Explicit shutdown hook for application shutdown and isolated tests. */
    public static void shutdown() {
        synchronized (LOCK) {
            MemoryLifecyclePipeline current = pipeline;
            pipeline = null;
            configuredStore = null;
            captureHook = null;
            recallHook = null;
            bridge = null;
            recallOptions = MemoryRecallHook.RecallOptions.defaults();
            ACTIVE_RECALLS.clear();
            if (current != null) current.close();
        }
    }

    private static MemoryLifecyclePipeline ensurePipeline() {
        VersionedMemoryStore primary = VersionedMemoryStore.getPrimaryStore();
        MemoryLifecyclePipeline current = pipeline;
        if (primary == null) return current;
        if (current == null || configuredStore != primary) {
            configure(primary);
            current = pipeline;
        }
        return current;
    }

    /** Prefer an already-bound signed child context, otherwise create a root turn context. */
    private static MemoryAccessContext contextFor(String agentId, String tenantId,
                                                  String workflowId) {
        MemoryAccessContext current = MemoryAccessContext.current();
        if (current != null && current.hasIdentity() && agentId.equals(current.agentId())
                && (tenantId == null || tenantId.equals(current.effectiveTenantId()))
                && (workflowId == null || workflowId.equals(current.effectiveWorkflowId()))) {
            return current;
        }
        return MemoryAccessContext.of(agentId, tenantId, workflowId, null);
    }

    private static String effective(String requested, String contextValue) {
        return requested == null || requested.isBlank() ? contextValue : requested;
    }

    private static MemoryRecallHook.RecallResult deniedRecall(
            MemoryBridgeResult<MemoryRecallHook.RecallResult> bridgeResult) {
        String reason = bridgeResult == null || bridgeResult.decision() == null
                ? "memory_bridge_denied" : bridgeResult.decision().reason();
        return new MemoryRecallHook.RecallResult(List.of(), "", false, reason, 1, 0);
    }

    private static void emitTurn(String channel, TurnInput input, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("agentId", input.agentId());
        payload.addProperty("turnId", input.turnId());
        if (input.tenantId() != null) payload.addProperty("tenantId", input.tenantId());
        if (input.workflowId() != null) payload.addProperty("workflowId", input.workflowId());
        if (input.sessionId() != null) payload.addProperty("sessionId", input.sessionId());
        if (error != null) payload.addProperty("error", error);
        EventBus.instance().broadcast(channel, payload.toString());
    }

    private static void finishRecall(String agentId, String turnId,
                                     String assistantResponse, boolean succeeded) {
        if (turnId == null || turnId.isBlank()) return;
        MemoryRecallHook.RecallResult result = ACTIVE_RECALLS.remove(turnId);
        if (result != null) {
            MemoryRecallMetrics.recordOutcome(agentId, turnId, result,
                    assistantResponse, succeeded);
        }
    }

    private static void trackRecall(String turnId, MemoryRecallHook.RecallResult result) {
        if (ACTIVE_RECALLS.size() >= 4_096 && !ACTIVE_RECALLS.containsKey(turnId)) {
            String oldest = ACTIVE_RECALLS.keySet().stream().findFirst().orElse(null);
            if (oldest != null) ACTIVE_RECALLS.remove(oldest);
        }
        ACTIVE_RECALLS.put(turnId, result);
    }
}
