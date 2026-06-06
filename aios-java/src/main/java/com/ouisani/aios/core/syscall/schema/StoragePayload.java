package com.ouisani.aios.core.syscall.schema;

import java.util.Set;

/**
 * Storage namespace payload — strongly-typed contract for VFS/storage syscalls.
 * <p>
 * Standardizes all file-system-like operations under a single ABI:
 * <ul>
 *   <li>{@code read} — read data from a path</li>
 *   <li>{@code write} — write data to a path (overwrite)</li>
 *   <li>{@code append} — append data to a path</li>
 * </ul>
 *
 * @param path  the VFS path to operate on (e.g. "/dev/shm/blackboard")
 * @param data  the data to write/append (null for read operations)
 * @param mode  the I/O mode: "read", "write", or "append"
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
