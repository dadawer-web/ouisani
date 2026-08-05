package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.compact.CompactService;
import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.core.tool.ToolExecutionPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多层自愈编排器 — 对标 oh-my-openagent 的 11 层恢复机制。
 * <p>
 * AIOS 原有 2 层自愈（反思注入 + AutoMedic），现扩展为 11 层：
 * <ol>
 *   <li>空内容恢复 — 处理 LLM 返回空/null 响应</li>
 *   <li>JSON 解析错误恢复 — 检测并纠正格式偏差</li>
 *   <li>编辑错误恢复 — 文件编辑失败时自动重试</li>
 *   <li>工具调用错误恢复 — 工具执行异常时注入纠正提示</li>
 *   <li>上下文窗口限制恢复 — 5 种截断策略按优先级执行</li>
 *   <li>运行时回退 — API 429/503/配额耗尽时切换模型</li>
 *   <li>反思注入重试 — 捕获错误注入下一次 LLM 上下文</li>
 *   <li>任务委派重试 — 子 Agent 失败时重试委派</li>
 *   <li>Todo 继续强制器 — 未完成 todo 强制注入继续提示</li>
 *   <li>不稳定 Agent 看护 — 检测循环/震荡行为并干预</li>
 *   <li>熔断升级 — 多次失败后升级为 Human-in-the-Loop</li>
 * </ol>
 * <p>
 * OS 类比：相当于 Linux 的异常处理层级 — 从缺页中断（轻量恢复）
 * 到 Kernel Panic（熔断升级），逐级升级。
 *
 * @see RecoveryStrategy
 * @see RecoveryContext
 */
