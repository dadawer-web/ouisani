package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.LifecycleResult;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.TurnInput;
import com.ouisani.aios.core.memory.providers.MemoryRecord;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** In-process adapter used by Neuron's default experience plane. */
public final class LocalLongTermMemoryAdapter implements LongTermMemoryAdapter {

    private final MemoryCaptureHook captureHook;
    private final MemoryRecallHook recallHook;

    public LocalLongTermMemoryAdapter(MemoryCaptureHook captureHook,
                                      MemoryRecallHook recallHook) {
        this.captureHook = Objects.requireNonNull(captureHook, "captureHook must not be null");
        this.recallHook = Objects.requireNonNull(recallHook, "recallHook must not be null");
    }

    @Override
    public CompletableFuture<LifecycleResult> capture(TurnInput input,
                                                       MemoryAccessContext context) {
        return captureHook.captureCompleted(input);
    }

    @Override
    public CompletableFuture<MemoryRecord> capturePartial(TurnInput input,
                                                          MemoryAccessContext context) {
        return captureHook.capturePartial(input);
    }

    @Override
    public MemoryRecallHook.RecallResult recall(MemoryRecallHook.RecallRequest request,
                                                MemoryAccessContext context) {
        return recallHook.recall(request, context);
    }

    @Override
    public String adapterName() {
        return "local-lifecycle";
    }

    public MemoryCaptureHook captureHook() {
        return captureHook;
    }

    public MemoryRecallHook recallHook() {
        return recallHook;
    }
}
