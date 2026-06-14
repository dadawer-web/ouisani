package com.ouisani.aios.core.security;

import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单工具级熔断器 — 对标 oh-my-openagent 的 background-agent-circuit-breaker。
 * <p>
 * 当同一个工具被同一个 Agent 连续调用失败 N 次后，强制剥夺该 Agent
 * 对该工具的使用权，并向主控报警。防止失控的 Agent 陷入死循环
 * （比如疯狂重复调用同一个 Bash 命令且失败）。
 * <p>
 * 与 WatchdogDaemon 的关系：
 * - WatchdogDaemon 是系统级看门狗（整个系统卡死时触发重置）
 * - ToolCircuitBreaker 是工具级熔断器（单个工具连续失败时剥夺使用权）
 * <p>
 * OS 类比：相当于 Linux 的 per-cgroup 资源限制 + seccomp 系统调用过滤。
 * 不是一刀切杀进程，而是精准剥夺特定系统调用的权限。
 *
 * @see com.ouisani.aios.core.rtos.WatchdogDaemon
 */
public class ToolCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ToolCircuitBreaker.class);
    private static final ToolCircuitBreaker INSTANCE = new ToolCircuitBreaker();

    /** 触发熔断的连续失败次数阈值 */
    private static final int FAILURE_THRESHOLD = 5;

    /** 熔断冷却时间 (ms) */
    private static final long COOLDOWN_MS = 60_000; // 1 分钟

    /** 熔断状态：key = "agentId:toolName" → CircuitState */
    private final ConcurrentHashMap<String, CircuitState> circuits = new ConcurrentHashMap<>();

    public static ToolCircuitBreaker instance() {
        return INSTANCE;
    }

    private ToolCircuitBreaker() {}

    // ════════════════════════════════════════════════════════════════
    //  核心逻辑
    // ════════════════════════════════════════════════════════════════

    /**
     * 记录工具调用失败 — 连续失败 N 次后触发熔断。
     *
     * @param agentId  Agent ID
     * @param toolName 工具名
     * @param errorMsg 错误信息
     */
    public void recordFailure(String agentId, String toolName, String errorMsg) {
        String key = agentId + ":" + toolName;
        CircuitState state = circuits.computeIfAbsent(key, k -> new CircuitState());

        int failures = state.consecutiveFailures.incrementAndGet();
        log.warn("[ToolCircuitBreaker] Tool '{}' failed for agent '{}' ({}/{} consecutive failures): {}",
                toolName, agentId, failures, FAILURE_THRESHOLD, errorMsg);

        if (failures >= FAILURE_THRESHOLD && !state.tripped) {
            tripBreaker(key, agentId, toolName, failures);
        }
    }

    /**
     * 记录工具调用成功 — 重置连续失败计数。
     *
     * @param agentId  Agent ID
     * @param toolName 工具名
     */
    public void recordSuccess(String agentId, String toolName) {
        String key = agentId + ":" + toolName;
        CircuitState state = circuits.get(key);
        if (state != null) {
            state.consecutiveFailures.set(0);
            if (state.tripped) {
                state.tripped = false;
                log.info("[ToolCircuitBreaker] Circuit RESET for tool '{}' / agent '{}'", toolName, agentId);
            }
        }
    }

    /**
     * 检查工具是否被熔断（Agent 是否被剥夺了该工具的使用权）。
     *
     * @param agentId  Agent ID
     * @param toolName 工具名
     * @return true = 熔断中，禁止使用；false = 正常
     */
    public boolean isTripped(String agentId, String toolName) {
        String key = agentId + ":" + toolName;
        CircuitState state = circuits.get(key);

        if (state == null || !state.tripped) {
            return false;
        }

        // 检查冷却期是否已过
        long now = System.currentTimeMillis();
        if (now > state.trippedAt + COOLDOWN_MS) {
            // 冷却期已过 — 半开状态，允许一次尝试
            state.tripped = false;
            state.consecutiveFailures.set(0);
            log.info("[ToolCircuitBreaker] Circuit HALF-OPEN for tool '{}' / agent '{}' — allowing one attempt",
                    toolName, agentId);
            return false;
        }

        return true;
    }

    /**
     * 获取 Agent 被熔断的工具列表。
     */
    public java.util.List<String> getTrippedTools(String agentId) {
        java.util.List<String> tripped = new java.util.ArrayList<>();
        for (Map.Entry<String, CircuitState> entry : circuits.entrySet()) {
            if (entry.getKey().startsWith(agentId + ":") && entry.getValue().tripped) {
                String toolName = entry.getKey().substring(agentId.length() + 1);
                tripped.add(toolName);
            }
        }
        return tripped;
    }

    /**
     * 手动重置某个 Agent 的所有熔断器。
     */
    public void resetAll(String agentId) {
        for (Map.Entry<String, CircuitState> entry : circuits.entrySet()) {
            if (entry.getKey().startsWith(agentId + ":")) {
                entry.getValue().tripped = false;
                entry.getValue().consecutiveFailures.set(0);
            }
        }
        log.info("[ToolCircuitBreaker] All circuits reset for agent '{}'", agentId);
    }

    // ── 熔断触发 ──

    private void tripBreaker(String key, String agentId, String toolName, int failures) {
        CircuitState state = circuits.get(key);
        state.tripped = true;
        state.trippedAt = System.currentTimeMillis();

        log.error("  ╔══════════════════════════════════════════════════════════════╗");
        log.error("  ║  [TOOL CIRCUIT BREAKER] TRIPPED!                           ║");
        log.error("  ║  Agent: {}   Tool: {}              ", agentId, toolName);
        log.error("  ║  Consecutive failures: {}                                  ║", failures);
        log.error("  ║  Tool access REVOKED for {}ms                             ║", COOLDOWN_MS);
        log.error("  ╚══════════════════════════════════════════════════════════════╝");

        // 广播遥测事件
        try {
            String payload = String.format(
                    "{\"eventType\":\"TOOL_CIRCUIT_BREAKER_TRIPPED\", \"agentId\":\"%s\", \"tool\":\"%s\", \"failures\":%d, \"cooldownMs\":%d, \"timestamp\":%d}",
                    agentId, toolName, failures, COOLDOWN_MS, System.currentTimeMillis()
            );
            EventBus.instance().broadcast("sys.telemetry.events", payload);
        } catch (Exception ignore) {}

        // 触发 Hook
        HookManager.instance().trigger(HookManager.HookEvent.CIRCUIT_BREAKER_TRIGGERED, Map.of(
                "agentId", agentId,
                "toolName", toolName,
                "consecutiveFailures", failures,
                "source", "tool_circuit_breaker"
        ));

        // 遥测
        TelemetryService.instance().logEvent("tool_circuit_breaker_tripped", Map.of(
                "agentId", agentId,
                "toolName", toolName,
                "consecutiveFailures", failures
        ));
    }

    // ════════════════════════════════════════════════════════════════
    //  内部类
    // ════════════════════════════════════════════════════════════════

    private static class CircuitState {
        AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile boolean tripped = false;
        volatile long trippedAt = 0;
    }
}
