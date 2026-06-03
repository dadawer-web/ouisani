package com.ouisani.aios.core.syscall;

/**
 * Thrown when an Agent issues an unknown or unsupported system call action.
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
