package com.ouisani.aios.core.overnight;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OvernightManifest 单元测试 — 验证时间锚点计算与不可变更新。
 */
class OvernightManifestTest {

    @Test
    void create_shouldComputeTimeAnchorsFor4HourDuration() {
        OvernightManifest m = OvernightManifest.create(
                "test-4h", "测试使命", Duration.ofHours(4), "/var/run/overnight/test-4h");

        assertEquals(OvernightManifest.OvernightRunStatus.RUNNING, m.status());
        assertNotNull(m.startedAt());
        assertNotNull(m.targetWakeAt());

        // targetWakeAt = startedAt + 4h
        assertEquals(m.startedAt().plus(Duration.ofHours(4)), m.targetWakeAt());

        // handoffReadyAt = targetWakeAt - min(30min, 4h/4=1h) = targetWakeAt - 30min
        assertEquals(m.targetWakeAt().minus(Duration.ofMinutes(30)), m.handoffReadyAt());

        // postWakeGraceUntil = targetWakeAt + 2h
        assertEquals(m.targetWakeAt().plus(Duration.ofHours(2)), m.postWakeGraceUntil());

        assertNull(m.morningReportPostedAt());
        assertNull(m.completedAt());
        assertNull(m.cancelRequestedAt());
        assertEquals(-1, m.coordinatorPid());
    }

    @Test
    void create_shouldComputeHandoffLeadAsDurationDividedBy4ForShortRuns() {
        // duration=1h → handoffLead = min(30min, 15min) = 15min
        OvernightManifest m = OvernightManifest.create(
                "test-1h", null, Duration.ofHours(1), "/var/run/overnight/test-1h");

        Duration expectedLead = Duration.ofMinutes(15);
        assertEquals(m.targetWakeAt().minus(expectedLead), m.handoffReadyAt());
    }

    @Test
    void create_shouldCapHandoffLeadAt30MinutesForLongRuns() {
        // duration=8h → handoffLead = min(30min, 2h) = 30min
        OvernightManifest m = OvernightManifest.create(
                "test-8h", "使命", Duration.ofHours(8), "/var/run/overnight/test-8h");

        assertEquals(m.targetWakeAt().minus(Duration.ofMinutes(30)), m.handoffReadyAt());
    }

    @Test
    void create_shouldUseDefaultMissionWhenNull() {
        OvernightManifest m = OvernightManifest.create(
                "test", null, Duration.ofHours(2), "/var/run/overnight/test");

        assertNotNull(m.effectiveMission());
        assertFalse(m.effectiveMission().isBlank());
    }

    @Test
    void create_shouldUseProvidedMissionWhenNonEmpty() {
        OvernightManifest m = OvernightManifest.create(
                "test", "修复登录bug", Duration.ofHours(2), "/var/run/overnight/test");

        assertEquals("修复登录bug", m.effectiveMission());
    }

    @Test
    void withStatus_shouldReturnNewCopyWithUpdatedStatus() {
        OvernightManifest m = OvernightManifest.create(
                "test", "使命", Duration.ofHours(2), "/var/run/test");

        OvernightManifest completed = m.withStatus(OvernightManifest.OvernightRunStatus.COMPLETED);

        assertNotSame(m, completed);
        assertEquals(OvernightManifest.OvernightRunStatus.RUNNING, m.status());
        assertEquals(OvernightManifest.OvernightRunStatus.COMPLETED, completed.status());
        assertNotNull(completed.completedAt());
    }

    @Test
    void withStatus_cancelRequested_shouldSetCancelTimestamp() {
        OvernightManifest m = OvernightManifest.create(
                "test", "使命", Duration.ofHours(2), "/var/run/test");

        OvernightManifest cancelled = m.withStatus(OvernightManifest.OvernightRunStatus.CANCEL_REQUESTED);

        assertEquals(OvernightManifest.OvernightRunStatus.CANCEL_REQUESTED, cancelled.status());
        assertNotNull(cancelled.cancelRequestedAt());
    }

    @Test
    void withMorningReportPosted_shouldReturnNewCopyWithTimestamp() {
        OvernightManifest m = OvernightManifest.create(
                "test", "使命", Duration.ofHours(2), "/var/run/test");
        assertNull(m.morningReportPostedAt());

        Instant t = Instant.now();
        OvernightManifest updated = m.withMorningReportPosted(t);

        assertNotSame(m, updated);
        assertNull(m.morningReportPostedAt());
        assertEquals(t, updated.morningReportPostedAt());
    }

