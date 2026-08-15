package com.ouisani.aios.core.plan;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VersionedPlan 守护进程 — 镜像 jcode {@code swarm.rs} 的运行时行为。
 * <p>
 * 单例 Holder 模式（与 {@link com.ouisani.aios.core.overnight.OvernightRunner} 一致），
 * 暴露对外的心跳/checkpoint/控制 API，跑陈旧性扫描守护线程，版本变更时通过 EventBus 广播。
 * <p>
 * <b>常量</b>（镜像 swarm.rs:81-83，可被 env 覆盖）：
 * <ul>
 *   <li>{@code HEARTBEAT_SECS=10} — 心跳间隔</li>
 *   <li>{@code STALE_AFTER_SECS=45} — 陈旧阈值</li>
 *   <li>{@code SWEEP_INTERVAL_SECS=5} — 扫描间隔</li>
 *   <li>{@code RECOMPILE_AFTER_SECS=90} — 重编译阈值（2×stale）</li>
 * </ul>
 * <p>
 * <b>广播事件类型</b>：
 * <ul>
 *   <li>{@code "plan_version"} — 版本变更，payload 含 swarmId/version/summary/newlyReadyIds/reason</li>
 *   <li>{@code "topology_recompile_needed"} — 陈旧超阈值，payload 为 stale task id 列表。
 *       <b>核心→用户态单向事件</b>，核心不感知 TopologyCompiler（守依赖边界）。</li>
 * </ul>
 */
public final class VersionedPlanStore {

    private static final Logger log = LoggerFactory.getLogger(VersionedPlanStore.class);

    private static final class Holder {
        static final VersionedPlanStore INSTANCE = new VersionedPlanStore();
    }

    public static VersionedPlanStore instance() {
        return Holder.INSTANCE;
    }

    // ── 常量（env 可覆盖，package-private 便于测试覆盖）──
    static long staleAfterMs = envLong("AIOS_PLAN_STALE_AFTER_SECS", 45) * 1000L;
    static long sweepIntervalSecs = envLong("AIOS_PLAN_SWEEP_INTERVAL_SECS", 5);
    static long recompileAfterMs = envLong("AIOS_PLAN_RECOMPILE_AFTER_SECS", 90) * 1000L;

