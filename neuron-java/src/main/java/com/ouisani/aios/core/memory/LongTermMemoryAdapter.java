package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.LifecycleResult;
import com.ouisani.aios.core.memory.MemoryLifecyclePipeline.TurnInput;
import com.ouisani.aios.core.memory.providers.MemoryRecord;

import java.util.concurrent.CompletableFuture;

/**
 * Application-owned port used by Neuron's governed bridge.
 *
 * <p>The Java kernel knows only this contract. A local implementation may use
 * the in-process lifecycle queue. The seam keeps the long-term experience
 * plane replaceable in tests or by another application-owned implementation
 * without coupling Neuron to a vendor service. It never owns Agent
 * scheduling or execution memory.</p>
 */
public interface LongTermMemoryAdapter extends AutoCloseable {

    /** Capture a completed turn; implementations must not block Agent output. */
    CompletableFuture<LifecycleResult> capture(TurnInput input, MemoryAccessContext context);

    /** Capture raw evidence for a failed/interrupted turn. */
    CompletableFuture<MemoryRecord> capturePartial(TurnInput input, MemoryAccessContext context);

    /** Recall bounded, already-authorized context for prompt construction. */
    MemoryRecallHook.RecallResult recall(MemoryRecallHook.RecallRequest request,
                                         MemoryAccessContext context);

    /** Stable adapter name for diagnostics and telemetry. */
    default String adapterName() {
        return getClass().getSimpleName();
    }

    @Override
    default void close() {
        // The default in-process implementation is process-managed; a custom
        // application-owned implementation may override this when it owns
        // resources.
    }
}
