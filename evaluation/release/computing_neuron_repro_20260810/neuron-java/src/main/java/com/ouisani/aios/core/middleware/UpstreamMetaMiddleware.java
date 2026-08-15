package com.ouisani.aios.core.middleware;

import com.ouisani.aios.core.middleware.Middleware.ActingContext;
import com.ouisani.aios.core.observability.UpstreamMeta;
import com.ouisani.aios.core.observability.UpstreamMetaHook;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import com.ouisani.aios.core.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * UpstreamMeta 增量观测中间件 — 在 QueryEngine 层记录工具调用的上游元数据。
 * <p>
 * <b>增量非迁移（D5）</b>：{@code UpstreamMetaHook.onUpstreamCall} 现仅从
 * {@code SyscallDispatcher.recordUpstreamMeta}（syscall 层）调用，记录 {@code tool.<name>}。
 * 本中间件在 <b>QueryEngine 层</b>记录工具调用，用 <b>distinct 前缀 {@code tool.query.<name>}</b>
 * （vs syscall 层 {@code tool.<name>}），复用现有 JSONL+FIFO+查询设施。syscall 层记录原样不动。
 * 两者磁盘共存，现有 {@code UpstreamMetaQuery.listByUpstream("tool.*")} 行为不变。
 *
 * <h3>字段映射</h3>
 * <table>
 *   <tr><th>字段</th><th>来源</th></tr>
 *   <tr><td>upstream_name</td><td>{@code "tool.query." + ctx.toolName()}</td></tr>
 *   <tr><td>upstream_duration_ms</td><td>{@code next.proceed()} 墙钟耗时（含内层中间件 + leaf）</td></tr>
 *   <tr><td>upstream_status_code</td><td>成功 200 / 逻辑失败(output.success()==false) 500 / 异常 500</td></tr>
 *   <tr><td>upstream_cost_units</td><td>null（v1 不计 token，同 SyscallDispatcher）</td></tr>
 *   <tr><td>upstream_bytes</td><td>0L（保持 on_acting 边界——不做 toText() 转换；syscall 层记录真实字节）</td></tr>
 *   <tr><td>error_code</td><td>异常时 "TOOL_FAIL"，否则 null</td></tr>
 *   <tr><td>ts</td><td>{@code System.currentTimeMillis()}</td></tr>
 *   <tr><td>agentId</td><td>{@code ctx.agentId()}（fallback {@code ProvenanceHook.CURRENT_AGENT_ID}）</td></tr>
 *   <tr><td>sessionId</td><td>{@code ProvenanceHook.CURRENT_SESSION_ID.get()}（同 SyscallDispatcher，DAG 联合查询键）</td></tr>
 * </table>
 *
 * <h3>异常处理</h3>
 * {@code next.proceed()} 抛异常时，仍记录 failure meta（{@code error_code=TOOL_FAIL, status=500}）后
 * <b>rethrow</b>（异常向上传播，由 {@code QueryEngine.executeTool} 现有 catch 处理）。
 * {@code UpstreamMetaHook.onUpstreamCall} 内部 best-effort，永不抛——本中间件不中断主流程。
 *
 * @see UpstreamMeta
 * @see UpstreamMetaHook
 * @see com.ouisani.aios.core.syscall.SyscallDispatcher
 */
public class UpstreamMetaMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(UpstreamMetaMiddleware.class);

    /** QueryEngine 层工具调用记录的 upstream_name 前缀（与 syscall 层 "tool." 区分）。 */
    public static final String UPSTREAM_NAME_PREFIX = "tool.query.";

    /** 异常路径的 error_code（与 syscall 层 rejectionCode 风格一致）。 */
    public static final String ERROR_CODE_TOOL_FAIL = "TOOL_FAIL";

    @Override
    public ToolOutput onActing(ActingContext ctx, NextHandler<ToolOutput> next) throws Exception {
        long startNano = System.nanoTime();
        ToolOutput output;
        boolean succeeded = false;
        boolean threw = false;
        try {
            output = next.proceed();
            succeeded = output.success();
            return output;
        } catch (Exception e) {
            threw = true;
            recordMeta(ctx, startNano, false, ERROR_CODE_TOOL_FAIL);
            throw e;
        } finally {
            if (!threw) {
                // 成功或逻辑失败（output.success()==false）——errorCode=null，statusCode 按 success 定
                recordMeta(ctx, startNano, succeeded, null);
            }
        }
    }

    /**
     * 构造 UpstreamMeta 并落盘（best-effort）。
     * <p>
     * 复用 {@link UpstreamMetaHook#onUpstreamCall}——内部 catch Throwable，永不抛。
     */
    private void recordMeta(ActingContext ctx, long startNano, boolean success, String errorCode) {
        try {
            long durationMs = (System.nanoTime() - startNano) / 1_000_000;
            int statusCode = success ? 200 : 500;
            String agentId = ctx.agentId() != null ? ctx.agentId()
                    : ProvenanceHook.CURRENT_AGENT_ID.get();
            String sessionId = ProvenanceHook.CURRENT_SESSION_ID.get();
            UpstreamMeta meta = new UpstreamMeta(
                    UPSTREAM_NAME_PREFIX + ctx.toolName(),
                    durationMs,
                    statusCode,
                    null,           // v1: cost_units 留 null（同 SyscallDispatcher）
                    0L,             // bytes=0L：保持 on_acting 边界，不做 toText() 转换
                    errorCode,
                    System.currentTimeMillis(),
                    agentId,
                    sessionId
            );
            UpstreamMetaHook.onUpstreamCall(meta);
        } catch (Throwable t) {
            // 防御性双保险——UpstreamMetaHook 内部已 catch，此处不应触发
            log.warn("[UpstreamMetaMiddleware] 记录失败 (tool={}): {}",
                    ctx.toolName(), t.getMessage());
        }
    }

    @Override
    public Set<String> implementedHooks() {
        return Set.of(Middleware.ON_ACTING);
    }
}