    private final ConcurrentHashMap<String, VersionedPlan> plans = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweepDaemon =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("aios-plan-sweep").daemon(true).unstarted(r));
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Gson gson = new Gson();

    private VersionedPlanStore() {}

    // ════════════════════════════════════════════════════════════════
    //  生命周期 — 由 TaskScheduler.start()/shutdown() 调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动守护进程 — 加载持久化计划 + 启动陈旧性扫描线程。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        loadAllPersisted();
        sweepDaemon.scheduleAtFixedRate(this::sweepTick,
                sweepIntervalSecs, sweepIntervalSecs, TimeUnit.SECONDS);
        log.info("[VersionedPlanStore] started — sweepInterval={}s staleAfter={}s recompileAfter={}s",
                sweepIntervalSecs, staleAfterMs / 1000, recompileAfterMs / 1000);
    }

    /**
     * 停止守护进程。
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        sweepDaemon.shutdownNow();
        log.info("[VersionedPlanStore] stopped");
    }

    private void loadAllPersisted() {
        try {
            List<String> swarmIds = VersionedPlanPersistence.listSwarmIds();
            for (String swarmId : swarmIds) {
                VersionedPlan plan = VersionedPlanPersistence.load(swarmId);
                if (plan != null) {
                    plans.put(swarmId, plan);
                    log.info("[VersionedPlanStore] restored plan: swarm={} v={}", swarmId, plan.version());
                }
            }
        } catch (Exception e) {
            log.warn("[VersionedPlanStore] loadAllPersisted failed: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  对外 API — 委托 VersionedPlan，捕获版本变化后统一 afterMutation
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取或创建 swarm 的 VersionedPlan。
     */
    public VersionedPlan getOrCreatePlan(String swarmId) {
        return plans.computeIfAbsent(swarmId, k -> new VersionedPlan());
    }

    public VersionedPlan getPlan(String swarmId) {
        return plans.get(swarmId);
    }

    /**
     * 记录心跳 — 不触发 version+1（除非触发 revive）。
     */
    public void recordHeartbeat(String swarmId, String taskId, String sessionId, String detail) {
        VersionedPlan plan = plans.get(swarmId);
        if (plan == null) return;

        long beforeVersion = plan.version();
        List<PlanItem> beforeSnapshot = plan.snapshotItems();

        plan.recordHeartbeat(taskId, sessionId, detail, System.currentTimeMillis());

        if (plan.version() > beforeVersion) {
            // revive 触发了版本变更
            afterMutation(swarmId, "heartbeat_revive", beforeSnapshot);
        } else {
            // 纯心跳 — 仅持久化，不广播
            VersionedPlanPersistence.save(swarmId, plan);
        }
    }

    /**
     * 记录检查点 — 不触发 version+1。
     */
    public void recordCheckpoint(String swarmId, String taskId, String summary) {
        VersionedPlan plan = plans.get(swarmId);
        if (plan == null) return;

        plan.recordCheckpoint(taskId, summary, System.currentTimeMillis());
        VersionedPlanPersistence.save(swarmId, plan);
    }

    public boolean startTask(String swarmId, String taskId) {
        return mutateAndBroadcast(swarmId, "start_task", plan -> plan.startTask(taskId));
    }

    public boolean completeTask(String swarmId, String taskId) {
        return mutateAndBroadcast(swarmId, "complete_task", plan -> plan.completeTask(taskId));
    }

    public boolean failTask(String swarmId, String taskId, String reason) {
        return mutateAndBroadcast(swarmId, "fail_task", plan -> plan.failTask(taskId, reason));
    }

    public boolean assignTask(String swarmId, String taskId, String assignee, String sessionId) {
        return mutateAndBroadcast(swarmId, "assign_task",
                plan -> plan.assignTask(taskId, assignee, sessionId));
    }

    public void replacePlan(String swarmId, List<PlanItem> items, Set<String> participants) {
        mutateAndBroadcast(swarmId, "replace_plan", plan -> {
            plan.replaceItems(items, participants);
            return true;
        });
    }

    @FunctionalInterface
    private interface PlanMutation {
        boolean apply(VersionedPlan plan);
    }

    private boolean mutateAndBroadcast(String swarmId, String reason, PlanMutation mutation) {
        VersionedPlan plan = plans.get(swarmId);
        if (plan == null) {
            log.warn("[VersionedPlanStore] plan not found: swarm={}", swarmId);
            return false;
        }

        List<PlanItem> beforeSnapshot = plan.snapshotItems();
        boolean changed = mutation.apply(plan);

        if (changed) {
            afterMutation(swarmId, reason, beforeSnapshot);
        }
        return changed;
    }

    // ════════════════════════════════════════════════════════════════
    //  版本变更后统一后处理 — 持久化 + 广播 + trace
    // ════════════════════════════════════════════════════════════════

    private void afterMutation(String swarmId, String reason, List<PlanItem> beforeSnapshot) {
        VersionedPlan plan = plans.get(swarmId);
        if (plan == null) return;

        List<String> newlyReady = plan.newlyReadyItemIds(beforeSnapshot);
        PlanGraphSummary summary = plan.summarizeGraph();

        VersionedPlanPersistence.save(swarmId, plan);
        broadcastPlanVersion(swarmId, plan.version(), summary, newlyReady, reason);

        SemanticEtw.getInstance().logEvent("PLAN", "MUTATE",
                "swarm=" + swarmId + " v=" + plan.version() + " reason=" + reason
                        + " ready=" + summary.readyIds().size()
                        + " newlyReady=" + newlyReady.size());
    }

    private void broadcastPlanVersion(String swarmId, long version, PlanGraphSummary summary,
                                       List<String> newlyReadyIds, String reason) {
        JsonObject payload = new JsonObject();
        payload.addProperty("swarmId", swarmId);
        payload.addProperty("version", version);
        payload.addProperty("reason", reason);
        payload.add("summary", gson.toJsonTree(summary));
        payload.add("newlyReadyIds", gson.toJsonTree(newlyReadyIds));

        EventBus.instance().broadcast("plan_version", payload.toString());
        log.debug("[VersionedPlanStore] broadcast plan_version: swarm={} v={} newlyReady={}",
                swarmId, version, newlyReadyIds.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  陈旧性扫描 — 镜像 jcode swarm.rs:228-293 refresh_swarm_task_staleness
    // ════════════════════════════════════════════════════════════════

    /**
     * 扫描 tick — 每 SWEEP_INTERVAL_SECS 执行一次。
     * <p>
     * 遍历所有活跃任务：
     * <ul>
     *   <li>{@code now - lastActivity ≥ STALE_AFTER_MS} → flipToStale（version+1）</li>
     *   <li>{@code stale_for ≥ RECOMPILE_AFTER_MS} → 收集到 recompileNeeded 列表</li>
     * </ul>
     * 末尾按 swarmId 聚合广播，避免广播风暴。
     */
    private void sweepTick() {
        try {
            sweepOnce(System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[VersionedPlanStore] sweepTick error: {}", e.getMessage());
        }
    }

    /**
     * 执行一次陈旧性扫描 — package-private 便于测试注入受控时间戳。
     * <p>
     * 遍历所有活跃任务：
     * <ul>
     *   <li>{@code now - lastActivity ≥ staleAfterMs} → flipToStale（version+1）</li>
     *   <li>{@code stale_for ≥ recompileAfterMs} → 收集到 recompileNeeded 列表</li>
     * </ul>
     * 末尾按 swarmId 聚合广播，避免广播风暴。
     */
    void sweepOnce(long now) {
        List<String> recompileNeeded = new ArrayList<>();

        for (var entry : plans.entrySet()) {
            String swarmId = entry.getKey();
            VersionedPlan plan = entry.getValue();

            List<PlanItem> beforeSnapshot = plan.snapshotItems();
            boolean changed = false;

            for (PlanItem item : plan.itemsView()) {
                if (!PlanItem.isActive(item.status())) continue;

                SwarmTaskProgress p = plan.progress(item.id());
                Long lastTs = p != null ? p.lastActivityTimestamp() : null;

                // running 且超时 → flipToStale
                if ("running".equals(item.status())) {
                    boolean isStale = (lastTs == null) || (now - lastTs >= staleAfterMs);
                    if (isStale) {
                        plan.flipToStale(item.id(), now);
                        changed = true;
                    }
                }

                // running_stale 且超重编译阈值 → 收集
                if ("running_stale".equals(item.status()) && p != null && p.staleSinceUnixMs() != null) {
                    if (now - p.staleSinceUnixMs() >= recompileAfterMs) {
                        recompileNeeded.add(item.id());
                    }
                }
            }

            // 同 swarmId 多变更合并一次广播
            if (changed) {
                afterMutation(swarmId, "task_staleness_changed", beforeSnapshot);
            }
        }

        // 陈旧超阈值 — 广播重编译请求（核心→用户态单向事件）
        if (!recompileNeeded.isEmpty()) {
            broadcastRecompileNeeded(recompileNeeded);
        }
    }

    /**
     * 广播重编译请求 — 核心不感知 TopologyCompiler，仅广播事件。
     * <p>
     * 用户态订阅者（如 omnifactory 侧）通过 {@code EventBus.subscribe("topology_recompile_needed", ...)}
     * 消费此事件，调用 TopologyCompiler 重新编译拓扑。
     */
    private void broadcastRecompileNeeded(List<String> staleTaskIds) {
        EventBus.instance().broadcast("topology_recompile_needed", gson.toJson(staleTaskIds));
        log.warn("[VersionedPlanStore] broadcast topology_recompile_needed: {} stale tasks: {}",
                staleTaskIds.size(), staleTaskIds);
        SemanticEtw.getInstance().logEvent("PLAN", "RECOMPILE_NEEDED",
                "staleTaskCount=" + staleTaskIds.size() + " taskIds=" + staleTaskIds);
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助
    // ════════════════════════════════════════════════════════════════

    private static long envLong(String key, long defaultValue) {
        String val = System.getenv(key);
        if (val != null && !val.isEmpty()) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
