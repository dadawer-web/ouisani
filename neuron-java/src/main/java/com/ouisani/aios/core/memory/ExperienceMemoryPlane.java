package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.LifecycleResult;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.TurnInput;
import com.ouisani.aios.core.memory.providers.MemoryRecord;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Facade for Neuron's in-process long-term experience plane.
 *
 * <p>Capture is queued and continues through L0→L3. Recall returns bounded,
 * low-trust external context; it never writes the result into execution
 * memory or upgrades it to a system instruction.</p>
 */
public final class ExperienceMemoryPlane {

    private final LongTermMemoryAdapter adapter;

    public ExperienceMemoryPlane(MemoryCaptureHook captureHook,
                                 MemoryRecallHook recallHook) {
        this(new LocalLongTermMemoryAdapter(captureHook, recallHook));
    }

    public ExperienceMemoryPlane(LongTermMemoryAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
    }

    public MemoryPlane plane() {
        return MemoryPlane.EXPERIENCE_SIDECAR;
    }

    public CompletableFuture<LifecycleResult> captureCompleted(TurnInput input) {
        return captureCompleted(input, null);
    }

    public CompletableFuture<LifecycleResult> captureCompleted(TurnInput input,
                                                               MemoryAccessContext context) {
        return adapter.capture(input, context);
    }

    public CompletableFuture<MemoryRecord> capturePartial(TurnInput input) {
        return capturePartial(input, null);
    }

    public CompletableFuture<MemoryRecord> capturePartial(TurnInput input,
                                                          MemoryAccessContext context) {
        return adapter.capturePartial(input, context);
    }

    public MemoryRecallHook.RecallResult recall(MemoryRecallHook.RecallRequest request,
                                                MemoryAccessContext context) {
        return adapter.recall(request, context);
    }

    public LongTermMemoryAdapter adapter() {
        return adapter;
    }

    public String adapterName() {
        return adapter.adapterName();
    }
}
