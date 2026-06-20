package com.ouisani.aios.user.bridge.rpa;

/**
 * 权限拒绝异常 — 当未授权的组件尝试访问宿主物理资源时抛出。
 * <p>
 * 任何试图调用 {@link HostRpaManager} 但未持有有效 SYS_ADMIN
 * SecurityToken 的操作，都会被此异常阻止。
 * <p>
 * 这是 AIOS 安全边界的关键防线 — 防止沙箱逃逸到宿主机物理层。
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }

    public PermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
