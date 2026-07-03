package com.ouisani.aios.core.overnight;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OvernightPhase 单元测试 — 验证阶段机的时间边界转换。
 * <p>
 * 使用固定时间戳构造 manifest，确保测试可重现。
 */
class OvernightPhaseTest {

    // 固定基准时间：2026-01-01T00:00:00Z
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant STARTED = T0;
    private static final Instant TARGET_WAKE = T0.plusSeconds(4 * 3600);       // +4h
    private static final Instant HANDOFF_READY = T0.plusSeconds(3 * 3600 + 1800); // +3.5h
    private static final Instant GRACE_UNTIL = T0.plusSeconds(6 * 3600);        // +6h
    private static final Instant REPORT_POSTED = T0.plusSeconds(4 * 3600 + 60); // target+1min

    /** 构造测试用 manifest（直接用 record 构造器，绕过 create 的 now 依赖） */
    private OvernightManifest buildManifest(OvernightManifest.OvernightRunStatus status,
                                            Instant morningReportPostedAt) {
        return new OvernightManifest(
                "test-run", "测试使命", status,
                STARTED, TARGET_WAKE, HANDOFF_READY, GRACE_UNTIL,
                morningReportPostedAt, null, null, STARTED,
                "/var/run/overnight/test-run", -1, (byte) 2
        );
    }

    @Test
    void compute_beforeHandoffReady_returnsRunning() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.RUNNING, null);
        Instant now = T0.plusSeconds(3600); // +1h，早于 handoffReady(+3.5h)

        assertEquals(OvernightPhase.RUNNING, OvernightPhase.compute(m, now));
    }

    @Test
    void compute_atHandoffReady_returnsWindDown() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.RUNNING, null);
        // exactly at handoffReady → 不再 before → WIND_DOWN
        assertEquals(OvernightPhase.WIND_DOWN, OvernightPhase.compute(m, HANDOFF_READY));
    }

    @Test
    void compute_betweenHandoffAndTarget_returnsWindDown() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.RUNNING, null);
        Instant now = T0.plusSeconds(3 * 3600 + 2700); // +3h45m

        assertEquals(OvernightPhase.WIND_DOWN, OvernightPhase.compute(m, now));
    }

    @Test
    void compute_atTargetWakeWithoutReport_returnsMorningReport() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.RUNNING, null);
        // at targetWake, no report posted → MORNING_REPORT
        assertEquals(OvernightPhase.MORNING_REPORT, OvernightPhase.compute(m, TARGET_WAKE));
    }

    @Test
    void compute_afterTargetWithReport_returnsPostWake() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.RUNNING, REPORT_POSTED);
        Instant now = T0.plusSeconds(4 * 3600 + 600); // target+10min, before grace

        assertEquals(OvernightPhase.POST_WAKE, OvernightPhase.compute(m, now));
    }

    @Test
    void compute_afterGrace_returnsFinalizing() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.RUNNING, REPORT_POSTED);
        // at grace_until → no longer before → FINALIZING
        assertEquals(OvernightPhase.FINALIZING, OvernightPhase.compute(m, GRACE_UNTIL));
    }

    @Test
    void compute_afterGraceByOneSecond_returnsFinalizing() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.RUNNING, REPORT_POSTED);
        Instant now = GRACE_UNTIL.plusSeconds(1);

        assertEquals(OvernightPhase.FINALIZING, OvernightPhase.compute(m, now));
    }

    @Test
    void compute_completedStatus_returnsCompleted() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.COMPLETED, null);
        assertEquals(OvernightPhase.COMPLETED, OvernightPhase.compute(m, T0));
    }

    @Test
    void compute_failedStatus_returnsFailed() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.FAILED, null);
        assertEquals(OvernightPhase.FAILED, OvernightPhase.compute(m, T0));
    }

    @Test
    void compute_cancelRequestedStatus_returnsCancelling() {
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.CANCEL_REQUESTED, null);
        assertEquals(OvernightPhase.CANCELLING, OvernightPhase.compute(m, T0));
    }

    @Test
    void compute_cancelRequestedDuringRunning_returnsCancelling() {
        // 即使时间在 running 区间，CANCEL_REQUESTED 优先返回 CANCELLING
        OvernightManifest m = buildManifest(OvernightManifest.OvernightRunStatus.CANCEL_REQUESTED, null);
        Instant now = T0.plusSeconds(3600); // +1h，本应是 RUNNING

        assertEquals(OvernightPhase.CANCELLING, OvernightPhase.compute(m, now));
    }

    @Test
    void isActive_shouldReturnTrueForRunningPhases() {
        assertTrue(OvernightPhase.RUNNING.isActive());
        assertTrue(OvernightPhase.WIND_DOWN.isActive());
        assertTrue(OvernightPhase.MORNING_REPORT.isActive());
        assertTrue(OvernightPhase.POST_WAKE.isActive());
        assertTrue(OvernightPhase.FINALIZING.isActive());
    }

    @Test
    void isActive_shouldReturnFalseForTerminalPhases() {
        assertFalse(OvernightPhase.COMPLETED.isActive());
        assertFalse(OvernightPhase.FAILED.isActive());
        assertFalse(OvernightPhase.CANCELLING.isActive());
    }

    @Test
    void isTerminal_shouldReturnTrueForCompletedAndFailed() {
        assertTrue(OvernightPhase.COMPLETED.isTerminal());
        assertTrue(OvernightPhase.FAILED.isTerminal());
        assertFalse(OvernightPhase.RUNNING.isTerminal());
        assertFalse(OvernightPhase.CANCELLING.isTerminal());
    }

    @Test
    void formatMinutes_shouldFormatCorrectly() {
        assertEquals("0m", OvernightPhase.formatMinutes(0));
        assertEquals("45m", OvernightPhase.formatMinutes(45));
        assertEquals("1h", OvernightPhase.formatMinutes(60));
        assertEquals("1h 30m", OvernightPhase.formatMinutes(90));
        assertEquals("2h", OvernightPhase.formatMinutes(120));
        assertEquals("1d", OvernightPhase.formatMinutes(1440));
        assertEquals("1d 2h", OvernightPhase.formatMinutes(1560));
    }

    @Test
    void formatMinutes_shouldClampNegativeToZero() {
        assertEquals("0m", OvernightPhase.formatMinutes(-10));
        assertEquals("0m", OvernightPhase.formatMinutes(-1));
    }

    @Test
    void code_shouldReturnEnglishLabel() {
        assertEquals("running", OvernightPhase.RUNNING.code());
        assertEquals("wind-down", OvernightPhase.WIND_DOWN.code());
        assertEquals("morning report", OvernightPhase.MORNING_REPORT.code());
        assertEquals("post-wake", OvernightPhase.POST_WAKE.code());
        assertEquals("finalizing", OvernightPhase.FINALIZING.code());
    }

    @Test
    void cnLabel_shouldReturnChineseLabel() {
        assertEquals("调度期", OvernightPhase.RUNNING.cnLabel());
        assertEquals("收尾期", OvernightPhase.WIND_DOWN.cnLabel());
        assertEquals("汇报期", OvernightPhase.MORNING_REPORT.cnLabel());
        assertEquals("宽限期", OvernightPhase.POST_WAKE.cnLabel());
        assertEquals("终结", OvernightPhase.FINALIZING.cnLabel());
    }
}
