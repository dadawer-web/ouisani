package com.ouisani.aios.core.syscall;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.memory.ContextInjector;
import com.ouisani.aios.core.memory.MemoryManager;
import com.ouisani.aios.core.mcp.McpClientRegistry;
import com.ouisani.aios.core.observability.UpstreamMeta;
import com.ouisani.aios.core.observability.UpstreamMetaHook;
import com.ouisani.aios.core.plugin.AgentToolContext;
import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.plugin.ToolDefinition;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import com.ouisani.aios.core.sandbox.DockerSandboxProvider;
import com.ouisani.aios.user.bridge.rpa.HostRpaManager;
import com.ouisani.aios.user.bridge.rpa.SecurityToken;
import com.ouisani.aios.user.bridge.rpa.PermissionDeniedException;
import com.ouisani.aios.core.security.BpfManager;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.security.SyscallFilter;
import com.ouisani.aios.core.syscall.schema.LlmPayload;
import com.ouisani.aios.core.syscall.schema.MemoryPayload;
import com.ouisani.aios.core.syscall.schema.RawPayload;
import com.ouisani.aios.core.syscall.schema.StoragePayload;
import com.ouisani.aios.core.syscall.schema.SyscallPayload;
import com.ouisani.aios.core.syscall.schema.ToolPayload;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.core.trace.TraceSpan;
import com.ouisani.aios.core.trace.TracingManager;
import com.ouisani.aios.vfs.DeviceOfflineException;
import com.ouisani.aios.vfs.MutableFileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central Syscall Dispatcher — the sole gateway between Agents and the AIOS kernel.
 * <p>
 * Routes {@link SyscallRequest}s by {@code namespace} to the appropriate backend
 * service, with strict type-safety enforcement: each namespace requires its
 * corresponding {@link SyscallPayload} subtype. Mismatched payloads trigger
 * a kernel panic exception.
 *
 * <h3>Standard Namespace Routing (ABI v2):</h3>
 * <ul>
 *   <li>{@code llm} → {@link LlmPayload} → {@link LlmRouter}</li>
 *   <li>{@code storage} → {@link StoragePayload} → {@link VfsManager}</li>
 *   <li>{@code tool} → {@link ToolPayload} → {@link PluginManager} / {@link DockerSandboxProvider}</li>
 *   <li>{@code memory} → {@link MemoryPayload} → MemoryProvider (pending)</li>
 * </ul>
 *
 * <h3>Legacy Namespace Routing (backward compat):</h3>
 * <ul>
 *   <li>{@code vfs}, {@code handle}, {@code coreutils}, {@code apt}, {@code bin}</li>
 * </ul>
 */
