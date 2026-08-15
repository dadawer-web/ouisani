package com.ouisani.aios.core.plan;

/**
 * 任务可持久化进度 — 镜像 jcode {@code jcode-plan/src/lib.rs:25-51} 的 {@code SwarmTaskProgress}。
 * <p>
 * 全部字段可空（{@code Long}/{@code String}），对应 jcode 的 {@code Option<u64>}/{@code Option<String>}。
 * <p>
 * <b>陈旧性语义（get_or_insert）</b>：{@code staleSinceUnixMs} 首次置陈旧时记录时刻，
 * 后续不覆盖；{@link #clearedStale()} 复活时置 null。
 * <p>
 * <b>3 级 fallback</b>：{@link #lastActivityTimestamp()} 返回
 * {@code lastHeartbeat.or(started).or(assigned)}，全 null 返回 null（视为陈旧）。
 *
 * @param assignedSessionId    分配的会话 id
 * @param assignmentSummary    分配摘要
 * @param assignedAtUnixMs     分配时刻（毫秒）
 * @param startedAtUnixMs      启动时刻（毫秒）
 * @param lastHeartbeatUnixMs  最近心跳时刻（毫秒）
 * @param lastDetail           最近心跳详情
 * @param lastCheckpointUnixMs 最近检查点时刻（毫秒）
 * @param checkpointSummary    检查点摘要
 * @param completedAtUnixMs    完成时刻（毫秒）
 * @param staleSinceUnixMs     首次陈旧时刻（毫秒）— get_or_insert 语义
 * @param heartbeatCount       心跳计数
 * @param checkpointCount      检查点计数
 */
public record SwarmTaskProgress(
        String assignedSessionId,
        String assignmentSummary,
        Long assignedAtUnixMs,
        Long startedAtUnixMs,
        Long lastHeartbeatUnixMs,
        String lastDetail,
        Long lastCheckpointUnixMs,
        String checkpointSummary,
        Long completedAtUnixMs,
        Long staleSinceUnixMs,
        Long heartbeatCount,
        Long checkpointCount
) {

    /** 全 null 工厂 — 新分配任务无进度时的初始状态。 */
    public static SwarmTaskProgress empty() {
        return new SwarmTaskProgress(null, null, null, null, null, null, null, null, null, null, 0L, 0L);
    }

    /**
     * 记录心跳 — 返回新副本，更新 {@code lastHeartbeatUnixMs}/{@code lastDetail}，
     * 递增 {@code heartbeatCount}。<b>不更新 {@code staleSinceUnixMs}</b>（复活由 VersionedPlan 处理）。
     * <p>
     * <b>不触发 version+1</b>（镜像 jcode swarm.rs:199-208 touch_swarm_task_progress）。
     */
    public SwarmTaskProgress withHeartbeat(long nowMs, String detail) {
        return new SwarmTaskProgress(
                assignedSessionId, assignmentSummary, assignedAtUnixMs, startedAtUnixMs,
                nowMs, detail,
                lastCheckpointUnixMs, checkpointSummary,
                completedAtUnixMs, staleSinceUnixMs,
                (heartbeatCount != null ? heartbeatCount : 0L) + 1,
                checkpointCount
        );
    }

    /**
     * 记录检查点 — 返回新副本，更新 {@code lastCheckpointUnixMs}/{@code checkpointSummary}，
     * 递增 {@code checkpointCount}。<b>不触发 version+1</b>。
     */
    public SwarmTaskProgress withCheckpoint(long nowMs, String summary) {
        return new SwarmTaskProgress(
                assignedSessionId, assignmentSummary, assignedAtUnixMs, startedAtUnixMs,
                lastHeartbeatUnixMs, lastDetail,
                nowMs, summary,
                completedAtUnixMs, staleSinceUnixMs,
                heartbeatCount,
                (checkpointCount != null ? checkpointCount : 0L) + 1
        );
    }

    /**
     * get_or_insert 陈旧时刻 — 镜像 jcode refresh_swarm_task_staleness 的 get_or_insert 语义。
     * <p>
     * 若 {@code staleSinceUnixMs} 已有值，原样返回（不覆盖首次陈旧时刻）；
     * 否则返回新副本，设置 {@code staleSinceUnixMs = nowMs}。
     */
    public SwarmTaskProgress withStaleSince(long nowMs) {
        if (staleSinceUnixMs != null) {
            return this; // get_or_insert：已有则不动
        }
        return new SwarmTaskProgress(
                assignedSessionId, assignmentSummary, assignedAtUnixMs, startedAtUnixMs,
                lastHeartbeatUnixMs, lastDetail,
                lastCheckpointUnixMs, checkpointSummary,
                completedAtUnixMs, nowMs,
                heartbeatCount, checkpointCount
        );
    }

    /**
     * 清除陈旧标记 — 复活时调用（running_stale→running），置 {@code staleSinceUnixMs = null}。
     */
    public SwarmTaskProgress clearedStale() {
        if (staleSinceUnixMs == null) return this;
        return new SwarmTaskProgress(
                assignedSessionId, assignmentSummary, assignedAtUnixMs, startedAtUnixMs,
                lastHeartbeatUnixMs, lastDetail,
                lastCheckpointUnixMs, checkpointSummary,
                completedAtUnixMs, null,
                heartbeatCount, checkpointCount
        );
    }

    /**
     * 最近活动时间戳 — 3 级 fallback：{@code lastHeartbeat.or(started).or(assigned)}。
     * 全 null 返回 null（视为陈旧，保守策略）。
     * <p>
     * 镜像 jcode refresh_swarm_task_staleness 的三级回退。
     */
    public Long lastActivityTimestamp() {
        if (lastHeartbeatUnixMs != null) return lastHeartbeatUnixMs;
        if (startedAtUnixMs != null) return startedAtUnixMs;
        return assignedAtUnixMs;
    }
}
