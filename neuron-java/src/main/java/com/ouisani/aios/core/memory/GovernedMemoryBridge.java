package com.ouisani.aios.core.memory;

import com.google.gson.JsonObject;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.ipc.MemoryRecord;
import com.ouisani.aios.core.ipc.TraceContext;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.LifecycleResult;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.ToolObservation;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.TurnInput;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.tool.DelegationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The only supported bridge between execution memory and the experience
 * long-term experience plane.
 *
 * <p>It applies execution identity/delegation checks before either plane is
 * touched, records an auditable allow/deny decision, and keeps the crossing
 * directional:</p>
 * <ul>
 *   <li>execution → experience: explicit turn/tool evidence is queued for L0-L3;</li>
 *   <li>experience → execution prompt: recall is returned as bounded
 *       {@code external_memory} context only;</li>
 *   <li>experience results are never written back as execution system state by
 *       this class.</li>
 * </ul>
 */
public final class GovernedMemoryBridge {

    public static final String AUDIT_CHANNEL = "memory.bridge.audit";
    public static final String EXTERNAL_MEMORY_BOUNDARY = "external_memory";
    private static final int MAX_EXECUTION_RECORD_CHARS = 4_000;
    private static final int MAX_EXECUTION_EVIDENCE_CHARS = 12_000;

    private final ExecutionMemoryPlane execution;
    private final ExperienceMemoryPlane experience;
    private final MemoryAssetRegistry assets;

    public GovernedMemoryBridge(ExecutionMemoryPlane execution,
                                ExperienceMemoryPlane experience,
                                MemoryAssetRegistry assets) {
        this.execution = Objects.requireNonNull(execution, "execution plane must not be null");
        this.experience = Objects.requireNonNull(experience, "experience plane must not be null");
        this.assets = assets == null ? MemoryAssetRegistry.global() : assets;
    }

    public GovernedMemoryBridge(MemoryCaptureHook captureHook,
                                MemoryRecallHook recallHook,
                                MemoryAssetRegistry assets) {
        this(new ExecutionMemoryPlane(), new ExperienceMemoryPlane(captureHook, recallHook), assets);
    }

    public GovernedMemoryBridge(MemoryCaptureHook captureHook,
                                MemoryRecallHook recallHook) {
        this(captureHook, recallHook, MemoryAssetRegistry.global());
    }

    public ExecutionMemoryPlane executionPlane() {
        return execution;
    }

    public ExperienceMemoryPlane experiencePlane() {
        return experience;
    }

    public MemoryAssetRegistry assetRegistry() {
        return assets;
    }

    /**
     * Queue a completed Agent turn from the execution plane into L0→L3.
     * Authentication happens before queue admission and failures are returned
     * as a structured bridge result rather than thrown into the Agent turn.
     */
    public CompletableFuture<MemoryBridgeResult<LifecycleResult>> captureCompleted(
            TurnInput input, MemoryAccessContext context) {
        Objects.requireNonNull(input, "input must not be null");
        long started = System.nanoTime();
        Authorization authorization = authorize(input.agentId(), input.tenantId(),
                input.workflowId(), context, "capture", "write");
        if (!authorization.allowed()) {
            return CompletableFuture.completedFuture(denied(authorization,
                    MemoryPlane.EXECUTION, MemoryPlane.EXPERIENCE_SIDECAR, "capture", started));
        }
        assets.ensureChatMemory(input.agentId(), input.tenantId());
        return experience.captureCompleted(input, authorization.context()).handle((value, error) -> {
            String reason = error == null ? "accepted" : "experience_capture_failed";
            MemoryBridgeDecision decision = decision(authorization,
                    MemoryPlane.EXECUTION, MemoryPlane.EXPERIENCE_SIDECAR, "capture",
                    reason, value == null ? 0 : 1, started);
            audit(decision, input.sessionId(), errorMessage(error));
            return new MemoryBridgeResult<>(decision, error == null ? value : null);
        });
    }

