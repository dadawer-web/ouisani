package com.ouisani.aios.core.syscall;

/**
 * 系统调用异常 — Agent 发出未知或不支持的 syscall 时抛出。
 * <p>
 * OS 类比: Linux 的 ENOSYS (Function not implemented) 错误码。
 */
public class SyscallException extends RuntimeException {

    private final String action;

    public SyscallException(String action) {
        super("Unknown syscall action: '" + action + "'");
        this.action = action;
    }

    public SyscallException(String action, String detail) {
        super("Syscall error for action '" + action + "': " + detail);
        this.action = action;
    }

    public String action() {
        return action;
    }
}
