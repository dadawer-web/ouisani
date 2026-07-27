package com.ouisani.aios.core.middleware;

import com.ouisani.aios.core.middleware.Middleware.ActingContext;
import com.ouisani.aios.core.middleware.Middleware.CompressContext;
import com.ouisani.aios.core.middleware.Middleware.ModelCallContext;
import com.ouisani.aios.core.middleware.Middleware.ModelCallResult;
import com.ouisani.aios.core.middleware.Middleware.ReplyContext;
import com.ouisani.aios.core.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 中间件注册表 — 全局单例，管理 {@link Middleware} 注册与链构建。
 * <p>
 * 同 {@code EventBus} / {@code HookManager} 范式：Holder 单例 + {@link CopyOnWriteArrayList}
 * （注册/注销低频，fire 高频读，COW 读无锁）。
 *
 * <h3>链构建（D1 + D7）</h3>
 * <ul>
 *   <li><b>过滤</b>：每个 fire 方法按目标 hook 名过滤——{@link Middleware#implementedHooks()}
 *       不含目标 hook 名的中间件<b>跳过</b>（不调其 pass-through 默认实现，减少无意义调用栈深度）。</li>
 *   <li><b>Onion 包裹</b>：按注册序逆序包裹，<b>先注册 = 最外层</b>（最先 PRE、最后 POST）。
 *       调用顺序：m1.PRE → m2.PRE → ... → leaf → ... → m2.POST → m1.POST。</li>
 *   <li><b>Transformer 串行</b>：按注册序左→右组合，{@code m3(m2(m1(prompt)))}。</li>
 *   <li><b>异常处理（D7）</b>：每个中间件调用包 try/catch Throwable。
 *       PRE 异常（{@code next.proceed} 前）→ 跳过本中间件 PRE，仍调 {@code next.proceed()}；
 *       POST 异常（{@code next.proceed} 后）→ 返回 {@code next.proceed()} 的结果不变；
 *       LEAF 异常 → <b>不 catch</b>，向上传播。</li>
 * </ul>
 *
 * <h3>启动顺序（D8）</h3>
 * 静态初始化器自注册 {@link HookManagerBridgeMiddleware}（先）+ {@link UpstreamMetaMiddleware}（后）。
 * 保证 PRE/POST_HOOK 在 UpstreamMeta 计时窗口内（UpstreamMeta duration 含 HookManager PRE/POST
 * 触发时间，与现有行为一致）。零调用方接触——{@code QueryEngine} 只调 fire 方法，内置中间件自动生效。
 *
 * <h3>测试隔离</h3>
 * {@link #clearForTesting()} 清空注册表；测试在 {@code @BeforeEach} 调用后手动注册被测中间件，
 * 避免内置中间件干扰。静态初始化器仅运行一次，{@code clearForTesting()} 后内置中间件不再自动恢复。
 *
 * @see Middleware
 * @see NextHandler
 */
public class MiddlewareRegistry {

    private static final Logger log = LoggerFactory.getLogger(MiddlewareRegistry.class);

    private static final class Holder {
        static final MiddlewareRegistry INSTANCE = new MiddlewareRegistry();
    }

    private final CopyOnWriteArrayList<Middleware> middlewares = new CopyOnWriteArrayList<>();

    private MiddlewareRegistry() {
    }

    public static MiddlewareRegistry instance() {
        return Holder.INSTANCE;
    }

    static {
        // D8: 启动顺序——HookManagerBridge 先（外层），UpstreamMeta 后（内层）。
        // 调用栈：Bridge.PRE → UpstreamMeta.PRE → tool.call(LEAF) → UpstreamMeta.POST → Bridge.POST
        // 这样 UpstreamMeta 的 duration 包含 tool.call 纯 I/O 时间，
        // Bridge 的 POST_TOOL_USE 触发在 UpstreamMeta 记录之后（duration 含 Bridge.POST 时间，与原行为一致）。
        try {
            Holder.INSTANCE.register(new HookManagerBridgeMiddleware());
            Holder.INSTANCE.register(new UpstreamMetaMiddleware());
        } catch (Throwable t) {
            // 静态初始化器永不失败——降级为空注册表，fire 退化为直接调 leaf
            log.error("[MiddlewareRegistry] 内置中间件自注册失败，降级为空注册表: {}", t.getMessage(), t);
        }
    }

    /**
     * 注册中间件（追加到链尾 = 最内层）。
     */
    public void register(Middleware m) {
        if (m != null) {
            middlewares.add(m);
            log.debug("[MiddlewareRegistry] 已注册中间件: {} (总数: {})",
                    m.getClass().getSimpleName(), middlewares.size());
        }
    }

    /**
     * 注销中间件。
     */
    public void unregister(Middleware m) {
        if (m != null && middlewares.remove(m)) {
            log.debug("[MiddlewareRegistry] 已注销中间件: {} (剩余: {})",
                    m.getClass().getSimpleName(), middlewares.size());
        }
    }

    /**
     * 已注册中间件数（含内置）——供测试断言。
     */
    public int size() {
        return middlewares.size();
    }

    /**
     * 清空注册表——<b>仅测试使用</b>。
     * <p>
     * 静态初始化器仅运行一次；调用此方法后内置中间件不再自动恢复，
     * 测试需手动注册被测中间件以隔离环境。
     */
    public void clearForTesting() {
        middlewares.clear();
    }

    // ════════════════════════════════════════════════════════════════
    //  Onion fire 方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 触发 {@code on_acting} 洋葱链。
     * <p>
     * <b>边界</b>：leaf 是 {@code tool.call(input, context)}（纯 I/O）。权限检查、guardrail、
     * telemetry、handoff 全在此 fire 的<b>外</b>（由 {@code QueryEngine.executeTool} 处理）。
     *
     * @param ctx  工具调用上下文
     * @param leaf 叶子续延（{@code tool.call}）；异常向上传播（D7 LEAF 不 catch）
     * @return 工具产出
     */
    public ToolOutput fireOnActing(ActingContext ctx, NextHandler<ToolOutput> leaf) throws Exception {
        List<Middleware> chain = filterChain(Middleware.ON_ACTING);
        if (chain.isEmpty()) {
            return leaf.proceed();
        }
        NextHandler<ToolOutput> h = leaf;
        // 逆序包裹：先注册 = 最外层（最先 PRE）
        for (int i = chain.size() - 1; i >= 0; i--) {
            Middleware m = chain.get(i);
            final NextHandler<ToolOutput> currentNext = h;
            final Middleware mid = m;
            h = () -> invokeOnion(Middleware.ON_ACTING, mid,
                    (mm, n) -> mm.onActing(ctx, n), currentNext);
        }
        return h.proceed();
    }

    /**
     * 触发 {@code on_model_call} 洋葱链。
     */
    public ModelCallResult fireOnModelCall(ModelCallContext ctx, NextHandler<ModelCallResult> leaf) throws Exception {
        List<Middleware> chain = filterChain(Middleware.ON_MODEL_CALL);
        if (chain.isEmpty()) {
            return leaf.proceed();
        }
        NextHandler<ModelCallResult> h = leaf;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Middleware m = chain.get(i);
            final NextHandler<ModelCallResult> currentNext = h;
            final Middleware mid = m;
            h = () -> invokeOnion(Middleware.ON_MODEL_CALL, mid,
                    (mm, n) -> mm.onModelCall(ctx, n), currentNext);
        }
        return h.proceed();
    }

    /**
     * 触发 {@code on_reply} 洋葱链。
     */
    public String fireOnReply(ReplyContext ctx, NextHandler<String> leaf) throws Exception {
        List<Middleware> chain = filterChain(Middleware.ON_REPLY);
        if (chain.isEmpty()) {
            return leaf.proceed();
        }
        NextHandler<String> h = leaf;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Middleware m = chain.get(i);
            final NextHandler<String> currentNext = h;
            final Middleware mid = m;
            h = () -> invokeOnion(Middleware.ON_REPLY, mid,
                    (mm, n) -> mm.onReply(ctx, n), currentNext);
        }
        return h.proceed();
    }

    /**
     * 触发 {@code on_compress_context} 洋葱链。
     */
    public String fireOnCompressContext(CompressContext ctx, NextHandler<String> leaf) throws Exception {
        List<Middleware> chain = filterChain(Middleware.ON_COMPRESS_CONTEXT);
        if (chain.isEmpty()) {
            return leaf.proceed();
        }
        NextHandler<String> h = leaf;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Middleware m = chain.get(i);
            final NextHandler<String> currentNext = h;
            final Middleware mid = m;
            h = () -> invokeOnion(Middleware.ON_COMPRESS_CONTEXT, mid,
                    (mm, n) -> mm.onCompressContext(ctx, n), currentNext);
        }
        return h.proceed();
    }

    // ════════════════════════════════════════════════════════════════
    //  Transformer fire 方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 触发 {@code on_system_prompt} 串行管道。
     * <p>
     * 按注册序左→右组合：每个中间件拿到上一个的输出。返回 null 的中间件视为「不修改」（保留上一个）。
     * 单个中间件异常 → 保留上一个 prompt（D7），不中断管道。
     *
     * @param prompt 原始系统提示词
     * @return 变换后的系统提示词
     */
    public String fireOnSystemPrompt(String prompt) {
        String current = prompt;
        for (Middleware m : middlewares) {
            if (!m.implementedHooks().contains(Middleware.ON_SYSTEM_PROMPT)) {
                continue;
            }
            try {
                String next = m.onSystemPrompt(current);
                if (next != null) {
                    current = next;
                }
            } catch (Throwable t) {
                log.warn("[MiddlewareRegistry] {} onSystemPrompt 异常，保留上一个 prompt: {}",
                        m.getClass().getSimpleName(), t.getMessage());
                // 保留 current 不变
            }
        }
        return current;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部 — 链构建工具
    // ════════════════════════════════════════════════════════════════

    /**
     * 按 hook 名过滤中间件——跳过 {@code implementedHooks()} 不含目标 hook 的中间件（D1）。
     */
    private List<Middleware> filterChain(String hookName) {
        if (middlewares.isEmpty()) {
            return List.of();
        }
        List<Middleware> filtered = null;
        for (Middleware m : middlewares) {
            if (m.implementedHooks().contains(hookName)) {
                if (filtered == null) {
                    filtered = new ArrayList<>();
                }
                filtered.add(m);
            }
        }
        return filtered == null ? List.of() : filtered;
    }

    /**
     * Onion 中间件统一调用入口——实现 D7 异常处理。
     * <p>
     * 通过追踪 {@code next.proceed()} 是否被调用，区分 PRE / POST 异常：
     * <ul>
     *   <li><b>未调 next</b>（PRE 异常）→ 跳过本中间件，仍调 {@code next.proceed()} 让下游执行</li>
     *   <li><b>已调 next 且 next 成功</b>（POST 异常）→ 返回 next 的结果不变（不丢数据）</li>
     *   <li><b>已调 next 且 next 抛异常</b>→ 重抛（leaf 异常或中间件包装后的异常，向上传播）</li>
     * </ul>
     *
     * @param hookName hook 名（仅用于日志）
     * @param m        被调中间件
     * @param hook     中间件 hook 调用器（绑定 ctx）
     * @param next     下游续延
     */
    @SuppressWarnings("unchecked")
    private <T> T invokeOnion(String hookName, Middleware m, OnionHook<T> hook, NextHandler<T> next) throws Exception {
        // 追踪 next.proceed() 是否被调用 + 捕获其结果
        boolean[] proceeded = {false};
        Object[] nextResult = {null};
        NextHandler<T> trackingNext = () -> {
            proceeded[0] = true;
            T r = next.proceed();
            nextResult[0] = r;
            return r;
        };
        try {
            return hook.apply(m, trackingNext);
        } catch (Throwable t) {
            String midName = m.getClass().getSimpleName();
            if (proceeded[0]) {
                // POST 异常——next 已被调用
                if (nextResult[0] != null) {
                    log.warn("[MiddlewareRegistry] {}.{} POST 异常，返回 next 结果不变: {}",
                            midName, hookName, t.getMessage());
                    return (T) nextResult[0];
                }
                // next 抛了异常（leaf 异常传播，或中间件包装后重抛）
                log.warn("[MiddlewareRegistry] {}.{} next 抛异常，向上传播: {}",
                        midName, hookName, t.getMessage());
                throw t;
            } else {
                // PRE 异常——next 未被调用，跳过本中间件，仍调 next 让下游执行
                log.warn("[MiddlewareRegistry] {}.{} PRE 异常，跳过本中间件仍调下游: {}",
                        midName, hookName, t.getMessage());
                return next.proceed();
            }
        }
    }

    /** Onion hook 调用器——绑定 ctx，将 (Middleware, NextHandler) 映射到 T。 */
    @FunctionalInterface
    private interface OnionHook<T> {
        T apply(Middleware m, NextHandler<T> next) throws Exception;
    }
}
