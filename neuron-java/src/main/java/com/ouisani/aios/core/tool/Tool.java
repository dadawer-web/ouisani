package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.permission.SafetyCheckResult;

import java.util.List;
import java.util.Optional;

/**
 * 工具接口 — AIOS 工具系统的核心抽象，对标 Claude Code 的 Tool<Input,Output,P>。
 * <p>
 * 每个工具必须实现此接口，提供：
 * - 元数据（名称、描述、输入 schema）供 LLM 理解如何调用
 * - 执行逻辑（call 方法）供 QueryEngine 调度
 * - 权限检查（可选）供安全子系统验证
 * - 强类型 I/O 契约（inputPorts / outputPorts）供流水线类型检查
 * <p>
 * OS 类比：相当于 Linux 的系统调用表项 — 每个系统调用有编号（name）、
 * 参数格式（inputSchema）、处理函数（call）、以及 I/O 类型签名（inputPorts/outputPorts）。
 *
 * @param <I> 工具输入类型
 * @see Port
 * @see DataTypes
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
     * <p>
     * <b>已过时</b>：仅返回 String 无法表达"safety ASK 不可被 allow 覆盖"的语义。
     * 新代码应覆写 {@link #checkPermissionDetailed}；本方法保留向后兼容，默认委托新方法。
     */
    default String checkPermission(I input, ToolContext context) {
        return null;
    }

    /**
     * 工具自身权限检查（详细版）— 借鉴 AgentScope 2.0 的 {@code bypass_immune} 字段。
     * <p>
     * 默认实现委托旧 {@link #checkPermission}（向后兼容）：
     * <ul>
     *   <li>旧方法返回 null → {@link SafetyCheckResult#allowed()}</li>
     *   <li>旧方法返回非空 → {@link SafetyCheckResult#deny(String)}（非 bypass_immune）</li>
     * </ul>
     * 危险工具（BashTool rm -rf /、写 ~/.bashrc 等）应覆写本方法返回
     * {@link SafetyCheckResult#safetyAsk(String)}，标记为不可被 allow 规则覆盖。
     *
     * @param input   工具输入
     * @param context 执行上下文
     * @return 安全检查结果；默认 ALLOW
     */
    default SafetyCheckResult checkPermissionDetailed(I input, ToolContext context) {
        String simple = checkPermission(input, context);
        if (simple == null) return SafetyCheckResult.allowed();
        return SafetyCheckResult.deny(simple);
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

    // ════════════════════════════════════════════════════════════════
    //  强类型 I/O 契约 (Type-Safe I/O Contract)
    // ════════════════════════════════════════════════════════════════

    /**
     * 工具的输入端口声明 — 描述此工具需要什么类型的数据作为输入。
     * <p>
     * 像流水线上的机器一样，明确声明"吃进去什么"。
     * ToolRegistry 在注册时会校验此声明，未声明的工具会收到 WARN。
     *
     * @return 输入端口列表，默认空列表（向后兼容旧工具）
     * @see Port
     * @see DataTypes
     */
    default List<Port> inputPorts() {
        return List.of();
    }

    /**
     * 工具的输出端口声明 — 描述此工具产出什么类型的数据。
     * <p>
     * 像流水线上的机器一样，明确声明"吐出来什么"。
     * 下游节点/工具可据此判断是否能接收此工具的输出。
     *
     * @return 输出端口列表，默认空列表（向后兼容旧工具）
     */
    default List<Port> outputPorts() {
        return List.of();
    }

    /**
     * 是否已声明强类型 I/O 契约。
     * ToolRegistry 用此判断工具是否为"白盒"（已声明 I/O）还是"黑盒"（未声明）。
     */
    default boolean hasIOContract() {
        return !inputPorts().isEmpty() || !outputPorts().isEmpty();
    }

    /**
     * 工具使用示例 — 借鉴 EasyTool 的 Example schema。
     * 供 LLM 在 TopologyCompiler 中编排工具时参考，提升调用成功率。
     * 默认返回空（向后兼容）。
     */
    default Optional<ToolExample> example() {
        return Optional.empty();
    }
}

