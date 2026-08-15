package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.hook.HookManager.HookEvent;
import com.ouisani.aios.core.hook.HookManager.HookResult;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.security.ToolCircuitBreaker;
import com.ouisani.aios.core.telemetry.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 工具执行流水线 — 对标 Claude Code 的 toolExecution.ts + toolHooks.ts + toolOrchestration.ts。
 * <p>
 * 提供完整的工具执行生命周期：
 * beforeHook → permissionCheck → execute → afterHook → telemetry
 * <p>
 * 各阶段职责：
 * - beforeHook：前置拦截/修改，可跳过或中止执行
 * - permissionCheck：权限检查，对标 hasPermissionsToUseTool
 * - execute：调用工具的 call 方法
 * - afterHook：后置处理，可修改输出或记录日志
 * - telemetry：遥测统计，记录工具使用次数和耗时
 * <p>
 * OS 类比：相当于 Linux 的系统调用入口 — 从用户态陷入内核态后，
 * 先经过审计子系统（beforeHook），再检查 SELinux 策略（permissionCheck），
 * 然后执行系统调用（execute），最后写审计日志（afterHook + telemetry）。
 */
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);
    private static final ToolExecutionPipeline INSTANCE = new ToolExecutionPipeline();

    /** Hook 决策枚举 */
    public enum HookDecision {
        /** 继续执行 */
        PROCEED,
        /** 跳过当前阶段（对 beforeHook 表示跳过工具执行，对 afterHook 表示跳过后续处理） */
        SKIP,
        /** 中止整个流水线 */
        ABORT
    }

    /** 前置 Hook 注册表：toolName → hook 函数 */
    private final Map<String, BiFunction<String, ToolContext, HookDecision>> beforeHooks = new ConcurrentHashMap<>();

    /** 后置 Hook 注册表：toolName → hook 函数 */
    private final Map<String, BiFunction<String, ToolOutput, HookDecision>> afterHooks = new ConcurrentHashMap<>();

    /** 通用前置 Hook 列表（匹配所有工具），按注册顺序执行 */
    private final ConcurrentHashMap<String, BiFunction<String, ToolContext, HookDecision>> globalBeforeHooks = new ConcurrentHashMap<>();

    /** 通用后置 Hook 列表（匹配所有工具），按注册顺序执行 */
    private final ConcurrentHashMap<String, BiFunction<String, ToolOutput, HookDecision>> globalAfterHooks = new ConcurrentHashMap<>();

    private final ToolRegistry toolRegistry;
    private final HookManager hookManager;
    private final PermissionChecker permissionChecker;
    private final TelemetryService telemetryService;

    private ToolExecutionPipeline() {
        this.toolRegistry = ToolRegistry.instance();
        this.hookManager = HookManager.instance();
        this.permissionChecker = new PermissionChecker();
        this.telemetryService = TelemetryService.instance();
    }

    public static ToolExecutionPipeline instance() {
        return INSTANCE;
    }

    /**
     * 执行工具的完整生命周期。
     * <p>
     * 流程：beforeHook → permissionCheck → execute → afterHook → telemetry
     *
     * @param toolName  工具名称
     * @param inputJson 输入参数的 JSON 字符串
     * @param context   工具执行上下文
     * @return 工具执行结果
     */
    public ToolOutput execute(String toolName, String inputJson, ToolContext context) {
        long startTime = System.currentTimeMillis();

        log.debug("[ToolPipeline] 开始执行工具: {}", toolName);

        // ── 阶段 1：前置 Hook ──
        HookDecision beforeDecision = runBeforeHooks(toolName, context);
        if (beforeDecision == HookDecision.ABORT) {
            log.info("[ToolPipeline] 前置 Hook 中止执行: {}", toolName);
            return ToolOutput.fail("Tool execution aborted by beforeHook: " + toolName);
        }
        if (beforeDecision == HookDecision.SKIP) {
            log.info("[ToolPipeline] 前置 Hook 跳过执行: {}", toolName);
            return ToolOutput.ok("Tool execution skipped by beforeHook: " + toolName);
        }

        // 触发 HookManager 的 PRE_TOOL_USE 事件
        HookResult hookResult = hookManager.trigger(HookEvent.PRE_TOOL_USE, Map.of(
                "toolName", toolName,
                "inputJson", inputJson,
                "agentId", context.agentId()
        ));
        if (!hookResult.proceed()) {
            log.info("[ToolPipeline] HookManager PRE_TOOL_USE 拒绝执行: {} — {}", toolName, hookResult.message());
            return ToolOutput.fail("Tool execution denied by hook: " + hookResult.message());
        }

        // ── 阶段 2：工具熔断检查 ──
        if (ToolCircuitBreaker.instance().isTripped(context.agentId(), toolName)) {
            log.warn("[ToolPipeline] 工具熔断中，拒绝执行: {} / agent={}", toolName, context.agentId());
            return ToolOutput.fail("Tool '" + toolName + "' is circuit-broken for agent '"
                    + context.agentId() + "'. The tool has failed too many times consecutively. "
                    + "Please try a different approach or wait for the cooldown period.");
        }

        // ── 阶段 3：权限检查 ──
        // 由于 ToolRegistry 返回 Tool<? extends ToolInput>，通配符捕获导致泛型不兼容，
        // 此处使用原始类型 + suppressWarnings 处理，因为流水线层无法预知具体工具的输入类型
        @SuppressWarnings("unchecked")
        Tool<ToolInput> tool = (Tool<ToolInput>) (Tool<?>) toolRegistry.<ToolInput>get(toolName).orElse(null);
        if (tool == null) {
            log.warn("[ToolPipeline] 工具未注册: {}", toolName);
            return ToolOutput.fail("Tool not found: " + toolName);
        }

        // 从 JSON 解析输入（简化处理：使用原始 JSON 包装）
        ToolInput input = new JsonToolInput(inputJson);

        @SuppressWarnings("unchecked")
        PermissionDecision permDecision = permissionChecker.checkPermission(
                (Tool) tool, (ToolInput) input, context);
        if (permDecision.isDenied()) {
            log.info("[ToolPipeline] 权限拒绝: {} — {}", toolName, permDecision.message());
            permissionChecker.recordDenial();
            return ToolOutput.fail("Permission denied: " + permDecision.message());
        }
        if (permDecision.needsPrompt()) {
            // 需要用户确认的场景 — 在自动模式下视为拒绝
            log.info("[ToolPipeline] 权限需要确认: {} — {}", toolName, permDecision.message());
            return ToolOutput.fail("Permission requires user confirmation: " + permDecision.message());
        }

        // ── 阶段 4：执行工具 ──
        ToolOutput output;
        try {
            output = tool.call(input, context);
            log.debug("[ToolPipeline] 工具执行完成: {} — success={}", toolName, output.success());

            // 记录工具执行结果到熔断器
            if (output.success()) {
                ToolCircuitBreaker.instance().recordSuccess(context.agentId(), toolName);
            } else {
                ToolCircuitBreaker.instance().recordFailure(context.agentId(), toolName, output.toText());
            }
        } catch (Exception e) {
            log.error("[ToolPipeline] 工具执行异常: {} — {}", toolName, e.getMessage(), e);
            output = ToolOutput.fail("Tool execution error: " + e.getMessage());

            // 记录异常到熔断器
            ToolCircuitBreaker.instance().recordFailure(context.agentId(), toolName, e.getMessage());

            // 触发失败后置事件
            hookManager.trigger(HookEvent.POST_TOOL_USE_FAILURE, Map.of(
                    "toolName", toolName,
                    "error", e.getMessage(),
                    "agentId", context.agentId()
            ));
        }

        // ── 阶段 4：后置 Hook ──
        HookDecision afterDecision = runAfterHooks(toolName, output);
        if (afterDecision == HookDecision.ABORT) {
            log.info("[ToolPipeline] 后置 Hook 中止: {}", toolName);
            // 后置 ABORT 仍然返回原始输出，但记录中止状态
        }

        // 触发 HookManager 的 POST_TOOL_USE 事件
        hookManager.trigger(HookEvent.POST_TOOL_USE, Map.of(
                "toolName", toolName,
                "success", output.success(),
                "agentId", context.agentId()
        ));

        // ── 阶段 5：遥测 ──
        long durationMs = System.currentTimeMillis() - startTime;
        telemetryService.recordToolUsage(toolName, durationMs);
        telemetryService.logEvent("tool_execution", Map.of(
                "toolName", toolName,
                "success", output.success(),
                "durationMs", durationMs,
                "agentId", context.agentId()
        ));

        log.debug("[ToolPipeline] 工具执行流水线完成: {} — 耗时 {}ms", toolName, durationMs);

        return output;
    }

    /**
     * 注册工具特定的前置 Hook。
     * <p>
     * 前置 Hook 在工具执行前调用，可决定是否继续执行。
     *
     * @param toolName 工具名称
     * @param hook     Hook 函数，接收工具名称和上下文，返回决策
     */
    public void registerBeforeHook(String toolName, BiFunction<String, ToolContext, HookDecision> hook) {
        beforeHooks.put(toolName, hook);
        log.debug("[ToolPipeline] 注册前置 Hook: {}", toolName);
    }

    /**
     * 注册工具特定的后置 Hook。
     * <p>
     * 后置 Hook 在工具执行后调用，可决定是否继续后续处理。
     *
     * @param toolName 工具名称
     * @param hook     Hook 函数，接收工具名称和输出，返回决策
     */
    public void registerAfterHook(String toolName, BiFunction<String, ToolOutput, HookDecision> hook) {
        afterHooks.put(toolName, hook);
        log.debug("[ToolPipeline] 注册后置 Hook: {}", toolName);
    }

    /**
     * 注册全局前置 Hook（匹配所有工具）。
     *
     * @param id   Hook 标识（用于去重）
     * @param hook Hook 函数
     */
    public void registerGlobalBeforeHook(String id, BiFunction<String, ToolContext, HookDecision> hook) {
        globalBeforeHooks.put(id, hook);
        log.debug("[ToolPipeline] 注册全局前置 Hook: {}", id);
    }

    /**
     * 注册全局后置 Hook（匹配所有工具）。
     *
     * @param id   Hook 标识（用于去重）
     * @param hook Hook 函数
     */
    public void registerGlobalAfterHook(String id, BiFunction<String, ToolOutput, HookDecision> hook) {
        globalAfterHooks.put(id, hook);
        log.debug("[ToolPipeline] 注册全局后置 Hook: {}", id);
    }

    /**
     * 注销工具特定的前置 Hook。
     */
    public void unregisterBeforeHook(String toolName) {
        beforeHooks.remove(toolName);
    }

    /**
     * 注销工具特定的后置 Hook。
     */
    public void unregisterAfterHook(String toolName) {
        afterHooks.remove(toolName);
    }

    /**
     * 注销全局前置 Hook。
     */
    public void unregisterGlobalBeforeHook(String id) {
        globalBeforeHooks.remove(id);
    }

    /**
     * 注销全局后置 Hook。
     */
    public void unregisterGlobalAfterHook(String id) {
        globalAfterHooks.remove(id);
    }

    /**
     * 获取权限检查器实例（用于配置权限模式和规则）。
     */
    public PermissionChecker getPermissionChecker() {
        return permissionChecker;
    }

    // ── 内部方法 ──

    /**
     * 执行前置 Hook 链：先执行全局 Hook，再执行工具特定 Hook。
     * 任何一个 Hook 返回 ABORT 则立即中止；返回 SKIP 则跳过执行。
     */
    private HookDecision runBeforeHooks(String toolName, ToolContext context) {
        // 全局前置 Hook
        for (var entry : globalBeforeHooks.entrySet()) {
            try {
                HookDecision decision = entry.getValue().apply(toolName, context);
                log.debug("[ToolPipeline] 全局前置 Hook {} 返回: {}", entry.getKey(), decision);
                if (decision == HookDecision.ABORT) return HookDecision.ABORT;
                if (decision == HookDecision.SKIP) return HookDecision.SKIP;
            } catch (Exception e) {
                log.warn("[ToolPipeline] 全局前置 Hook {} 执行异常: {}", entry.getKey(), e.getMessage());
            }
        }

        // 工具特定前置 Hook
        BiFunction<String, ToolContext, HookDecision> specificHook = beforeHooks.get(toolName);
        if (specificHook != null) {
            try {
                HookDecision decision = specificHook.apply(toolName, context);
                log.debug("[ToolPipeline] 前置 Hook {} 返回: {}", toolName, decision);
                return decision;
            } catch (Exception e) {
                log.warn("[ToolPipeline] 前置 Hook {} 执行异常: {}", toolName, e.getMessage());
            }
        }

        return HookDecision.PROCEED;
    }

    /**
     * 执行后置 Hook 链：先执行工具特定 Hook，再执行全局 Hook。
     * 任何一个 Hook 返回 ABORT 则立即中止链。
     */
    private HookDecision runAfterHooks(String toolName, ToolOutput output) {
        // 工具特定后置 Hook
        BiFunction<String, ToolOutput, HookDecision> specificHook = afterHooks.get(toolName);
        if (specificHook != null) {
            try {
                HookDecision decision = specificHook.apply(toolName, output);
                log.debug("[ToolPipeline] 后置 Hook {} 返回: {}", toolName, decision);
                if (decision == HookDecision.ABORT) return HookDecision.ABORT;
            } catch (Exception e) {
                log.warn("[ToolPipeline] 后置 Hook {} 执行异常: {}", toolName, e.getMessage());
            }
        }

        // 全局后置 Hook
        for (var entry : globalAfterHooks.entrySet()) {
            try {
                HookDecision decision = entry.getValue().apply(toolName, output);
                log.debug("[ToolPipeline] 全局后置 Hook {} 返回: {}", entry.getKey(), decision);
                if (decision == HookDecision.ABORT) return HookDecision.ABORT;
            } catch (Exception e) {
                log.warn("[ToolPipeline] 全局后置 Hook {} 执行异常: {}", entry.getKey(), e.getMessage());
            }
        }

        return HookDecision.PROCEED;
    }

    /**
     * JSON 包装的 ToolInput 实现 — 用于将原始 JSON 字符串作为工具输入传递。
     * <p>
     * 在流水线内部使用，因为外部调用者传入的是 JSON 字符串，
     * 而权限检查和工具执行需要 ToolInput 接口。
     */
    private record JsonToolInput(String json) implements ToolInput {
        @Override
        public String toJson() {
            return json;
        }
    }
}
