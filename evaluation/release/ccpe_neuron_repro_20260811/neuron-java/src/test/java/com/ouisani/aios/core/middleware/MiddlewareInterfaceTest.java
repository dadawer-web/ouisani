package com.ouisani.aios.core.middleware;

import com.ouisani.aios.core.middleware.Middleware.ActingContext;
import com.ouisani.aios.core.middleware.Middleware.CompressContext;
import com.ouisani.aios.core.middleware.Middleware.ModelCallContext;
import com.ouisani.aios.core.middleware.Middleware.ModelCallResult;
import com.ouisani.aios.core.middleware.Middleware.ReplyContext;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Middleware} 接口默认语义测试 — 验证 pass-through 默认实现 + implementedHooks() 默认空集。
 * <p>
 * 对标 AgentScope 2.0 {@code middleware/_base.py} 的反射检测（base_method is not sub_method），
 * 但本项目用显式 {@link Middleware#implementedHooks()} Set 替代反射（D1，JPMS 安全）。
 * <p>
 * 核心断言：no-op 中间件（不覆写任何 hook）调 {@code onActing(ctx, () -> X)} 返回 X 不变——
 * pass-through 默认 = 调 {@code next.proceed()} 返回其结果。
 */
class MiddlewareInterfaceTest {

    private static final ToolContext TOOL_CTX = new ToolContext("agent_test", null, "/tmp");
    private static final ToolInput INPUT = () -> "{}";

    @Test
    @DisplayName("implementedHooks() 默认空集")
    void implementedHooks_defaultIsEmptySet() {
        Middleware m = new Middleware() {};
        assertTrue(m.implementedHooks().isEmpty());
    }

    @Test
    @DisplayName("onActing pass-through：调 next.proceed() 返回其结果不变")
    void onActing_passThrough_returnsLeafResult() throws Exception {
        Middleware m = new Middleware() {};
        ToolOutput expected = ToolOutput.ok("leaf-result");
        ActingContext ctx = new ActingContext("a", "bash", INPUT, TOOL_CTX, Map.of());
        ToolOutput out = m.onActing(ctx, () -> expected);
        assertSame(expected, out, "pass-through 应原样返回 leaf 结果");
    }

    @Test
    @DisplayName("onModelCall pass-through：返回 leaf 的 ModelCallResult")
    void onModelCall_passThrough_returnsLeafResult() throws Exception {
        Middleware m = new Middleware() {};
        ModelCallResult expected = ModelCallResult.of("resp");
        ModelCallContext ctx = new ModelCallContext("a", "prompt", "run1", 1);
        ModelCallResult out = m.onModelCall(ctx, () -> expected);
        assertSame(expected, out);
        assertEquals("resp", out.response());
    }

    @Test
    @DisplayName("onReply pass-through：返回 leaf 字符串不变")
    void onReply_passThrough_returnsLeafResult() throws Exception {
        Middleware m = new Middleware() {};
        ReplyContext ctx = new ReplyContext("a", "run1", "answer");
        String out = m.onReply(ctx, () -> "answer");
        assertEquals("answer", out);
    }

    @Test
    @DisplayName("onCompressContext pass-through：返回 leaf 历史文本不变")
    void onCompressContext_passThrough_returnsLeafResult() throws Exception {
        Middleware m = new Middleware() {};
        CompressContext ctx = new CompressContext("a", List.of(), null);
        String out = m.onCompressContext(ctx, () -> "history-text");
        assertEquals("history-text", out);
    }

    @Test
    @DisplayName("onSystemPrompt pass-through：原样返回 prompt（transformer identity）")
    void onSystemPrompt_passThrough_returnsPromptUnchanged() {
        Middleware m = new Middleware() {};
        assertEquals("system-prompt", m.onSystemPrompt("system-prompt"));
        // null 也原样返回（identity）
        assertNull(m.onSystemPrompt(null));
    }

    @Test
    @DisplayName("子类覆写 onActing 后可短路（不调 next.proceed）")
    void onActing_override_canShortCircuit() throws Exception {
        Middleware m = new Middleware() {
            @Override
            public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) {
                return ToolOutput.fail("blocked by test middleware");
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_ACTING);
            }
        };
        ActingContext ctx = new ActingContext("a", "bash", INPUT, TOOL_CTX, Map.of());
        // leaf 永不应被调用（用 fail 证明）
        NextHandler<ToolOutput> leaf = () -> { throw new AssertionError("leaf should not be called"); };
        ToolOutput out = m.onActing(ctx, leaf);
        assertFalse(out.success());
        assertEquals("ERROR: blocked by test middleware", out.toText());
    }

    @Test
    @DisplayName("ModelCallResult.of 便捷构造：无附加属性")
    void modelCallResult_of_hasEmptyAttributes() {
        ModelCallResult r = ModelCallResult.of("resp");
        assertEquals("resp", r.response());
        assertTrue(r.attributes().isEmpty());
    }

    @Test
    @DisplayName("Hook 名常量值稳定（用于 implementedHooks 声明）")
    void hookNameConstants_areStable() {
        assertEquals("on_acting", Middleware.ON_ACTING);
        assertEquals("on_model_call", Middleware.ON_MODEL_CALL);
        assertEquals("on_reply", Middleware.ON_REPLY);
        assertEquals("on_compress_context", Middleware.ON_COMPRESS_CONTEXT);
        assertEquals("on_system_prompt", Middleware.ON_SYSTEM_PROMPT);
    }
}
