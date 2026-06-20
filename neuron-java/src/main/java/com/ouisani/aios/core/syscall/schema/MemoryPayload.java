package com.ouisani.aios.core.syscall.schema;

import java.util.Set;

/**
 * Memory 命名空间载荷 — 向量内存 syscall 的强类型契约。
 * <p>
 * 提供 AIOS 语义内存子系统的 CRUD 操作：
 * <ul>
 *   <li>{@code store} — 持久化一条记忆条目</li>
 *   <li>{@code retrieve} — 通过语义查询召回记忆</li>
 *   <li>{@code delete} — 删除一条记忆条目</li>
 * </ul>
 * <p>
 * OS 类比: 共享内存的 shmctl/shmget 操作参数结构体。
 *
 * @param operation     内存操作类型: "store"、"retrieve" 或 "delete"
 * @param query         检索时的语义查询，或删除时的键
 * @param memoryContent 存储的内容（仅 "store" 操作使用）
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
