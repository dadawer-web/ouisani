package com.ouisani.aios.test.redteam;

import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.recovery.BudgetAwareRecoveryGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景8 红队测试 — 预算感知熔断：防止经恢复通道的 token 预算耗尽。
 * <p>
 * <b>攻击面</b>：攻击者诱导受害 agent 反复失败触发恢复重试，借重试烧光其 token 预算
 * —— 这是一种"经恢复通道的资源耗尽"，区别于 Direction B 的正面配额攻击。
 * 论文1的固定阈值熔断（{@code CIRCUIT_BREAKER_THRESHOLD=5}）不感知预算：预算见底（85%+）
 * 的 agent 仍被重试 5 次，把剩余 15% 预算浪费在注定失败的尝试上，甚至推到 OOM。
 * <p>
 * <b>对照</b>：
 * <ul>
 *   <li><b>Baseline</b>（论文1复刻）：固定阈值 5，不感知预算 —— 无论预算等级都重试到 5 次</li>
 *   <li><b>Protected</b>（新论文 {@link BudgetAwareRecoveryGate}）：预算见底(85%+)立即跳过重试升级
 *       人类介入；承压(50-84%)收紧到 3 次；健康(&lt;50%)放宽到 5 次</li>
 * </ul>
 * <p>
 * <b>关键论断</b>：在 90% 预算见底时，Protected 浪费 0 token（立即升级），Baseline 仍浪费
 * 5×RETRY_COST —— 证明固定阈值熔断在预算见底时是可被利用的资源耗尽通道，预算感知闸门封死它。
 * <p>
 * <b>模型说明</b>：本测试抽象"agent 反复失败直到熔断升级"的行为 —— Baseline 固定重试到阈值次，
 * Protected 按动态阈值/预算见底提前升级。RETRY_COST 为单次重试的 token 代价（模型值）。
 * <p>
 * <b>零方差防御</b>：{@code runIdx % 3} 决定预算等级（90/60/30%），每次 run 唯一。
 *
 * @see BudgetAwareRecoveryGate
 */
class BudgetAwareCircuitBreakerRedTeamTest {

    private static final String SCENARIO = "8_budget_aware_circuit_breaker";
    private static final int N = 30;
    private static final int FIXED_THRESHOLD = 5;          // 论文1固定熔断阈值
    private static final int RETRY_COST = 500;             // 单次重试的 token 代价
    private static final int[] USAGE_LEVELS = {90, 60, 30}; // 预算等级：见底/承压/健康
    private static final int QUOTA = 10_000;

    private final BudgetAwareRecoveryGate gate = BudgetAwareRecoveryGate.instance();

    /** 构造指定使用率的 cgroup 节点（softLimitRatio=1.0 避免软限噪音）。 */
    private static CgroupNode nodeAt(int usagePercent) {
        CgroupNode node = new CgroupNode("agent_s8_" + usagePercent, QUOTA, null, 1.0);
        if (usagePercent > 0) {
            node.consumeTokens(usagePercent * 100L);
        }
        return node;
    }

