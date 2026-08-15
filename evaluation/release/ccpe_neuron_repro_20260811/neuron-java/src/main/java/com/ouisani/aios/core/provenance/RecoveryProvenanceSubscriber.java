package com.ouisani.aios.core.provenance;

import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 恢复决策 provenance 订阅者 — 把论文1 orchestrator 已广播的恢复事件转写进新论文的审计链。
 * <p>
 * <b>与论文1的边界（关键）</b>：本组件订阅论文1 orchestrator <b>已经在广播</b>的 EventBus 通道，
 * 把事件转写成 {@link RecoveryProvenanceRecord} 写入审计链。<b>不修改</b>
 * {@link com.ouisani.aios.core.recovery.RecoveryOrchestrator} 的任何方法 —— 它们继续按论文1的逻辑
 * 广播，本订阅者只是旁路监听。这是"在 orchestrator 里插桩"的 no-touch 等价方案。
 * <p>
 * <b>捕获的论文1事件</b>（P2 扩展为完整链书挡：crash → 决策 → 终态）：
 * <ul>
 *   <li>{@code sys.semantic.crash} 的 {@code SEMANTIC_CRASH} —— 恢复链<b>起点</b>：节点崩溃到达。
 *       WorkflowEngine 广播，payload 含 nodeId/workflowId/error。注意 orchestrator 用 nodeId 作
 *       agentId（见 {@code performCrashRecovery} 的 {@code new RecoveryContext(nodeId, ...)}），故
 *       本记录以 nodeId 为 agentId，与下游 RECOVERY_* 记录同 key，链可拼接。</li>
 *   <li>{@code sys.telemetry.events} 的 {@code RECOVERY_SUCCESS} / {@code RECOVERY_FAILED}
 *       —— 某层恢复策略执行结果（含 agentId/strategy/message）。strategy="crash_recovery" 的
 *       RECOVERY_SUCCESS 即对应 resumeNode 成功（链的 resumeNode 节点）。</li>
 *   <li>{@code sys.kernel.panic} 的 {@code CIRCUIT_BREAKER_TRIGGERED}
 *       —— 固定阈值熔断触发（含 agentId/consecutiveFailures），链的终态之一。</li>
 *   <li>{@code sys.human_intervention_required} 的 {@code HUMAN_INTERVENTION_REQUIRED}
 *       —— 升级人类介入（含 nodeId/diagnosis），链的终态之二。以 nodeId 为 agentId。</li>
 * </ul>
 * <p>
 * <b>链粒度限制（已接受）</b>：classify（ErrorCategory）、strategy_triggered（apply 前）、resumeNode
 * 作为<b>独立</b>事件均未被 orchestrator 广播 —— 旁路拿不到。其中 resumeNode 成功间接由
 * strategy="crash_recovery" 的 RECOVERY_SUCCESS 表达。要拿全粒度需直接改 orchestrator（破坏论文1
 * 字节级稳定），本方案按 P2 决策不取。
 * <p>
 * <b>关于 traceId</b>：EventBus 的 handler 在独立虚拟线程异步执行（见
 * {@code EventBus.broadcast} 的 {@code Thread.startVirtualThread}），不继承广播方的
 * {@link com.ouisani.aios.core.ipc.TraceContext} ThreadLocal，且论文1 orchestrator 的广播
 * payload 也不携带 traceId —— 故经 EventBus 转写的记录 traceId=null。这是 best-effort 审计的
 * 已知限制；新论文决策点（guard/gate）在同步上下文直接调 {@link RecoveryProvenanceRecorder#onRecoveryDecision}
 * 时 traceId 可富化。
 * <p>
 * <b>生命周期</b>：{@link #start()} 幂等订阅；{@link #stop()} 取消订阅。生产路径应在内核
 * 启动时 start，关闭时 stop。新论文的实验/测试按需 start。
 *
 * @see RecoveryProvenanceRecorder
 * @see RecoveryProvenanceRecord
 */
public final class RecoveryProvenanceSubscriber {

    private static final Logger log = LoggerFactory.getLogger(RecoveryProvenanceSubscriber.class);

    /** 论文1 WorkflowEngine 广播语义崩溃的通道（恢复链起点）。 */
    static final String CHANNEL_SEMANTIC_CRASH = "sys.semantic.crash";
    /** 论文1 orchestrator 广播恢复事件的通道（broadcastRecoveryEvent）。 */
    static final String CHANNEL_TELEMETRY = "sys.telemetry.events";
    /** 论文1 orchestrator 广播熔断事件的通道（triggerCircuitBreaker）。 */
    static final String CHANNEL_KERNEL_PANIC = "sys.kernel.panic";
    /** 论文1 orchestrator 广播人类介入事件的通道（triggerHumanIntervention，恢复链终态）。 */
    static final String CHANNEL_HUMAN_INTERVENTION = "sys.human_intervention_required";

    private static final RecoveryProvenanceSubscriber INSTANCE = new RecoveryProvenanceSubscriber();

    private final Consumer<String> semanticCrashHandler = this::handleSemanticCrashEvent;
    private final Consumer<String> telemetryHandler = this::handleTelemetryEvent;
    private final Consumer<String> kernelPanicHandler = this::handleKernelPanicEvent;
    private final Consumer<String> humanInterventionHandler = this::handleHumanInterventionEvent;
    private volatile boolean started = false;

    private RecoveryProvenanceSubscriber() {
    }

    public static RecoveryProvenanceSubscriber instance() {
        return INSTANCE;
    }

    /**
     * 幂等启动 —— 订阅四个恢复链事件通道。重复调用安全（仅首次生效）。
     */
    public synchronized void start() {
        if (started) return;
        EventBus.instance().subscribe(CHANNEL_SEMANTIC_CRASH, semanticCrashHandler);
        EventBus.instance().subscribe(CHANNEL_TELEMETRY, telemetryHandler);
        EventBus.instance().subscribe(CHANNEL_KERNEL_PANIC, kernelPanicHandler);
        EventBus.instance().subscribe(CHANNEL_HUMAN_INTERVENTION, humanInterventionHandler);
        started = true;
        log.info("[RecoveryProvenanceSubscriber] 已启动，订阅 {} + {} + {} + {}",
                CHANNEL_SEMANTIC_CRASH, CHANNEL_TELEMETRY, CHANNEL_KERNEL_PANIC, CHANNEL_HUMAN_INTERVENTION);
    }

    /**
     * 取消订阅。重复调用安全。
     */
    public synchronized void stop() {
        if (!started) return;
        EventBus.instance().unsubscribe(CHANNEL_SEMANTIC_CRASH, semanticCrashHandler);
        EventBus.instance().unsubscribe(CHANNEL_TELEMETRY, telemetryHandler);
        EventBus.instance().unsubscribe(CHANNEL_KERNEL_PANIC, kernelPanicHandler);
        EventBus.instance().unsubscribe(CHANNEL_HUMAN_INTERVENTION, humanInterventionHandler);
        started = false;
        log.info("[RecoveryProvenanceSubscriber] 已停止");
    }

    boolean isStarted() {
        return started;
    }

    // ════════════════════════════════════════════════════════════════
    //  事件转写 — 公开供单元测试直接喂 payload（绕开 EventBus）
    //════════════════════════════════════════════════════════════════

    /**
     * 处理 {@code sys.semantic.crash} 通道的崩溃事件 payload —— 恢复链起点。
     * <p>
     * payload 形如（论文1 WorkflowEngine 手写 JSON）：
     * {@code {"eventType":"SEMANTIC_CRASH","nodeId":"...","workflowId":"...","dumpPath":"...","error":"...","durationMs":N,"role":"...","blueprintId":"...","timestamp":...}}
     * <p>
     * <b>agentId 取 nodeId</b>：orchestrator 在 {@code performCrashRecovery} 用 nodeId 作 RecoveryContext
     * 的 agentId，下游所有 RECOVERY_* 记录同 key，故本记录也用 nodeId 以保证链可拼接。
     *
     * @param payload JSON payload 字符串
     */
    public void handleSemanticCrashEvent(String payload) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            String eventType = optString(o, "eventType");
            if (!"SEMANTIC_CRASH".equals(eventType)) {
                return; // 非崩溃事件不记入恢复审计链
            }
            String nodeId = optString(o, "nodeId");
            String workflowId = optString(o, "workflowId");
            String role = optString(o, "role");
            String error = optString(o, "error");
            String reason = "workflowId=" + workflowId + "; role=" + role + "; error=" + truncate(error, 200);
            // crash 到达 = 恢复链起点，本身不是"成功放行重试"，success=false
            RecoveryProvenanceRecorder.instance().onRecoveryDecision(
                    nodeId, "SEMANTIC_CRASH", "CRASH_ARRIVED", false, reason, null);
        } catch (Exception e) {
            log.debug("[RecoveryProvenanceSubscriber] semantic crash payload 解析失败: {}", e.getMessage());
        }
    }

    /**
     * 处理 {@code sys.telemetry.events} 通道的恢复事件 payload。
     * <p>
     * payload 形如（论文1 broadcastRecoveryEvent 手写 JSON）：
     * {@code {"eventType":"RECOVERY_SUCCESS","agentId":"...","strategy":"...","message":"...","timestamp":...}}
     *
     * @param payload JSON payload 字符串
     */
    public void handleTelemetryEvent(String payload) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            String eventType = optString(o, "eventType");
            String agentId = optString(o, "agentId");
            String strategy = optString(o, "strategy");
            String message = optString(o, "message");

            boolean success;
            String category;
            if ("RECOVERY_SUCCESS".equals(eventType)) {
                success = true;
                category = "RECOVERY_SUCCESS";
            } else if ("RECOVERY_FAILED".equals(eventType)) {
                success = false;
                category = "RECOVERY_FAILED";
            } else {
                // 非恢复事件（telemetry 通道还有别的）—— 忽略，不污染恢复审计链
                return;
            }
            RecoveryProvenanceRecorder.instance().onRecoveryDecision(
                    agentId, strategy, category, success, message, null);
        } catch (Exception e) {
            log.debug("[RecoveryProvenanceSubscriber] telemetry payload 解析失败: {}", e.getMessage());
        }
    }

    /**
     * 处理 {@code sys.kernel.panic} 通道的熔断事件 payload。
     * <p>
     * payload 形如（论文1 triggerCircuitBreaker 手写 JSON）：
     * {@code {"eventType":"CIRCUIT_BREAKER_TRIGGERED","agentId":"...","consecutiveFailures":5,"timestamp":...}}
     *
     * @param payload JSON payload 字符串
     */
    public void handleKernelPanicEvent(String payload) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            String eventType = optString(o, "eventType");
            if (!"CIRCUIT_BREAKER_TRIGGERED".equals(eventType)) {
                return; // 非熔断的 panic 事件不记入恢复审计链
            }
            String agentId = optString(o, "agentId");
            String failures = o.has("consecutiveFailures") && o.get("consecutiveFailures").isJsonPrimitive()
                    ? String.valueOf(o.get("consecutiveFailures").getAsInt()) : "?";
            RecoveryProvenanceRecorder.instance().onRecoveryDecision(
                    agentId, "CIRCUIT_BREAKER", "CIRCUIT_BREAKER_TRIGGERED",
                    false, "consecutiveFailures=" + failures + " (fixed threshold reached)", null);
        } catch (Exception e) {
            log.debug("[RecoveryProvenanceSubscriber] kernel panic payload 解析失败: {}", e.getMessage());
        }
    }

    /**
     * 处理 {@code sys.human_intervention_required} 通道的人类介入事件 payload —— 恢复链终态。
     * <p>
     * payload 形如（论文1 triggerHumanIntervention 手写 JSON）：
     * {@code {"eventType":"HUMAN_INTERVENTION_REQUIRED","nodeId":"...","workflowId":"...","dumpPath":"...","diagnosis":"...","timestamp":...}}
     * <p>
     * <b>agentId 取 nodeId</b>：triggerHumanIntervention 以 nodeId 为参数，与崩溃链同 key。
     *
     * @param payload JSON payload 字符串
     */
    public void handleHumanInterventionEvent(String payload) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            String eventType = optString(o, "eventType");
            if (!"HUMAN_INTERVENTION_REQUIRED".equals(eventType)) {
                return;
            }
            String nodeId = optString(o, "nodeId");
            String diagnosis = optString(o, "diagnosis");
            RecoveryProvenanceRecorder.instance().onRecoveryDecision(
                    nodeId, "HUMAN_INTERVENTION", "HUMAN_INTERVENTION_REQUIRED",
                    false, "diagnosis=" + truncate(diagnosis, 300), null);
        } catch (Exception e) {
            log.debug("[RecoveryProvenanceSubscriber] human intervention payload 解析失败: {}", e.getMessage());
        }
    }

    private static String optString(com.google.gson.JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /** 截断长文本，避免 reason 字段撑爆审计记录（与 sanitizer 截断理念一致）。 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
