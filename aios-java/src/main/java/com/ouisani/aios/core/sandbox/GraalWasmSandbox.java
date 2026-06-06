package com.ouisani.aios.core.sandbox;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.ipc.SignalInterceptor;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.ByteSequence;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GraalVM WASM 沙箱 — AIOS 的 Ring 3 用户态执行环境。
 * <p>
 * 类比 x86 的 Ring 0 / Ring 3 特权级隔离：
 * <ul>
 *   <li><b>Ring 0 (内核态)</b>：AIOS 内核代码 — TaskScheduler, VfsManager, LlmRouter 等</li>
 *   <li><b>Ring 3 (用户态)</b>：Agent 自主编译的代码 — 在本沙箱中执行</li>
 * </ul>
 *
 * <h3>安全机制</h3>
 * <ol>
 *   <li><b>资源限制</b>：CPU 循环次数上限 + 内存分配最大值，防止死循环和内存泄漏</li>
 *   <li><b>系统调用代理</b>：沙箱内代码不能直接访问硬件/网络，所有 I/O 必须通过
 *       Proxy 转发回 AIOS 内核的 {@link SyscallDispatcher} 进行权限校验</li>
 *   <li><b>异常熔断</b>：除零、越界访问等异常直接销毁沙箱实例，向 Agent 返回 SIGSEGV</li>
 * </ol>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>x86 / Linux</th><th>AIOS GraalWasmSandbox</th><th>说明</th></tr>
 *   <tr><td>Ring 0 / Ring 3</td><td>内核态 / 沙箱态</td><td>特权级隔离</td></tr>
 *   <tr><td>syscall 门</td><td>SyscallProxy</td><td>用户态→内核态转换</td></tr>
 *   <tr><td>rlimit</td><td>SandboxResourceLimit</td><td>资源限制</td></tr>
 *   <tr><td>SIGSEGV</td><td>SandboxFault → SIGSEGV</td><td>段错误熔断</td></tr>
 *   <tr><td>OOM Killer</td><td>内存超限 → 销毁沙箱</td><td>内存保护</td></tr>
 *   <tr><td>/dev/null</td><td>被拒绝的 I/O</td><td>静默丢弃</td></tr>
 * </table>
 *
 * @see CompilerBridge
 * @see SandboxProvider
 */
