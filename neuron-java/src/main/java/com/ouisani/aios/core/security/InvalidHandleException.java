package com.ouisani.aios.core.security;

/**
 * 无效句柄异常 — Agent 尝试使用无效或已关闭的句柄访问 VFS 节点时抛出。
 *
 * <h3>OS 类比: Windows ERROR_INVALID_HANDLE / Linux EBADF</h3>
 * Windows 返回 ERROR_INVALID_HANDLE (6)，Linux 返回 EBADF (9)。
 * AIOS 抛出此异常，表示 Agent 使用的句柄不存在或已被关闭。
 *
 * @see com.ouisani.aios.core.security.ObjectManager
 */
public class InvalidHandleException extends RuntimeException {

    private final int handle;

    public InvalidHandleException(int handle) {
        super("Invalid handle: 0x" + Integer.toHexString(handle).toUpperCase());
        this.handle = handle;
    }

    public InvalidHandleException(int handle, String detail) {
        super("Invalid handle: 0x" + Integer.toHexString(handle).toUpperCase() + " — " + detail);
        this.handle = handle;
    }

    public int handle() {
        return handle;
    }
}
