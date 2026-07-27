package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.middleware.Middleware;
import com.ouisani.aios.core.middleware.MiddlewareRegistry;
import com.ouisani.aios.core.middleware.NextHandler;
import com.ouisani.aios.core.permission.PermissionProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link QueryEngine#executeTool} 的 on_acting 边界测试 — 核心工程决策验证。
 * <p>
 * 引用 AgentScope L114-L158 文档：on_acting 仅包裹纯 I/O（tool.call），permission/state mutation
 * 都在洋葱外。本测试验证：<b>权限拒绝时，中间件 onActing 不被调用</b>——证明 permission 在洋葱外。
 * <p>
 * 同包（{@code core.tool}）以访问 package-private {@link QueryEngine.ToolCall}，
 * 反射调用 private {@code executeTool} 绕过 query 循环（避免 ReviewGate/ToolCallParser 复杂度）。
 */
class QueryEngineOnActingBoundaryTest {

    @TempDir
    Path tempDir;

    private MiddlewareRegistry registry;
    private CountingMiddleware spy;

    @BeforeEach
    void setUp() {
        registry = MiddlewareRegistry.instance();
        registry.clearForTesting();
        spy = new CountingMiddleware();
        registry.register(spy);
    }

    @AfterEach
    void tearDown() {
        registry.clearForTesting();
    }

    /** 计数 onActing 调用——increment 在 next.proceed() 前，确保即使 leaf 抛异常也计数。 */
    private static class CountingMiddleware implements Middleware {
        final AtomicInteger onActingCount = new AtomicInteger();
        @Override
        public ToolOutput onActing(Middleware.ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
            onActingCount.incrementAndGet();
            return next.proceed();
        }
        @Override
        public Set<String> implementedHooks() {
            return Set.of(Middleware.ON_ACTING);
        }
    }

    /** reviewer blindness 画像：*:deny（拒绝所有工具） */
    private static PermissionProfile denyAllProfile() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("mode", "default");
        perm.put("deny", List.of("*"));
        perm.put("allow", List.of());
        return PermissionProfile.fromMap(perm);
    }

    private static Tool<ToolInput> stubTool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("stub-ok"); }
            @Override public boolean readOnly() { return false; }
        };
    }

    /** 反射调用 private executeTool，绕过 query 循环。 */
    private String invokeExecuteTool(QueryEngine engine, String toolName, String paramsJson) throws Exception {
        Method m = QueryEngine.class.getDeclaredMethod("executeTool", QueryEngine.ToolCall.class);
        m.setAccessible(true);
        QueryEngine.ToolCall tc = new QueryEngine.ToolCall(toolName, paramsJson);
        return (String) m.invoke(engine, tc);
    }

    @Test
    @DisplayName("权限拒绝时 onActing 不被调用（permission 在洋葱外）")
    void permissionDenied_middlewareNotCalled() throws Exception {
        // *:deny 画像 → file_write 被拒绝
        QueryEngine engine = new QueryEngine(
                null, "boundary_agent", "/tmp",
                List.of(stubTool("file_write")), denyAllProfile());

        String result = invokeExecuteTool(engine, "file_write",
                "{\"path\":\"/tmp/middleware_boundary.txt\",\"content\":\"x\"}");

        assertTrue(result.contains("权限被拒绝"),
                "权限拒绝应返回拒绝消息，实际: " + result);
        assertEquals(0, spy.onActingCount.get(),
                "权限在洋葱外先拒 → onActing 不应被调用");
    }

    @Test
    @DisplayName("权限放行时 onActing 被调用（验证正向路径 reach fireOnActing）")
    void permissionAllowed_middlewareCalled() throws Exception {
        // 无 deny 画像 → 默认 mode（needsPrompt 但 Agent 模式自动允许）
        Path readFile = tempDir.resolve("target.txt");
        Files.writeString(readFile, "hello-boundary");

        QueryEngine engine = new QueryEngine(
                null, "boundary_agent", "/tmp",
                List.of(stubTool("file_read")));

        String result = invokeExecuteTool(engine, "file_read",
                "{\"path\":\"" + readFile + "\",\"offset\":0,\"limit\":100}");

        // 无论 file_read 由 stub 还是 ToolRegistry 的真实工具处理，中间件都应被调用
        assertEquals(1, spy.onActingCount.get(),
                "权限放行 → onActing 应被调用一次。结果: " + result);
    }

    @Test
    @DisplayName("权限拒绝时返回消息零回归：精确包含 '权限被拒绝' 前缀")
    void permissionDenied_zeroRegressionMessage() throws Exception {
        QueryEngine engine = new QueryEngine(
                null, "boundary_agent", "/tmp",
                List.of(stubTool("file_write")), denyAllProfile());

        String result = invokeExecuteTool(engine, "file_write",
                "{\"path\":\"/tmp/x\",\"content\":\"y\"}");

        // 原 executeTool 返回 "权限被拒绝: " + decision.message()
        assertTrue(result.startsWith("权限被拒绝"),
                "零回归：消息应以 '权限被拒绝' 开头，实际: " + result);
    }
}
