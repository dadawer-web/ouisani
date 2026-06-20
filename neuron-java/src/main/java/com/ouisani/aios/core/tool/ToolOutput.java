package com.ouisani.aios.core.tool;

/**
 * 工具输出基类 — 所有工具的执行结果必须继承此接口。
 * <p>
 * 对标 Claude Code 的 Tool Output，包含执行结果和元数据。
 * <p>
 * OS 类比：相当于系统调用的返回值寄存器 (rax)。
 */
public interface ToolOutput {

    /**
     * 执行是否成功。
     */
    boolean success();

    /**
     * 将输出序列化为文本，供 LLM 理解工具执行结果。
     */
    String toText();

    /**
     * 创建一个成功的的结果。
     */
    static ToolOutput ok(String text) {
        return new ToolOutput() {
            @Override public boolean success() { return true; }
            @Override public String toText() { return text; }
        };
    }

    /**
     * 创建一个失败的结果。
     */
    static ToolOutput fail(String error) {
        return new ToolOutput() {
            @Override public boolean success() { return false; }
            @Override public String toText() { return "ERROR: " + error; }
        };
    }
}
