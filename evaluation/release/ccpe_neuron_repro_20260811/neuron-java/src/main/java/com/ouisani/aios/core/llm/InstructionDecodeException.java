package com.ouisani.aios.core.llm;

import java.util.Set;

/**
 * 指令解码异常 — AIOS 的"内核恐慌"。
 * <p>
 * 类比操作系统中的 Kernel Panic：当所有解码策略（严格解码 + 语义模糊解码）
 * 均无法解析 LLM 输出时，抛出此异常，表示指令完全不可解析。
 * 类似于 CPU 遇到无效指令（Illegal Opcode）时的异常。
 * <p>
 * 也用于工具幻觉检测：当 LLM 调用了不存在的工具时，
 * InstructionDecoder 在解码后、进入 DAG 引擎前抛出此异常。
 *
 * @see InstructionDecoder
 * @see com.ouisani.aios.core.tool.ToolHallucinationChecker
 */
public class InstructionDecodeException extends RuntimeException {

    /** 解码尝试次数（即已尝试的策略数量） */
    private final int attempts;

    /** 幻觉工具名集合（非 null 表示由工具幻觉检测触发） */
    private final Set<String> hallucinatedTools;

    /** 可用工具名集合（供 RecoveryOrchestrator 注入反思提示） */
    private final Set<String> availableTools;

    /**
     * @param message  错误描述
     * @param attempts 已尝试的解码策略数量
     */
    public InstructionDecodeException(String message, int attempts) {
        super(message);
        this.attempts = attempts;
        this.hallucinatedTools = null;
        this.availableTools = null;
    }

    /**
     * @param message  错误描述
     * @param cause    原始异常
     * @param attempts 已尝试的解码策略数量
     */
    public InstructionDecodeException(String message, Throwable cause, int attempts) {
        super(message, cause);
        this.attempts = attempts;
        this.hallucinatedTools = null;
        this.availableTools = null;
    }

    /**
     * 工具幻觉检测构造器 — 当 LLM 调用了不存在的工具时使用。
     * <p>
     * RecoveryOrchestrator 可通过 {@link #getHallucinatedTools()} 和
     * {@link #getAvailableTools()} 构造反思提示，注入给 LLM 进行自愈重试。
     *
     * @param message            错误描述
     * @param hallucinatedTools  幻觉工具名集合
     * @param availableTools     可用工具名集合
     */
    public InstructionDecodeException(String message, Set<String> hallucinatedTools, Set<String> availableTools) {
        super(message);
        this.attempts = 0;
        this.hallucinatedTools = hallucinatedTools;
        this.availableTools = availableTools;
    }

    /** 返回解码尝试次数 */
    public int getAttempts() {
        return attempts;
    }

    /** 返回幻觉工具名集合，非幻觉检测触发时返回 null */
    public Set<String> getHallucinatedTools() {
        return hallucinatedTools;
    }

    /** 返回可用工具名集合，非幻觉检测触发时返回 null */
    public Set<String> getAvailableTools() {
        return availableTools;
    }

    /** 是否由工具幻觉检测触发 */
    public boolean isToolHallucination() {
        return hallucinatedTools != null && !hallucinatedTools.isEmpty();
    }
}
