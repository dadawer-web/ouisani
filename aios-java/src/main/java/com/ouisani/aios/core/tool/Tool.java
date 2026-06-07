package com.ouisani.aios.core.tool;

/**
 * 工具接口 — AIOS 工具系统的核心抽象，对标 Claude Code 的 Tool<Input,Output,P>。
 * <p>
 * 每个工具必须实现此接口，提供：
 * - 元数据（名称、描述、输入 schema）供 LLM 理解如何调用
 * - 执行逻辑（call 方法）供 QueryEngine 调度
 * - 权限检查（可选）供安全子系统验证
 * <p>
 * OS 类比：相当于 Linux 的系统调用表项 — 每个系统调用有编号（name）、
 * 参数格式（inputSchema）、处理函数（call）。
 *
 * @param <I> 工具输入类型
 */
public interface Tool<I extends ToolInput> {

    /**
     * 工具名称 — 必须全局唯一，用于 LLM 调用时的标识符。
     * 例如：bash, file_read, file_edit, grep, glob, web_fetch
     */
    String name();

    /**
     * 工具描述 — 供 LLM 理解工具用途，决定是否调用。
     */
    String description();

    /**
     * 输入参数的 JSON Schema 描述，供 LLM 生成正确的调用参数。
     * 返回 JSON Schema 格式的字符串。
     */
    String inputSchema();

    /**
     * 执行工具逻辑。
     *
     * @param input   工具输入参数
     * @param context 执行上下文（agentId, sdk, workingDir）
     * @return 工具执行结果
     */
    ToolOutput call(I input, ToolContext context);

    /**
     * 权限检查 — 默认不限制，子类可覆盖。
     * 返回 null 表示允许，返回错误消息表示拒绝。
     */
    default String checkPermission(I input, ToolContext context) {
        return null;
    }

    /**
     * 是否为只读工具（不修改文件系统状态）。
     * 只读工具在 plan 模式下也可以执行。
     */
    default boolean readOnly() {
        return false;
    }

    /**
     * 工具的系统提示词 — 注入到 LLM 上下文中，指导如何使用该工具。
     * 默认为空，子类可覆盖。
     */
    default String prompt() {
        return "";
    }
}
