package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.overnight.OvernightManifest.OvernightRunStatus;
import com.ouisani.aios.core.permission.PermissionChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Overnight catch-up 单元测试 — 验证 run-once-catch-up + skip-on-overlap
 * （借鉴 OpenWorker automation/scheduler.py:64-68, 92-95）。
 * <p>
 * {@link OvernightRunner#selectCatchUpCandidate} 是 catch-up 决策核心（扫描 overnightDir、
 * 过滤终态/取消、取最新 RUNNING、activeManifest 已设则 skip-on-overlap）。本测试用真实 in-memory
 * {@link VfsManager} 植入 manifest，通过反射重置单例状态隔离用例（与 OvernightSnapshotReplayTest 同模式）。
 * <p>
 * 注：{@link TaskScheduler} 为 final 类，Mockito 5 默认 inline mock maker 可 mock；
 * {@link LlmProvider} 为接口。spawn 被 mock 后不执行 coordinator Runnable，避免触发真实 LLM 循环。
 */
class OvernightCatchUpTest {

    private final OvernightRunner runner = OvernightRunner.instance();
    private String overnightDir;

    @BeforeEach
    void setUp() throws Exception {
        // 每个用例独立 overnightDir（AiosPaths.resolve 每次现读系统属性，不缓存）
        overnightDir = "/test-overnight-" + System.nanoTime();
        System.setProperty("aios.overnight.dir", overnightDir);
        // 初始化 in-memory VFS（与 LearnedSkillDistillerTest 同模式；未 init 时 writeText 静默失败）
        VfsManager.instance().init();
        resetSingleton();
    }

    @AfterEach
    void tearDown() throws Exception {
        // 关闭 supervisor 线程池 + 重置单例，避免污染其它测试
        shutdownSupervisor();
        resetSingleton();
        PermissionChecker.clearGlobalDenialSink();
        System.clearProperty("aios.overnight.dir");
    }

    // ── selectCatchUpCandidate：catch-up 决策逻辑 ──

    @Test
    void selectCatchUpCandidate_picksLatestRunning() {
        Instant t1 = Instant.parse("2026-07-30T01:00:00Z");
        Instant t2 = Instant.parse("2026-07-30T05:00:00Z");  // 更新
        plantManifest(buildManifest("older", t1, OvernightRunStatus.RUNNING));
        plantManifest(buildManifest("newer", t2, OvernightRunStatus.RUNNING));

        OvernightManifest candidate = runner.selectCatchUpCandidate();

        assertNotNull(candidate);
        assertEquals("newer", candidate.runId(), "应选最新 startedAt 的 RUNNING manifest");
    }

    @Test
    void selectCatchUpCandidate_skipsTerminalRuns() {
        plantManifest(buildManifest("done", Instant.parse("2026-07-30T05:00:00Z"),
                OvernightRunStatus.COMPLETED));

        assertNull(runner.selectCatchUpCandidate(), "终态 manifest 不应被 resume");
    }

    @Test
    void selectCatchUpCandidate_skipsWhenActiveManifestSet() throws Exception {
        // skip-on-overlap：已有 active run → 不 resume
        plantManifest(buildManifest("persisted", Instant.parse("2026-07-30T05:00:00Z"),
                OvernightRunStatus.RUNNING));
        setField("activeManifest", buildManifest("active", Instant.now(), OvernightRunStatus.RUNNING));

        assertNull(runner.selectCatchUpCandidate(), "已有 active run 时不应 double-resume");
    }

    @Test
    void selectCatchUpCandidate_finalizesCancelledNotSelected() {
        // CANCEL_REQUESTED → 标记 COMPLETED 并 persist，不 resume
        String runId = "cancelled-run";
        plantManifest(buildManifest(runId, Instant.parse("2026-07-30T05:00:00Z"),
                OvernightRunStatus.CANCEL_REQUESTED));

        assertNull(runner.selectCatchUpCandidate(), "被取消的 run 不应被 resume");

        // 验证 finalize 副作用：持久化的 manifest 已变为 COMPLETED
        String json = VfsManager.instance().readText(overnightDir + "/" + runId + "/manifest.json");
        OvernightManifest reloaded = OvernightManifest.fromJson(json, overnightDir + "/" + runId);
        assertEquals(OvernightRunStatus.COMPLETED, reloaded.status(), "CANCEL_REQUESTED 应被终结为 COMPLETED");
    }

    // ── reloadAndCatchUp 端到端 wiring（Mockito mock TaskScheduler + LlmProvider）──

    @Test
    void reloadAndCatchUp_resumesAndSpawnsCoordinator() {
        TaskScheduler ts = mock(TaskScheduler.class);
        when(ts.nextPid()).thenReturn(7777);
        when(ts.spawn(any(), any(), any())).thenReturn(7777);
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.name()).thenReturn("stub-llm");

        // 植入一个非终态 manifest（targetWakeAt 已过、晨报未发 → resume 后阶段机应补跑晨报）
        plantManifest(buildManifest("interrupted", Instant.parse("2026-07-30T00:00:00Z"),
                OvernightRunStatus.RUNNING));

        // configure 末尾会调 reloadAndCatchUp → selectCatchUpCandidate → resumeRun → spawn
        runner.configure(llm, ts);

        // spawn-not-await 契约：coordinator 被 spawn（mock 不执行 Runnable，无真实 LLM 循环）
        verify(ts, times(1)).spawn(any(), any(), any());
        verify(ts, times(1)).nextPid();
    }

    // ── 辅助 ──

    /** 用规范构造器构造 manifest（显式 startedAt，便于确定性测试）。 */
    private OvernightManifest buildManifest(String runId, Instant startedAt, OvernightRunStatus status) {
        Instant targetWake = startedAt.plus(Duration.ofHours(8));
        Instant handoff = targetWake.minus(Duration.ofMinutes(30));
        Instant grace = targetWake.plus(Duration.ofHours(2));
        return new OvernightManifest(runId, "catch-up 测试使命", status, startedAt, targetWake,
                handoff, grace, null, null, null, startedAt,
                overnightDir + "/" + runId, -1, (byte) 2);
    }

    /** 序列化 manifest 并植入 VFS（overnightDir/{runId}/manifest.json）。 */
    private void plantManifest(OvernightManifest m) {
        String path = m.vfsRunDir() + "/manifest.json";
        VfsManager.instance().writeText(path, runner.manifestToJson(m));
    }

    private void resetSingleton() throws Exception {
        setField("activeManifest", null);
        setField("llmProvider", null);
        setField("taskScheduler", null);
        AtomicBoolean sr = (AtomicBoolean) getField("supervisorRunning");
        sr.set(false);
        // 清理当前线程的 overnight 上下文（resumeRun 会 set）
        Field profile = OvernightRunner.class.getDeclaredField("overnightProfile");
        profile.setAccessible(true);
        ((ThreadLocal<?>) profile.get(null)).remove();
    }

    private void shutdownSupervisor() throws Exception {
        Field ss = OvernightRunner.class.getDeclaredField("supervisorScheduler");
        ss.setAccessible(true);
        ScheduledExecutorService sched = (ScheduledExecutorService) ss.get(runner);
        if (sched != null) {
            sched.shutdownNow();
            ss.set(runner, null);
        }
    }

    private void setField(String name, Object value) throws Exception {
        Field f = OvernightRunner.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(runner, value);
    }

    private Object getField(String name) throws Exception {
        Field f = OvernightRunner.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(runner);
    }
}
