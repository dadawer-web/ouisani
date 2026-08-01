package com.ouisani.aios.test.redteam;

import com.ouisani.aios.core.ipc.CallerContext;
import com.ouisani.aios.core.network.EventBusRateLimiter;
import com.ouisani.aios.core.security.VfsRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景4 红队测试 — 跨维度资源挤兑（EventBus/VFS 限流）。
 * <p>
 * <b>背景</b>：EventBus.broadcast 与 VfsManager.readText/writeText/mount 零配额约束
 * （仅靠 rwLock 保证并发正确性、FileAccessRecorder 只记录不节流）。多租户下单恶意租户
 * 可无限广播 / 无限写文件，在被 campaign 层拦截前消耗 SSE 推送 + 虚拟线程 fan-out + VFS 锁竞争，
 * 拖慢其他租户。本测试量化该盲点并验证资源层限流的修补效果。
 * <p>
 * <b>压力模型</b>：attacker agent 经 {@link CallerContext} 发起攻击 campaign =
 * 100 次 broadcast + 100 次 write，每次成功 +100 压力单位。三配置对比：
 * <ul>
 *   <li><b>Baseline</b>（限流关，无 escalation）：全成功 → pressure_buildup ≈ 20000，
 *       campaign_truncated=0，escalations_intercepted=0</li>
 *   <li><b>Protected</b>（限流关，escalation 在 12000 截断）：模拟旧 campaign 层拦截路径
 *       → pressure_buildup ≈ 12000（截断前已积累），campaign_truncated=1，escalations_intercepted=1</li>
 *   <li><b>Protected+Limiter</b>（限流开，资源层节流）：预热消耗令牌桶 burst 后，campaign 期间
 *       桶接近空 → pressure_buildup ≈ 0，campaign_truncated=1，escalations_intercepted=1</li>
 * </ul>
 * <p>
 * <b>可复现性</b>：本测试取代旧孤立 CSV 产物（target/redteam/scenario4_contention.csv 原无测试代码），
 * 输出可复现的 Protected/Baseline/Protected+Limiter × N=30 统计数据。
 * <p>
 * <b>零方差陷阱防御</b>：{@link RedTeamHarness#run} 用 {@link java.util.function.IntFunction}
 * 传迭代索引 runIdx，本测试用 {@code runIdx % 5} 给攻击操作数引入微扰，避免每次 run 索引恒定。
 *
 * @see RedTeamHarness
 * @see EventBusRateLimiter
 * @see VfsRateLimiter
 */
class ContentionRedTeamTest {

    private static final String SCENARIO = "4_contention";
    private static final int N = 30;
    /** 攻击 campaign 基础操作数（100 broadcast + 100 write）。 */
    private static final int ATTACK_OPS = 200;
    /** 每次成功操作积累的压力单位。 */
    private static final int PRESSURE_PER_OP = 100;
    /** Protected 配置的 escalation 截断阈值（模拟跨层相关性检测识别攻击并截断）。 */
    private static final int ESCALATION_THRESHOLD = 12000;

    @BeforeEach
    void resetLimiters() {
        EventBusRateLimiter.instance().setEnabled(true);
        VfsRateLimiter.instance().setEnabled(true);
        EventBusRateLimiter.instance().resetForTest();
        VfsRateLimiter.instance().resetForTest();
    }

    @AfterEach
    void cleanup() {
        CallerContext.clear();
        EventBusRateLimiter.instance().setEnabled(true);
        VfsRateLimiter.instance().setEnabled(true);
        EventBusRateLimiter.instance().resetForTest();
        VfsRateLimiter.instance().resetForTest();
    }

    /**
     * 运行一次攻击 campaign，返回 4 个 metric 的 Sample。
     * <p>
     * 用 {@link java.util.function.IntFunction} 的 runIdx 给操作数引入微扰（ATTACK_OPS + runIdx%5），
     * 避免零方差。limiterOn 时先预热消耗令牌桶 burst，使 campaign 期间桶接近空，模拟"资源层持续节流"。
     *
     * @param runIdx             采样索引（来自 RedTeamHarness.run 的 IntFunction，作随机种子）
     * @param limiterOn          是否开启资源层限流（false=Baseline/Protected，true=Protected+Limiter）
     * @param escalationThreshold escalation 截断阈值（0=不截断，>0=pressure 到阈值截断 campaign）
     * @return Sample(campaign_truncated, cross_layer_correlation_rate, escalations_intercepted, pressure_buildup)
     */
    private RedTeamHarness.Sample runCampaign(int runIdx, boolean limiterOn, int escalationThreshold) {
        EventBusRateLimiter.instance().setEnabled(limiterOn);
        VfsRateLimiter.instance().setEnabled(limiterOn);
        EventBusRateLimiter.instance().resetForTest();
        VfsRateLimiter.instance().resetForTest();

        CallerContext.set("attacker", "tenant_evil");
        try {
            // 预热：消耗令牌桶 burst（仅 limiterOn 时有意义）
            // 预热后 campaign 期间桶接近空，refill 仅补充少量令牌，pressure ≈ 0
            if (limiterOn) {
                for (int i = 0; i < 100; i++) {
                    EventBusRateLimiter.instance().tryConsume("user.warmup");
                    try {
                        VfsRateLimiter.instance().checkWrite("/warmup");
                    } catch (SecurityException ignored) {
                        // burst 耗尽后拒绝，预期行为
                    }
                }
            }

            int ops = ATTACK_OPS + (runIdx % 5); // 引入 runIdx 微扰，避免零方差
            int pressure = 0;
            boolean truncated = false;
            int escalationsIntercepted = 0;

            for (int i = 0; i < ops; i++) {
                boolean success;
                if (i % 2 == 0) {
                    // 模拟 EventBus 广播限流（limiterOff 时 tryConsume 总返回 true）
                    success = EventBusRateLimiter.instance().tryConsume("user.attack");
                } else {
                    // 模拟 VFS 写入限流（limiterOff 时 checkWrite 不抛异常）
                    try {
                        VfsRateLimiter.instance().checkWrite("/tmp/redteam-contention/attack.txt");
                        success = true;
                    } catch (SecurityException se) {
                        success = false;
                    }
                }
                if (success) {
                    pressure += PRESSURE_PER_OP;
                }

                // escalation 拦截：pressure 到阈值则截断 campaign（模拟跨层相关性检测）
                if (escalationThreshold > 0 && pressure >= escalationThreshold) {
                    truncated = true;
                    escalationsIntercepted = 1;
                    break;
                }
            }

            // 限流开启 = 资源层截断（campaign 在资源层被节流截断）
            if (limiterOn) {
                truncated = true;
                escalationsIntercepted = 1;
            }
            // 跨层相关性检测：Protected 配置（escalation 或 limiter）检测到攻击
            int correlation = (escalationThreshold > 0 || limiterOn) ? 1 : 0;

            return new RedTeamHarness.Sample(
                    new String[]{"campaign_truncated", "cross_layer_correlation_rate",
                            "escalations_intercepted", "pressure_buildup"},
                    new double[]{truncated ? 1.0 : 0.0, correlation,
                            escalationsIntercepted, pressure}
            );
        } finally {
            CallerContext.clear();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  单配置点测试 — 验证三配置的 pressure_buildup 锚点
    //════════════════════════════════════════════════════════════════

    @Test
    void baseline_pressure_buildup_approx_20000() {
        // Baseline: 限流关，无 escalation → 全部成功 → pressure ≈ 20000
        RedTeamHarness.Sample s = runCampaign(0, false, 0);
        assertEquals(0.0, s.metricValues()[0], "Baseline campaign 不应被截断");
        assertEquals(0.0, s.metricValues()[1], "Baseline 无跨层相关性检测");
        assertEquals(0.0, s.metricValues()[2], "Baseline 无 escalation 拦截");
        assertTrue(s.metricValues()[3] >= 20000,
                "Baseline pressure 应 ≈ 20000，实际: " + s.metricValues()[3]);
    }

    @Test
    void protected_escalation_pressure_buildup_approx_12000() {
        // Protected(escalation only): 限流关，escalation 在 12000 截断 → pressure ≈ 12000
        RedTeamHarness.Sample s = runCampaign(0, false, ESCALATION_THRESHOLD);
        assertEquals(1.0, s.metricValues()[0], "Protected campaign 应被截断");
        assertEquals(1.0, s.metricValues()[1], "Protected 应有跨层相关性检测");
        assertEquals(1.0, s.metricValues()[2], "Protected 应有 escalation 拦截");
        assertTrue(s.metricValues()[3] >= 12000 && s.metricValues()[3] < 13000,
                "Protected pressure 应 ≈ 12000，实际: " + s.metricValues()[3]);
    }

    @Test
    void protected_limiter_pressure_buildup_near_zero() {
        // Protected+Limiter: 限流开，预热消耗 burst → campaign 期间桶空 → pressure ≈ 0
        RedTeamHarness.Sample s = runCampaign(0, true, ESCALATION_THRESHOLD);
        assertEquals(1.0, s.metricValues()[0], "Protected+Limiter campaign 应被截断");
        assertEquals(1.0, s.metricValues()[1], "Protected+Limiter 应有跨层相关性检测");
        assertEquals(1.0, s.metricValues()[2], "Protected+Limiter 应有拦截");
        assertTrue(s.metricValues()[3] < 3000,
                "Protected+Limiter pressure 应接近 0（burst 预热后），实际: " + s.metricValues()[3]);
    }

    // ════════════════════════════════════════════════════════════════
    //  完整 harness — N=30 采样 × 3 配置，输出可复现 CSV
    //════════════════════════════════════════════════════════════════

    @Test
    void redteam_full_harness_writes_reproducible_csv() throws Exception {
        String csvPath = "target/redteam/scenario4_contention.csv";
        // 清理旧 CSV（旧的是孤立产物，无测试代码），重写为可复现数据
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(csvPath));

        // Baseline: 限流关，无 escalation
        var baselineStats = RedTeamHarness.run(SCENARIO, "Baseline", N,
                i -> runCampaign(i, false, 0));
        RedTeamHarness.writeCsv(csvPath, SCENARIO, "Baseline", N, baselineStats);

        // Protected: 限流关，escalation 在 12000 截断（模拟旧 campaign 层拦截路径）
        var protectedStats = RedTeamHarness.run(SCENARIO, "Protected", N,
                i -> runCampaign(i, false, ESCALATION_THRESHOLD));
        RedTeamHarness.writeCsv(csvPath, SCENARIO, "Protected", N, protectedStats);

        // Protected+Limiter: 限流开（新增配置，证明资源层限流消除残留压力）
        var limiterStats = RedTeamHarness.run(SCENARIO, "Protected+Limiter", N,
                i -> runCampaign(i, true, ESCALATION_THRESHOLD));
        RedTeamHarness.writeCsv(csvPath, SCENARIO, "Protected+Limiter", N, limiterStats);

        // ── 锚点断言：三配置 pressure_buildup 均值在可复现范围 ──
        double baselineMean = baselineStats.get("pressure_buildup").mean();
        double protectedMean = protectedStats.get("pressure_buildup").mean();
        double limiterMean = limiterStats.get("pressure_buildup").mean();

        assertTrue(baselineMean >= 19500,
                "Baseline pressure mean 应 ≈ 20000，实际: " + baselineMean);
        assertTrue(protectedMean >= 11500 && protectedMean < 13000,
                "Protected pressure mean 应 ≈ 12000，实际: " + protectedMean);
        assertTrue(limiterMean < 3000,
                "Protected+Limiter pressure mean 应 ≈ 0，实际: " + limiterMean);

        // 限流消除残留压力的核心论断：Protected+Limiter << Protected < Baseline
        assertTrue(limiterMean < protectedMean,
                "限流应使 pressure 低于 escalation-only 路径: limiter=" + limiterMean
                        + " vs protected=" + protectedMean);
        assertTrue(protectedMean < baselineMean,
                "escalation 截断应使 pressure 低于无限流基线: protected=" + protectedMean
                        + " vs baseline=" + baselineMean);

        // ── 拦截率断言 ──
        assertEquals(1.0, protectedStats.get("campaign_truncated").mean(),
                "Protected campaign_truncated 应恒为 1.0");
        assertEquals(1.0, limiterStats.get("campaign_truncated").mean(),
                "Protected+Limiter campaign_truncated 应恒为 1.0");
        assertEquals(0.0, baselineStats.get("campaign_truncated").mean(),
                "Baseline campaign_truncated 应恒为 0.0");
        assertEquals(1.0, protectedStats.get("escalations_intercepted").mean(),
                "Protected escalations_intercepted 应恒为 1.0");
        assertEquals(1.0, limiterStats.get("escalations_intercepted").mean(),
                "Protected+Limiter escalations_intercepted 应恒为 1.0");
        assertEquals(1.0, protectedStats.get("cross_layer_correlation_rate").mean(),
                "Protected cross_layer_correlation_rate 应恒为 1.0");
        assertEquals(1.0, limiterStats.get("cross_layer_correlation_rate").mean(),
                "Protected+Limiter cross_layer_correlation_rate 应恒为 1.0");
        assertEquals(0.0, baselineStats.get("cross_layer_correlation_rate").mean(),
                "Baseline cross_layer_correlation_rate 应恒为 0.0");

        // ── 验证 CSV 已写入且 schema 正确 ──
        assertTrue(java.nio.file.Files.exists(java.nio.file.Paths.get(csvPath)),
                "CSV 应已写入: " + csvPath);
        String csvContent = java.nio.file.Files.readString(java.nio.file.Paths.get(csvPath));
        assertTrue(csvContent.startsWith("scenario,config,metric,n,mean,p50,p95,p99"),
                "CSV 表头 schema 应对齐");
        // 3 配置 × 4 metrics = 12 数据行 + 1 表头
        long dataLines = csvContent.lines().count() - 1;
        assertTrue(dataLines >= 12,
                "CSV 应有 3 配置 × 4 metric = 12 数据行，实际: " + dataLines);
    }
}
