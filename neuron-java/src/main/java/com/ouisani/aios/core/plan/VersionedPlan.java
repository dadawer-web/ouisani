package com.ouisani.aios.core.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 版本化任务图 — 镜像 jcode {@code jcode-plan/src/lib.rs:88-97} 的 {@code VersionedPlan}。
 * <p>
 * 持有 {@code items} / {@code version} / {@code participants} / {@code taskProgress}，
 * 提供<b>仅状态转换才 version+1</b> 的变更方法。纯查询委托 {@link PlanGraphQuery}。
 * <p>
 * <b>版本递增规则（核心约束，镜像 jcode swarm.rs:175-226）</b>：
 * <ul>
 *   <li>状态转换（start/complete/fail/assign/replaceItems/flipToStale/revive）→ version+1</li>
 *   <li>心跳 / 检查点 → version 不变</li>
 * </ul>
 * <p>
 * 线程安全：变更方法 synchronized（find-and-replace 原子性）；
 * {@code taskProgress} 用 ConcurrentHashMap；{@code items} 用 CopyOnWriteArrayList。
 */
public final class VersionedPlan {

    private final List<PlanItem> items = new CopyOnWriteArrayList<>();
    private final AtomicLong version = new AtomicLong(0);
    private final Set<String> participants = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, SwarmTaskProgress> taskProgress = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════
    //  版本化变更（每次 +1）— synchronized 保证 find-and-replace 原子性
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动任务 — runnable → running，设置 startedAt，version+1。
     *
     * @return true 若状态转换成功
     */
    public synchronized boolean startTask(String id) {
        int idx = indexOf(id);
        if (idx < 0) return false;
        PlanItem item = items.get(idx);
        if (!PlanItem.isRunnable(item.status())) return false;

        items.set(idx, item.withStatus("running"));
        long now = System.currentTimeMillis();
        SwarmTaskProgress p = taskProgress.getOrDefault(id, SwarmTaskProgress.empty());
        p = new SwarmTaskProgress(
                p.assignedSessionId(), p.assignmentSummary(), p.assignedAtUnixMs(),
                now, now, "started",
                p.lastCheckpointUnixMs(), p.checkpointSummary(),
                p.completedAtUnixMs(), p.staleSinceUnixMs(),
                p.heartbeatCount(), p.checkpointCount()
        );
        taskProgress.put(id, p);
        version.incrementAndGet();
        return true;
    }

    /**
     * 完成任务 — active → completed，设置 completedAt，version+1。
     */
    public synchronized boolean completeTask(String id) {
        int idx = indexOf(id);
        if (idx < 0) return false;
        PlanItem item = items.get(idx);
        if (!PlanItem.isActive(item.status())) return false;

        items.set(idx, item.withStatus("completed"));
        long now = System.currentTimeMillis();
        SwarmTaskProgress p = taskProgress.getOrDefault(id, SwarmTaskProgress.empty());
        p = new SwarmTaskProgress(
                p.assignedSessionId(), p.assignmentSummary(), p.assignedAtUnixMs(),
                p.startedAtUnixMs(), p.lastHeartbeatUnixMs(), p.lastDetail(),
                p.lastCheckpointUnixMs(), p.checkpointSummary(),
                now, p.staleSinceUnixMs(),
                p.heartbeatCount(), p.checkpointCount()
        );
        taskProgress.put(id, p);
        version.incrementAndGet();
        return true;
    }

    /**
     * 失败任务 — active → failed，记录原因，version+1。
     */
    public synchronized boolean failTask(String id, String reason) {
        int idx = indexOf(id);
        if (idx < 0) return false;
        PlanItem item = items.get(idx);
        if (!PlanItem.isActive(item.status())) return false;

        items.set(idx, item.withStatus("failed"));
        long now = System.currentTimeMillis();
        SwarmTaskProgress p = taskProgress.getOrDefault(id, SwarmTaskProgress.empty());
        p = new SwarmTaskProgress(
                p.assignedSessionId(), p.assignmentSummary(), p.assignedAtUnixMs(),
                p.startedAtUnixMs(), p.lastHeartbeatUnixMs(),
                reason != null ? reason : "failed",
                p.lastCheckpointUnixMs(), p.checkpointSummary(),
                now, p.staleSinceUnixMs(),
                p.heartbeatCount(), p.checkpointCount()
        );
        taskProgress.put(id, p);
        version.incrementAndGet();
        return true;
    }

