package com.ouisani.aios.core.syscall.schema;

import java.util.Set;

/**
 * Memory namespace payload — strongly-typed contract for vector memory syscalls.
 * <p>
 * Provides CRUD operations over the AIOS semantic memory subsystem:
 * <ul>
 *   <li>{@code store} — persist a memory entry</li>
 *   <li>{@code retrieve} — recall memories by semantic query</li>
 *   <li>{@code delete} — remove a memory entry</li>
 * </ul>
 *
 * @param operation      the memory operation: "store", "retrieve", or "delete"
 * @param query          the semantic query for retrieval, or the key for delete
 * @param memoryContent  the content to store (only used with "store" operation)
 */
public record MemoryPayload(
        String operation,
        String query,
        String memoryContent
) implements SyscallPayload {

    /** Legal operation values. */
    public static final Set<String> VALID_OPERATIONS = Set.of("store", "retrieve", "delete");

    public MemoryPayload {
        if (operation == null || !VALID_OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException(
                    "Memory operation must be one of " + VALID_OPERATIONS + ", got: " + operation);
        }
        if ("store".equals(operation) && (memoryContent == null || memoryContent.isEmpty())) {
            throw new IllegalArgumentException("Memory 'store' operation requires non-empty memoryContent");
        }
        if ("retrieve".equals(operation) && (query == null || query.isEmpty())) {
            throw new IllegalArgumentException("Memory 'retrieve' operation requires non-empty query");
        }
        if ("delete".equals(operation) && (query == null || query.isEmpty())) {
            throw new IllegalArgumentException("Memory 'delete' operation requires non-empty query");
        }
    }
}