public final class SyscallDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SyscallDispatcher.class);

    private static final class Holder {
        static final SyscallDispatcher INSTANCE = new SyscallDispatcher();
    }

    public static SyscallDispatcher getInstance() {
        return Holder.INSTANCE;
    }

    private LlmRouter llmRouter;
    private VfsManager vfsManager;
    private ObjectManager objectManager;

    /** Seccomp/eBPF-style syscall firewall filter chain. */
    private final List<SyscallFilter> filters = new CopyOnWriteArrayList<>();

    /** 幂等性账本 — 写操作去重的最后一道防线。 */
    private final IdempotencyLedger idempotencyLedger = IdempotencyLedger.getInstance();

    private SyscallDispatcher() {}

    public void configure(LlmRouter llmRouter, VfsManager vfsManager, ObjectManager objectManager) {
        this.llmRouter = llmRouter;
        this.vfsManager = vfsManager;
        this.objectManager = objectManager;
        log.info("[Syscall Dispatcher] 已配置: llmRouter={}, vfsManager={}, objectManager={}",
                llmRouter != null, vfsManager != null, objectManager != null);

        // ── Kernel ABI v2: Namespace-based ABI Router ──
        System.out.println("[Kernel Dispatcher] ABI Router 已升级，正在监听标准命名空间。");
        log.info("[Kernel Dispatcher] ABI Router 已升级，正在监听标准命名空间。");

        // ── MCP routing protocol engaged ──
        System.out.println("[Kernel Dispatcher] MCP 路由协议已启用，AIOS 生态系统现已无限扩展。");
        log.info("[Kernel Dispatcher] MCP 路由协议已启用，AIOS 生态系统现已无限扩展。");

        // ── Semantic eBPF: 注册 BpfManager 到 Seccomp 过滤器链 ──
        // BpfManager 实现了 SyscallFilter 接口，在每次 Syscall 执行前
        // 进行意图拦截（Prompt 注入检测、VFS 破坏性写入保护、权限提升拦截等）
        addFilter(BpfManager.instance());
        log.info("[Kernel Dispatcher] Semantic eBPF 探针已注册，意图拦截已激活。");
        System.out.println("[Kernel Dispatcher] Semantic eBPF 探针已注册，意图拦截已激活。");

        // ── Privilege Filter: 注册特权级过滤器（Linux Capabilities 模型） ──
        // PrivilegeSyscallFilter 拦截高危操作（vfs.mount、run_docker、apt.install 等），
        // 要求调用 Agent 至少持有 HIGH 优先级；低优先级 Agent 触发 HITL 审批门。
        addFilter(new com.ouisani.aios.core.security.PrivilegeSyscallFilter());
        log.info("[Kernel Dispatcher] Privilege Syscall Filter 已注册，高危操作将走 HITL 审批门。");
        System.out.println("[Kernel Dispatcher] Privilege Syscall Filter 已注册，高危操作将走 HITL 审批门。");

        // ── Semantic Firewall: 注册 AI 语义审核过滤器 ──
        // SemanticSyscallFilter 拦截高危操作（bash, fs_write, fs_delete 等），
        // 并移交 AiSecurityAuditor 进行 LLM 语义判定。
        // 这是 Seccomp-BPF 的语义升级版：不仅检查 syscall 号，还理解意图。
        addFilter(new com.ouisani.aios.core.security.SemanticSyscallFilter());
        log.info("[Kernel Dispatcher] Semantic Firewall (AI Auditor) 已注册，高危 Syscall 将被审计。");
        System.out.println("[Kernel Dispatcher] Semantic Firewall (AI Auditor) 已注册，高危 Syscall 将被审计。");
    }

    /**
     * Register a syscall filter into the Seccomp firewall chain.
     * Filters are executed in registration order.
     */
    public void addFilter(SyscallFilter filter) {
        filters.add(filter);
        log.info("[Syscall Dispatcher] Seccomp 过滤器已注册: {} (total={})",
                filter.getClass().getSimpleName(), filters.size());
    }

    /**
     * 获取已配置的 LlmRouter 实例 — 供流式推理等需要直接访问 Router 的场景使用。
     *
     * @return LlmRouter 实例，未配置时返回 null
     */
    public LlmRouter getLlmRouter() {
        return llmRouter;
    }

    // ════════════════════════════════════════════════════════════════
    //  CORE EXECUTE — Namespace-based ABI Router
    // ════════════════════════════════════════════════════════════════

    /**
     * Execute a system call on behalf of an Agent.
     * <p>
     * The kernel ABI router dispatches based on {@code request.namespace()}:
     * <ul>
     *   <li>{@code llm} — strict LlmPayload, routes to LlmRouter</li>
     *   <li>{@code storage} — strict StoragePayload, routes to VfsManager</li>
     *   <li>{@code tool} — strict ToolPayload, routes to PluginManager or DockerSandboxProvider</li>
     *   <li>{@code memory} — strict MemoryPayload, routes to MemoryProvider</li>
     *   <li>others — legacy fallback routing</li>
     * </ul>
     *
     * @param agentId the agent issuing the syscall
     * @param request the syscall request
     * @return the syscall response
     */
    public SyscallResponse execute(String agentId, SyscallRequest request) {
        long startNanos = System.nanoTime();

        String fullAction = request.fullAction();

        // ── Tracing Span：CUSTOM 级，覆盖单次 syscall 执行 ──
        TraceSpan syscallSpan = TracingManager.instance().startSpan(
                "syscall." + fullAction, TraceSpan.SpanType.CUSTOM);
        if (syscallSpan != null) {
            syscallSpan.setAttribute("agent_id", agentId);
            syscallSpan.setAttribute("namespace", request.namespace());
            syscallSpan.setAttribute("action", request.action());
        }

        SyscallResponse response = null;
        // ── UpstreamMeta 拒绝码：filter / Hook 早返回路径用，正常路径为 null ──
        String rejectionCode = null;
        try {
        log.info("[Syscall Dispatcher] 拦截命名空间='{}' 动作='{}' 来自 Agent '{}'",
                request.namespace(), request.action(), agentId);

        // ── Seccomp/eBPF Firewall: pre-filter chain ──
        for (SyscallFilter filter : filters) {
            try {
                filter.preFilter(agentId, request);
            } catch (SecurityException e) {
                SemanticEtw.getInstance().logEvent("SECURITY", "BPF_INTERCEPT",
                        "agent=" + agentId + " action=" + fullAction
                        + " filter=" + filter.getClass().getSimpleName()
                        + " reason=" + e.getMessage());
                log.warn("[Security BPF] 恶意 Syscall 已拦截! agent={}, action={}, filter={}",
                        agentId, fullAction, filter.getClass().getSimpleName());
                // 赋值给 response，使 finally 块能记录 UpstreamMeta（标记 errorCode="SECURITY"）
                response = SyscallResponse.fail("SECURITY: " + e.getMessage());
                rejectionCode = "SECURITY";
                return response;
            }
        }

        // ── 幂等性账本：写操作去重 ──
        // 命中终态 → 直接重放，跳过执行；命中 PENDING → 重放 pending 响应（不重复执行）；
        // 未命中 → tryReserve 占位 PENDING，执行后 resolve。
        String idemKey = request.idempotencyKey();
        if (idemKey != null && !idemKey.isEmpty()) {
            var cached = idempotencyLedger.lookup(idemKey);
            if (cached.isPresent()) {
                SyscallResponse replay = cached.get().toResponse();
                SemanticEtw.getInstance().logEvent("IDEMPOTENCY", "REPLAY",
                        "agent=" + agentId + " action=" + fullAction
                        + " key=" + idemKey + " state=" + cached.get().resultState());
                log.info("[Idempotency] 命中账本，重放: agent={}, action={}, key={}, state={}",
                        agentId, fullAction, idemKey, cached.get().resultState());
                return replay;
            }
            if (!idempotencyLedger.tryReserve(idemKey)) {
                // 并发竞争：另一个线程刚占位，重放其 pending
                var raced = idempotencyLedger.lookup(idemKey);
                if (raced.isPresent()) {
                    SyscallResponse replay = raced.get().toResponse();
                    log.info("[Idempotency] 并发占位冲突，重放 pending: agent={}, action={}, key={}",
                            agentId, fullAction, idemKey);
                    return replay;
                }
                // 极端竞态：占位失败又查不到，继续执行（降级，不阻断）
                log.warn("[Idempotency] 占位失败但查无记录，降级执行: agent={}, action={}, key={}",
                        agentId, fullAction, idemKey);
            }
        }

        SemanticEtw.getInstance().logEvent("SYSCALL", "ENTER",
                "agent=" + agentId + " namespace=" + request.namespace() + " action=" + fullAction);

        // ── PreToolUse Hook (前置钩子) ──
        // 在工具真正执行前触发，允许钩子修改参数或拒绝执行。
        // 借鉴 ECC 的 4 层架构：Agents(委托层) → Rules(规则层) → Hooks(触发层) → Skills(工作流层)
        // 映射到 PrivilegeSyscallFilter 之外，提供 AOP 式的切面扩展点。
        if ("tool".equals(request.namespace())) {
            java.util.Map<String, Object> hookData = new java.util.HashMap<>();
            hookData.put("agentId", agentId);
            hookData.put("action", fullAction);
            hookData.put("request", request);
            if (request.payload() instanceof com.ouisani.aios.core.syscall.schema.ToolPayload tool) {
                hookData.put("toolName", tool.toolName());
                hookData.put("args", tool.arguments());
            }
            com.ouisani.aios.core.hook.HookManager.HookResult preResult =
                    com.ouisani.aios.core.hook.HookManager.instance()
                            .trigger(com.ouisani.aios.core.hook.HookManager.HookEvent.PRE_TOOL_USE, hookData);
            if (!preResult.proceed()) {
                log.info("[Syscall Hook] PreToolUse 钩子拒绝执行: agent={}, action={}, reason={}",
                        agentId, fullAction, preResult.message());
                SemanticEtw.getInstance().logEvent("HOOK", "PRE_TOOL_USE_DENIED",
                        "agent=" + agentId + " action=" + fullAction + " reason=" + preResult.message());
                // 赋值给 response，使 finally 块能记录 UpstreamMeta（标记 errorCode="HOOK_DENIED"）
                response = SyscallResponse.fail("HOOK_DENIED: " + preResult.message());
                rejectionCode = "HOOK_DENIED";
                return response;
            }
        }

        try {
            response = switch (request.namespace()) {
                case "llm"     -> routeLlm(agentId, request);
                case "storage" -> routeStorage(agentId, request);
                case "tool"    -> routeTool(agentId, request);
                case "memory"  -> routeMemory(agentId, request);
                default        -> routeLegacy(agentId, request);
            };
        } catch (SyscallException e) {
            response = SyscallResponse.fail(e);
        } catch (SecurityException e) {
            response = SyscallResponse.fail("SECURITY: " + e.getMessage());
        } catch (Exception e) {
            response = SyscallResponse.fail(e);
        }

        // ── 幂等性账本：记录终态结果 ──
        // 仅记录 response 自身的 resultState（ok→COMMITTED, fail→FAILED）。
        // PENDING_UNKNOWN 不在此设置——由外层超时包装器显式 markPending。
        if (idemKey != null && !idemKey.isEmpty() && response != null
                && response.resultState() != ResultState.PENDING_UNKNOWN) {
            idempotencyLedger.resolve(idemKey, response.resultState(),
                    response.data(), response.errorMessage());
        }

        // ── PostToolUse Hook (后置钩子) ──
        // 工具执行完成后触发，允许钩子进行后处理（如语法检查、状态同步）。
        // 借鉴 ECC：当 FileWriteTool 成功写入后，触发后置 Hook 进行实时语法树检查。
        if ("tool".equals(request.namespace())) {
            java.util.Map<String, Object> postHookData = new java.util.HashMap<>();
            postHookData.put("agentId", agentId);
            postHookData.put("action", fullAction);
            postHookData.put("response", response);
            postHookData.put("success", response.success());

            com.ouisani.aios.core.hook.HookManager.HookEvent postEvent = response.success()
                    ? com.ouisani.aios.core.hook.HookManager.HookEvent.POST_TOOL_USE
                    : com.ouisani.aios.core.hook.HookManager.HookEvent.POST_TOOL_USE_FAILURE;

            com.ouisani.aios.core.hook.HookManager.instance().trigger(postEvent, postHookData);
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        SemanticEtw.getInstance().logEvent("SYSCALL", "EXIT",
                "agent=" + agentId + " action=" + fullAction
                + " success=" + response.success()
                + " latencyMs=" + elapsedMs);

        log.info("[Syscall Dispatcher] 动作 '{}' 已完成，Agent '{}': 成功={}, 延迟={}ms",
                fullAction, agentId, response.success(), elapsedMs);

        // ── 喂狗 — 每次成功 syscall 执行后向 WatchdogDaemon 报告系统存活 ──
        if (response.success()) {
            com.ouisani.aios.core.rtos.WatchdogDaemon.instance().ping("syscall");
        }

        return response;
        } finally {
            // ── Tracing Span：设置 success/latency_ms 并结束 ──
            if (syscallSpan != null) {
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                syscallSpan.setAttribute("latency_ms", latencyMs);
                if (response != null) {
                    syscallSpan.setAttribute("success", response.success());
                    syscallSpan.setStatus(response.success()
                            ? TraceSpan.Status.OK : TraceSpan.Status.ERROR);
                    // ── UpstreamMeta 注入：捕获 per-call 元数据，落盘 + 桥接 Span attributes ──
                    // 幂等重放路径 response == null 时跳过（重放是缓存命中，非真实上游调用）
                    recordUpstreamMeta(agentId, request, response, latencyMs, syscallSpan, rejectionCode);
                }
                TracingManager.instance().endSpan(syscallSpan.spanId());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  UpstreamMeta — 上游调用元数据捕获
    //  借鉴 nuwa UpstreamMeta 模式：把 res.locals.upstream 中间件链元数据透传
    //  适配为 Java：落盘到 .aios/upstream_meta.jsonl + TraceSpan attributes 桥接
    // ════════════════════════════════════════════════════════════════

    /**
     * 构造 UpstreamMeta 并落盘 + 桥接 TraceSpan。
     * <p>
     * <b>Best-effort</b>：所有异常 catch，永不中断 syscall 主流程（与 ProvenanceHook 一致）。
     * <p>
     * 字段映射详见 plan 文档 .trae/documents/upstream-meta-eventbus-propagation.md §4.1。
     *
     * @param agentId       syscall 调用方
     * @param request       syscall 请求（用于解析 upstreamName）
     * @param response      syscall 响应（用于解析 status_code / bytes / error_code）
     * @param latencyMs     墙钟耗时（毫秒）
     * @param span          已激活的 TraceSpan（用于桥接 upstream.* attributes）
     * @param rejectionCode 早返回路径的拒绝码（"SECURITY" / "HOOK_DENIED" / null）
     */
    private void recordUpstreamMeta(String agentId, SyscallRequest request,
                                    SyscallResponse response, long latencyMs,
                                    TraceSpan span, String rejectionCode) {
        try {
            String upstreamName = resolveUpstreamName(request);
            int statusCode = resolveUpstreamStatusCode(response, rejectionCode);
            long bytes = response.data() == null ? 0L
                    : response.data().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String errorCode = resolveUpstreamErrorCode(response, request, rejectionCode);

            UpstreamMeta meta = new UpstreamMeta(
                    upstreamName,
                    latencyMs,
                    statusCode,
                    null,           // v1: cost_units 留 null，待 LlmProvider 暴露 per-call token usage
                    bytes,
                    errorCode,
                    System.currentTimeMillis(),
                    ProvenanceHook.CURRENT_AGENT_ID.get(),
                    ProvenanceHook.CURRENT_SESSION_ID.get()
            );

            // 1. 落盘到 .aios/upstream_meta.jsonl（best-effort，永不抛）
            UpstreamMetaHook.onUpstreamCall(meta);

            // 2. 桥接 TraceSpan：设置 upstream.* attributes，让 OpenTelemetry 后端可查询
            span.setAttribute("upstream.name", meta.upstreamName());
            span.setAttribute("upstream.duration_ms", meta.upstreamDurationMs());
            span.setAttribute("upstream.status_code", meta.upstreamStatusCode());
            span.setAttribute("upstream.bytes", meta.upstreamBytes());
            if (meta.upstreamCostUnits() != null) {
                span.setAttribute("upstream.cost_units", meta.upstreamCostUnits());
            }
            if (meta.errorCode() != null) {
                span.setAttribute("upstream.error_code", meta.errorCode());
            }
        } catch (Throwable t) {
            // best-effort: 永不中断主流程
            log.warn("[UpstreamMeta] 记录失败 (action={}): {}", request.fullAction(), t.getMessage());
        }
    }

    /**
     * 解析 upstream_name — 根据 namespace + payload 类型给出有意义的上游标识。
     * <p>
     * 映射规则：
     * <ul>
     *   <li>{@code llm} → {@code "llm." + action}</li>
     *   <li>{@code tool} → {@code tool.toolName()} 或 {@code "tool." + action}</li>
     *   <li>{@code storage} → {@code "storage." + mode}</li>
     *   <li>{@code memory} → {@code "memory." + operation}</li>
     *   <li>default → {@code request.fullAction()}</li>
     * </ul>
     */
    private String resolveUpstreamName(SyscallRequest request) {
        try {
            return switch (request.namespace()) {
                case "llm" -> "llm." + request.action();
                case "tool" -> {
                    if (request.payload() instanceof com.ouisani.aios.core.syscall.schema.ToolPayload tp) {
                        yield "tool." + tp.toolName();
                    }
                    yield "tool." + request.action();
                }
                case "storage" -> {
                    if (request.payload() instanceof com.ouisani.aios.core.syscall.schema.StoragePayload sp) {
                        yield "storage." + sp.mode();
                    }
                    yield "storage." + request.action();
                }
                case "memory" -> {
                    if (request.payload() instanceof com.ouisani.aios.core.syscall.schema.MemoryPayload mp) {
                        yield "memory." + mp.operation();
                    }
                    yield "memory." + request.action();
                }
                default -> request.fullAction();
            };
        } catch (Exception e) {
            return request.fullAction();
        }
    }

    /**
     * 解析 upstream_status_code — HTTP 风格状态码。
     * <p>
     * 映射规则：
     * <ul>
     *   <li>rejectionCode="SECURITY" / "HOOK_DENIED" → 403</li>
     *   <li>COMMITTED → 200</li>
     *   <li>PENDING_UNKNOWN → 408</li>
     *   <li>ROLLED_BACK → 409</li>
     *   <li>FAILED → 500</li>
     * </ul>
     */
    private int resolveUpstreamStatusCode(SyscallResponse response, String rejectionCode) {
        if ("SECURITY".equals(rejectionCode) || "HOOK_DENIED".equals(rejectionCode)) {
            return 403;
        }
        ResultState state = response.resultState();
        return switch (state) {
            case COMMITTED -> 200;
            case PENDING_UNKNOWN -> 408;
            case ROLLED_BACK -> 409;
            case FAILED -> 500;
        };
    }

    /**
     * 解析 errorCode — 失败时的错误码（成功为 null）。
     * <p>
     * 映射规则：
     * <ul>
     *   <li>success → null</li>
     *   <li>rejection → rejectionCode（SECURITY / HOOK_DENIED）</li>
     *   <li>其他失败 → errorMessage 中 {@code :} 前缀（异常类名，由 SyscallResponse.fail(Throwable) 格式保证）</li>
     * </ul>
     */
    private String resolveUpstreamErrorCode(SyscallResponse response, SyscallRequest request,
                                            String rejectionCode) {
        if (response.success()) {
            return null;
        }
        if (rejectionCode != null) {
            return rejectionCode;
        }
        // SyscallException → 显式标记
        if (response.errorMessage() != null && response.errorMessage().startsWith("SyscallException")) {
            return "SYSCALL_" + request.action();
        }
        // 其他异常：errorMessage 格式为 "SimpleName: message"，取前缀作为 errorCode
        if (response.errorMessage() != null) {
            int colonIdx = response.errorMessage().indexOf(':');
            if (colonIdx > 0) {
                return response.errorMessage().substring(0, colonIdx);
            }
            return response.errorMessage();
        }
        return "UNKNOWN";
    }

    // ════════════════════════════════════════════════════════════════
    //  RETRY — 读/写差异化重试
    // ════════════════════════════════════════════════════════════════

    /**
     * 带重试策略的 syscall 执行入口。
     * <p>
     * 读操作：失败按 {@link SyscallRetryPolicy} 指数退避重试。
     * 写操作：仅当响应为 {@link ResultState#PENDING_UNKNOWN} 且 ledger 无 COMMITTED 记录、
     * 且策略允许（{@code allowWriteRetryOnPending}）时重试一次。写重试必须携带
     * idempotencyKey，由 {@link IdempotencyLedger} 保证不重复执行。
     * <p>
     * <b>注意</b>：超时检测由调用方负责。调用方在超时应先
     * {@link IdempotencyLedger#markPending} 再调用本方法，本方法据此判定是否重试。
     *
     * @param agentId the agent issuing the syscall
     * @param request the syscall request（写操作应携带 idempotencyKey）
     * @param policy  重试策略
     * @return 最终响应
     */
    public SyscallResponse executeWithRetry(String agentId, SyscallRequest request,
                                            SyscallRetryPolicy policy) {
        SyscallResponse last = null;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                last = execute(agentId, request);
            } catch (Exception e) {
                // execute 内部已包装异常为 fail，此处兜底极端未捕获异常
                last = SyscallResponse.fail(e);
            }
            if (!policy.shouldRetry(request, attempt, last, idempotencyLedger)) {
                return last;
            }
            long backoff = policy.nextBackoffMs(attempt);
            if (backoff > 0) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
            log.info("[Retry] syscall 将重试: agent={}, action={}, attempt={}, lastState={}, backoffMs={}",
                    agentId, request.fullAction(), attempt + 1,
                    last != null ? last.resultState() : "null", backoff);
        }
    }

    /** 暴露幂等账本，供外层超时包装器/governance 层调用 markPending/lookup。 */
    public IdempotencyLedger getIdempotencyLedger() {
        return idempotencyLedger;
    }

    // ════════════════════════════════════════════════════════════════
    //  NAMESPACE ROUTERS — Strict Type-Safe Dispatch
    // ════════════════════════════════════════════════════════════════

    // ── LLM Namespace ──────────────────────────────────────────────

    private SyscallResponse routeLlm(String agentId, SyscallRequest request) {
        SyscallPayload payload = request.payload();

        // Strict type check: only LlmPayload is legal in the "llm" namespace
        if (!(payload instanceof LlmPayload llm)) {
            throw new SyscallException(request.fullAction(),
                    "Kernel Panic: 无效的内存段 — 期望 LlmPayload，实际为 "
                    + payload.getClass().getSimpleName());
        }

        if (llmRouter == null) {
            return SyscallResponse.fail("LLM 路由器未配置");
        }

        String prompt = llm.prompt();
        if (prompt == null || prompt.isEmpty()) {
            return SyscallResponse.fail("LlmPayload.prompt 不能为空");
        }

        try {
            // Transparent context injection: augment prompt with Vector Memory
            String augmentedPrompt = ContextInjector.getInstance().augmentPrompt(prompt);

            String result = switch (request.action()) {
                case "think" -> llmRouter.think(augmentedPrompt, "");
                case "think_with_history" -> {
                    var messages = List.of(new LlmProvider.ChatMessage("user", augmentedPrompt));
                    yield llmRouter.thinkWithHistory(messages, "");
                }
                default -> throw new SyscallException(request.fullAction());
            };

            log.info("[Dispatcher] LLM 命名空间: action='{}', promptLen={}, temp={}, maxTokens={}",
                    request.action(), prompt.length(), llm.temperature(), llm.maxTokens());

            return SyscallResponse.ok(result);
        } catch (SyscallException e) {
            throw e;
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    // ── Storage Namespace ──────────────────────────────────────────

    private SyscallResponse routeStorage(String agentId, SyscallRequest request) {
        SyscallPayload payload = request.payload();

        // Strict type check: only StoragePayload is legal in the "storage" namespace
        if (!(payload instanceof StoragePayload storage)) {
            throw new SyscallException(request.fullAction(),
                    "Kernel Panic: 无效的内存段 — 期望 StoragePayload，实际为 "
                    + payload.getClass().getSimpleName());
        }

        if (vfsManager == null) {
            return SyscallResponse.fail("VFS 管理器未配置");
        }

        String path = storage.path();
        String mode = storage.mode();

        log.info("[Dispatcher] Storage 命名空间: path='{}', mode='{}'", path, mode);

        try {
            return switch (mode) {
                case "read" -> {
                    var nodeOpt = vfsManager.resolve(path);
                    if (nodeOpt.isEmpty()) {
                        yield SyscallResponse.fail("路径未找到: " + path);
                    }
                    String content = nodeOpt.get().read();
                    yield SyscallResponse.ok(content);
                }
                case "write" -> {
                    String data = storage.data();
                    if (data == null) {
                        yield SyscallResponse.fail("Storage 写入需要非空数据");
                    }
                    var nodeOpt = vfsManager.resolve(path);
                    if (nodeOpt.isEmpty()) {
                        MutableFileNode newNode = new MutableFileNode(path);
                        newNode.write(data);
                        vfsManager.mount(SyscallLegacyHandler.extractDirPath(path), SyscallLegacyHandler.extractFileName(path), newNode);
                        log.debug("[VFS] 自动创建文件节点: {}", path);
                        yield SyscallResponse.ok();
                    }
                    boolean ok = nodeOpt.get().write(data);
                    yield ok ? SyscallResponse.ok() : SyscallResponse.fail("写入被节点拒绝");
                }
                case "exists" -> {
                    var nodeOpt = vfsManager.resolve(path);
                    yield SyscallResponse.ok(nodeOpt.isPresent() ? "true" : "false");
                }
                case "append" -> {
                    String data = storage.data();
                    if (data == null) {
                        yield SyscallResponse.fail("Storage 追加需要非空数据");
                    }
                    var nodeOpt = vfsManager.resolve(path);
                    if (nodeOpt.isEmpty()) {
                        MutableFileNode newNode = new MutableFileNode(path);
                        newNode.write(data);
                        vfsManager.mount(SyscallLegacyHandler.extractDirPath(path), SyscallLegacyHandler.extractFileName(path), newNode);
                        yield SyscallResponse.ok();
                    }
                    String existing = nodeOpt.get().read();
                    boolean ok = nodeOpt.get().write(existing != null ? existing + data : data);
                    yield ok ? SyscallResponse.ok() : SyscallResponse.fail("追加被节点拒绝");
                }
                default -> SyscallResponse.fail("未知的存储模式: " + mode);
            };
        } catch (DeviceOfflineException e) {
            log.warn("[Dispatcher] Agent '{}' 的设备离线: path={}, device={}", agentId, path, e.deviceId());
            return SyscallResponse.fail("设备离线: " + e.deviceId() + " at " + e.devicePath()
                    + ". 远程主机已断开连接，请稍后重试或使用其他设备。");
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    private SyscallResponse routeTool(String agentId, SyscallRequest request) {
        SyscallPayload payload = request.payload();

        // Strict type check: only ToolPayload is legal in the "tool" namespace
        if (!(payload instanceof ToolPayload tool)) {
            throw new SyscallException(request.fullAction(),
                    "Kernel Panic: 无效的内存段 — 期望 ToolPayload，实际为 "
                    + payload.getClass().getSimpleName());
        }

        String toolName = tool.toolName();
        Map<String, Object> args = tool.arguments();

        log.info("[Dispatcher] Tool 命名空间: toolName='{}', args={}", toolName, args.keySet());

        try {
            // ── Dynamic Module Loading: sys_insmod / sys_rmmod ──
            if ("kernel.insmod".equals(toolName)) {
                return executeInsmod(agentId, args);
            }
            if ("kernel.rmmod".equals(toolName)) {
                return executeRmmod(agentId, args);
            }
            if ("kernel.lsmod".equals(toolName)) {
                return executeLsmod(agentId);
            }
            if ("kernel.modprobe".equals(toolName)) {
                return executeModprobe(agentId, args);
            }

            // ── 动态工具锻造（借鉴 Agent Zero 运行时工具生成） ──
            if ("kernel.register_tool".equals(toolName)) {
                return executeRegisterTool(agentId, args);
            }
            if ("kernel.forge_tool".equals(toolName)) {
                return executeForgeTool(agentId, args);
            }

            // MCP universal tool bus: external capabilities via Model Context Protocol
            if (isMcpTool(toolName)) {
                return executeMcpTool(agentId, toolName, args);
            }

            // RPA physical driver: host GUI actuator and vision
            if (isRpaTool(toolName)) {
                return executeRpaTool(agentId, toolName, args);
            }

            // Docker sandbox: if toolName indicates a docker/container operation
            if (isDockerTool(toolName)) {
                return executeDockerTool(agentId, toolName, args);
            }

            // WASM plugin: delegate to PluginManager
            return executeWasmTool(agentId, toolName, args);
        } catch (Exception e) {
            return SyscallResponse.fail("工具执行失败: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  sys_insmod / sys_rmmod / sys_lsmod / sys_modprobe
    //  Dynamic Kernel Module Loading (insmod/rmmod)
    // ════════════════════════════════════════════════════════════════

    /**
     * sys_insmod: Load a tool into the Agent's active context.
     * <p>
     * Arguments:
     * <ul>
     *   <li>{@code query} (String) — natural language tool requirement, e.g., "我需要一个能搜索网页的工具"</li>
     *   <li>{@code tool_name} (String, optional) — exact tool name to load (bypasses semantic search)</li>
     * </ul>
     * <p>
     * Returns the loaded tool's JSON Schema in the response data.
     */
    private SyscallResponse executeInsmod(String agentId, Map<String, Object> args) {
        PluginManager pm = PluginManager.getInstance();

        // Check for direct tool name first
        String toolName = args.get("tool_name") instanceof String s ? s : null;
        if (toolName != null && !toolName.isBlank()) {
            ToolDefinition def = pm.insmodByName(agentId, toolName);
            if (def != null) {
                return SyscallResponse.ok("[insmod] 工具 '" + def.name()
                        + "' 已加载。Schema: " + def.toFunctionSchema());
            }
            return SyscallResponse.fail("[insmod] 工具 '" + toolName + "' 未在目录中找到。可用: "
                    + pm.availableTools());
        }

        // Semantic search by natural language query
        String query = args.get("query") instanceof String s ? s : null;
        if (query == null || query.isBlank()) {
            return SyscallResponse.fail("[insmod] 缺少 'query' 或 'tool_name' 参数");
        }

        ToolDefinition def = pm.insmod(agentId, query);
        if (def != null) {
            return SyscallResponse.ok("[insmod] 工具 '" + def.name() + "' 已加载 (匹配: '"
                    + query + "'). Schema: " + def.toFunctionSchema());
        }

        return SyscallResponse.fail("[insmod] 未找到匹配的工具: '" + query
                + "'. 可用: " + pm.availableTools());
    }

    /**
     * sys_rmmod: Unload a tool from the Agent's active context.
     * <p>
     * Arguments:
     * <ul>
     *   <li>{@code tool_name} (String) — the tool to unload</li>
     * </ul>
     */
    private SyscallResponse executeRmmod(String agentId, Map<String, Object> args) {
        String toolName = args.get("tool_name") instanceof String s ? s : null;
        if (toolName == null || toolName.isBlank()) {
            return SyscallResponse.fail("[rmmod] 缺少 'tool_name' 参数");
        }

        PluginManager pm = PluginManager.getInstance();
        boolean removed = pm.rmmod(agentId, toolName);
        if (removed) {
            return SyscallResponse.ok("[rmmod] 工具 '" + toolName + "' 已卸载。Token 预算已释放。");
        }
        return SyscallResponse.fail("[rmmod] 工具 '" + toolName + "' 未在活跃上下文中找到。");
    }

    /**
     * sys_lsmod: List all tools currently loaded in the Agent's context.
     */
    private SyscallResponse executeLsmod(String agentId) {
        PluginManager pm = PluginManager.getInstance();
        AgentToolContext ctx = pm.getAgentContext(agentId);

        if (ctx.toolCount() == 0) {
            return SyscallResponse.ok("[lsmod] 未加载任何工具。使用 kernel.insmod 加载工具。");
        }

        return SyscallResponse.ok("[lsmod] " + ctx.toolCount() + " 个工具已加载 ("
                + ctx.tokenCostUsed() + "/" + ctx.tokenBudget() + " tokens):\n"
                + ctx.toProcStatus());
    }

    /**
     * sys_modprobe: Search for available tools without loading them.
     * <p>
     * Arguments:
     * <ul>
     *   <li>{@code query} (String) — natural language tool requirement</li>
     * </ul>
     */
    private SyscallResponse executeModprobe(String agentId, Map<String, Object> args) {
        String query = args.get("query") instanceof String s ? s : null;
        if (query == null || query.isBlank()) {
            return SyscallResponse.fail("[modprobe] 缺少 'query' 参数");
        }

        PluginManager pm = PluginManager.getInstance();
        java.util.List<ToolDefinition> matches = pm.semanticSearch(query, 5);

        if (matches.isEmpty()) {
            return SyscallResponse.ok("[modprobe] 未找到匹配的工具: '" + query + "'");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[modprobe] 为 '").append(query).append("' 找到 ").append(matches.size()).append(" 个工具:\n");
        for (int i = 0; i < matches.size(); i++) {
            ToolDefinition t = matches.get(i);
            sb.append(String.format("  %d. %-30s [%s] cost=%d — %s%n",
                    i + 1, t.name(), t.type(), t.tokenCost(),
                    t.description().length() > 60 ? t.description().substring(0, 60) + "..." : t.description()));
        }
        sb.append("\n使用 kernel.insmod 并指定 tool_name 加载特定工具。");

        return SyscallResponse.ok(sb.toString());
    }

    private boolean isMcpTool(String toolName) {
        return toolName.startsWith("mcp.");
    }

    // ════════════════════════════════════════════════════════════════
    //  动态工具锻造（借鉴 Agent Zero 运行时工具生成）
    //  kernel.forge_tool / kernel.register_tool
    // ════════════════════════════════════════════════════════════════

    /**
     * 执行动态工具锻造 — 借鉴 Agent Zero 的运行时工具生成。
     * Agent 描述所需工具功能，LLM 生成代码，注册为可调用工具。
     */
    private SyscallResponse executeForgeTool(String agentId, Map<String, Object> args) {
        String description = args.get("description") instanceof String s ? s : null;
        if (description == null || description.isBlank()) {
            return SyscallResponse.fail("Missing 'description' field for tool forging");
        }

        com.ouisani.aios.user.sdk.AiosSdk sdk = com.ouisani.aios.user.sdk.AiosSdk.getInstance();
        String workingDir = System.getProperty("user.dir");

        String toolName = com.ouisani.aios.core.tool.ToolForgeService.getInstance()
                .forge(description, agentId, sdk, workingDir);
        if (toolName != null) {
            return SyscallResponse.ok("{\"toolName\":\"" + toolName + "\",\"status\":\"forged\"}");
        } else {
            return SyscallResponse.fail("Tool forging failed for: " + description);
        }
    }

    /**
     * 执行动态工具注册 — 将已生成的代码直接注册为工具。
     */
    private SyscallResponse executeRegisterTool(String agentId, Map<String, Object> args) {
        String toolName = args.get("toolName") instanceof String s ? s : null;
        String code = args.get("code") instanceof String s ? s : null;
        String description = args.get("description") instanceof String s ? s : null;

        if (toolName == null || code == null) {
            return SyscallResponse.fail("Missing 'toolName' or 'code' field");
        }

        com.ouisani.aios.user.sdk.AiosSdk sdk = com.ouisani.aios.user.sdk.AiosSdk.getInstance();
        String workingDir = System.getProperty("user.dir");

        com.ouisani.aios.core.tool.DynamicForgedTool tool = new com.ouisani.aios.core.tool.DynamicForgedTool(
                toolName,
                description != null ? description : "Dynamically forged tool: " + toolName,
                code,
                "main",
                null,
                agentId,
                sdk,
                workingDir
        );

        com.ouisani.aios.core.tool.ToolRegistry.instance().register(tool);
        com.ouisani.aios.core.tool.ToolForgeService.getInstance().registerForgedTool(tool, agentId);

        return SyscallResponse.ok("{\"toolName\":\"" + toolName + "\",\"status\":\"registered\"}");
    }

    /**
     * Execute an MCP tool call via the universal tool bus.
     * <p>
     * Tool name format: {@code mcp.{serverName}.{mcpToolName}}
     * <p>
     * Example: {@code mcp.weather.get_forecast} →
     * serverName = "weather", mcpToolName = "get_forecast"
     * <p>
     * Errors are returned as valid SyscallResponse (not exceptions) so
     * the LLM can see the failure and attempt self-repair.
     */
    private SyscallResponse executeMcpTool(String agentId, String toolName, Map<String, Object> args) {
        // Parse: mcp.{serverName}.{mcpToolName}
        String[] parts = toolName.split("\\.", 3);
        if (parts.length < 3) {
            return SyscallResponse.fail(
                    "MCP 工具名格式无效: 期望 'mcp.{serverName}.{toolName}'，实际为 '" + toolName + "'");
        }

        String serverName = parts[1];
        String mcpToolName = parts[2];

        McpClientRegistry registry = McpClientRegistry.getInstance();

        if (!registry.hasServer(serverName)) {
            return SyscallResponse.fail(
                    "MCP 服务器 '" + serverName + "' 未注册。可用: " + registry.serverNames());
        }

        log.info("[Dispatcher] MCP 路由: agent='{}', server='{}', tool='{}', args={}",
                agentId, serverName, mcpToolName, args.keySet());

        try {
            Object result = registry.callTool(serverName, mcpToolName, args);

            String resultStr;
            if (result == null) {
                resultStr = "";
            } else if (result instanceof String s) {
                resultStr = s;
            } else {
                resultStr = result.toString();
            }

            log.info("[Dispatcher] MCP 工具 '{}/{}' 对 Agent '{}' 返回成功: resultLen={}",
                    serverName, mcpToolName, agentId, resultStr.length());

            return SyscallResponse.ok(resultStr);
        } catch (Exception e) {
            log.warn("[Dispatcher] MCP 工具 '{}/{}' 对 Agent '{}' 执行失败: {}",
                    serverName, mcpToolName, agentId, e.getMessage());
            // Return error as valid SyscallResponse — let the LLM self-repair
            return SyscallResponse.fail(
                    "MCP 工具 '" + serverName + "/" + mcpToolName + "' 调用失败: " + e.getMessage());
        }
    }

    private boolean isRpaTool(String toolName) {
        return toolName.startsWith("rpa.");
    }

    private SyscallResponse executeRpaTool(String agentId, String toolName, Map<String, Object> args) {
        HostRpaManager rpa = HostRpaManager.getInstance();

        // ── Security: HEADLESS mode check ──
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        if (headless) {
            log.error("[Kernel Security] RPA 工具 '{}' 被拒绝: 系统处于 HEADLESS 模式", toolName);
            return SyscallResponse.fail("RPA 工具在 HEADLESS 模式下不可用");
        }

        // ── Security: SYS_ADMIN Token 鉴权 ──
        // 从 args 中提取 SecurityToken（必须由启动时签发的受信组件传入）
        Object tokenObj = args.get("_security_token");
        SecurityToken token = (tokenObj instanceof SecurityToken st) ? st : null;

        try {
            rpa.requireSysAdmin(token);
        } catch (PermissionDeniedException e) {
            log.error("[Kernel Security] RPA 工具 '{}' 对 Agent '{}' 被拒绝: {}", toolName, agentId, e.getMessage());
            return SyscallResponse.fail("权限被拒绝: " + e.getMessage());
        }

        log.warn("[Kernel Security] 警告: Agent {} 正在操控宿主机物理指针! (token={})",
                agentId, token != null ? token.id() : "null");
        System.out.println("[Kernel Security] 警告: Agent " + agentId + " 正在操控宿主机物理指针!");

        if (!rpa.isAvailable()) {
            return SyscallResponse.fail("RPA 子系统不可用 — Robot 初始化失败");
        }

        return switch (toolName) {
            case "rpa.screenshot" -> {
                String base64 = rpa.takeScreenshotBase64(token);
                log.info("[Dispatcher] 已为 Agent '{}' 截取 RPA 截图: base64Len={}", agentId, base64.length());
                yield SyscallResponse.ok(base64);
            }
            case "rpa.mouse_move" -> {
                int x = toInt(args.get("x"), -1);
                int y = toInt(args.get("y"), -1);
                if (x < 0 || y < 0) {
                    yield SyscallResponse.fail("rpa.mouse_move 需要整数 'x' 和 'y' 参数");
                }
                rpa.mouseMove(token, x, y);
                log.info("[Dispatcher] Agent '{}' 的 RPA mouse_move: ({}, {})", agentId, x, y);
                yield SyscallResponse.ok("鼠标已移动到 (" + x + ", " + y + ")");
            }
            case "rpa.click" -> {
                rpa.mouseClick(token);
                log.info("[Dispatcher] Agent '{}' 的 RPA click", agentId);
                yield SyscallResponse.ok("鼠标已点击");
            }
            case "rpa.right_click" -> {
                rpa.mouseRightClick(token);
                log.info("[Dispatcher] Agent '{}' 的 RPA right_click", agentId);
                yield SyscallResponse.ok("已右键点击");
            }
            case "rpa.click_at" -> {
                int x = toInt(args.get("x"), -1);
                int y = toInt(args.get("y"), -1);
                if (x < 0 || y < 0) {
                    yield SyscallResponse.fail("rpa.click_at 需要整数 'x' 和 'y' 参数");
                }
                rpa.mouseClickAt(token, x, y);
                log.info("[Dispatcher] Agent '{}' 的 RPA click_at: ({}, {})", agentId, x, y);
                yield SyscallResponse.ok("已点击 (" + x + ", " + y + ")");
            }
            case "rpa.scroll" -> {
                int amount = toInt(args.get("amount"), 1);
                rpa.mouseScroll(token, amount);
                log.info("[Dispatcher] Agent '{}' 的 RPA scroll: amount={}", agentId, amount);
                yield SyscallResponse.ok("已滚动 " + amount);
            }
            case "rpa.type" -> {
                String text = args.get("text") != null ? args.get("text").toString() : null;
                if (text == null || text.isEmpty()) {
                    yield SyscallResponse.fail("rpa.type 需要 'text' 参数");
                }
                rpa.keyboardType(token, text);
                log.info("[Dispatcher] Agent '{}' 的 RPA type: textLen={}", agentId, text.length());
                yield SyscallResponse.ok("已输入 " + text.length() + " 个字符");
            }
            case "rpa.key_combo" -> {
                int keyCode = toInt(args.get("keyCode"), -1);
                int modifiers = toInt(args.get("modifiers"), 0);
                if (keyCode < 0) {
                    yield SyscallResponse.fail("rpa.key_combo 需要 'keyCode' 参数");
                }
                rpa.keyCombo(token, modifiers, keyCode);
                log.info("[Dispatcher] Agent '{}' 的 RPA key_combo: keyCode={}, modifiers={}", agentId, keyCode, modifiers);
                yield SyscallResponse.ok("组合键已执行");
            }
            default -> SyscallResponse.fail("未知的 RPA 工具: " + toolName);
        };
    }

    private static int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private boolean isDockerTool(String toolName) {
        return toolName.startsWith("run_docker")
                || toolName.startsWith("docker_")
                || toolName.equals("container_exec");
    }

    private SyscallResponse executeDockerTool(String agentId, String toolName, Map<String, Object> args) {
        DockerSandboxProvider dockerSandbox = new DockerSandboxProvider();

        String script = args.get("script") != null ? args.get("script").toString() : "";
        String entrypoint = args.get("entrypoint") != null ? args.get("entrypoint").toString() : "main";

        if (script.isEmpty()) {
            return SyscallResponse.fail("Docker 工具需要 'script' 参数");
        }

        try {
            String result = dockerSandbox.executeCode(script, entrypoint);
            log.info("[Dispatcher] Docker 工具 '{}' 已为 Agent '{}' 执行", toolName, agentId);
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("Docker 执行失败: " + e.getMessage());
        }
    }

    private SyscallResponse executeWasmTool(String agentId, String toolName, Map<String, Object> args) {
        PluginManager pluginManager = PluginManager.getInstance();
        String pluginAction = "tool." + toolName;

        if (!pluginManager.hasPlugin(pluginAction)) {
            return SyscallResponse.fail("插件未注册: " + pluginAction);
        }

        String paramsJson = serializeParams(args);

        try {
            String result = pluginManager.executePlugin(pluginAction, paramsJson);
            log.info("[Dispatcher] WASM 插件 '{}' 已为 Agent '{}' 执行", pluginAction, agentId);
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("WASM 插件执行失败: " + e.getMessage());
        }
    }

    // ── Memory Namespace ───────────────────────────────────────────

    private SyscallResponse routeMemory(String agentId, SyscallRequest request) {
        SyscallPayload payload = request.payload();

        // Strict type check: only MemoryPayload is legal in the "memory" namespace
        if (!(payload instanceof MemoryPayload mem)) {
            throw new SyscallException(request.fullAction(),
                    "Kernel Panic: 无效的内存段 — 期望 MemoryPayload，实际为 "
                    + payload.getClass().getSimpleName());
        }

        log.info("[Dispatcher] 正在路由到 Memory 子系统... operation='{}', queryLen={}",
                mem.operation(), mem.query() != null ? mem.query().length() : 0);

        // Delegate to the unified MemoryManager
        return MemoryManager.getInstance().processMemorySyscall(agentId, mem);
    }

    // ════════════════════════════════════════════════════════════════
    //  LEGACY NAMESPACE ROUTER — Backward Compatibility
    // ════════════════════════════════════════════════════════════════

    private SyscallResponse routeLegacy(String agentId, SyscallRequest request) {
        String fullAction = request.fullAction();

        if (fullAction.startsWith("tool.")) {
            return SyscallLegacyHandler.handleToolPlugin(agentId, request);
        } else if (fullAction.startsWith("coreutils.")) {
            return SyscallLegacyHandler.handleCoreUtils(agentId, request);
        } else if (fullAction.startsWith("apt.")) {
            return SyscallLegacyHandler.handleApt(agentId, request);
        } else if (fullAction.startsWith("jit.")) {
            return SyscallLegacyHandler.handleJit(agentId, request);
        } else if (fullAction.startsWith("bin.")) {
            return SyscallLegacyHandler.handleBin(agentId, request);
        } else {
            return switch (fullAction) {
                case "vfs.read" -> SyscallLegacyHandler.handleVfsRead(agentId, request, vfsManager);
                case "vfs.write" -> SyscallLegacyHandler.handleVfsWrite(agentId, request, vfsManager);
                case "vfs.rollback" -> SyscallLegacyHandler.handleVfsRollback(agentId, request, vfsManager);
                case "vfs.snapshot" -> SyscallLegacyHandler.handleVfsSnapshot(agentId, request, vfsManager);
                case "handle.open" -> SyscallLegacyHandler.handleOpen(agentId, request, objectManager);
                case "handle.read" -> SyscallLegacyHandler.handleRead(agentId, request, objectManager);
                case "handle.close" -> SyscallLegacyHandler.handleClose(agentId, request, objectManager);
                default -> throw new SyscallException(fullAction);
            };
        }
    }

    // ── Utility ──

    static String serializeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : params.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object v = entry.getValue();
            if (v instanceof Number) {
                sb.append(v);
            } else if (v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append("\"").append(escapeJson(v.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
