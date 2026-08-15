package com.ouisani.aios.core.security;

import java.util.concurrent.CompletableFuture;

/**
 * 三级护栏体系根接口 — 参考 OpenAI Agents Python 的 Guardrail 设计。
 * <p>
 * 与现有 {@link SyscallFilter}（拦截器模式：要么放行要么拒绝）不同，
 * Guardrail 提供更细粒度的三态行为，覆盖 Agent 生命周期的三个关键阶段：
 * <ul>
 *   <li>{@link InputGuardrail} — Agent 执行前的输入检查（支持并行执行）</li>
 *   <li>{@link OutputGuardrail} — Agent 输出后的结果验证</li>
 *   <li>{@link ToolGuardrail} — 工具调用的输入/输出检查</li>
 * </ul>
 *
 * <h3>设计哲学</h3>
 * InputGuardrail 与 LLM 调用并行运行：护栏触发则取消模型调用，节省 Token。
 * 这借鉴了 OpenAI Agents Python 的并行护栏机制——护栏和模型同时启动，
 * 谁先完成以谁为准，护栏触发即短路。
 *
 * @see GuardrailEngine
 */
public interface Guardrail {

    /**
     * 护栏动作枚举 — 决定 tripwire 触发后的处理方式。
     * <ul>
     *   <li>{@link #ALLOW} — 放行（tripwire 未触发时的默认动作）</li>
     *   <li>{@link #REJECT_CONTENT} — 拒绝内容，替换为拦截信息</li>
     *   <li>{@link #RAISE_EXCEPTION} — 抛出异常，中止当前流程</li>
     * </ul>
     */
    enum GuardrailAction {
        ALLOW,
        REJECT_CONTENT,
        RAISE_EXCEPTION
    }

    /**
     * 护栏检查结果。
     *
     * @param tripwireTriggered 是否触发了绊线（true 表示护栏拦截了内容）
     * @param outputInfo        附加信息（触发原因、调试上下文等）
     * @param action            触发后的动作
     */
    record GuardrailResult(boolean tripwireTriggered, String outputInfo, GuardrailAction action) {

        /** 未触发时的放行结果 */
        public static GuardrailResult allowed() {
            return new GuardrailResult(false, "", GuardrailAction.ALLOW);
        }

        /** 触发绊线的结果工厂方法 */
        public static GuardrailResult tripped(String outputInfo, GuardrailAction action) {
            return new GuardrailResult(true, outputInfo, action);
        }
    }

    /**
     * 输入护栏 — 在 Agent 执行前检查用户输入。
     * <p>
     * 返回 {@link CompletableFuture} 以支持与 LLM 调用并行执行：
     * 护栏启动后立即开始 LLM 调用，不等待护栏完成；护栏触发则取消模型调用。
     *
     * @param agentId Agent 标识
     * @param prompt  待检查的输入（用户消息或完整 prompt）
     * @return 异步护栏检查结果
     */
    interface InputGuardrail {
        CompletableFuture<GuardrailResult> check(String agentId, String prompt);
    }

    /**
     * 输出护栏 — 在 Agent 输出后验证结果。
     * <p>
     * 同步执行，在最终输出返回给用户前进行验证。
     *
     * @param agentId Agent 标识
     * @param output  待验证的 Agent 输出
     * @return 护栏检查结果
     */
    interface OutputGuardrail {
        GuardrailResult check(String agentId, String output);
    }

    /**
     * 工具护栏 — 检查工具调用的输入和输出。
     * <p>
     * 同步执行，在工具执行完成后检查其输入/输出是否包含敏感数据等问题。
     *
     * @param agentId  Agent 标识
     * @param toolName 工具名称
     * @param input    工具输入（参数 JSON）
     * @param output   工具输出文本
     * @return 护栏检查结果
     */
    interface ToolGuardrail {
        GuardrailResult check(String agentId, String toolName, String input, String output);
    }
}
