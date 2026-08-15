package com.ouisani.aios.core.middleware;

import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.middleware.Middleware.ActingContext;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HookManagerBridgeMiddleware} 桥接测试 — 向后兼容验证。
 * <p>
 * 验证现有 HookManager handler（PRE/POST_TOOL_USE）经洋葱中间件仍触发，
 * 且 deny 语义零回归（toText 精确返回 "Blocked by PreToolUse hook: X"，不加 "ERROR: " 前缀）。
 */
class HookManagerBridgeMiddlewareTest {

    private MiddlewareRegistry registry;
    private HookManagerBridgeMiddleware bridge;

    @BeforeEach
    void setUp() {
        registry = MiddlewareRegistry.instance();
        registry.clearForTesting();
        // 隔离 HookManager — 清除其他测试可能残留的 handler
        HookManager.instance().clearAll();
        bridge = new HookManagerBridgeMiddleware();
        registry.register(bridge);
    }

    @AfterEach
    void tearDown() {
        registry.clearForTesting();
        HookManager.instance().clearAll();
    }

    private static final ToolContext TOOL_CTX = new ToolContext("agent_test", null, "/tmp");
    private static final ToolInput INPUT = () -> "{\"command\":\"ls\"}";

    private static ActingContext actingCtx() {
        return new ActingContext("agent_test", "bash", INPUT, TOOL_CTX, Map.of());
    }

    @Test
    @DisplayName("PRE_TOOL_USE handler 在洋葱内触发：deny 时跳过 leaf，toText 精确匹配原字符串")
    void preToolUseDeny_skipsLeafAndPreservesExactMessage() throws Exception {
        HookManager.instance().register(HookManager.HookEvent.PRE_TOOL_USE, (event, data) ->
                HookManager.HookResult.deny("security policy violation"));

        boolean[] leafCalled = {false};
        ToolOutput out = registry.fireOnActing(actingCtx(), () -> {
            leafCalled[0] = true;
            return ToolOutput.ok("should-not-reach");
        });

        assertFalse(leafCalled[0], "deny 时 leaf 不应被执行");
        assertFalse(out.success());
        // 零回归：精确匹配原 QueryEngine 返回的字符串，不加 "ERROR: " 前缀
        assertEquals("Blocked by PreToolUse hook: security policy violation", out.toText());
    }

    @Test
    @DisplayName("PRE_TOOL_USE proceed=true：leaf 执行，返回 leaf 结果")
    void preToolUseProceed_leafExecutes() throws Exception {
        HookManager.instance().register(HookManager.HookEvent.PRE_TOOL_USE, (event, data) ->
                HookManager.HookResult.ok());

        ToolOutput out = registry.fireOnActing(actingCtx(), () -> ToolOutput.ok("leaf-done"));

        assertTrue(out.success());
        assertEquals("leaf-done", out.toText());
    }

    @Test
    @DisplayName("PRE_TOOL_USE 传递 tool_name / input / agentId 到 handler data")
    void preToolUsePassesContextData() throws Exception {
        AtomicReference<String> seenTool = new AtomicReference<>();
        AtomicReference<String> seenInput = new AtomicReference<>();
        AtomicReference<String> seenAgent = new AtomicReference<>();
        HookManager.instance().register(HookManager.HookEvent.PRE_TOOL_USE, (event, data) -> {
            seenTool.set((String) data.get("tool_name"));
            seenInput.set((String) data.get("input"));
            seenAgent.set((String) data.get("agentId"));
            return HookManager.HookResult.ok();
        });

        registry.fireOnActing(actingCtx(), () -> ToolOutput.ok("ok"));

        assertEquals("bash", seenTool.get());
        assertEquals("{\"command\":\"ls\"}", seenInput.get());
        assertEquals("agent_test", seenAgent.get());
    }

    @Test
    @DisplayName("leaf 成功 → POST_TOOL_USE 触发（含 tool_name/success/duration_ms）")
    void postToolUseTriggered_onSuccess() throws Exception {
        AtomicInteger postCount = new AtomicInteger();
        AtomicReference<Boolean> seenSuccess = new AtomicReference<>();
        AtomicReference<String> seenTool = new AtomicReference<>();
        HookManager.instance().register(HookManager.HookEvent.POST_TOOL_USE, (event, data) -> {
            postCount.incrementAndGet();
            seenSuccess.set((Boolean) data.get("success"));
            seenTool.set((String) data.get("tool_name"));
            return HookManager.HookResult.ok();
        });

        registry.fireOnActing(actingCtx(), () -> ToolOutput.ok("ok"));

        assertEquals(1, postCount.get(), "POST_TOOL_USE 应触发一次");
        assertTrue(seenSuccess.get(), "success=true");
        assertEquals("bash", seenTool.get());
    }

    @Test
    @DisplayName("leaf 逻辑失败（output.success()==false）→ POST_TOOL_USE_FAILURE 触发")
    void postToolUseFailureTriggered_onLogicalFailure() throws Exception {
        AtomicInteger failCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();
        HookManager.instance().register(HookManager.HookEvent.POST_TOOL_USE, (event, data) -> {
            successCount.incrementAndGet();
            return HookManager.HookResult.ok();
        });
        HookManager.instance().register(HookManager.HookEvent.POST_TOOL_USE_FAILURE, (event, data) -> {
            failCount.incrementAndGet();
            return HookManager.HookResult.ok();
        });

        ToolOutput out = registry.fireOnActing(actingCtx(), () -> ToolOutput.fail("tool error"));

        assertFalse(out.success());
        assertEquals(0, successCount.get(), "POST_TOOL_USE 不应触发");
        assertEquals(1, failCount.get(), "POST_TOOL_USE_FAILURE 应触发");
    }

    @Test
    @DisplayName("无 HookManager handler 时：bridge pass-through，leaf 正常执行")
    void noHandlers_bridgePassThrough() throws Exception {
        // 不注册任何 HookManager handler
        ToolOutput out = registry.fireOnActing(actingCtx(), () -> ToolOutput.ok("leaf"));

        assertTrue(out.success());
        assertEquals("leaf", out.toText());
    }

    @Test
    @DisplayName("implementedHooks() 声明 on_acting（链构建器不跳过 bridge）")
    void implementedHooks_declaresOnActing() {
        assertTrue(bridge.implementedHooks().contains(Middleware.ON_ACTING));
        assertEquals(1, bridge.implementedHooks().size());
    }
}
