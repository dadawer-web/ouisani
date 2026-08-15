package com.ouisani.aios.core.middleware;

import com.ouisani.aios.core.middleware.Middleware.ActingContext;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MiddlewareRegistry#fireOnActing} 跳过 no-op 中间件测试 — D1（反射-free 过滤）。
 * <p>
 * 核心断言：{@link Middleware#implementedHooks()} 不含目标 hook 名的中间件，
 * 链构建器<b>跳过</b>（不调其 pass-through 默认实现）。
 * <p>
 * 这替代了 AgentScope 的反射检测（base_method is not sub_method）——
 * 语义等价（中间件只实现需要的 hook，框架自动跳过未实现的），但 JPMS 安全、可调试。
 */
class MiddlewareRegistrySkipNoOpTest {

    private MiddlewareRegistry registry;

    @BeforeEach
    void setUp() {
        registry = MiddlewareRegistry.instance();
        registry.clearForTesting();
    }

    @AfterEach
    void tearDown() {
        registry.clearForTesting();
    }

    private static final ToolContext TOOL_CTX = new ToolContext("agent_test", null, "/tmp");
    private static final ToolInput INPUT = () -> "{}";

    private static ActingContext actingCtx() {
        return new ActingContext("agent_test", "bash", INPUT, TOOL_CTX, Map.of());
    }

    /** Spy 中间件：计数 onActing 调用次数，但声明只实现 on_system_prompt。 */
    private static class SpyDeclaresSystemPromptOnly implements Middleware {
        final AtomicInteger onActingCount = new AtomicInteger();
        final AtomicInteger onSystemPromptCount = new AtomicInteger();

        @Override
        public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
            onActingCount.incrementAndGet();
            return next.proceed();
        }
        @Override
        public String onSystemPrompt(String prompt) {
            onSystemPromptCount.incrementAndGet();
            return prompt + "?";
        }
        @Override
        public Set<String> implementedHooks() {
            return Set.of(Middleware.ON_SYSTEM_PROMPT);  // 只声明 on_system_prompt
        }
    }

    @Test
    @DisplayName("implementedHooks()=Set.of(on_system_prompt) 的中间件在 fireOnActing 时不被调用")
    void skipOnActing_whenNotDeclared() throws Exception {
        SpyDeclaresSystemPromptOnly spy = new SpyDeclaresSystemPromptOnly();
        registry.register(spy);

        registry.fireOnActing(actingCtx(), () -> ToolOutput.ok("leaf"));

        assertEquals(0, spy.onActingCount.get(),
                "on_acting 未声明 → 链构建器跳过，onActing 不应被调用");
    }

    @Test
    @DisplayName("同一中间件在 fireOnSystemPrompt 时被调用（声明了 on_system_prompt）")
    void calledOnSystemPrompt_whenDeclared() {
        SpyDeclaresSystemPromptOnly spy = new SpyDeclaresSystemPromptOnly();
        registry.register(spy);

        String out = registry.fireOnSystemPrompt("base");

        assertEquals(1, spy.onSystemPromptCount.get(), "on_system_prompt 已声明 → 应被调用");
        assertEquals("base?", out);
        assertEquals(0, spy.onActingCount.get(), "on_acting 仍不应被调用");
    }

    @Test
    @DisplayName("implementedHooks()=Set.of()（默认空）的中间件对所有 fire 都被跳过")
    void skipAll_whenEmptyImplementedHooks() throws Exception {
        AtomicInteger onActingCount = new AtomicInteger();
        Middleware m = new Middleware() {
            @Override
            public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
                onActingCount.incrementAndGet();
                return next.proceed();
            }
            // 不覆写 implementedHooks() → 默认 Set.of()
        };
        registry.register(m);

        registry.fireOnActing(actingCtx(), () -> ToolOutput.ok("leaf"));

        assertEquals(0, onActingCount.get(),
                "implementedHooks() 默认空集 → 所有 fire 跳过此中间件");
    }

    @Test
    @DisplayName("声明 on_acting 的中间件在 fireOnActing 时被调用")
    void calledOnActing_whenDeclared() throws Exception {
        AtomicInteger onActingCount = new AtomicInteger();
        Middleware m = new Middleware() {
            @Override
            public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
                onActingCount.incrementAndGet();
                return next.proceed();
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_ACTING);
            }
        };
        registry.register(m);

        registry.fireOnActing(actingCtx(), () -> ToolOutput.ok("leaf"));

        assertEquals(1, onActingCount.get(), "已声明 on_acting → 应被调用一次");
    }

    @Test
    @DisplayName("混合：on_acting 中间件被调用，on_system_prompt 中间件被跳过")
    void mixedChain_onlyRelevantMiddlewaresCalled() throws Exception {
        AtomicInteger actingCount = new AtomicInteger();
        AtomicInteger systemPromptCount = new AtomicInteger();

        registry.register(new Middleware() {
            @Override
            public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
                actingCount.incrementAndGet();
                return next.proceed();
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_ACTING);
            }
        });
        registry.register(new Middleware() {
            @Override
            public String onSystemPrompt(String prompt) {
                systemPromptCount.incrementAndGet();
                return prompt;
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_SYSTEM_PROMPT);
            }
        });

        registry.fireOnActing(actingCtx(), () -> ToolOutput.ok("leaf"));

        assertEquals(1, actingCount.get(), "on_acting 中间件应被调用");
        assertEquals(0, systemPromptCount.get(), "on_system_prompt 中间件在 fireOnActing 时被跳过");
    }
}