    /** Queue raw evidence for an interrupted/failed execution turn. */
    public CompletableFuture<MemoryBridgeResult<com.ouisani.aios.core.memory.providers.MemoryRecord>> capturePartial(
            TurnInput input, MemoryAccessContext context) {
        Objects.requireNonNull(input, "input must not be null");
        long started = System.nanoTime();
        Authorization authorization = authorize(input.agentId(), input.tenantId(),
                input.workflowId(), context, "capture_partial", "write");
        if (!authorization.allowed()) {
            return CompletableFuture.completedFuture(denied(authorization,
                    MemoryPlane.EXECUTION, MemoryPlane.EXPERIENCE_SIDECAR,
                    "capture_partial", started));
        }
        assets.ensureChatMemory(input.agentId(), input.tenantId());
        return experience.capturePartial(input, authorization.context()).handle((value, error) -> {
            String reason = error == null ? "accepted" : "experience_capture_failed";
            MemoryBridgeDecision decision = decision(authorization,
                    MemoryPlane.EXECUTION, MemoryPlane.EXPERIENCE_SIDECAR,
                    "capture_partial", reason, value == null ? 0 : 1, started);
            audit(decision, input.sessionId(), errorMessage(error));
            return new MemoryBridgeResult<>(decision, error == null ? value : null);
        });
    }

    /**
     * Explicitly copy selected execution namespaces into the same turn's
     * evidence before queueing it. Empty namespaces are fail-closed and do
     * not mean "all execution memory".
     */
    public CompletableFuture<MemoryBridgeResult<LifecycleResult>> captureExecutionEvidence(
            TurnInput input, Set<String> namespaces, MemoryAccessContext context) {
        Objects.requireNonNull(input, "input must not be null");
        long started = System.nanoTime();
        Authorization authorization = authorize(input.agentId(), input.tenantId(),
                input.workflowId(), context, "capture_execution_evidence", "read");
        if (!authorization.allowed()) {
            return CompletableFuture.completedFuture(denied(authorization,
                    MemoryPlane.EXECUTION, MemoryPlane.EXPERIENCE_SIDECAR,
                    "capture_execution_evidence", started));
        }

        final List<MemoryRecord> records;
        try {
            records = execution.list(namespaces, authorization.context());
        } catch (RuntimeException error) {
            MemoryBridgeDecision decision = decision(authorization,
                    MemoryPlane.EXECUTION, MemoryPlane.EXPERIENCE_SIDECAR,
                    "capture_execution_evidence", "execution_read_failed", 0, started);
            audit(decision, input.sessionId(), errorMessage(error));
            return CompletableFuture.completedFuture(new MemoryBridgeResult<>(decision, null));
        }

        TurnInput enriched = withExecutionEvidence(input, records);
        return captureCompleted(enriched, context).thenApply(result -> {
            MemoryBridgeDecision prior = result.decision();
            MemoryBridgeDecision adjusted = new MemoryBridgeDecision(
                    prior.allowed(), prior.sourcePlane(), prior.targetPlane(),
                    "capture_execution_evidence", prior.reason(), prior.agentId(),
                    prior.tenantId(), prior.workflowId(), prior.traceId(), records.size(),
                    prior.latencyMs());
            audit(adjusted, input.sessionId(), null);
            return new MemoryBridgeResult<>(adjusted, result.value());
        });
    }

    /**
     * Recall experience context for prompt construction. The returned value is
     * explicitly marked as external memory by the hook and remains outside
     * system instructions and the Action Gate.
     */
    public MemoryBridgeResult<MemoryRecallHook.RecallResult> recallForPrompt(
            MemoryRecallHook.RecallRequest request, MemoryAccessContext context) {
        Objects.requireNonNull(request, "request must not be null");
        long started = System.nanoTime();
        Authorization authorization = authorize(request.agentId(), request.tenantId(),
                request.workflowId(), context, "recall", "read");
        if (!authorization.allowed()) {
            return denied(authorization, MemoryPlane.EXPERIENCE_SIDECAR,
                    MemoryPlane.EXECUTION, "recall", started);
        }
        MemoryRecallHook.RecallResult result;
        try {
            result = experience.recall(request, authorization.context());
            if (result == null) {
                result = MemoryRecallHook.RecallResult.unavailable(
                        "memory_bridge_empty_result", "bridge", "experience returned no recall result");
            }
        } catch (RuntimeException error) {
            result = MemoryRecallHook.RecallResult.unavailable(
                    "memory_bridge_recall_failed", "bridge", errorMessage(error));
        }
        MemoryBridgeDecision decision = decision(authorization,
                MemoryPlane.EXPERIENCE_SIDECAR, MemoryPlane.EXECUTION, "recall",
                result.authorized() ? EXTERNAL_MEMORY_BOUNDARY : "experience_recall_denied",
                result.records().size(), started);
        audit(decision, request.sessionId(), result.error() == null
                ? null : result.error().message());
        return new MemoryBridgeResult<>(decision, result);
    }