    @Test
    void withCoordinatorPid_shouldReturnNewCopyWithPid() {
        OvernightManifest m = OvernightManifest.create(
                "test", "使命", Duration.ofHours(2), "/var/run/test");
        assertEquals(-1, m.coordinatorPid());

        OvernightManifest updated = m.withCoordinatorPid(1001);

        assertEquals(-1, m.coordinatorPid());
        assertEquals(1001, updated.coordinatorPid());
    }

    @Test
    void isTerminal_shouldReturnTrueForCompletedAndFailed() {
        OvernightManifest running = OvernightManifest.create(
                "test", "使命", Duration.ofHours(2), "/var/run/test");
        assertFalse(running.isTerminal());

        OvernightManifest completed = running.withStatus(OvernightManifest.OvernightRunStatus.COMPLETED);
        assertTrue(completed.isTerminal());

        OvernightManifest failed = running.withStatus(OvernightManifest.OvernightRunStatus.FAILED);
        assertTrue(failed.isTerminal());
    }

    @Test
    void isCancelled_shouldReturnTrueOnlyForCancelRequested() {
        OvernightManifest m = OvernightManifest.create(
                "test", "使命", Duration.ofHours(2), "/var/run/test");
        assertFalse(m.isCancelled());

        OvernightManifest cancelled = m.withStatus(OvernightManifest.OvernightRunStatus.CANCEL_REQUESTED);
        assertTrue(cancelled.isCancelled());
    }

    @Test
    void minutesToTarget_shouldReturnRemainingMinutes() {
        Instant started = Instant.now();
        Duration duration = Duration.ofHours(2);
        OvernightManifest m = OvernightManifest.create(
                "test", "使命", duration, "/var/run/test");

        long minutes = m.minutesToTarget(started.plus(Duration.ofMinutes(30)));
        assertTrue(minutes > 0);
        assertTrue(minutes <= 120);
    }

    @Test
    void fromJson_roundTripsPersistedSubset() {
        // catch-up reload 契约：manifestToJson 持久化子集 → fromJson 还原核心字段。
        // omitted 字段（completedAt/cancelRequestedAt/lastActivityAt）默认 null，
        // maxAgentsGuidance 默认 DEFAULT，vfsRunDir 由调用方重算。
        OvernightManifest original = OvernightManifest.create(
                "catchup-1", "晨报补跑测试", Duration.ofHours(4), "/var/run/overnight/catchup-1");
        OvernightManifest posted = original.withMorningReportPosted(Instant.now())
                .withCoordinatorPid(4242);

        String json = OvernightRunner.instance().manifestToJson(posted);
        OvernightManifest reloaded = OvernightManifest.fromJson(json, "/var/run/overnight/catchup-1");

        assertEquals(posted.runId(), reloaded.runId());
        assertEquals(posted.status(), reloaded.status());
        assertEquals(posted.startedAt(), reloaded.startedAt());
        assertEquals(posted.targetWakeAt(), reloaded.targetWakeAt());
        assertEquals(posted.handoffReadyAt(), reloaded.handoffReadyAt());
        assertEquals(posted.postWakeGraceUntil(), reloaded.postWakeGraceUntil());
        assertEquals(posted.morningReportPostedAt(), reloaded.morningReportPostedAt());
        assertEquals(4242, reloaded.coordinatorPid());
        assertEquals(posted.effectiveMission(), reloaded.effectiveMission());
        assertEquals("/var/run/overnight/catchup-1", reloaded.vfsRunDir());
        // omitted 字段默认
        assertNull(reloaded.completedAt());
        assertNull(reloaded.cancelRequestedAt());
        assertNull(reloaded.lastActivityAt());
    }

    @Test
    void fromJson_handlesNullMorningReportAndStatusLabel() {
        // 新建 run：morningReportPostedAt=null（序列化为 "null" 字符串），status="running"
        OvernightManifest original = OvernightManifest.create(
                "catchup-2", null, Duration.ofHours(2), "/var/run/overnight/catchup-2");
        String json = OvernightRunner.instance().manifestToJson(original);

        OvernightManifest reloaded = OvernightManifest.fromJson(json, "/var/run/overnight/catchup-2");

        assertEquals(OvernightManifest.OvernightRunStatus.RUNNING, reloaded.status());
        assertNull(reloaded.morningReportPostedAt());
        assertEquals(original.targetWakeAt(), reloaded.targetWakeAt());
    }
}
