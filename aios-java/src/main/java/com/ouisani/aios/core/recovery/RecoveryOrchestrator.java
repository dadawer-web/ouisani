package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.compact.CompactService;
import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.TelemetryService;
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

    /** 最大全局恢复尝试次数（防止无限循环） */
    private static final int MAX_GLOBAL_ATTEMPTS = 20;

    /** 熔断阈值：连续失败 N 次后触发熔断 */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    /** 按错误类型注册的策略列表（有序，按优先级执行） */
    private final Map<ErrorCategory, List<RecoveryStrategy>> strategies = new ConcurrentHashMap<>();

    /** 每个 Agent 的连续失败计数器 */
    private final Map<String, FailureCounter> failureCounters = new ConcurrentHashMap<>();

    /** 每个 Agent 的冷却状态（熔断后进入冷却期） */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    private RecoveryOrchestrator() {
        registerBuiltinStrategies();
    }

    public static RecoveryOrchestrator instance() {
        return INSTANCE;
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

        log.info("[RecoveryOrchestrator] Agent={}, Error={}, Category={}, Attempt={}/{}",
                context.agentId(), context.exception().getMessage(),
                category, context.attempt(), MAX_GLOBAL_ATTEMPTS);

        // ── 检查熔断状态 ──
        Long cooldownEnd = cooldownUntil.get(context.agentId());
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            log.warn("[RecoveryOrchestrator] Agent {} is in circuit-breaker cooldown until {}",
                    context.agentId(), new Date(cooldownEnd));
            return RecoveryResult.failed("Circuit breaker active. Agent " + context.agentId()
                    + " is in cooldown. Escalating to human intervention.");
        }

        // ── 检查全局尝试上限 ──
        if (context.attempt() > MAX_GLOBAL_ATTEMPTS) {
            log.error("[RecoveryOrchestrator] Max global attempts ({}) exceeded for agent {}",
                    MAX_GLOBAL_ATTEMPTS, context.agentId());
            triggerCircuitBreaker(context.agentId());
            return RecoveryResult.failed("Max recovery attempts exceeded. Escalating to human intervention.");
        }

        // ── 获取策略链 ──
        List<RecoveryStrategy> chain = strategies.getOrDefault(category, strategies.get(ErrorCategory.UNKNOWN));

        // ── 逐个尝试恢复策略 ──
        for (RecoveryStrategy strategy : chain) {
            if (!strategy.shouldApply(context)) {
                log.debug("[RecoveryOrchestrator] Strategy {} skipped (conditions not met)", strategy.name());
                continue;
            }

            try {
                log.info("[RecoveryOrchestrator] Applying strategy: {} for agent {}", strategy.name(), context.agentId());
                RecoveryResult result = strategy.apply(context);

                if (result.success()) {
                    // 恢复成功 — 重置失败计数器
                    failureCounters.remove(context.agentId());
                    log.info("[RecoveryOrchestrator] Recovery SUCCESS via {} for agent {}",
                            strategy.name(), context.agentId());

                    // 广播恢复成功事件
                    broadcastRecoveryEvent(context.agentId(), strategy.name(), true, result.message());

                    return result;
                } else {
                    log.warn("[RecoveryOrchestrator] Strategy {} failed for agent {}: {}",
                            strategy.name(), context.agentId(), result.message());
                }
            } catch (Exception e) {
                log.warn("[RecoveryOrchestrator] Strategy {} threw exception for agent {}: {}",
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

        log.error("[RecoveryOrchestrator] CIRCUIT BREAKER triggered for agent {}. Cooldown for {}ms.",
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
        log.debug("[RecoveryOrchestrator] Registered strategy '{}' for category {}",
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

        log.info("[RecoveryOrchestrator] Built-in recovery strategies registered (11 layers)");
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
    //  内部类
    // ════════════════════════════════════════════════════════════════

    /** 失败计数器 */
    private static class FailureCounter {
        private int count = 0;
        int increment() { return ++count; }
        int count() { return count; }
    }
}
