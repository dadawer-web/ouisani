package com.ouisani.aios.core.overnight;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Overnight 运行清单 — 一次长跑会话的完整时间锚点与状态。
 * <p>
 * 镜像 jcode 的 OvernightManifest：记录 runId、使命、运行状态、以及由
 * 起始时间 + 持续时长推导出的四个时间锚点。阶段机 {@link OvernightPhase}
 * 根据这些锚点实时计算当前阶段，而非存储阶段。
 * <p>
 * 时间锚点（借鉴 jcode overnight.rs:57 start_overnight_run）：
 * <ul>
 *   <li>targetWakeAt = startedAt + duration</li>
 *   <li>handoffReadyAt = targetWakeAt - min(30min, duration/4)</li>
 *   <li>postWakeGraceUntil = targetWakeAt + 2h</li>
 * </ul>
 * manifest 本身不可变（record），状态变更通过 withXxx 返回新副本。
 *
 * @see OvernightPhase
 * @see OvernightRunner
 */
public record OvernightManifest(
        String runId,
        String mission,
        OvernightRunStatus status,
        Instant startedAt,
        Instant targetWakeAt,
        Instant handoffReadyAt,
        Instant postWakeGraceUntil,
        Instant morningReportPostedAt,
        Instant completedAt,
        Instant cancelRequestedAt,
        Instant lastActivityAt,
        String vfsRunDir,
        int coordinatorPid,
        byte maxAgentsGuidance
) {

    /** 长跑运行状态 — 镜像 jcode OvernightRunStatus */
    public enum OvernightRunStatus {
        RUNNING,
        CANCEL_REQUESTED,
        COMPLETED,
        FAILED;

        public String label() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    /** 默认最大并发 agent 数：1 coordinator + 至多 1 helper */
    private static final byte DEFAULT_MAX_AGENTS = 2;

    /** 宽限期默认 2 小时 */
    private static final Duration DEFAULT_GRACE = Duration.ofHours(2);

    /** handoff 提前量上限 30 分钟 */
    private static final Duration HANDOFF_LEAD_CAP = Duration.ofMinutes(30);

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm z")
            .withZone(ZoneId.systemDefault());

    /**
     * 创建一次 overnight run 的 manifest，自动计算全部时间锚点。
     *
     * @param runId    运行 ID
     * @param mission  使命描述（可空，空则用默认使命）
     * @param duration 持续时长
     * @param vfsRunDir VFS 持久化目录（如 /var/run/overnight/{runId}）
     * @return 初始化的 manifest（status=RUNNING, coordinatorPid=-1）
     */
    public static OvernightManifest create(String runId, String mission,
                                           Duration duration, String vfsRunDir) {
        Instant started = Instant.now();
        Instant targetWake = started.plus(duration);
        Duration handoffLead = duration.dividedBy(4);
        if (handoffLead.compareTo(HANDOFF_LEAD_CAP) > 0) {
            handoffLead = HANDOFF_LEAD_CAP;
        }
        Instant handoffReady = targetWake.minus(handoffLead);
        Instant grace = targetWake.plus(DEFAULT_GRACE);

        return new OvernightManifest(
                runId,
                mission == null || mission.isBlank() ? null : mission,
                OvernightRunStatus.RUNNING,
                started,
                targetWake,
                handoffReady,
                grace,
                null,
                null,
                null,
                started,
                vfsRunDir,
                -1,
                DEFAULT_MAX_AGENTS
        );
    }

    /** 便捷工厂：默认 VFS 目录由 AiosPaths.overnightDir() 推导 */
    public static OvernightManifest create(String runId, String mission, Duration duration) {
        return create(runId, mission, duration, null);
    }

    // ── 不可变更新方法 ──

    public OvernightManifest withStatus(OvernightRunStatus s) {
        return new OvernightManifest(runId, mission, s, startedAt, targetWakeAt,
                handoffReadyAt, postWakeGraceUntil, morningReportPostedAt,
                s == OvernightRunStatus.COMPLETED ? Instant.now() : completedAt,
                s == OvernightRunStatus.CANCEL_REQUESTED ? Instant.now() : cancelRequestedAt,
                Instant.now(), vfsRunDir, coordinatorPid, maxAgentsGuidance);
    }

    public OvernightManifest withMorningReportPosted(Instant t) {
        return new OvernightManifest(runId, mission, status, startedAt, targetWakeAt,
                handoffReadyAt, postWakeGraceUntil, t, completedAt, cancelRequestedAt,
                Instant.now(), vfsRunDir, coordinatorPid, maxAgentsGuidance);
    }

    public OvernightManifest withLastActivity(Instant t) {
        return new OvernightManifest(runId, mission, status, startedAt, targetWakeAt,
                handoffReadyAt, postWakeGraceUntil, morningReportPostedAt, completedAt,
                cancelRequestedAt, t, vfsRunDir, coordinatorPid, maxAgentsGuidance);
    }

    public OvernightManifest withCoordinatorPid(int pid) {
        return new OvernightManifest(runId, mission, status, startedAt, targetWakeAt,
                handoffReadyAt, postWakeGraceUntil, morningReportPostedAt, completedAt,
                cancelRequestedAt, lastActivityAt, vfsRunDir, pid, maxAgentsGuidance);
    }

    public OvernightManifest withCancelRequested(Instant t) {
        return new OvernightManifest(runId, mission, OvernightRunStatus.CANCEL_REQUESTED,
                startedAt, targetWakeAt, handoffReadyAt, postWakeGraceUntil,
                morningReportPostedAt, completedAt, t, Instant.now(), vfsRunDir,
                coordinatorPid, maxAgentsGuidance);
    }

    // ── 派生查询 ──

    /** 默认使命（当 mission 为空时使用） */
    public String effectiveMission() {
        return (mission != null && !mission.isBlank())
                ? mission
                : "继续当前会话的最高价值工作，优先已验证、低风险的进展。";
    }

    /** 距 targetWakeAt 的剩余分钟数（已过则为负） */
    public long minutesToTarget(Instant now) {
        return Duration.between(now, targetWakeAt).toMinutes();
    }

    /** targetWakeAt 的可读格式 */
    public String targetWakeLabel() {
        return FMT.format(targetWakeAt);
    }

    /** postWakeGraceUntil 的可读格式 */
    public String graceLabel() {
        return FMT.format(postWakeGraceUntil);
    }

    /** 是否已被取消 */
    public boolean isCancelled() {
        return status == OvernightRunStatus.CANCEL_REQUESTED;
    }

    /** 是否已终结（非 RUNNING 状态） */
    public boolean isTerminal() {
        return status == OvernightRunStatus.COMPLETED || status == OvernightRunStatus.FAILED;
    }
}