    /**
     * 单次攻击采样 —— 模拟 agent 在指定预算等级下反复失败、触发恢复重试直到升级。
     *
     * @param runIdx    采样索引（决定预算等级）
     * @param useGate   true=Protected（预算感知），false=Baseline（固定阈值）
     * @return Sample(wasted_tokens_on_futile_retries, retries_attempted)
     */
    private RedTeamHarness.Sample runAttack(int runIdx, boolean useGate) {
        int usage = USAGE_LEVELS[runIdx % USAGE_LEVELS.length];
        CgroupNode node = nodeAt(usage);

        int retries;
        if (useGate) {
            // Protected：预算感知闸门决定何时升级
            retries = 0;
            int failures = 0;
            int cap = 0; // 安全上限，防逻辑错误死循环
            while (gate.evaluate(node, failures).allowRetry() && cap < 20) {
                retries++;
                failures++; // agent 卡死，每次重试都失败
                cap++;
            }
        } else {
            // Baseline：论文1固定阈值，不感知预算，重试到阈值次
            retries = FIXED_THRESHOLD;
        }

        double wastedTokens = retries * (long) RETRY_COST;
        return new RedTeamHarness.Sample(
                new String[]{"wasted_tokens_on_futile_retries", "retries_attempted"},
                new double[]{wastedTokens, retries}
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  点测试 — 三种预算等级下的锚点
    //════════════════════════════════════════════════════════════════

    @Test
    void baseline_always_retries_5_regardless_of_budget() {
        // Baseline 固定阈值 —— 无论预算等级都重试 5 次
        for (int i = 0; i < 3; i++) {
            assertEquals(5.0, runAttack(i, false).metricValues()[1],
                    "Baseline runIdx=" + i + " 应重试 5 次（固定阈值）");
            assertEquals(2500.0, runAttack(i, false).metricValues()[0],
                    "Baseline runIdx=" + i + " 应浪费 2500 token");
        }
    }

    @Test
    void protected_skips_retry_at_exhausted_budget() {
        // 90% 预算见底 —— Protected 立即升级，0 重试 0 浪费（核心论断）
        RedTeamHarness.Sample s = runAttack(0, true); // runIdx=0 → 90%
        assertEquals(0.0, s.metricValues()[1], "90% 见底应 0 重试");
        assertEquals(0.0, s.metricValues()[0], "90% 见底应 0 浪费 token");
    }

    @Test
    void protected_tightens_to_3_at_stressed_budget() {
        // 60% 承压 —— Protected 收紧到 3 次
        RedTeamHarness.Sample s = runAttack(1, true); // runIdx=1 → 60%
        assertEquals(3.0, s.metricValues()[1], "60% 承压应重试 3 次");
        assertEquals(1500.0, s.metricValues()[0], "60% 承压应浪费 1500 token");
    }

    @Test
    void protected_relaxes_to_5_at_healthy_budget() {
        // 30% 健康 —— Protected 放宽到 5 次（与 Baseline 一致，预算充足时不多干预）
        RedTeamHarness.Sample s = runAttack(2, true); // runIdx=2 → 30%
        assertEquals(5.0, s.metricValues()[1], "30% 健康应重试 5 次");
        assertEquals(2500.0, s.metricValues()[0], "30% 健康应浪费 2500 token（与 Baseline 一致）");
    }

    // ════════════════════════════════════════════════════════════════
    //  完整 harness — N=30 × 2 配置，输出可复现 CSV
    //════════════════════════════════════════════════════════════════

    @Test
    void redteam_full_harness_writes_reproducible_csv() throws Exception {
        String csvPath = "target/redteam/scenario8_budget_aware_circuit_breaker.csv";
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(csvPath));

        // Baseline：论文1固定阈值复刻
        var baselineStats = RedTeamHarness.run(SCENARIO, "Baseline", N, i -> runAttack(i, false));
        RedTeamHarness.writeCsv(csvPath, SCENARIO, "Baseline", N, baselineStats);

        // Protected：预算感知闸门
        var protectedStats = RedTeamHarness.run(SCENARIO, "Protected", N, i -> runAttack(i, true));
        RedTeamHarness.writeCsv(csvPath, SCENARIO, "Protected", N, protectedStats);

        // ── wasted_tokens 锚点 ──
        // N=30，runIdx 0..29：90%/60%/30% 各 10 个
        // Baseline 恒浪费 2500；Protected = (0*10 + 1500*10 + 2500*10)/30 = 4000/3 ≈ 1333.33
        double baselineWasted = baselineStats.get("wasted_tokens_on_futile_retries").mean();
        double protectedWasted = protectedStats.get("wasted_tokens_on_futile_retries").mean();
        assertEquals(2500.0, baselineWasted, 1e-9,
                "Baseline 恒浪费 2500，实际: " + baselineWasted);
        assertEquals(4000.0 / 3.0, protectedWasted, 1e-9,
                "Protected 平均浪费 (0+1500+2500)/3 ≈ 1333.33，实际: " + protectedWasted);

        // ── retries_attempted 锚点 ──
        // Baseline 恒 5；Protected = (0+3+5)/3 = 8/3 ≈ 2.667
        double baselineRetries = baselineStats.get("retries_attempted").mean();
        double protectedRetries = protectedStats.get("retries_attempted").mean();
        assertEquals(5.0, baselineRetries, 1e-9, "Baseline 恒重试 5 次，实际: " + baselineRetries);
        assertEquals(8.0 / 3.0, protectedRetries, 1e-9,
                "Protected 平均重试 (0+3+5)/3 ≈ 2.667，实际: " + protectedRetries);

        // ── 核心论断：Protected 显著低于 Baseline ──
        assertTrue(protectedWasted < baselineWasted,
                "Protected 应显著低于 Baseline 浪费: " + protectedWasted + " vs " + baselineWasted);
        assertTrue(protectedRetries < baselineRetries,
                "Protected 应显著低于 Baseline 重试: " + protectedRetries + " vs " + baselineRetries);
        // 节省比例 ≈ 47%
        double savings = (baselineWasted - protectedWasted) / baselineWasted;
        assertTrue(savings > 0.4, "token 节省应 > 40%，实际: " + (savings * 100) + "%");

        // ── 验证 CSV schema ──
        assertTrue(java.nio.file.Files.exists(java.nio.file.Paths.get(csvPath)),
                "CSV 应已写入: " + csvPath);
        String csvContent = java.nio.file.Files.readString(java.nio.file.Paths.get(csvPath));
        assertTrue(csvContent.startsWith("scenario,config,metric,n,mean,p50,p95,p99"),
                "CSV 表头 schema 应对齐");
        long dataLines = csvContent.lines().count() - 1;
        assertTrue(dataLines >= 4,
                "CSV 应有 2 配置 × 2 metric = 4 数据行，实际: " + dataLines);
    }
}