    /** Recall overload that validates an explicit Agent loadout boundary. */
    public MemoryBridgeResult<MemoryRecallHook.RecallResult> recallForPrompt(
            String agentId, String tenantId, String workflowId, String sessionId,
            String query, MemoryAssetLoadout loadout, MemoryAccessContext context) {
        if (loadout != null && loadout.childAgentId() != null
                && !loadout.childAgentId().equals(agentId)) {
            Authorization denied = Authorization.denied("loadout_agent_mismatch", agentId,
                    tenantId, workflowId, context);
            return denied(denied, MemoryPlane.EXPERIENCE_SIDECAR,
                    MemoryPlane.EXECUTION, "recall", System.nanoTime());
        }
        if (loadout != null && context != null && context.delegationToken() != null
                && !loadout.isSubsetOf(context.delegationToken().delegableMemoryAssets())) {
            Authorization denied = Authorization.denied("loadout_delegation_scope_denied", agentId,
                    tenantId, workflowId, context);
            return denied(denied, MemoryPlane.EXPERIENCE_SIDECAR,
                    MemoryPlane.EXECUTION, "recall", System.nanoTime());
        }
        if (loadout != null) {
            for (String assetId : loadout.assetIds()) {
                if (assets.get(assetId).isEmpty()) {
                    Authorization denied = Authorization.denied("loadout_asset_not_registered",
                            agentId, tenantId, workflowId, context);
                    return denied(denied, MemoryPlane.EXPERIENCE_SIDECAR,
                            MemoryPlane.EXECUTION, "recall", System.nanoTime());
                }
                if (!assets.isRecallAllowed(assetId, context)) {
                    Authorization denied = Authorization.denied("loadout_asset_acl_denied",
                            agentId, tenantId, workflowId, context);
                    return denied(denied, MemoryPlane.EXPERIENCE_SIDECAR,
                            MemoryPlane.EXECUTION, "recall", System.nanoTime());
                }
            }
        }
        MemoryRecallHook.RecallRequest request = new MemoryRecallHook.RecallRequest(
                agentId, tenantId, workflowId, sessionId, query, 8, 6_000,
                loadout == null ? Set.of() : loadout.assetIds(), loadout != null, true);
        return recallForPrompt(request, context);
    }

    private TurnInput withExecutionEvidence(TurnInput input, List<MemoryRecord> records) {
        if (records == null || records.isEmpty()) return input;
        List<ToolObservation> observations = new ArrayList<>(input.toolObservations());
        int remaining = MAX_EXECUTION_EVIDENCE_CHARS;
        for (MemoryRecord record : records) {
            if (remaining <= 0) break;
            String content = record.content() == null ? "" : record.content();
            int length = Math.min(Math.min(MAX_EXECUTION_RECORD_CHARS, remaining), content.length());
            String bounded = content.substring(0, length);
            observations.add(new ToolObservation(
                    "execution-memory:" + record.namespace() + ":" + record.memoryId(), bounded));
            remaining -= length;
        }
        return new TurnInput(input.tenantId(), input.workflowId(), input.sessionId(),
                input.agentId(), input.turnId(), input.userMessage(),
                input.assistantResponse(), observations);
    }

