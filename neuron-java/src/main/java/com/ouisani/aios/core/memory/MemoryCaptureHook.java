package com.ouisani.aios.core.memory;

import com.google.gson.JsonObject;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.LifecycleResult;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.TurnInput;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import com.ouisani.aios.core.network.EventBus;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Agent-turn capture boundary for the memory lifecycle.
 *
 * <p>The hook is deliberately small: it accepts the immutable turn snapshot,
 * queues the L0 capture, and lets {@link MemoryLifecyclePipeline} continue with
 * asynchronous L1-L3 processing.  A failed/interrupted turn can use
 * {@link #capturePartial(TurnInput)} to retain evidence without promoting it.
 * The hook never executes model/tool work synchronously.</p>
 */
public final class MemoryCaptureHook {

    public static final String ACCEPTED_CHANNEL = "memory.capture.accepted";
    public static final String FAILED_CHANNEL = "memory.capture.failed";

    private final MemoryLifecyclePipeline pipeline;

    public MemoryCaptureHook(MemoryLifecyclePipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline must not be null");
    }

    /**
     * Capture a completed turn.  The returned future completes after L0-L3;
     * queue admission and all persistence remain asynchronous to the caller.
     */
    public CompletableFuture<LifecycleResult> captureCompleted(TurnInput input) {
        Objects.requireNonNull(input, "input must not be null");
        emit(ACCEPTED_CHANNEL, input, "completed");
        CompletableFuture<LifecycleResult> future = pipeline.submit(input);
        future.whenComplete((ignored, error) -> {
            if (error != null) emit(FAILED_CHANNEL, input, error.getMessage());
        });
        return future;
    }

    /** Capture only raw L0 evidence for an interrupted or failed turn. */
    public CompletableFuture<MemoryRecord> capturePartial(TurnInput input) {
        Objects.requireNonNull(input, "input must not be null");
        emit(ACCEPTED_CHANNEL, input, "partial");
        CompletableFuture<MemoryRecord> future = pipeline.capture(input);
        future.whenComplete((ignored, error) -> {
            if (error != null) emit(FAILED_CHANNEL, input, error.getMessage());
        });
        return future;
    }

    public MemoryLifecyclePipeline pipeline() {
        return pipeline;
    }

    private static void emit(String channel, TurnInput input, String mode) {
        JsonObject payload = new JsonObject();
        payload.addProperty("agentId", input.agentId());
        payload.addProperty("turnId", input.turnId());
        payload.addProperty("mode", mode);
        if (input.tenantId() != null) payload.addProperty("tenantId", input.tenantId());
        if (input.workflowId() != null) payload.addProperty("workflowId", input.workflowId());
        if (input.sessionId() != null) payload.addProperty("sessionId", input.sessionId());
        EventBus.instance().broadcast(channel, payload.toString());
    }
}