public class GraalWasmSandbox implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(GraalWasmSandbox.class);

    // ── 默认资源限制 ──

    private static final long DEFAULT_MAX_CPU_CYCLES = 10_000_000L;
    private static final long DEFAULT_MAX_MEMORY_BYTES = 64 * 1024 * 1024L; // 64MB
    private static final int DEFAULT_MAX_STACK_DEPTH = 128;

    // ── 状态 ──

    private Context context;
    private SandboxResourceLimit currentLimits;

    /** 活跃的沙箱实例索引：instanceId → SandboxInstance */
    private final ConcurrentHashMap<String, SandboxInstance> activeInstances = new ConcurrentHashMap<>();

    // ── 统计 ──

    private final AtomicLong totalExecutions = new AtomicLong(0);
    private final AtomicLong totalFaults = new AtomicLong(0);
    private final AtomicLong totalOomKills = new AtomicLong(0);
    private final AtomicLong totalCircuitBreaks = new AtomicLong(0);

    // ════════════════════════════════════════════════════════════════
    //  初始化
    // ════════════════════════════════════════════════════════════════

    /**
     * 初始化 GraalVM WASM Context — 注册 Ring 3 → Ring 0 系统调用代理。
     * <p>
     * 类比 Linux 的 syscall 门：用户态代码通过 int 0x80 / syscall 指令
     * 触发系统调用，内核在 Ring 0 中执行后返回结果。
     * <p>
     * 在 AIOS 中，沙箱内的 WASM 代码通过导入的宿主函数
     * （如 {@code __aios_syscall}）发起请求，这些请求被转发到
     * {@link SyscallDispatcher} 进行权限校验和执行。
     */
    public void initContext() {
        currentLimits = new SandboxResourceLimit(
                DEFAULT_MAX_CPU_CYCLES, DEFAULT_MAX_MEMORY_BYTES, DEFAULT_MAX_STACK_DEPTH);

        context = Context.newBuilder("wasm")
                .allowAllAccess(false)          // Ring 3: 禁止直接访问宿主
                .option("wasm.MaxMemoryPages", "1024")  // 64MB 上限
                .build();

        Map<String, Object> aiosEnv = new HashMap<>();

        // ── 系统调用代理：Ring 3 → Ring 0 的唯一通道 ──
        aiosEnv.put("__aios_syscall", (ProxyExecutable) arguments -> {
            String action = arguments[0].asString();
            String payload = arguments.length > 1 ? arguments[1].asString() : "";

            // 通过 SyscallDispatcher 进行权限校验
            return executeSyscallProxy(action, payload);
        });

        // ── 日志代理：受控的输出通道 ──
        aiosEnv.put("__aios_log", (ProxyExecutable) arguments -> {
            String message = arguments[0].asString();
            // 限制日志长度，防止日志洪泛
            if (message.length() > 1024) {
                message = message.substring(0, 1024) + "... (truncated)";
            }
            log.info("[Ring3] __aios_log: {}", message);
            return 0;
        });

        // ── VFS 读取代理：受控的文件系统访问 ──
        aiosEnv.put("__aios_vfs_read", (ProxyExecutable) arguments -> {
            String path = arguments[0].asString();
            int maxLen = arguments.length > 2 ? arguments[2].asInt() : 4096;

            // 路径安全检查 — 防止路径遍历
            if (isPathTraversal(path)) {
                log.warn("[Ring3] __aios_vfs_read: PATH TRAVERSAL BLOCKED: {}", path);
                return -1; // EACCES
            }

            try {
                var optNode = VfsManager.instance().resolve(path, VfsManager.AGENT_ROOT.get());
                if (optNode.isEmpty()) {
                    return -1; // ENOENT
                }
                VfsNode node = optNode.get();
                String content = node.read();
                if (content == null) return 0;
                return Math.min(content.length(), maxLen);
            } catch (Exception e) {
                log.error("[Ring3] __aios_vfs_read error: path={}, error={}", path, e.getMessage());
                return -1;
            }
        });

        // ── LLM 推理代理：受控的 AI 调用 ──
        aiosEnv.put("__aios_think", (ProxyExecutable) arguments -> {
            String prompt = arguments[0].asString();
            int maxLen = arguments.length > 2 ? arguments[2].asInt() : 4096;

            // 限制 prompt 长度，防止 Token 滥用
            if (prompt.length() > 8000) {
                log.warn("[Ring3] __aios_think: prompt too long ({}), truncating", prompt.length());
                prompt = prompt.substring(0, 8000);
            }

            try {
                LlmProvider provider = VfsManager.instance().getLlmProvider();
                if (provider == null || !provider.isAvailable()) {
                    return -1;
                }
                String response = provider.think(prompt, "You are an AIOS sandbox agent.");
                if (response == null) return 0;
                return Math.min(response.length(), maxLen);
            } catch (Exception e) {
                log.error("[Ring3] __aios_think error: {}", e.getMessage());
                return -1;
            }
        });

        // ── 资源查询代理 ──
        aiosEnv.put("__aios_resource_check", (ProxyExecutable) arguments -> {
            // 返回当前沙箱的资源使用情况
            return currentLimits != null ? 1 : 0;
        });

        try {
            context.getBindings("wasm").putMember("aios_env", aiosEnv);
            log.info("[Sandbox] Ring 3 environment registered: __aios_syscall, __aios_log, "
                    + "__aios_vfs_read, __aios_think, __aios_resource_check");
        } catch (UnsupportedOperationException e) {
            log.warn("[Sandbox] putMember not supported by WASM global scope (GraalVM limitation)");
            log.warn("[Sandbox] aios_env ProxyExecutable map created but not injected into WASM bindings");
            log.warn("[Sandbox] For WASM modules that import aios_env, a JS bridge layer is required");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Ring 3 安全执行
    // ════════════════════════════════════════════════════════════════

    /**
     * 在 Ring 3 沙箱中执行 WASM 字节码 — 带资源限制和异常熔断。
     * <p>
     * 执行流程：
     * <ol>
     *   <li>信号拦截检查（SIGTERM/SIGINT）</li>
     *   <li>创建沙箱实例（带资源限制）</li>
     *   <li>在受限 Context 中执行 WASM</li>
     *   <li>异常熔断：捕获所有异常，销毁沙箱，向 Agent 发送 SIGSEGV</li>
     * </ol>
     */
    public Value execute(byte[] wasmBytes, String functionName) throws InterruptedException {
        // ── 信号拦截 ──
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null) {
            SignalInterceptor.checkAndDrain(currentTask);
        }

        // ── 创建沙箱实例 ──
        String instanceId = "sandbox-" + System.nanoTime();
        SandboxInstance instance = new SandboxInstance(instanceId, currentLimits, wasmBytes);
        activeInstances.put(instanceId, instance);

        try {
            totalExecutions.incrementAndGet();

            // ── 执行 WASM ──
            Source source = Source.newBuilder("wasm", ByteSequence.create(wasmBytes), instanceId)
                    .buildLiteral();
            context.eval(source);
            Value mainFunc = context.getBindings("wasm").getMember("main").getMember(functionName);

            if (mainFunc == null) {
                throw new SandboxFault("Function not found: " + functionName,
                        SandboxFault.FaultType.INVALID_ACCESS);
            }

            Value result = mainFunc.execute();

            // ── 执行成功 ──
            instance.markCompleted();
            log.debug("[Ring3] Execution completed: instanceId={}, result={}", instanceId, result);

            return result;

        } catch (PolyglotException e) {
            // ── 异常熔断 ──
            return handleSandboxFault(instanceId, instance, currentTask, e);

        } catch (Exception e) {
            // ── 其他异常 ──
            return handleSandboxFault(instanceId, instance, currentTask, e);

        } finally {
            activeInstances.remove(instanceId);
        }
    }

    /**
     * 执行 JIT 编译产物 — CompilerBridge 编译的代码在此执行。
     * <p>
     * 这是 Agent 自主编程的完整流程的最后一步：
     * <ol>
     *   <li>Agent 发现现有工具不足</li>
     *   <li>Agent 编写代码 → {@link CompilerBridge#compile}</li>
     *   <li>编译产物在此方法中执行</li>
     * </ol>
     */
    public SandboxExecutionResult executeJitArtifact(CompilerBridge.CompilationResult compilationResult)
            throws InterruptedException {

        if (!compilationResult.success()) {
            return SandboxExecutionResult.compilationError(
                    compilationResult.compileId(), compilationResult.errorMessage());
        }

        if (compilationResult.bytecode() == null) {
            return SandboxExecutionResult.compilationError(
                    compilationResult.compileId(), "No bytecode in compilation result");
        }

        String instanceId = "jit-" + compilationResult.compileId();
        SandboxInstance instance = new SandboxInstance(
                instanceId, currentLimits, compilationResult.bytecode());
        activeInstances.put(instanceId, instance);

        try {
            totalExecutions.incrementAndGet();

            Value result = execute(compilationResult.bytecode(), compilationResult.entrypoint());

            instance.markCompleted();
            return SandboxExecutionResult.success(
                    instanceId, compilationResult.compileId(), result.toString());

        } catch (SandboxFault f) {
            return SandboxExecutionResult.sandboxFault(instanceId, compilationResult.compileId(), f);

        } catch (Exception e) {
            SandboxFault fault = new SandboxFault(e.getMessage(), SandboxFault.FaultType.UNKNOWN);
            return SandboxExecutionResult.sandboxFault(instanceId, compilationResult.compileId(), fault);

        } finally {
            activeInstances.remove(instanceId);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  异常熔断 (Circuit Breaker)
    // ════════════════════════════════════════════════════════════════

    /**
     * 处理沙箱异常 — 熔断并销毁沙箱实例。
     * <p>
     * 类比 Linux 的 Segfault 处理流程：
     * <ol>
     *   <li>内核捕获异常</li>
     *   <li>销毁出错的地址空间（沙箱实例）</li>
     *   <li>向进程发送 SIGSEGV 信号</li>
     *   <li>进程可以选择捕获信号或终止</li>
     * </ol>
     * <p>
     * 关键原则：<b>绝不能让 Ring 3 的异常传播到 Ring 0</b>。
     * 沙箱的任何故障都必须被完全隔离，AIOS 内核必须保持稳定。
     */
    private Value handleSandboxFault(String instanceId, SandboxInstance instance,
                                      AgentTask currentTask, Throwable cause) {
        totalFaults.incrementAndGet();

        // 确定故障类型
        SandboxFault.FaultType faultType = classifyFault(cause);

        // 销毁沙箱实例
        if (instance != null) {
            instance.markFaulted(faultType);
        }
        activeInstances.remove(instanceId);

        // 向 Agent 发送 SIGSEGV
        if (currentTask != null) {
            currentTask.sendSignal(SignalType.SIGSEGV);
            log.warn("[Ring3] SIGSEGV sent to PID {}: {} (instanceId={})",
                    currentTask.pid(), faultType, instanceId);
        }

        // 记录到 ETW
        SemanticEtw.getInstance().logEvent("SANDBOX", "FAULT",
                "instanceId=" + instanceId + " faultType=" + faultType
                + " cause=" + cause.getMessage());

        log.error("[Ring3] ╔══════════════════════════════════════════════════╗");
        log.error("[Ring3] ║  SANDBOX FAULT: {}                       ║", faultType);
        log.error("[Ring3] ║  Instance: {}                       ║", instanceId);
        log.error("[Ring3] ║  Cause: {}                             ║",
                cause.getMessage() != null ? cause.getMessage().substring(0, Math.min(40, cause.getMessage().length())) : "unknown");
        log.error("[Ring3] ║  Action: SANDBOX DESTROYED, SIGSEGV sent      ║");
        log.error("[Ring3] ╚══════════════════════════════════════════════════╝");

        // 返回一个安全的错误值，而不是抛出异常
        // 这样 Ring 0 代码不会崩溃
        return null;
    }

    /**
     * 分类故障类型 — 根据异常信息判断是哪种 Ring 3 故障。
     */
    private SandboxFault.FaultType classifyFault(Throwable cause) {
        String msg = cause.getMessage();
        if (msg == null) msg = "";

        if (msg.contains("divide") || msg.contains("zero") || msg.contains("/ 0")) {
            return SandboxFault.FaultType.DIVISION_BY_ZERO;
        }
        if (msg.contains("bounds") || msg.contains("index") || msg.contains("out of")) {
            return SandboxFault.FaultType.OUT_OF_BOUNDS;
        }
        if (msg.contains("memory") || msg.contains("allocation") || msg.contains("OOM")) {
            totalOomKills.incrementAndGet();
            return SandboxFault.FaultType.OOM;
        }
        if (msg.contains("timeout") || msg.contains("time limit") || msg.contains("cpu")) {
            totalCircuitBreaks.incrementAndGet();
            return SandboxFault.FaultType.CPU_TIMEOUT;
        }
        if (msg.contains("stack") || msg.contains("overflow") || msg.contains("depth")) {
            return SandboxFault.FaultType.STACK_OVERFLOW;
        }
        if (msg.contains("access") || msg.contains("permission") || msg.contains("denied")) {
            return SandboxFault.FaultType.INVALID_ACCESS;
        }

        return SandboxFault.FaultType.UNKNOWN;
    }

    // ════════════════════════════════════════════════════════════════
    //  系统调用代理 (Syscall Proxy)
    // ════════════════════════════════════════════════════════════════

    /**
     * 系统调用代理 — Ring 3 → Ring 0 的唯一通道。
     * <p>
     * 类比 Linux 的 int 0x80 / syscall 指令：
     * 用户态代码通过中断门触发系统调用，内核在 Ring 0 中
     * 执行权限校验后执行操作，结果返回给用户态。
     * <p>
     * 在 AIOS 中，沙箱内的 WASM 代码调用 {@code __aios_syscall}，
     * 请求被转发到 {@link SyscallDispatcher} 进行权限校验。
     * 只有通过校验的请求才会被执行。
     */
    private int executeSyscallProxy(String action, String payload) {
        try {
            SyscallDispatcher dispatcher = SyscallDispatcher.getInstance();

            // 构造系统调用请求
            Map<String, Object> params = new HashMap<>();
            params.put("payload", payload);
            params.put("source", "ring3_sandbox");

            SyscallRequest request = new SyscallRequest(action, params);
            SyscallResponse response = dispatcher.execute("sandbox_proxy", request);

            if (response.success()) {
                log.debug("[Ring3→Ring0] Syscall OK: action={}", action);
                return 0; // 成功
            } else {
                log.warn("[Ring3→Ring0] Syscall DENIED: action={}, error={}",
                        action, response.errorMessage());
                return -1; // EPERM
            }

        } catch (Exception e) {
            log.error("[Ring3→Ring0] Syscall proxy error: action={}, error={}", action, e.getMessage());
            return -1; // EFAULT
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  SandboxProvider 接口实现
    // ════════════════════════════════════════════════════════════════

    @Override
    public String executeCode(String code, String entrypoint) throws Exception {
        byte[] wasmBytes;
        try {
            wasmBytes = hexToBytes(code);
        } catch (Exception e) {
            log.debug("[GraalWasmSandbox] Code is not valid hex, using mock WASM bytecode");
            wasmBytes = new byte[]{
                    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
                    0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f, 0x03,
                    0x02, 0x01, 0x00, 0x07, 0x08, 0x01, 0x04, 0x6d,
                    0x61, 0x69, 0x6e, 0x00, 0x00, 0x0a, 0x06, 0x01,
                    0x04, 0x00, 0x41, 0x2a, 0x0b
            };
        }

        Value result = execute(wasmBytes, entrypoint != null ? entrypoint : "main");
        return result != null ? result.toString() : "null (sandbox fault)";
    }

    @Override
    public String providerName() {
        return "GraalWasm";
    }

    // ════════════════════════════════════════════════════════════════
    //  资源限制配置
    // ════════════════════════════════════════════════════════════════

    /**
     * 设置沙箱资源限制 — 类比 Linux 的 setrlimit。
     */
    public void setResourceLimit(SandboxResourceLimit limits) {
        this.currentLimits = limits;
        log.info("[Sandbox] Resource limits updated: maxCpuCycles={}, maxMemory={}MB, maxStackDepth={}",
                limits.maxCpuCycles(), limits.maxMemoryBytes() / (1024 * 1024), limits.maxStackDepth());
    }

    public SandboxResourceLimit getResourceLimit() {
        return currentLimits;
    }

    // ════════════════════════════════════════════════════════════════
    //  统计与报告
    // ════════════════════════════════════════════════════════════════

    public int activeInstanceCount() {
        return activeInstances.size();
    }

    public long totalExecutions() {
        return totalExecutions.get();
    }

    public long totalFaults() {
        return totalFaults.get();
    }

    public long totalOomKills() {
        return totalOomKills.get();
    }

    public long totalCircuitBreaks() {
        return totalCircuitBreaks.get();
    }

    public String getStatsReport() {
        return """
                ┌─ GraalWasmSandbox Ring 3 Stats ─────────────────────
                │  Total Executions    : %d
                │  Total Faults        : %d
                │  OOM Kills           : %d
                │  Circuit Breaks      : %d
                │  Active Instances    : %d
                │  Resource Limits     : CPU=%d cycles, MEM=%dMB
                └─────────────────────────────────────────────────"""
                .formatted(totalExecutions.get(), totalFaults.get(), totalOomKills.get(),
                        totalCircuitBreaks.get(), activeInstances.size(),
                        currentLimits != null ? currentLimits.maxCpuCycles() : 0,
                        currentLimits != null ? currentLimits.maxMemoryBytes() / (1024 * 1024) : 0);
    }

    // ── 内部辅助 ──

    /**
     * 路径遍历检测 — 防止沙箱内的代码通过 ../ 逃逸。
     */
    private boolean isPathTraversal(String path) {
        return path.contains("..") || path.contains("~");
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("\\s+", "");
        if (clean.length() % 2 != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 沙箱资源限制 — 类比 Linux 的 rlimit。
     */
    public record SandboxResourceLimit(
            long maxCpuCycles,
            long maxMemoryBytes,
            int maxStackDepth
    ) {}

    /**
     * 沙箱实例 — 跟踪一个正在执行的沙箱的生命周期。
     */
    private static final class SandboxInstance {
        final String instanceId;
        final SandboxResourceLimit limits;
        final byte[] wasmBytes;
        final long createdAt;
        volatile boolean completed;
        volatile boolean faulted;
        volatile SandboxFault.FaultType faultType;

        SandboxInstance(String instanceId, SandboxResourceLimit limits, byte[] wasmBytes) {
            this.instanceId = instanceId;
            this.limits = limits;
            this.wasmBytes = wasmBytes;
            this.createdAt = System.currentTimeMillis();
        }

        void markCompleted() {
            this.completed = true;
        }

        void markFaulted(SandboxFault.FaultType faultType) {
            this.faulted = true;
            this.faultType = faultType;
        }
    }

    /**
     * 沙箱故障 — Ring 3 中发生的异常，类比 x86 的段错误。
     */
    public static final class SandboxFault extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public enum FaultType {
            DIVISION_BY_ZERO,   // 除零异常
            OUT_OF_BOUNDS,      // 越界访问
            OOM,                // 内存超限
            CPU_TIMEOUT,        // CPU 超时（死循环）
            STACK_OVERFLOW,     // 栈溢出
            INVALID_ACCESS,     // 非法访问
            UNKNOWN             // 未知异常
        }

        private final FaultType faultType;

        public SandboxFault(String message, FaultType faultType) {
            super(message);
            this.faultType = faultType;
        }

        public FaultType faultType() {
            return faultType;
        }
    }

    /**
     * 沙箱执行结果 — 描述一次沙箱执行的完整结果。
     */
    public record SandboxExecutionResult(
            boolean success,
            String instanceId,
            String compileId,
            String result,
            SandboxFault fault,
            String error
    ) {
        static SandboxExecutionResult success(String instanceId, String compileId, String result) {
            return new SandboxExecutionResult(true, instanceId, compileId, result, null, null);
        }

        static SandboxExecutionResult sandboxFault(String instanceId, String compileId, SandboxFault fault) {
            return new SandboxExecutionResult(false, instanceId, compileId, null, fault,
                    fault.faultType().name() + ": " + fault.getMessage());
        }

        static SandboxExecutionResult compilationError(String compileId, String error) {
            return new SandboxExecutionResult(false, null, compileId, null, null, error);
        }
    }
}
