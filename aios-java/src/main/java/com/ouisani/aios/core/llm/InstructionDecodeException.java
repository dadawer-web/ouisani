package com.ouisani.aios.core.llm;

public class InstructionDecodeException extends RuntimeException {

    private final int attempts;

    public InstructionDecodeException(String message, int attempts) {
        super(message);
        this.attempts = attempts;
    }

    public InstructionDecodeException(String message, Throwable cause, int attempts) {
        super(message, cause);
        this.attempts = attempts;
    }

    public int getAttempts() {
        return attempts;
    }
}
