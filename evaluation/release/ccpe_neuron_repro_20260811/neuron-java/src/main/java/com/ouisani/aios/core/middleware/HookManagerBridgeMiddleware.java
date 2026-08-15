package com.ouisani.aios.core.middleware;

import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.middleware.Middleware.ActingContext;
import com.ouisani.aios.core.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * HookManager 桥接中间件 — 把现有 {@link HookManager} 的扁平 PRE/POST_TOOL_USE 事件分发
 * 接入洋葱中间件链（向后兼容桥）。
 * <p>
 * <b>为什么需要桥</b>：{@link HookManager#trigger} 是两次独立调用（PRE 在 tool.call 前，
 * POST 在 tool.call 后），没有 {@code next_handler} 包裹语义。本中间件在 {@code onActing}
 * 内用 {@link NextHandler} 把 PRE → leaf → POST 串成洋葱一层，使现有 HookManager handler
 * （{@code SecurityAuditHook} / {@code VfsAsyncWriterHook} / {@code SecurityScanApprovalHook} 等）
 * <b>零改动</b>继续生效，且获得洋葱语义（PRE 可短路、POST 拿 leaf 结果）。
 *
 * <h3>deny 语义（零回归）</h3>
 * PRE handler 返回 {@code proceed=false} 时，原 {@code QueryEngine.executeTool} 返回字符串
 * {@code "Blocked by PreToolUse hook: <msg>"}。本中间件返回一个 {@link ToolOutput}，
 * 其 {@code toText()} <b>精确</b>返回该字符串（不添加 {@code ToolOutput.fail} 的 "ERROR: " 前缀），
 * 保证现有测试与日志断言不变。
 *
 * <h3>POST 事件选择</h3>
 * leaf 成功 → {@link HookManager.HookEvent#POST_TOOL_USE}；
 * leaf 失败（{@code output.success()==false}）→ {@link HookManager.HookEvent#POST_TOOL_USE_FAILURE}。
 * leaf 抛异常 → 不触发 POST（异常向上传播，由 {@code executeTool} 现有 catch 处理，与原行为一致）。
 *
 * <h3>计时</h3>
 * 本中间件测量 {@code next.proceed()} 的墙钟耗时（含内层中间件 + leaf），用于 POST 的 {@code duration_ms}。
 * 与原 {@code QueryEngine} 测量 {@code tool.call} 时间略有差异（多了内层 UpstreamMeta 开销，可忽略）。
 *
 * @see HookManager
 * @see MiddlewareRegistry
 */
public class HookManagerBridgeMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(HookManagerBridgeMiddleware.class);

    @Override
    public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
        // ── PRE_TOOL_USE ──
        Map<String, Object> preData = new HashMap<>();
        preData.put("tool_name", ctx.toolName());
        preData.put("input", ctx.input() != null ? ctx.input().toJson() : null);
        preData.put("agentId", ctx.agentId());
        HookManager.HookResult preResult = HookManager.instance().trigger(
                HookManager.HookEvent.PRE_TOOL_USE, preData);
        if (!preResult.proceed()) {
            log.info("[HookManagerBridge] PreToolUse 拒绝工具 '{}': {}", ctx.toolName(), preResult.message());
            // 零回归：toText() 精确返回原字符串，不加 "ERROR: " 前缀
            final String blocked = "Blocked by PreToolUse hook: " + preResult.message();
            return new ToolOutput() {
                @Override public boolean success() { return false; }
                @Override public String toText() { return blocked; }
            };
        }

        // ── LEAF（含内层中间件）── next.proceed() 异常向上传播（D7 LEAF 不 catch）
        long start = System.currentTimeMillis();
        ToolOutput output = next.proceed();
        long duration = System.currentTimeMillis() - start;

        // ── POST_TOOL_USE / POST_TOOL_USE_FAILURE ──
        Map<String, Object> postData = new HashMap<>();
        postData.put("tool_name", ctx.toolName());
        postData.put("success", output.success());
        postData.put("duration_ms", duration);
        postData.put("agentId", ctx.agentId());
        HookManager.HookEvent postEvent = output.success()
                ? HookManager.HookEvent.POST_TOOL_USE
                : HookManager.HookEvent.POST_TOOL_USE_FAILURE;
        try {
            HookManager.instance().trigger(postEvent, postData);
        } catch (Throwable t) {
            // HookManager.trigger 内部已 catch，但防御性双保险——POST 异常永不影响主流程
            log.warn("[HookManagerBridge] POST_TOOL_USE 触发异常（已忽略）: {}", t.getMessage());
        }

        return output;
    }

    @Override
    public Set<String> implementedHooks() {
        return Set.of(Middleware.ON_ACTING);
    }
}
