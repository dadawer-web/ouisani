package com.ouisani.aios.core.security;

/**
 * Thrown when an agent attempts to use an invalid or closed handle
 * to access a VFS node through the Object Manager.
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
