package com.ouisani.aios.core.ipc;

/** Raised when a caller attempts to cross a scoped-memory boundary. */
public final class MemoryAccessDeniedException extends SecurityException {

    public MemoryAccessDeniedException(String message) {
        super(message);
    }
}