public class RecoveryOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RecoveryOrchestrator.class);
    private static final RecoveryOrchestrator INSTANCE = new RecoveryOrchestrator();

    /** 最大全局恢复尝试次数 — 从 20 降为 2，大模型前 2 次都没看懂，第 20 次也不会看懂 */
    private static final int MAX_GLOBAL_ATTEMPTS = 2;

    /** 熔断阈值：连续失败 N 次后触发熔断 */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    /** 按错误类型注册的策略列表（有序，按优先级执行） */
    private final Map<ErrorCategory, List<RecoveryStrategy>> strategies = new ConcurrentHashMap<>();

    /** 每个 Agent 的连续失败计数器 */
    private final Map<String, FailureCounter> failureCounters = new ConcurrentHashMap<>();

    /** 每个 Agent 的冷却状态（熔断后进入冷却期） */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    /** 等待人类干预的节点（nodeId → HumanInterventionRequest） */
    private final ConcurrentHashMap<String, HumanInterventionRequest> pendingHumanInterventions =
            new ConcurrentHashMap<>();

    /**
     * metadata 键：策略把待执行的副作用回调存入供编排器 reauth 通过后执行。
     * <p>
     * 值类型为 {@code Function<RecoveryContext, RecoveryResult>}。策略在 apply() 中声明
     * {@code requiresReauthorization=true} 并把真正的副作用（如 resumeNode 角色替换）封装为
     * 回调存入此键 —— 编排器在 {@link RecoveryReauthorizationGate} 通过后才取出执行，
     * 确保越权在副作用发生前被拦截（PREVENT）。
     */
    static final String META_PENDING_SIDE_EFFECT = "__pendingSideEffect";

    /**
     * 权限校验器覆盖（测试/显式注入用）；null 时从 {@link ToolExecutionPipeline} 懒加载。
     * <p>
     * 必须复用 pipeline 的同一个 {@link PermissionChecker} 实例，才能尊重用户已配置的
     * 规则/profile/mode —— 否则重校验会用空规则集误放行。
     */
    private volatile PermissionChecker permissionCheckerOverride;

    private RecoveryOrchestrator() {
        registerBuiltinStrategies();

        // ── 订阅语义崩溃事件 — 全局自愈的入口 ──
        EventBus.instance().subscribe("sys.semantic.crash", this::handleSemanticCrash);
        log.info("[RecoveryOrchestrator] 已订阅 sys.semantic.crash 事件通道");
    }

    public static RecoveryOrchestrator instance() {
        return INSTANCE;
    }

    /**
     * 注入权限校验器（主要用于测试隔离）。
     * <p>
     * 生产路径下无需调用 —— {@link #resolvePermissionChecker()} 会自动从
     * {@link ToolExecutionPipeline} 懒加载用户已配置规则的同款实例。
     * 设为 null 清除覆盖，恢复懒加载行为。
     */
    public void setPermissionChecker(PermissionChecker checker) {
        this.permissionCheckerOverride = checker;
    }

    /**
     * 解析当前可用的权限校验器。
     * <p>
     * 优先用显式注入的覆盖实例；否则懒加载 {@link ToolExecutionPipeline} 的单例实例；
     * pipeline 尚未初始化时返回 null（恢复守卫见 null 即放行，维持 legacy 行为）。
     */
    private PermissionChecker resolvePermissionChecker() {
        if (permissionCheckerOverride != null) return permissionCheckerOverride;
        try {
            return ToolExecutionPipeline.instance().getPermissionChecker();
        } catch (Throwable t) {
            // pipeline 未就绪或初始化中 —— 恢复路径不阻塞，legacy 放行
            log.debug("[RecoveryOrchestrator] ToolExecutionPipeline 尚未就绪，跳过权限重校验: {}", t.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  错误分类
    // ════════════════════════════════════════════════════════════════

    /**
     * 错误类别 — 对标 omo 的 error-classifier。
     * 不同类别的错误触发不同的恢复策略链。
     */
    public enum ErrorCategory {
        /** LLM 返回空/null 内容 */
        EMPTY_RESPONSE,
        /** JSON/工具调用格式解析错误 */
        PARSE_ERROR,
        /** 文件编辑操作失败（Hashline 不匹配等） */
        EDIT_ERROR,
        /** 工具执行异常（Bash 命令失败、文件不存在等） */
        TOOL_ERROR,
        /** 上下文窗口超限（Token 过多） */
        CONTEXT_WINDOW_EXCEEDED,
        /** API 限流/服务不可用（429/503/配额耗尽） */
        RATE_LIMITED,
        /** 节点验证失败（代码未通过测试） */
        VERIFICATION_FAILED,
        /** Agent 行为异常（循环/震荡/无限重试） */
        UNSTABLE_BEHAVIOR,
        /** 未知错误 — 兜底类别 */
        UNKNOWN
    }

    // ════════════════════════════════════════════════════════════════
    //  核心编排逻辑
    // ════════════════════════════════════════════════════════════════

    /**
     * 对错误进行分类。
     */
    public ErrorCategory classify(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String msgLower = msg.toLowerCase();

        // 上下文窗口超限
        if (msgLower.contains("context_length_exceeded")
                || msgLower.contains("max_tokens")
                || msgLower.contains("token limit")
                || msgLower.contains("too many tokens")) {
            return ErrorCategory.CONTEXT_WINDOW_EXCEEDED;
        }

        // API 限流
        if (msgLower.contains("429") || msgLower.contains("rate_limit")
                || msgLower.contains("quota") || msgLower.contains("too many requests")
                || msgLower.contains("503") || msgLower.contains("service unavailable")
                || msgLower.contains("overloaded")) {
            return ErrorCategory.RATE_LIMITED;
        }

        // 空响应
        if (msgLower.contains("empty response") || msgLower.contains("null content")
                || msgLower.contains("no output") || msg.trim().isEmpty()) {
            return ErrorCategory.EMPTY_RESPONSE;
        }

        // 解析错误
        if (msgLower.contains("json") || msgLower.contains("parse")
                || msgLower.contains("unexpected token") || msgLower.contains("invalid format")
                || msgLower.contains("xml") || msgLower.contains("tool_call")) {
            return ErrorCategory.PARSE_ERROR;
        }

        // 编辑错误
        if (msgLower.contains("hashline") || msgLower.contains("hash mismatch")
                || msgLower.contains("edit failed") || msgLower.contains("line not found")
                || msgLower.contains("ast rewrite error")) {
            return ErrorCategory.EDIT_ERROR;
        }

        // 工具错误
        if (msgLower.contains("tool") || msgLower.contains("bash")
                || msgLower.contains("command failed") || msgLower.contains("permission denied")
                || msgLower.contains("file not found") || msgLower.contains("no such file")) {
            return ErrorCategory.TOOL_ERROR;
        }

        // 验证失败
        if (msgLower.contains("not verified") || msgLower.contains("verification failed")
                || msgLower.contains("test failed") || msgLower.contains("assertion")) {
            return ErrorCategory.VERIFICATION_FAILED;
        }

        return ErrorCategory.UNKNOWN;
    }

    /**
     * 执行恢复编排 — 按优先级尝试所有匹配策略。
     * <p>
     * 核心流程：
     * <pre>
     *   1. 分类错误 → ErrorCategory
     *   2. 检查熔断状态 → 如果在冷却期内，直接返回失败
     *   3. 按优先级遍历策略链 → 逐个尝试恢复
     *   4. 如果恢复成功 → 重置失败计数器
     *   5. 如果全部失败 → 递增失败计数器，检查是否触发熔断
     *   6. 广播遥测事件
     * </pre>
     *
     * @param context 恢复上下文（包含错误信息、Agent ID、历史等）
     * @return 恢复结果
     */
    public RecoveryResult orchestrate(RecoveryContext context) {
        ErrorCategory category = classify(context.exception());
        context = context.withCategory(category);

        log.info("[RecoveryOrchestrator] Agent={}, 错误={}, 类别={}, 尝试={}/{}",
                context.agentId(), context.exception().getMessage(),
                category, context.attempt(), MAX_GLOBAL_ATTEMPTS);

        // ── 检查熔断状态 ──
        Long cooldownEnd = cooldownUntil.get(context.agentId());
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            log.warn("[RecoveryOrchestrator] Agent {} 处于熔断冷却期，直至 {}",
                    context.agentId(), new Date(cooldownEnd));
            return RecoveryResult.failed("Circuit breaker active. Agent " + context.agentId()
                    + " is in cooldown. Escalating to human intervention.");
        }

        // ── 检查全局尝试上限 ──
        if (context.attempt() > MAX_GLOBAL_ATTEMPTS) {
            log.error("[RecoveryOrchestrator] Agent {} 的全局最大尝试次数 ({}) 已超限",
                    MAX_GLOBAL_ATTEMPTS, context.agentId());
            triggerCircuitBreaker(context.agentId());
            return RecoveryResult.failed("Max recovery attempts exceeded. Escalating to human intervention.");
        }

        // ── 获取策略链 ──
        List<RecoveryStrategy> chain = strategies.getOrDefault(category, strategies.get(ErrorCategory.UNKNOWN));

        // ── 逐个尝试恢复策略 ──
        for (RecoveryStrategy strategy : chain) {
            if (!strategy.shouldApply(context)) {
                log.debug("[RecoveryOrchestrator] 策略 {} 已跳过（条件不满足）", strategy.name());
                continue;
            }

            // ── 恢复重试权限守卫 ──
            // 防御"借恢复通道绕过权限"攻击：恶意 app 故意制造看似正常的失败，借
            // ReflectionInjection/UnstableAgentBabysitter 等策略在重试时注入载荷，
            // 绕过原始权限检查。每次重试前用 PermissionChecker 对原始失败的工具调用
            // 重新背书 —— "恢复=安全"不再假设，而是被权限子系统重新校验。
            // 仅当上下文携带原始工具调用（originalToolInput != null）时触发；
            // 非工具调用失败（如节点崩溃）originalToolInput 为 null，守卫自动放行。
            if (context.originalToolInput() != null) {
                RecoveryPermissionGuard.GuardResult guard = RecoveryPermissionGuard.instance()
                        .recheck(resolvePermissionChecker(),
                                context.originalTool(), context.originalToolInput(),
                                context.originalToolContext());
                if (!guard.allowed()) {
                    log.warn("[RecoveryOrchestrator] 恢复重试被权限守卫拒绝: Agent={}, 策略={}, 原因={}",
                            context.agentId(), strategy.name(), guard.reason());
                    broadcastRecoveryEvent(context.agentId(), strategy.name(), false,
                            "Permission guard denied recovery retry: " + guard.reason());
                    // 权限重校验失败 = 恢复通道被用作攻击面，直接升级人类介入
                    return RecoveryResult.failed("Recovery retry denied by permission guard: "
                            + guard.reason());
                }
                log.debug("[RecoveryOrchestrator] 恢复重试权限守卫放行: Agent={}, 策略={}, 原因={}",
                        context.agentId(), strategy.name(), guard.reason());
            }

            try {
                log.info("[RecoveryOrchestrator] 正在应用策略: {}，Agent {}", strategy.name(), context.agentId());
                RecoveryResult result = strategy.apply(context);

                if (result.success()) {
                    // ── Phase 4 defense #4 强化：副作用结果强制重授权（不受 opt-in 开关控制）──
                    // requiresReauthorization=true 的结果（如拓扑突变角色替换）必须在副作用执行前
                    // 过 RecoveryReauthorizationGate。编排器层统一拦截，新增策略只要声明
                    // requiresReauthorization=true 即自动被保护。reauth 通过后才执行策略声明
                    // 的 pendingSideEffect（PREVENT 越权，而非事后检测）。
                    if (result.requiresReauthorization()) {
                        RecoveryReauthorizationGate.ReauthResult reauth =
                                RecoveryReauthorizationGate.check(result, context, resolvePermissionChecker());
                        if (!reauth.allowed()) {
                            log.warn("[RecoveryOrchestrator] 恢复副作用被重授权关卡拒绝: Agent={}, 策略={}, 原因={}",
                                    context.agentId(), strategy.name(), reauth.reason());
                            broadcastRecoveryEvent(context.agentId(), strategy.name(), false,
                                    "Recovery side-effect denied by reauthorization gate: " + reauth.reason());
                            return RecoveryResult.failed("Recovery side-effect denied by reauthorization gate: "
                                    + reauth.reason());
                        }
                        log.info("[RecoveryOrchestrator] 恢复副作用重授权通过: Agent={}, 策略={}, category={}",
                                context.agentId(), strategy.name(), reauth.category());

                        // reauth 通过 → 执行策略声明的待处理副作用（延后至此，确保越权在副作用前被拦）
                        Object sideEffect = context.metadata().get(META_PENDING_SIDE_EFFECT);
                        if (sideEffect instanceof java.util.function.Function<?, ?> f) {
                            @SuppressWarnings("unchecked")
                            java.util.function.Function<RecoveryContext, RecoveryResult> se =
                                    (java.util.function.Function<RecoveryContext, RecoveryResult>) f;
                            RecoveryResult seResult = se.apply(context);
                            if (!seResult.success()) {
                                log.warn("[RecoveryOrchestrator] 恢复副作用执行失败: Agent={}, 原因={}",
                                        context.agentId(), seResult.message());
                                return seResult;
                            }
                            log.info("[RecoveryOrchestrator] 恢复副作用执行成功: Agent={}", context.agentId());
                            result = seResult;
                        }
                    }

                    // 恢复成功 — 重置失败计数器
                    failureCounters.remove(context.agentId());
                    log.info("[RecoveryOrchestrator] 通过 {} 恢复成功，Agent {}",
                            strategy.name(), context.agentId());

                    // 广播恢复成功事件
                    broadcastRecoveryEvent(context.agentId(), strategy.name(), true, result.message());

                    return result;
                } else {
                    log.warn("[RecoveryOrchestrator] 策略 {} 对 Agent {} 失败: {}",
                            strategy.name(), context.agentId(), result.message());
                }
            } catch (Exception e) {
                log.warn("[RecoveryOrchestrator] 策略 {} 对 Agent {} 抛出异常: {}",
                        strategy.name(), context.agentId(), e.getMessage());
            }
        }

        // ── 所有策略都失败 — 递增失败计数 ──
        FailureCounter counter = failureCounters.computeIfAbsent(context.agentId(), k -> new FailureCounter());
        int consecutiveFailures = counter.increment();

        // 广播恢复失败事件
        broadcastRecoveryEvent(context.agentId(), "ALL_STRATEGIES_EXHAUSTED", false,
                "All recovery strategies failed. Consecutive failures: " + consecutiveFailures);

        // ── 检查熔断阈值 ──
        if (consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD) {
            triggerCircuitBreaker(context.agentId());
            return RecoveryResult.failed("Circuit breaker triggered after " + consecutiveFailures
                    + " consecutive failures. Escalating to human intervention.");
        }

        return RecoveryResult.failed("All recovery strategies failed for category " + category
                + ". Consecutive failures: " + consecutiveFailures);
    }

    /**
     * 触发熔断 — Agent 进入冷却期，升级为 Human-in-the-Loop。
     */
    private void triggerCircuitBreaker(String agentId) {
        // 冷却 5 分钟
        long cooldownMs = 5 * 60 * 1000;
        cooldownUntil.put(agentId, System.currentTimeMillis() + cooldownMs);

        log.error("[RecoveryOrchestrator] Agent {} 的熔断器已触发。冷却 {}ms。",
                agentId, cooldownMs);

        // 广播内核恐慌事件 → AutoMedic 和前端大屏都会响应
        try {
            String payload = String.format(
                    "{\"eventType\":\"CIRCUIT_BREAKER_TRIGGERED\", \"agentId\":\"%s\", \"consecutiveFailures\":%d, \"timestamp\":%d}",
                    agentId, CIRCUIT_BREAKER_THRESHOLD, System.currentTimeMillis()
            );
            EventBus.instance().broadcast("sys.kernel.panic", payload);
        } catch (Exception ignore) {}

        // 触发 Hook
        HookManager.instance().trigger(HookManager.HookEvent.STOP_FAILURE, Map.of(
                "agentId", agentId,
                "reason", "circuit_breaker",
                "consecutiveFailures", CIRCUIT_BREAKER_THRESHOLD
        ));

        // 遥测
        TelemetryService.instance().logEvent("circuit_breaker_triggered", Map.of(
                "agentId", agentId,
                "consecutiveFailures", CIRCUIT_BREAKER_THRESHOLD
        ));
    }

    /**
     * 重置 Agent 的失败计数器（在任务成功后调用）。
     */
    public void resetFailures(String agentId) {
        failureCounters.remove(agentId);
        cooldownUntil.remove(agentId);
    }

    /**
     * 获取 Agent 的连续失败次数。
     */
    public int getConsecutiveFailures(String agentId) {
        FailureCounter counter = failureCounters.get(agentId);
        return counter != null ? counter.count() : 0;
    }

    // ════════════════════════════════════════════════════════════════
    //  策略注册
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册恢复策略。
     */
    public void registerStrategy(ErrorCategory category, RecoveryStrategy strategy) {
        strategies.computeIfAbsent(category, k -> new ArrayList<>()).add(strategy);
        log.debug("[RecoveryOrchestrator] 已注册策略 '{}' 到类别 {}",
                strategy.name(), category);
    }

    /**
     * 注册内置策略 — 对标 omo 的 11 层恢复机制。
     */
    private void registerBuiltinStrategies() {
        // ── 1. 空内容恢复 ──
        registerStrategy(ErrorCategory.EMPTY_RESPONSE, new EmptyResponseRecovery());
        // ── 2. JSON 解析错误恢复 ──
        registerStrategy(ErrorCategory.PARSE_ERROR, new JsonParseErrorRecovery());
        // ── 3. 编辑错误恢复 ──
        registerStrategy(ErrorCategory.EDIT_ERROR, new EditErrorRecovery());
        // ── 4. 工具调用错误恢复 ──
        registerStrategy(ErrorCategory.TOOL_ERROR, new ToolErrorRecovery());
        // ── 5. 上下文窗口限制恢复（5 种子策略） ──
        registerStrategy(ErrorCategory.CONTEXT_WINDOW_EXCEEDED, new ContextWindowRecovery());
        // ── 6. 运行时回退 ──
        registerStrategy(ErrorCategory.RATE_LIMITED, new RuntimeFallbackRecovery());
        // ── 7. 反思注入重试 ──
        registerStrategy(ErrorCategory.VERIFICATION_FAILED, new ReflectionInjectionRecovery());
        // ── 8. 任务委派重试 ──
        registerStrategy(ErrorCategory.VERIFICATION_FAILED, new TaskDelegationRetryRecovery());
        // ── 9. Todo 继续强制器 ──
        registerStrategy(ErrorCategory.UNSTABLE_BEHAVIOR, new TodoContinuationRecovery());
        // ── 10. 不稳定 Agent 看护 ──
        registerStrategy(ErrorCategory.UNSTABLE_BEHAVIOR, new UnstableAgentBabysitterRecovery());
        // ── 11. 兜底策略 ──
        registerStrategy(ErrorCategory.UNKNOWN, new FallbackRecovery());
        // ── 12. 代码手术策略（CODE_ERROR → LLM 修复 + 热重启） ──
        registerStrategy(ErrorCategory.TOOL_ERROR, new CodeSurgeryStrategy());
        registerStrategy(ErrorCategory.EDIT_ERROR, new CodeSurgeryStrategy());
        registerStrategy(ErrorCategory.PARSE_ERROR, new CodeSurgeryStrategy());
        // ── 13. 拓扑突变策略（CAPABILITY_MISMATCH → 替代节点） ──
        registerStrategy(ErrorCategory.VERIFICATION_FAILED, new TopologyMutationStrategy());
        // ── 14. 资源补充策略（RESOURCE_EXHAUSTED → 增加限额 + 反思注入） ──
        registerStrategy(ErrorCategory.CONTEXT_WINDOW_EXCEEDED, new ResourceRefillStrategy());
        registerStrategy(ErrorCategory.RATE_LIMITED, new ResourceRefillStrategy());

        log.info("[RecoveryOrchestrator] 内置恢复策略已注册（14 层）");
    }

    // ── 遥测广播 ──

    private void broadcastRecoveryEvent(String agentId, String strategyName, boolean success, String message) {
        try {
            String payload = String.format(
                    "{\"eventType\":\"RECOVERY_%s\", \"agentId\":\"%s\", \"strategy\":\"%s\", \"message\":\"%s\", \"timestamp\":%d}",
                    success ? "SUCCESS" : "FAILED",
                    agentId,
                    strategyName,
                    message.replace("\"", "'").replace("\n", " "),
                    System.currentTimeMillis()
            );
            EventBus.instance().broadcast("sys.telemetry.events", payload);
        } catch (Exception ignore) {}
    }

    // ════════════════════════════════════════════════════════════════
    //  语义崩溃事件处理 — 全局自愈的异步调度中心
    // ════════════════════════════════════════════════════════════════

    /**
     * 人类干预请求 — 当 AutoMedic 无法修复时，挂起等待人类介入。
     *
     * @param nodeId    挂起的节点 ID
     * @param workflowId 工作流 ID
     * @param dumpPath  Core Dump 路径
     * @param diagnosis AutoMedic 的诊断信息
     * @param timestamp 请求时间
     */
    public record HumanInterventionRequest(
            String nodeId,
            String workflowId,
            String dumpPath,
            String diagnosis,
            long timestamp
    ) {}

    /**
     * 处理语义崩溃事件 — 异步调度 AutoMedic 进行修复。
     * <p>
     * 这是全局自愈的入口。任何组件（不只是 WorkflowEngine）只要
     * 向 sys.semantic.crash 抛出事件，Orchestrator 就会派 Medic 去修。
     * <p>
     * 流程：
     * 1. 解析崩溃事件 JSON
     * 2. 在虚拟线程中异步执行修复（不阻塞 EventBus 线程）
     * 3. 调用 AutoMedic 进行诊断
     * 4. 根据诊断结果路由到对应的 RecoveryStrategy
     * 5. 如果修复成功 → resumeNode
     * 6. 如果修复失败 → 触发 Human-in-the-Loop
     */
    public void handleSemanticCrash(String crashJson) {
        log.info("[RecoveryOrchestrator] 收到语义崩溃事件: {}",
                crashJson.substring(0, Math.min(crashJson.length(), 200)));

        // 解析事件
        String nodeId = extractField(crashJson, "nodeId");
        String workflowId = extractField(crashJson, "workflowId");
        String dumpPath = extractField(crashJson, "dumpPath");
        String error = extractField(crashJson, "error");
        String role = extractField(crashJson, "role");

        if (nodeId == null || workflowId == null) {
            log.error("[RecoveryOrchestrator] 崩溃事件缺少 nodeId 或 workflowId，忽略");
            return;
        }

        // ── 异步执行修复（虚拟线程，不阻塞 EventBus） ──
        Thread.startVirtualThread(() -> {
            try {
                performCrashRecovery(nodeId, workflowId, dumpPath, error, role);
            } catch (Exception e) {
                log.error("[RecoveryOrchestrator] 崩溃恢复异常: nodeId={}, error={}",
                        nodeId, e.getMessage());
            }
        });
    }

    /**
     * 执行崩溃恢复 — 在虚拟线程中运行。
     */
    private void performCrashRecovery(String nodeId, String workflowId,
                                        String dumpPath, String error, String role) {
        log.info("[RecoveryOrchestrator] 开始崩溃恢复: nodeId={}, workflowId={}", nodeId, workflowId);

        // 1. 构造 RecoveryContext
        Exception crashException = new RuntimeException(error != null ? error : "Unknown crash");
        RecoveryContext context = new RecoveryContext(nodeId, crashException, 1, "")
                .withMetadata("dumpPath", dumpPath)
                .withMetadata("workflowId", workflowId)
                .withMetadata("role", role);

        // 2. 分类错误
        ErrorCategory category = classify(crashException);
        context = context.withCategory(category);

        // 3. 尝试使用策略链恢复
        RecoveryResult result = orchestrate(context);

        // ── Phase 4 defense #4：恢复动作重新授权关卡 ──
        // 注意：副作用结果的强制 reauth + 副作用执行已在 orchestrate() 主路径内统一处理
        // （见 orchestrate() 的 requiresReauthorization 分支）。此处保留的检查是 belt-and-suspenders：
        // 对 orchestrate() 未覆盖的路径（如未来绕过 orchestrate 直接调 performCrashRecovery 的场景）
        // 兜底。requiresReauthorization=true 的结果在 orchestrate() 内副作用已执行并替换为普通结果，
        // 故此处通常 skip；若 orchestrate() 返回的仍是 requiresReauthorization=true（副作用未执行），
        // 此处拒绝并升级人类介入。
        if (result.success() && result.requiresReauthorization()) {
            RecoveryReauthorizationGate.ReauthResult reauth =
                    RecoveryReauthorizationGate.check(result, context, resolvePermissionChecker());
            if (!reauth.allowed()) {
                log.warn("[RecoveryOrchestrator] 恢复副作用被重新授权关卡拒绝: nodeId={}, reason={}",
                        nodeId, reauth.reason());
                broadcastRecoveryEvent(nodeId, "reauth_gate", false,
                        "Recovery side-effect denied by reauthorization gate: " + reauth.reason());
                // 关卡拒绝 = 恢复通道被用作提权攻击面，升级人类介入，不让 resumeNode 生效
                result = RecoveryResult.failed(
                        "Recovery side-effect denied by reauthorization gate: " + reauth.reason());
            }
        }

        if (result.success()) {
            log.info("[RecoveryOrchestrator] 崩溃恢复成功: nodeId={}, message={}", nodeId, result.message());

            // ── 关键：恢复成功后必须 resumeNode，否则节点永远 SUSPENDED ──
            // RecoveryOrchestrator 是"分诊台"，它只负责诊断和开药方，
            // 真正让病人"复活"的是 WorkflowEngine.resumeNode()
            boolean nodeResumed = false;
            try {
                java.util.Map<String, Object> resumeContext = new java.util.HashMap<>();
                if (result.modifiedPrompt() != null) {
                    resumeContext.put("_reflection_hint", result.modifiedPrompt());
                }
                nodeResumed = com.ouisani.aios.user.apps.omnifactory.WorkflowEngine.instance()
                        .resumeNode(nodeId, workflowId, resumeContext);
                if (nodeResumed) {
                    log.info("[RecoveryOrchestrator] 节点 '{}' 已通过 resumeNode 复活", nodeId);
                } else {
                    log.warn("[RecoveryOrchestrator] resumeNode 返回 false，节点 '{}' 可能已不处于 SUSPENDED 状态", nodeId);
                }
            } catch (Exception e) {
                log.error("[RecoveryOrchestrator] resumeNode 失败: nodeId={}, error={}", nodeId, e.getMessage());
            }

            if (nodeResumed) {
                // 节点真正复活 → 广播恢复成功
                broadcastRecoveryEvent(nodeId, "crash_recovery", true, result.message());
            } else {
                // ── 兜底：resumeNode 失败，强制将节点标记为 FAILED，避免工作流死锁 ──
                // 策略修复成功（如模型降级）不等于节点真正复活。
                // 如果 resumeNode 因竞态返回 false，残留的 SUSPENDED 节点会导致工作流永远等不到它完成。
                // 此时必须强制降级为 FAILED，让下游被 SKIPPED 的节点继续推进，避免死锁。
                log.warn("[RecoveryOrchestrator] 策略恢复成功但 resumeNode 失败，强制将节点 '{}' 标记为 FAILED 以避免死锁", nodeId);
                boolean forced = com.ouisani.aios.user.apps.omnifactory.WorkflowEngine.instance()
                        .forceFailNode(nodeId);
                if (forced) {
                    broadcastRecoveryEvent(nodeId, "crash_recovery", false,
                            "Strategy succeeded but resumeNode failed — node force-failed to prevent deadlock: " + result.message());
                } else {
                    // 节点和工作流都已注销，无法强制失败，只能记录
                    log.error("[RecoveryOrchestrator] 节点 '{}' 既无法 resume 也无法 forceFail，工作流可能已注销", nodeId);
                    broadcastRecoveryEvent(nodeId, "crash_recovery", false,
                            "Node and workflow already unregistered — orphaned SUSPENDED state: " + result.message());
                }
            }
        } else {
            // 4. 所有策略都失败 → 触发 Human-in-the-Loop
            log.warn("[RecoveryOrchestrator] 崩溃恢复失败，触发 Human-in-the-Loop: nodeId={}", nodeId);
            triggerHumanIntervention(nodeId, workflowId, dumpPath,
                    "All recovery strategies failed: " + result.message());
        }
    }

    /**
     * 触发人类干预 — 挂起节点，等待人类在前端 UI 修改后点击 Resume。
     * <p>
     * OS 类比：Linux 的 Kernel Panic → kdump 生成转储 →
     * 管理员收到告警 → 手动分析并修复 → 重启系统。
     */
    private void triggerHumanIntervention(String nodeId, String workflowId,
                                            String dumpPath, String diagnosis) {
        // 1. 记录干预请求
        HumanInterventionRequest request = new HumanInterventionRequest(
                nodeId, workflowId, dumpPath, diagnosis, System.currentTimeMillis());
        pendingHumanInterventions.put(nodeId, request);

        // 2. 广播人类干预事件 → 前端 UI 会收到并显示告警
        String alertPayload = String.format(
                "{\"eventType\":\"HUMAN_INTERVENTION_REQUIRED\","
                        + "\"nodeId\":\"%s\",\"workflowId\":\"%s\","
                        + "\"dumpPath\":\"%s\",\"diagnosis\":\"%s\","
                        + "\"timestamp\":%d}",
                nodeId.replace("\"", "\\\""),
                workflowId.replace("\"", "\\\""),
                dumpPath != null ? dumpPath.replace("\"", "\\\"") : "",
                diagnosis.replace("\"", "'").replace("\n", " ").substring(0, Math.min(diagnosis.length(), 500)),
                System.currentTimeMillis()
        );
        EventBus.instance().broadcast("sys.human_intervention_required", alertPayload);

        // 3. 触发 Hook
        try {
            HookManager.instance().trigger(
                    HookManager.HookEvent.STOP_FAILURE,
                    java.util.Map.of(
                            "nodeId", nodeId,
                            "workflowId", workflowId,
                            "reason", "human_intervention",
                            "diagnosis", diagnosis
                    ));
        } catch (Exception ignored) {}

        log.warn("[RecoveryOrchestrator] 人类干预已请求: nodeId={}, 等待前端 Resume", nodeId);
    }

    /**
     * 人类干预恢复 — 前端 UI 调用此方法恢复挂起的节点。
     * <p>
     * 当人类在前端修改了 Prompt 或上下文后，点击 Resume，
     * 前端调用此方法，Orchestrator 会唤醒挂起的节点。
     *
     * @param nodeId        挂起的节点 ID
     * @param humanGuidance 人类提供的指导（修改后的 Prompt 或上下文）
     * @return true=恢复成功, false=恢复失败
     */
    public boolean resumeFromHumanIntervention(String nodeId, String humanGuidance) {
        HumanInterventionRequest request = pendingHumanInterventions.remove(nodeId);
        if (request == null) {
            log.warn("[RecoveryOrchestrator] 找不到节点 '{}' 的人类干预请求", nodeId);
            return false;
        }

        log.info("[RecoveryOrchestrator] 人类干预恢复: nodeId={}, guidanceLen={}",
                nodeId, humanGuidance != null ? humanGuidance.length() : 0);

        try {
            // 构造 MedicalReport（人类指导作为反思提示）
            com.ouisani.aios.user.apps.omnifactory.AutoMedicAgent.MedicalReport report =
                    new com.ouisani.aios.user.apps.omnifactory.AutoMedicAgent.MedicalReport(
                    com.ouisani.aios.user.apps.omnifactory.AutoMedicAgent.Outcome.HEALED,
                    "Human intervention: " + request.diagnosis(),
                    null, null,
                    humanGuidance,
                    null
            );

            boolean resumed = com.ouisani.aios.user.apps.omnifactory.WorkflowEngine.getInstance()
                    .resumeNode(nodeId, report, request.workflowId());

            if (resumed) {
                log.info("[RecoveryOrchestrator] 人类干预恢复成功: nodeId={}", nodeId);
                broadcastRecoveryEvent(nodeId, "human_intervention", true, "Node resumed by human");
            } else {
                log.warn("[RecoveryOrchestrator] 人类干预恢复失败: nodeId={}", nodeId);
                // 重新放入等待队列
                pendingHumanInterventions.put(nodeId, request);
            }

            return resumed;
        } catch (Exception e) {
            log.error("[RecoveryOrchestrator] 人类干预恢复异常: nodeId={}, error={}", nodeId, e.getMessage());
            return false;
        }
    }

    /**
     * 获取所有等待人类干预的节点。
     */
    public java.util.Map<String, HumanInterventionRequest> getPendingHumanInterventions() {
        return java.util.Collections.unmodifiableMap(pendingHumanInterventions);
    }

    // ── JSON 字段提取 ──

    private static String extractField(String json, String key) {
        if (json == null || key == null) return null;
        java.util.regex.Pattern stringPattern = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        java.util.regex.Matcher m = stringPattern.matcher(json);
        if (m.find()) return m.group(1);
        // 尝试数字值
        java.util.regex.Pattern rawPattern = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*([^,}\\s]+)");
        java.util.regex.Matcher rawMatcher = rawPattern.matcher(json);
        if (rawMatcher.find()) return rawMatcher.group(1).trim();
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部类
    // ════════════════════════════════════════════════════════════════

    /** 失败计数器 */
    private static class FailureCounter {
        private int count = 0;
        int increment() { return ++count; }
        int count() { return count; }
    }
}