    /**
     * 分配任务 — 设置 assignedTo + session，version+1。
     */
    public synchronized boolean assignTask(String id, String assignee, String sessionId) {
        int idx = indexOf(id);
        if (idx < 0) return false;
        PlanItem item = items.get(idx);

        items.set(idx, item.withAssignedTo(assignee));
        long now = System.currentTimeMillis();
        SwarmTaskProgress p = taskProgress.getOrDefault(id, SwarmTaskProgress.empty());
        p = new SwarmTaskProgress(
                sessionId, p.assignmentSummary(), now,
                p.startedAtUnixMs(), p.lastHeartbeatUnixMs(), p.lastDetail(),
                p.lastCheckpointUnixMs(), p.checkpointSummary(),
                p.completedAtUnixMs(), p.staleSinceUnixMs(),
                p.heartbeatCount(), p.checkpointCount()
        );
        taskProgress.put(id, p);
        if (assignee != null) participants.add(assignee);
        version.incrementAndGet();
        return true;
    }

    /**
     * 替换全部 items + participants — plan-edit，version+1。
     */
    public synchronized void replaceItems(List<PlanItem> newItems, Set<String> newParticipants) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        participants.clear();
        if (newParticipants != null) participants.addAll(newParticipants);
        version.incrementAndGet();
    }

    /**
     * 翻转陈旧 — running → running_stale，stale_since get_or_insert，version+1。
     * <p>
     * 镜像 jcode refresh_swarm_task_staleness 的 stale 翻转。
     */
    public synchronized boolean flipToStale(String id, long nowMs) {
        int idx = indexOf(id);
        if (idx < 0) return false;
        PlanItem item = items.get(idx);
        if (!"running".equals(item.status())) return false;

        items.set(idx, item.withStatus("running_stale"));
        SwarmTaskProgress p = taskProgress.getOrDefault(id, SwarmTaskProgress.empty());
        p = p.withStaleSince(nowMs);
        taskProgress.put(id, p);
        version.incrementAndGet();
        return true;
    }

    /**
     * 复活 — running_stale → running，清除 stale_since，version+1。
     * <p>
     * 镜像 jcode touch_swarm_task_progress 的 revive 逻辑。
     */
    public synchronized boolean revive(String id, long nowMs) {
        int idx = indexOf(id);
        if (idx < 0) return false;
        PlanItem item = items.get(idx);
        if (!"running_stale".equals(item.status())) return false;

        items.set(idx, item.withStatus("running"));
        SwarmTaskProgress p = taskProgress.getOrDefault(id, SwarmTaskProgress.empty());
        p = p.clearedStale().withHeartbeat(nowMs, "revived");
        taskProgress.put(id, p);
        version.incrementAndGet();
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  非版本化变更 — heartbeat / checkpoint 不 bump version
    // ════════════════════════════════════════════════════════════════

    /**
     * 记录心跳 — 更新 progress 字段，<b>不触发 version+1</b>。
     * <p>
     * 镜像 jcode swarm.rs:199-208：纯心跳更新不递增版本号。
     * 若任务处于 running_stale，触发 revive（version+1）。
     */
    public void recordHeartbeat(String id, String sessionId, String detail, long nowMs) {
        PlanItem item = findItem(id);
        if (item == null) return;

        // running_stale 收到心跳 → revive（版本化）
        if ("running_stale".equals(item.status())) {
            revive(id, nowMs);
            SwarmTaskProgress p = taskProgress.getOrDefault(id, SwarmTaskProgress.empty());
            if (sessionId != null) {
                p = new SwarmTaskProgress(
                        sessionId, p.assignmentSummary(), p.assignedAtUnixMs(),
                        p.startedAtUnixMs(), p.lastHeartbeatUnixMs(), detail,
                        p.lastCheckpointUnixMs(), p.checkpointSummary(),
                        p.completedAtUnixMs(), p.staleSinceUnixMs(),
                        p.heartbeatCount(), p.checkpointCount()
                );
                taskProgress.put(id, p);
            }
            return;
        }

        // running 纯心跳 — 不 bump version
        if ("running".equals(item.status())) {
            SwarmTaskProgress p = taskProgress.getOrDefault(id, SwarmTaskProgress.empty());
            p = p.withHeartbeat(nowMs, detail);
            taskProgress.put(id, p);
        }
    }

    /**
     * 记录检查点 — 更新 progress 字段，<b>不触发 version+1</b>。
     */
    public void recordCheckpoint(String id, String summary, long nowMs) {
        PlanItem item = findItem(id);
        if (item == null) return;
        if (!PlanItem.isActive(item.status())) return;

        SwarmTaskProgress p = taskProgress.getOrDefault(id, SwarmTaskProgress.empty());
        p = p.withCheckpoint(nowMs, summary);
        taskProgress.put(id, p);
    }

    // ════════════════════════════════════════════════════════════════
    //  纯查询 — 委托 PlanGraphQuery
    // ════════════════════════════════════════════════════════════════

    public PlanGraphSummary summarizeGraph() {
        return PlanGraphQuery.summarize(snapshotItems());
    }

    public List<String> cycleItemIds() {
        return PlanGraphQuery.cycleItemIds(snapshotItems());
    }

    public List<String> newlyReadyItemIds(List<PlanItem> before) {
        return PlanGraphQuery.newlyReadyItemIds(before, snapshotItems());
    }

    public List<String> nextRunnableItemIds(int limit) {
        // 注入 ActivityResolver（默认 NOOP 零回归）；测试可 setActivityResolver 注入实现
        return PlanGraphQuery.nextRunnableItemIds(snapshotItems(), limit, PlanGraphQuery.ACTIVITY_RESOLVER);
    }

    public PlanGraphQuery.AssignmentAffinities affinitiesForTask(String taskId) {
        return PlanGraphQuery.affinitiesForTask(snapshotItems(), Set.copyOf(participants), taskId);
    }

    // ════════════════════════════════════════════════════════════════
    //  只读访问器
    // ════════════════════════════════════════════════════════════════

    public long version() {
        return version.get();
    }

    /** 不可变快照 — 供广播 diff 使用。 */
    public List<PlanItem> snapshotItems() {
        return List.copyOf(items);
    }

    /** 原始 items 视图（用于 sweepTick 遍历，不拷贝）。 */
    List<PlanItem> itemsView() {
        return Collections.unmodifiableList(items);
    }

    public SwarmTaskProgress progress(String id) {
        return taskProgress.get(id);
    }

    public Set<String> participants() {
        return Set.copyOf(participants);
    }

    public PlanItem findItem(String id) {
        for (PlanItem item : items) {
            if (item.id().equals(id)) return item;
        }
        return null;
    }

    private int indexOf(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    // ════════════════════════════════════════════════════════════════
    //  持久化专用 raw 写入（package-private）— 仅 VersionedPlanPersistence 调用
    // ════════════════════════════════════════════════════════════════

    void addItemRaw(PlanItem item) {
        if (item != null) items.add(item);
    }

    void putProgress(String id, SwarmTaskProgress progress) {
        if (id != null && progress != null) taskProgress.put(id, progress);
    }

    void setVersionRaw(long v) {
        version.set(v);
    }

    void addParticipantsRaw(Set<String> newParticipants) {
        if (newParticipants != null) participants.addAll(newParticipants);
    }

    /** 导出全部 taskProgress（持久化用）。 */
    Map<String, SwarmTaskProgress> progressSnapshot() {
        return new java.util.HashMap<>(taskProgress);
    }
}
