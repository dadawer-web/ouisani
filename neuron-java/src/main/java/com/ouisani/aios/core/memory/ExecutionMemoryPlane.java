package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.ipc.MemoryRecord;
import com.ouisani.aios.core.ipc.SharedMemoryManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Authenticated facade for Neuron's execution-memory plane.
 *
 * <p>The facade intentionally exposes only scoped operations. Callers must
 * provide the identity that the kernel uses for PRIVATE/TASK/TEAM checks;
 * there is no unscoped "read everything" operation here.</p>
 */
public final class ExecutionMemoryPlane {

    private final SharedMemoryManager manager;

    public ExecutionMemoryPlane() {
        this(SharedMemoryManager.instance());
    }

    public ExecutionMemoryPlane(SharedMemoryManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    public MemoryPlane plane() {
        return MemoryPlane.EXECUTION;
    }

    public MemoryRecord read(String namespace, String memoryId,
                             MemoryAccessContext context) {
        return manager.getMemory(namespace, memoryId, requiredContext(context));
    }

    public List<MemoryRecord> list(String namespace, MemoryAccessContext context) {
        return manager.listMemory(namespace, requiredContext(context));
    }

    /**
     * Read only explicitly named execution namespaces. An empty set is a
     * fail-closed empty projection, not an implicit wildcard.
     */
    public List<MemoryRecord> list(Set<String> namespaces, MemoryAccessContext context) {
        MemoryAccessContext caller = requiredContext(context);
        if (namespaces == null || namespaces.isEmpty()) return List.of();
        List<MemoryRecord> result = new ArrayList<>();
        for (String namespace : namespaces) {
            if (namespace == null || namespace.isBlank()) continue;
            result.addAll(manager.listMemory(namespace, caller));
        }
        return Collections.unmodifiableList(result);
    }

    public MemoryRecord write(MemoryRecord draft, MemoryAccessContext context) {
        return manager.putMemory(Objects.requireNonNull(draft, "draft must not be null"),
                requiredContext(context));
    }

    public Optional<MemoryRecord> compareAndSet(String namespace, String memoryId,
                                                 long expectedVersion, String content,
                                                 String source, MemoryAccessContext context) {
        return manager.compareAndSetMemory(namespace, memoryId, expectedVersion, content,
                source, requiredContext(context));
    }

    /** Authenticated recovery snapshot of records visible to this Agent. */
    public List<MemoryRecord> snapshot(MemoryAccessContext context) {
        return manager.listVisibleMemory(requiredContext(context));
    }

    /** Subscribe to execution-memory changes through the kernel's scoped API. */
    public String subscribe(String namespace, MemoryAccessContext context,
                            Consumer<MemoryRecord> handler) {
        return manager.subscribeMemoryUpdates(namespace, requiredContext(context), handler);
    }

    public boolean unsubscribe(String subscriptionId) {
        return manager.unsubscribeMemoryUpdates(subscriptionId);
    }

    private static MemoryAccessContext requiredContext(MemoryAccessContext context) {
        if (context == null || !context.hasIdentity()) {
            throw new SecurityException("execution memory requires an authenticated Agent context");
        }
        return context;
    }
}
