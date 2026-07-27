package com.ouisani.aios.core.middleware;

import com.ouisani.aios.core.middleware.Middleware.ActingContext;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MiddlewareRegistry} 链构建与异常处理测试 — D1（过滤）+ D7（异常 best-effort）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>注册序 = 洋葱外→内（先注册 = 最外层，最先 PRE、最后 POST）</li>
 *   <li>PRE 异常不中断 leaf（跳过本中间件，仍调 next.proceed）</li>
 *   <li>POST 异常返回 leaf 结果不变（不丢数据）</li>
 *   <li>transformer 左→右组合（m2(m1(prompt))）</li>
 *   <li>空注册表时 fire 直接调 leaf</li>
 * </ul>
 */
class MiddlewareRegistryChainTest {

    private MiddlewareRegistry registry;

    @BeforeEach
    void setUp() {
        registry = MiddlewareRegistry.instance();
        // 隔离静态初始化器自注册的内置中间件
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

    /** 记录 PRE/POST 顺序的中间件。 */
    private static class RecordingMiddleware implements Middleware {
        final String name;
        final List<String> trace;
        RecordingMiddleware(String name, List<String> trace) {
            this.name = name;
            this.trace = trace;
        }
        @Override
        public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
            trace.add(name + ".PRE");
            ToolOutput out = next.proceed();
            trace.add(name + ".POST");
            return out;
        }
        @Override
        public Set<String> implementedHooks() {
            return Set.of(Middleware.ON_ACTING);
        }
    }

    @Test
    @DisplayName("注册序 = 洋葱外→内：m1.PRE → m2.PRE → leaf → m2.POST → m1.POST")
    void chainOrder_outerToInner() throws Exception {
        List<String> trace = new ArrayList<>();
        registry.register(new RecordingMiddleware("m1", trace));
        registry.register(new RecordingMiddleware("m2", trace));

        ToolOutput out = registry.fireOnActing(actingCtx(), () -> {
            trace.add("leaf");
            return ToolOutput.ok("done");
        });

        assertEquals(List.of("m1.PRE", "m2.PRE", "leaf", "m2.POST", "m1.POST"), trace);
        assertTrue(out.success());
        assertEquals("done", out.toText());
    }

    @Test
    @DisplayName("PRE 异常不中断 leaf：中间件 PRE 抛异常 → 跳过本中间件，leaf 仍执行")
    void preException_doesNotBlockLeaf() throws Exception {
        // 外层中间件 PRE 抛异常，内层正常
        Middleware throwing = new Middleware() {
            @Override
            public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
                throw new RuntimeException("PRE boom");
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_ACTING);
            }
        };
        registry.register(throwing);

        boolean[] leafCalled = {false};
        ToolOutput out = registry.fireOnActing(actingCtx(), () -> {
            leafCalled[0] = true;
            return ToolOutput.ok("leaf-survived");
        });

        assertTrue(leafCalled[0], "leaf 必须被执行（PRE 异常不中断下游）");
        assertTrue(out.success());
        assertEquals("leaf-survived", out.toText());
    }

    @Test
    @DisplayName("POST 异常返回 leaf 结果不变：中间件 POST 抛异常 → 返回 next.proceed() 结果")
    void postException_returnsLeafResult() throws Exception {
        Middleware postThrower = new Middleware() {
            @Override
            public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
                ToolOutput out = next.proceed();
                throw new RuntimeException("POST boom");
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_ACTING);
            }
        };
        registry.register(postThrower);

        ToolOutput leafResult = ToolOutput.ok("leaf-data");
        ToolOutput out = registry.fireOnActing(actingCtx(), () -> leafResult);

        assertSame(leafResult, out, "POST 异常应返回 leaf 结果不变（不丢数据）");
        assertEquals("leaf-data", out.toText());
    }

    @Test
    @DisplayName("LEAF 异常向上传播：不 catch，由调用方处理")
    void leafException_propagates() {
        Middleware m = new Middleware() {
            @Override
            public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
                return next.proceed();  // 直接放行，不 catch leaf 异常
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_ACTING);
            }
        };
        registry.register(m);

        RuntimeException leafEx = new RuntimeException("leaf boom");
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                registry.fireOnActing(actingCtx(), () -> { throw leafEx; }));
        assertSame(leafEx, thrown, "leaf 异常应原样向上传播");
    }

    @Test
    @DisplayName("transformer 左→右组合：m2(m1(prompt))")
    void transformer_leftToRightComposition() {
        registry.register(new Middleware() {
            @Override
            public String onSystemPrompt(String prompt) {
                return "[m1]" + prompt;
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_SYSTEM_PROMPT);
            }
        });
        registry.register(new Middleware() {
            @Override
            public String onSystemPrompt(String prompt) {
                return "[m2]" + prompt;
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_SYSTEM_PROMPT);
            }
        });

        String out = registry.fireOnSystemPrompt("base");
        assertEquals("[m2][m1]base", out, "左→右：m1 先变换，m2 后变换");
    }

    @Test
    @DisplayName("transformer 异常保留上一个 prompt（不中断管道）")
    void transformer_exception_keepsPreviousPrompt() {
        registry.register(new Middleware() {
            @Override
            public String onSystemPrompt(String prompt) {
                return "[ok]" + prompt;
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_SYSTEM_PROMPT);
            }
        });
        registry.register(new Middleware() {
            @Override
            public String onSystemPrompt(String prompt) {
                throw new RuntimeException("transformer boom");
            }
            @Override
            public Set<String> implementedHooks() {
                return Set.of(Middleware.ON_SYSTEM_PROMPT);
            }
        });

        String out = registry.fireOnSystemPrompt("base");
        assertEquals("[ok]base", out, "异常中间件跳过，保留上一个 prompt");
    }

    @Test
    @DisplayName("空注册表：fire 直接调 leaf")
    void emptyRegistry_fireCallsLeafDirectly() throws Exception {
        // clearForTesting 已在 setUp 调用
        boolean[] leafCalled = {false};
        ToolOutput out = registry.fireOnActing(actingCtx(), () -> {
            leafCalled[0] = true;
            return ToolOutput.ok("direct");
        });
        assertTrue(leafCalled[0]);
        assertEquals("direct", out.toText());
    }

    @Test
    @DisplayName("三层洋葱：外→中→内→leaf→内→中→外")
    void threeLayerChain_fullOrder() throws Exception {
        List<String> trace = new ArrayList<>();
        registry.register(new RecordingMiddleware("outer", trace));
        registry.register(new RecordingMiddleware("mid", trace));
        registry.register(new RecordingMiddleware("inner", trace));

        registry.fireOnActing(actingCtx(), () -> {
            trace.add("leaf");
            return ToolOutput.ok("ok");
        });

        assertEquals(
                List.of("outer.PRE", "mid.PRE", "inner.PRE", "leaf",
                        "inner.POST", "mid.POST", "outer.POST"),
                trace);
    }
}