    private Authorization authorize(String agentId, String tenantId, String workflowId,
                                    MemoryAccessContext context, String operation,
                                    String capabilityOperation) {
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        if (caller == null || !caller.hasIdentity()) {
            return Authorization.denied("missing_agent_identity", agentId, tenantId, workflowId, caller);
        }
        if (agentId == null || !agentId.equals(caller.agentId())) {
            return Authorization.denied("agent_identity_mismatch", agentId, tenantId, workflowId, caller);
        }
        DelegationToken token = caller.delegationToken();
        if (token != null) {
            if (!token.isValid()) {
                return Authorization.denied("invalid_delegation_token", agentId, tenantId, workflowId, caller);
            }
            if (!agentId.equals(token.childAgentId())) {
                return Authorization.denied("delegation_identity_mismatch", agentId, tenantId, workflowId, caller);
            }
            if (!caller.allowsNamespace("memory", capabilityOperation)) {
                return Authorization.denied("delegation_memory_" + capabilityOperation + "_denied",
                        agentId, tenantId, workflowId, caller);
            }
        }
        String effectiveTenant = caller.effectiveTenantId();
        if (tenantId != null && !tenantId.equals(effectiveTenant)) {
            return Authorization.denied("tenant_mismatch", agentId, tenantId, workflowId, caller);
        }
        String effectiveWorkflow = caller.effectiveWorkflowId();
        if (workflowId != null && !workflowId.equals(effectiveWorkflow)) {
            return Authorization.denied("workflow_mismatch", agentId, tenantId, workflowId, caller);
        }
        return new Authorization(true, "accepted", agentId,
                tenantId == null ? effectiveTenant : tenantId,
                workflowId == null ? effectiveWorkflow : workflowId,
                caller, operation, TraceContext.getCurrentTraceId());
    }

    private static MemoryBridgeDecision decision(Authorization authorization,
                                                 MemoryPlane source, MemoryPlane target,
                                                 String operation, String reason,
                                                 int records, long started) {
        return new MemoryBridgeDecision(authorization.allowed(), source, target, operation,
                reason, authorization.agentId(), authorization.tenantId(),
                authorization.workflowId(), authorization.traceId(), records,
                elapsedMillis(started));
    }

    private static <T> MemoryBridgeResult<T> denied(Authorization authorization,
                                                     MemoryPlane source, MemoryPlane target,
                                                     String operation, long started) {
        MemoryBridgeDecision decision = MemoryBridgeDecision.denied(source, target, operation,
                authorization.reason(), authorization.agentId(), authorization.tenantId(),
                authorization.workflowId(), authorization.traceId(), elapsedMillis(started));
        audit(decision, null, null);
        return new MemoryBridgeResult<>(decision, null);
    }

    private static void audit(MemoryBridgeDecision decision, String sessionId, String detail) {
        JsonObject payload = new JsonObject();
        payload.addProperty("sourcePlane", decision.sourcePlane().wireName());
        payload.addProperty("targetPlane", decision.targetPlane().wireName());
        payload.addProperty("operation", decision.operation());
        payload.addProperty("allowed", decision.allowed());
        payload.addProperty("reason", decision.reason());
        if (decision.agentId() != null) payload.addProperty("agentId", decision.agentId());
        if (decision.tenantId() != null) payload.addProperty("tenantId", decision.tenantId());
        if (decision.workflowId() != null) payload.addProperty("workflowId", decision.workflowId());
        if (sessionId != null) payload.addProperty("sessionId", sessionId);
        if (decision.traceId() != null) payload.addProperty("traceId", decision.traceId());
        payload.addProperty("recordCount", decision.recordCount());
        payload.addProperty("latencyMs", decision.latencyMs());
        if (detail != null && !detail.isBlank()) payload.addProperty("detail", detail);
        EventBus.instance().broadcast(AUDIT_CHANNEL, payload.toString());
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_PERMISSION, "MEMORY_BRIDGE",
                decision.allowed() ? "BRIDGE_ALLOW" : "BRIDGE_DENY",
                decision.agentId(), decision.operation(), decision.reason(),
                new UnifiedAuditLog.AuditContext(decision.tenantId(), decision.workflowId(),
                        null, decision.traceId(), decision.agentId(), null, null, null, -1)));
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static String errorMessage(Throwable error) {
        if (error == null) return null;
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private record Authorization(boolean allowed, String reason, String agentId,
                                 String tenantId, String workflowId,
                                 MemoryAccessContext context, String operation,
                                 String traceId) {
        static Authorization denied(String reason, String agentId, String tenantId,
                                    String workflowId, MemoryAccessContext context) {
            return new Authorization(false, reason, agentId, tenantId, workflowId, context,
                    "denied", TraceContext.getCurrentTraceId());
        }
    }
}
