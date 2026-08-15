package com.ouisani.aios.core.action;

import com.ouisani.aios.core.snapshot.DiffExpectation;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshot;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager;
import com.ouisani.aios.core.snapshot.StateDiff;
import com.ouisani.aios.core.syscall.ResultState;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行动治理编排器 — 把"可信、可见、可控、可逆"落地到 syscall 执行链。
 * <p>
 * 复用 {@link EnvironmentSnapshotManager} 的 capture/restore/diff 三件套，不新写快照机制。
 * 编排逻辑：
 * <ol>
 *   <li>{@link #beforeAction}：按 {@link RiskLevel} 决定是否打"动作前快照"，REVERSIBLE/DESTRUCTIVE 入 undo 栈</li>
 *   <li>{@link #afterAction}：打"动作后快照"，diff(before, after, expectation)；REVERSIBLE 违反期望则自动 restore(before)</li>
 *   <li>{@link #undo}：REVERSIBLE 动作可回滚到动作前快照；DESTRUCTIVE 拒绝（仅审计）</li>
 *   <li>{@link #gcOlderThan}：清理过期/超深快照，调用 EnvironmentSnapshotManager.deleteSnapshot
 *       （HibernationSectionCapturer 等会级联到 HibernationManager）</li>
 * </ol>
 * <p>
 * <b>降级语义</b>：若 capturer 注册表为空，capture 返回空 sections 快照，restore 为 no-op，
 * diff 平凡满足期望——治理退化为审计 only，不会误触发自动回滚。
 * <p>
 * <b>线程安全</b>：per-agentId undo 栈用 synchronized 块保护（单 Agent 内动作通常串行）；
 * requestId 全局索引用 ConcurrentHashMap。
 */
public final class ActionGovernor {

    private static final Logger log = LoggerFactory.getLogger(ActionGovernor.class);

    /** 默认 undo 栈深度上限（每 Agent）。超出则 GC 最旧条目。 */
    private static final int DEFAULT_MAX_STACK_DEPTH = 32;

    private static final class Holder {
        static final ActionGovernor INSTANCE = new ActionGovernor(
                EnvironmentSnapshotManager.instance(), SyscallDispatcher.getInstance());
    }

    public static ActionGovernor getInstance() {
        return Holder.INSTANCE;
    }

    private final EnvironmentSnapshotManager snapshotManager;
    private final SyscallDispatcher dispatcher;

    /** agentId → undo 栈（栈顶为最近动作）。 */
    private final Map<String, Deque<ActionRecord>> undoStacks = new ConcurrentHashMap<>();
    /** requestId → ActionRecord 全局索引（用于 undo(requestId) 直查）。 */
    private final Map<String, ActionRecord> requestIndex = new ConcurrentHashMap<>();

    private volatile int maxStackDepth = DEFAULT_MAX_STACK_DEPTH;

    /** 生产构造器：单例用。 */
    private ActionGovernor(EnvironmentSnapshotManager snapshotManager, SyscallDispatcher dispatcher) {
        this.snapshotManager = snapshotManager;
        this.dispatcher = dispatcher;
    }

    /** 测试用：注入 collaborators。 */
    ActionGovernor(EnvironmentSnapshotManager snapshotManager, SyscallDispatcher dispatcher, boolean unused) {
        this.snapshotManager = snapshotManager;
        this.dispatcher = dispatcher;
    }

    public void setMaxStackDepth(int depth) {
        if (depth < 1) {
            throw new IllegalArgumentException("maxStackDepth must be >= 1");
        }
        this.maxStackDepth = depth;
    }

    // ════════════════════════════════════════════════════════════════
    //  before / after
    // ════════════════════════════════════════════════════════════════

    /**
     * 动作执行前：按风险分级决定是否打快照、是否入栈。
     *
     * @param agentId  发起 Agent
     * @param request  syscall 请求
     * @return 动作上下文，传给 {@link #afterAction}
     */
    public ActionContext beforeAction(String agentId, SyscallRequest request) {
        return beforeAction(agentId, request, null);
    }

    /**
     * 动作执行前，带风险等级覆盖。override 为 null 时用 {@link RiskClassifier} 静态判定。
     */
    public ActionContext beforeAction(String agentId, SyscallRequest request, RiskLevel override) {
        RiskLevel risk = override != null ? override
                : RiskClassifier.classify(request.namespace(), request.action());
        String requestId = "act-" + UUID.randomUUID();
        long now = System.currentTimeMillis();

        if (risk == RiskLevel.SAFE) {
            log.debug("[Governor] SAFE 动作，跳过快照: agent={}, action={}", agentId, request.fullAction());
            return new ActionContext(requestId, agentId, risk, null, null, request);
        }

        // REVERSIBLE / DESTRUCTIVE：打动作前快照
        // scopeId 含 requestId 便于追溯（哪个动作打的快照）；
        // EnvironmentSnapshotManager 内部用 AtomicLong counter 保证 snapshotId 唯一，
        // 不依赖 scopeId 区分（P1 补强 ② 修复了原 env-{ms}-{scopeHash} 的碰撞缺陷）
        EnvironmentSnapshot before = snapshotManager.capture(agentId + ":" + requestId);
        ActionRecord record = ActionRecord.initial(requestId, agentId, before.snapshotId(),
                request, risk, now);
        pushRecord(agentId, record);

        SemanticEtw.getInstance().logEvent("GOVERNANCE", "BEFORE_ACTION",
                "agent=" + agentId + " action=" + request.fullAction()
                        + " risk=" + risk + " snapshot=" + before.snapshotId());
        log.info("[Governor] 动作前快照已打: agent={}, action={}, risk={}, snapshot={}",
                agentId, request.fullAction(), risk, before.snapshotId());
        return new ActionContext(requestId, agentId, risk, before.snapshotId(), before, request);
    }

    /**
     * 动作执行后：默认宽松期望（仅审计，不自动回滚）。
     */
    public AfterActionResult afterAction(ActionContext ctx, SyscallResponse response) {
        return afterAction(ctx, response, DiffExpectation.permissive());
    }

    /**
     * 动作执行后：打动作后快照，diff，违反期望时（REVERSIBLE）自动回滚。
     *
     * @param ctx          {@link #beforeAction} 返回的上下文
     * @param response     syscall 执行响应
     * @param expectation  diff 期望约束；null 等价宽松
     * @return 执行结果摘要
     */
    public AfterActionResult afterAction(ActionContext ctx, SyscallResponse response,
                                         DiffExpectation expectation) {
        long now = System.currentTimeMillis();
        boolean success = response != null && response.success();
        ResultState state = response != null ? response.resultState() : null;

        if (ctx.riskLevel() == RiskLevel.SAFE || ctx.before() == null) {
            // SAFE 或无快照：仅记录结果（不入栈的不在此），返回
            return AfterActionResult.noSnapshot(success);
        }

        DiffExpectation exp = expectation != null ? expectation : DiffExpectation.permissive();
        StateDiff diff = null;
        boolean autoRolledBack = false;

        try {
            EnvironmentSnapshot after = snapshotManager.capture(
                    ctx.agentId() + ":" + ctx.requestId() + ":after");
            try {
                diff = snapshotManager.diff(ctx.before(), after, exp);

                if (!diff.meetsExpectation()) {
                    if (ctx.riskLevel() == RiskLevel.REVERSIBLE) {
                        // 可逆：自动回滚到动作前
                        snapshotManager.restore(ctx.before());
                        autoRolledBack = true;
                        success = false;
                        SemanticEtw.getInstance().logEvent("GOVERNANCE", "AUTO_ROLLBACK",
                                "agent=" + ctx.agentId() + " action=" + ctx.request().fullAction()
                                        + " snapshot=" + ctx.snapshotId() + " deltas=" + diff.totalDeltas());
                        log.warn("[Governor] 期望违反，已自动回滚: agent={}, action={}, deltas={}",
                                ctx.agentId(), ctx.request().fullAction(), diff.totalDeltas());
                    } else {
                        // DESTRUCTIVE：仅告警，不回滚（事后审计 + 人工恢复）
                        SemanticEtw.getInstance().logEvent("GOVERNANCE", "EXPECTATION_VIOLATION",
                                "agent=" + ctx.agentId() + " action=" + ctx.request().fullAction()
                                        + " risk=DESTRUCTIVE deltas=" + diff.totalDeltas()
                                        + " (no auto-rollback, audit only)");
                        log.warn("[Governor] DESTRUCTIVE 动作期望违反，仅审计不回滚: agent={}, action={}, deltas={}",
                                ctx.agentId(), ctx.request().fullAction(), diff.totalDeltas());
                    }
                }
            } finally {
                // after 快照为临时产物，diff 后立即删除避免 store 泄漏
                snapshotManager.deleteSnapshot(after.snapshotId());
            }
        } catch (Exception e) {
            log.error("[Governor] afterAction diff/restore 异常: agent={}, action={}, error={}",
                    ctx.agentId(), ctx.request().fullAction(), e.getMessage(), e);
        }

        // 更新栈中记录为完成态
        updateRecordOnCompletion(ctx.agentId(), ctx.requestId(), now, state, autoRolledBack, diff);
        return new AfterActionResult(diff, autoRolledBack, success);
    }

    // ════════════════════════════════════════════════════════════════
    //  undo
    // ════════════════════════════════════════════════════════════════

    /**
     * 撤销指定动作：REVERSIBLE 回滚到动作前快照；DESTRUCTIVE/SAFE 拒绝。
     *
     * @return true 表示已成功回滚
     */
    public boolean undo(String requestId) {
        ActionRecord record = requestIndex.get(requestId);
        if (record == null) {
            log.warn("[Governor] undo 失败：requestId 不存在: {}", requestId);
            return false;
        }
        if (!record.isUndoable()) {
            log.info("[Governor] undo 拒绝：risk={}, undone={}, snapshot={}",
                    record.riskLevel(), record.undone(), record.snapshotId());
            return false;
        }

        try {
            EnvironmentSnapshot before = snapshotManager.load(record.snapshotId()).orElse(null);
            if (before == null) {
                log.warn("[Governor] undo 失败：快照已不存在: {}", record.snapshotId());
                return false;
            }
            snapshotManager.restore(before);
            updateRecordUndone(record);
            SemanticEtw.getInstance().logEvent("GOVERNANCE", "UNDO",
                    "agent=" + record.agentId() + " action=" + record.request().fullAction()
                            + " snapshot=" + record.snapshotId());
            log.info("[Governor] undo 成功: agent={}, action={}, snapshot={}",
                    record.agentId(), record.request().fullAction(), record.snapshotId());
            return true;
        } catch (Exception e) {
            log.error("[Governor] undo 异常: requestId={}, error={}", requestId, e.getMessage(), e);
            return false;
        }
    }

    /** 撤销指定 Agent 最近一个可逆动作。 */
    public boolean undoLast(String agentId) {
        Deque<ActionRecord> stack = undoStacks.get(agentId);
        if (stack == null) {
            return false;
        }
        synchronized (stack) {
            for (ActionRecord r : stack) {
                if (r.isUndoable()) {
                    return undo(r.requestId());
                }
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  executeGoverned — 一体化入口
    // ════════════════════════════════════════════════════════════════

    /**
     * 受治理执行入口：beforeAction → dispatcher.execute → afterAction。
     * <p>
     * 不动 {@link SyscallDispatcher#execute} 热路径，作为 opt-in 包装。
     * 读操作走此入口仅多一次风险判定（SAFE 不打快照，开销极小）。
     *
     * @param agentId     发起 Agent
     * @param request     syscall 请求
     * @param expectation diff 期望；null 等价宽松（仅审计）
     * @return 最终响应（自动回滚后仍返回原响应，调用方可据 {@link #lastAction(agentId)} 判定）
     */
    public SyscallResponse executeGoverned(String agentId, SyscallRequest request,
                                           DiffExpectation expectation) {
        ActionContext ctx = beforeAction(agentId, request);
        SyscallResponse response;
        try {
            response = dispatcher.execute(agentId, request);
        } catch (Exception e) {
            afterAction(ctx, SyscallResponse.fail(e), expectation);
            throw e;
        }
        afterAction(ctx, response, expectation);
        return response;
    }

    /** 受治理执行，宽松期望。 */
    public SyscallResponse executeGoverned(String agentId, SyscallRequest request) {
        return executeGoverned(agentId, request, DiffExpectation.permissive());
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 / GC
    // ════════════════════════════════════════════════════════════════

    /** 某 Agent 的动作历史（栈顶在前）。 */
    public List<ActionRecord> history(String agentId) {
        Deque<ActionRecord> stack = undoStacks.get(agentId);
        if (stack == null) {
            return List.of();
        }
        synchronized (stack) {
            List<ActionRecord> list = new ArrayList<>(stack);
            Collections.reverse(list); // 栈顶在前
            return Collections.unmodifiableList(list);
        }
    }

    /** 某 Agent 最近一个动作（栈顶），无则 null。 */
    public ActionRecord lastAction(String agentId) {
        Deque<ActionRecord> stack = undoStacks.get(agentId);
        if (stack == null) {
            return null;
        }
        synchronized (stack) {
            return stack.peek();
        }
    }

    /**
     * GC：清理完成时间早于 ageMs 的动作及其快照，并裁剪超深栈。
     * <p>
     * 快照删除走 {@link EnvironmentSnapshotManager#deleteSnapshot}，
     * 对 HibernationSection 等会级联到底层 HibernationManager。
     */
    public int gcOlderThan(long ageMs) {
        long threshold = System.currentTimeMillis() - ageMs;
        int evicted = 0;
        for (Map.Entry<String, Deque<ActionRecord>> entry : undoStacks.entrySet()) {
            Deque<ActionRecord> stack = entry.getValue();
            List<ActionRecord> toEvict = new ArrayList<>();
            synchronized (stack) {
                // 从栈底（最旧）扫描
                List<ActionRecord> asList = new ArrayList<>(stack);
                for (ActionRecord r : asList) {
                    if (r.completedAtMs() > 0 && r.completedAtMs() < threshold) {
                        toEvict.add(r);
                    }
                }
                for (ActionRecord r : toEvict) {
                    stack.remove(r);
                    requestIndex.remove(r.requestId());
                    deleteSnapshotQuietly(r);
                    evicted++;
                }
                // 裁剪超深：保留 maxStackDepth 条最新
                while (stack.size() > maxStackDepth) {
                    ActionRecord dropped = stack.pollLast(); // 栈底
                    if (dropped != null) {
                        requestIndex.remove(dropped.requestId());
                        deleteSnapshotQuietly(dropped);
                        evicted++;
                    }
                }
            }
        }
        if (evicted > 0) {
            log.info("[Governor] GC 完成: evicted={} entries", evicted);
        }
        return evicted;
    }

    /** 仅供测试：清空所有栈与索引。 */
    void clearAll() {
        undoStacks.clear();
        requestIndex.clear();
    }

    // ════════════════════════════════════════════════════════════════
    //  内部
    // ════════════════════════════════════════════════════════════════

    private void pushRecord(String agentId, ActionRecord record) {
        Deque<ActionRecord> stack = undoStacks.computeIfAbsent(agentId, k -> new ArrayDeque<>());
        synchronized (stack) {
            stack.push(record);
            requestIndex.put(record.requestId(), record);
            // 裁剪超深
            while (stack.size() > maxStackDepth) {
                ActionRecord dropped = stack.pollLast();
                if (dropped != null) {
                    requestIndex.remove(dropped.requestId());
                    deleteSnapshotQuietly(dropped);
                }
            }
        }
    }

    private void updateRecordOnCompletion(String agentId, String requestId, long completedAtMs,
                                          ResultState state, boolean autoRolledBack, StateDiff diff) {
        Deque<ActionRecord> stack = undoStacks.get(agentId);
        if (stack == null) {
            return;
        }
        synchronized (stack) {
            List<ActionRecord> asList = new ArrayList<>(stack);
            for (int i = 0; i < asList.size(); i++) {
                ActionRecord r = asList.get(i);
                if (r.requestId().equals(requestId)) {
                    ActionRecord updated = r.withCompletion(completedAtMs, state, autoRolledBack, diff);
                    // 重建栈以替换
                    asList.set(i, updated);
                    requestIndex.put(requestId, updated);
                    stack.clear();
                    // asList 当前是栈顶在前，重新 push 需倒序
                    for (int j = asList.size() - 1; j >= 0; j--) {
                        stack.push(asList.get(j));
                    }
                    return;
                }
            }
        }
    }

    private void updateRecordUndone(ActionRecord record) {
        ActionRecord updated = record.withUndone();
        requestIndex.put(record.requestId(), updated);
        Deque<ActionRecord> stack = undoStacks.get(record.agentId());
        if (stack == null) {
            return;
        }
        synchronized (stack) {
            List<ActionRecord> asList = new ArrayList<>(stack);
            for (int i = 0; i < asList.size(); i++) {
                if (asList.get(i).requestId().equals(record.requestId())) {
                    asList.set(i, updated);
                    stack.clear();
                    for (int j = asList.size() - 1; j >= 0; j--) {
                        stack.push(asList.get(j));
                    }
                    return;
                }
            }
        }
    }

    private void deleteSnapshotQuietly(ActionRecord r) {
        if (r.snapshotId() == null) {
            return;
        }
        try {
            snapshotManager.deleteSnapshot(r.snapshotId());
        } catch (Exception e) {
            log.warn("[Governor] 删除快照失败: {}, error={}", r.snapshotId(), e.getMessage());
        }
    }
}
