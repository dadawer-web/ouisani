package com.ouisani.aios.core.middleware;

import com.ouisani.aios.core.tool.HistoryCompressor.Message;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一中间件接口 — 对标 AgentScope 2.0 {@code middleware/_base.py}。
 * <p>
 * 显式区分两种 hook 模式（借鉴 AgentScope L12-L50 类文档）：
 * <ul>
 *   <li><b>Onion（洋葱）</b> — {@link #onActing} / {@link #onModelCall} / {@link #onReply} /
 *       {@link #onCompressContext}：before/after 双向拦截，拿 {@link NextHandler} 决定是否调下游。
 *       中间件可在 {@code next.proceed()} 前后插入逻辑，也可短路（不调 {@code proceed}）。</li>
 *   <li><b>Transformer（管道）</b> — {@link #onSystemPrompt(String)}：串行 pipeline，
 *       每个中间件拿到上一个的输出，返回下一个的输入。</li>
 * </ul>
 *
 * <h3>{@code implementedHooks()} — 反射-free 的 hook 声明（D1）</h3>
 * AgentScope 用反射检测子类是否覆写基类方法（{@code base_method is not sub_method}）。
 * 但项目记忆硬约束「Core packages must not use reflection」「JPMS 反射脆弱」。
 * 改为<b>显式声明</b>：每个中间件覆写 {@link #implementedHooks()} 返回它实现的 hook 名集合
 * （如 {@code Set.of("on_acting")}），链构建器据此跳过 no-op 中间件——
 * 语义等价（中间件只实现需要的 hook，框架自动跳过未实现的），但 JPMS 安全、可调试。
 * <p>
 * 所有 hook 方法保留 <b>pass-through 默认实现</b>（onion 调 {@code next.proceed()} 返回其结果，
 * transformer 原样返回），保证「只实现需要的 hook」时默认行为正确——{@code implementedHooks()}
 * 仅作链构建优化，不影响正确性。
 *
 * <h3>{@code on_acting} 的精确边界（核心工程决策）</h3>
 * 引用 AgentScope L114-L158 文档：
 * <pre>
 *   This hook wraps only the toolkit.call_tool call — i.e. the pure I/O execution layer.
 *   Permission checking, input validation, and context writes are handled by the agent
 *   outside this hook.
 *
 *   This separation makes it safe to offload the next_handler coroutine to a background task:
 *   it will never mutate agent context on its own.
 * </pre>
 * 把 {@link #onActing} 限定在「纯 I/O」（只包 {@code tool.call()}），permission/state mutation
 * 都在洋葱外。这样工具调用能安全 offload 到后台任务（{@code run_in_background} / {@code TaskTool}）——
 * 修复项目记忆记录的「{@code AgentTool.runInBackground=true} fall back 到 {@code GLOBAL_DEFAULT_SCOPE}」根因。
 *
 * <h3>同步 await 语义（D3）</h3>
 * 项目记忆约束「所有 middleware 必须用 async/await，无 callback 风格」。Java 无原生 async/await，
 * 但 {@code next.proceed()} 内联调用并直接返回 {@code T} 就是 await 语义——中间件在调用点「等待」结果。
 * {@code QueryEngine} 跑在虚拟线程上，阻塞 {@code proceed()} 释放载体线程、成本极低。
 * 同步还保证 {@code UpstreamMetaContext} / {@code DelegationGuard} scope 等 ThreadLocal 在洋葱内全程可见
 * ——异步会破坏 ThreadLocal 传播。<b>不用 {@code CompletableFuture}</b>。
 *
 * <h3>异常处理（D7，best-effort，永不中断 chat flow）</h3>
 * <table>
 *   <tr><th>阶段</th><th>异常行为</th></tr>
 *   <tr><td>Onion PRE（{@code next.proceed} 前）</td><td>catch Throwable → log warn → 跳过本中间件 PRE，仍调 {@code next.proceed()} 让下游执行</td></tr>
 *   <tr><td>Onion POST（{@code next.proceed} 后）</td><td>catch Throwable → log warn → 返回 {@code next.proceed()} 的结果不变（不丢数据）</td></tr>
 *   <tr><td>Transformer onSystemPrompt</td><td>catch Throwable → log warn → 返回上一个 prompt 不变</td></tr>
 *   <tr><td>LEAF（{@code next_handler} 本身 = {@code tool.call}）</td><td><b>不 catch</b>——异常向上传播，由 {@code QueryEngine.executeTool} 现有 try/catch 处理</td></tr>
 * </table>
 *
 * @see NextHandler
 * @see com.ouisani.aios.core.middleware.MiddlewareRegistry
 */
public interface Middleware {

    // ── Hook 名常量（用于 implementedHooks() 声明） ──
    String ON_ACTING          = "on_acting";
    String ON_MODEL_CALL      = "on_model_call";
    String ON_REPLY           = "on_reply";
    String ON_COMPRESS_CONTEXT = "on_compress_context";
    String ON_SYSTEM_PROMPT   = "on_system_prompt";

    // ════════════════════════════════════════════════════════════════
    //  Onion hooks — pass-through 默认 = 调 next.proceed() 返回其结果
    // ════════════════════════════════════════════════════════════════

    /**
     * 包裹工具调用（纯 I/O 执行层）。
     * <p>
     * <b>边界</b>：仅包 {@code tool.call(input, context)}。权限检查、输入校验、
     * context 写入、guardrail、telemetry、handoff 全在此 hook <b>外</b>（由
     * {@code QueryEngine.executeTool} 处理）。这使得 {@code next.proceed()} 能安全
     * offload 到后台任务——它永远不会自行 mutate agent context。
     *
     * @param ctx  工具调用上下文（agentId / toolName / input / toolContext / metadata）
     * @param next 下游续延；调 {@code next.proceed()} = 执行 {@code tool.call}（LEAF）
     * @return 工具产出（{@link ToolOutput}）；短路时返回 {@code ToolOutput.fail(...)} 跳过 LEAF
     */
    default ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
        return next.proceed();
    }

    /**
     * 包裹 LLM 调用。on_reasoning 折叠进此 hook（D2）——代码库无独立 reasoning 阶段，
     * {@code sdk.thinkStream} 返回的 token 流混合 reasoning 与 content。
     *
     * @param ctx  模型调用上下文（agentId / prompt / runId / round）
     * @param next 下游续延；调 {@code next.proceed()} = 执行 {@code sdk.thinkStream}（LEAF）
     * @return 模型调用结果（响应文本 + 属性）
     */
    default ModelCallResult onModelCall(ModelCallContext ctx, NextHandler<ModelCallResult> next) throws Exception {
        return next.proceed();
    }

    /**
     * 包裹最终回复。在 {@code QueryEngine} 的每个回复出口触发（output guardrail 块 /
     * finalize gate / max-rounds gate）。
     *
     * @param ctx  回复上下文（agentId / runId / finalAnswer）
     * @param next 下游续延；调 {@code next.proceed()} = 返回原 finalAnswer（LEAF）
     * @return 最终回复文本
     */
    default String onReply(ReplyContext ctx, NextHandler<String> next) throws Exception {
        return next.proceed();
    }

    /**
     * 包裹上下文压缩。在 {@code HistoryCompressor.buildHistoryText()} 触发压缩时调用。
     *
     * @param ctx  压缩上下文（agentId / messages 快照 / currentSummary）
     * @param next 下游续延；调 {@code next.proceed()} = 执行原压缩逻辑（LEAF）
     * @return 压缩后的历史文本
     */
    default String onCompressContext(CompressContext ctx, NextHandler<String> next) throws Exception {
        return next.proceed();
    }

    // ════════════════════════════════════════════════════════════════
    //  Transformer hook — pass-through 默认 = 原样返回
    // ════════════════════════════════════════════════════════════════

    /**
     * 系统提示词串行管道。每个中间件拿到上一个的输出，返回下一个的输入。
     * <p>
     * 与 onion 不同：无 {@code next_handler}，纯函数式 {@code prompt → prompt}。
     * 链构建器按注册序左→右组合（{@code m3(m2(m1(prompt)))}）。
     *
     * @param prompt 上一个中间件的输出（首个中间件拿到原始 system prompt）
     * @return 变换后的 prompt（传给下一个中间件）
     */
    default String onSystemPrompt(String prompt) {
        return prompt;
    }

    // ════════════════════════════════════════════════════════════════
    //  反射-free hook 声明（D1）
    // ════════════════════════════════════════════════════════════════

    /**
     * 声明此中间件实际实现的 hook 名集合。
     * <p>
     * 链构建器据此跳过 no-op 中间件——不调用未实现的 hook 的 pass-through 默认实现，
     * 减少无意义调用栈深度。返回 {@link Set#of()}（默认）表示「全部 pass-through」，
     * 链构建器可跳过此中间件的所有 fire 路径。
     * <p>
     * <b>仅作优化</b>，不影响正确性：即使返回空集，pass-through 默认实现仍保证语义正确。
     * hook 名取本接口的常量（{@link #ON_ACTING} / {@link #ON_MODEL_CALL} / ...）。
     *
     * @return 已实现 hook 名集合；默认空集
     */
    default Set<String> implementedHooks() {
        return Set.of();
    }

    // ════════════════════════════════════════════════════════════════
    //  Context records — 嵌套类型（同 HookManager 范式：HookEvent/HookHandler/HookResult 均嵌套）
    // ════════════════════════════════════════════════════════════════

    /**
     * {@link #onActing} 的上下文。
     *
     * @param agentId     调用工具的 Agent ID
     * @param toolName    工具名
     * @param input       工具输入（已解析、已过权限检查）
     * @param toolContext 工具执行上下文（agentId / sdk / workingDir）
     * @param metadata    附加元数据（runId / round 等，中间件可读写）
     */
    record ActingContext(
            String agentId,
            String toolName,
            ToolInput input,
            ToolContext toolContext,
            Map<String, Object> metadata
    ) {}

    /**
     * {@link #onModelCall} 的上下文。
     *
     * @param agentId 调用 LLM 的 Agent ID
     * @param prompt  完整 prompt（system + history）
     * @param runId   当前 query 运行 ID
     * @param round   当前轮次（1-based）
     */
    record ModelCallContext(
            String agentId,
            String prompt,
            String runId,
            int round
    ) {}

    /**
     * {@link #onModelCall} 的产出。
     *
     * @param response   LLM 响应文本
     * @param attributes 附加属性（model / token usage 等，中间件可注入）
     */
    record ModelCallResult(
            String response,
            Map<String, Object> attributes
    ) {
        /** 便捷构造：无附加属性。 */
        public static ModelCallResult of(String response) {
            return new ModelCallResult(response, Map.of());
        }
    }

    /**
     * {@link #onReply} 的上下文。
     *
     * @param agentId     回复的 Agent ID
     * @param runId       当前 query 运行 ID
     * @param finalAnswer 原始最终回复（LEAF 产出）
     */
    record ReplyContext(
            String agentId,
            String runId,
            String finalAnswer
    ) {}

    /**
     * {@link #onCompressContext} 的上下文。
     *
     * @param agentId        压缩历史的 Agent ID
     * @param messages       待压缩消息快照（{@code HistoryCompressor.snapshotMessages()} 防御性拷贝）
     * @param currentSummary 当前已有摘要（old 区），可为 null
     */
    record CompressContext(
            String agentId,
            List<Message> messages,
            String currentSummary
    ) {}
}
