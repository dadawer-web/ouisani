package com.ouisani.aios.core.syscall.schema;

import java.util.Set;

/**
 * Storage 命名空间载荷 — VFS/存储 syscall 的强类型契约。
 * <p>
 * 将所有类文件系统操作标准化为统一 ABI：
 * <ul>
 *   <li>{@code read} — 从路径读取数据</li>
 *   <li>{@code write} — 向路径写入数据（覆盖）</li>
 *   <li>{@code append} — 向路径追加数据</li>
 * </ul>
 * <p>
 * OS 类比: Linux 的 pread/pwrite 系统调用参数结构体。
 *
 * @param path 操作的 VFS 路径（如 "/dev/shm/blackboard"）
 * @param data 写入/追加的数据（读操作时为 null）
 * @param mode I/O 模式: "read"、"write" 或 "append"
 */
public record StoragePayload(
        String path,
        String data,
        String mode
) implements SyscallPayload {

    /** Legal mode values. */
    public static final Set<String> VALID_MODES = Set.of("read", "write", "append");

    public StoragePayload {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Storage payload requires a non-empty path");
        }
        if (mode == null || !VALID_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                    "Storage mode must be one of " + VALID_MODES + ", got: " + mode);
        }
        if (("write".equals(mode) || "append".equals(mode)) && data == null) {
            throw new IllegalArgumentException("Storage '" + mode + "' mode requires non-null data");
        }
    }

    /**
     * Convenience constructor for read mode.
     */
    public static StoragePayload read(String path) {
        return new StoragePayload(path, null, "read");
    }

    /**
     * Convenience constructor for write mode.
     */
    public static StoragePayload write(String path, String data) {
        return new StoragePayload(path, data, "write");
    }

    /**
     * Convenience constructor for append mode.
     */
    public static StoragePayload append(String path, String data) {
        return new StoragePayload(path, data, "append");
    }
}
