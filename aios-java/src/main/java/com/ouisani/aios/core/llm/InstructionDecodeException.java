package com.ouisani.aios.core.llm;

/**
 * 指令解码异常 — AIOS 的"内核恐慌"。
 * <p>
 * 类比操作系统中的 Kernel Panic：当所有解码策略（严格解码 + 语义模糊解码）
 * 均无法解析 LLM 输出时，抛出此异常，表示指令完全不可解析。
 * 类似于 CPU 遇到无效指令（Illegal Opcode）时的异常。
 *
 * @see InstructionDecoder
 */
public class InstructionDecodeException extends RuntimeException {

    /** 解码尝试次数（即已尝试的策略数量） */
    private final int attempts;

    /**
     * @param message  错误描述
     * @param attempts 已尝试的解码策略数量
     */
    public InstructionDecodeException(String message, int attempts) {
        super(message);
        this.attempts = attempts;
    }

    /**
     * @param message  错误描述
     * @param cause    原始异常
     * @param attempts 已尝试的解码策略数量
     */
    public InstructionDecodeException(String message, Throwable cause, int attempts) {
        super(message, cause);
        this.attempts = attempts;
    }

    /** 返回解码尝试次数 */
    public int getAttempts() {
        return attempts;
    }
}
