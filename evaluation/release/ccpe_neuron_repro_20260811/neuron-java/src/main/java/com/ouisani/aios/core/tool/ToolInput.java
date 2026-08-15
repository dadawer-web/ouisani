package com.ouisani.aios.core.tool;

/**
 * 工具输入基类 — 所有工具的输入参数必须继承此接口。
 * <p>
 * 对标 Claude Code 的 Tool Input schema，每个工具定义自己的 Input record。
 * <p>
 * OS 类比：相当于系统调用的参数寄存器 (rdi, rsi, rdx...)。
 */
public interface ToolInput {
    /**
     * 将输入参数序列化为 JSON 字符串，供 LLM 理解工具调用上下文。
     */
    String toJson();
}
