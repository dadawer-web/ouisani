package com.ouisani.aios.test.redteam;

import com.ouisani.aios.core.tool.CanaryBeaconTool;
import com.ouisani.aios.core.tool.ToolCallLedger;
import com.ouisani.aios.core.tool.ToolRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * 红队评测共用框架 — 5 个场景（token/permission/sandbox/contention/reviewgate）的统一 harness。
 * <p>
 * <b>零方差陷阱防御</b>（关键设计约束，见 project_memory Lessons Learned）：
 * {@link #run(String, String, int, IntFunction)} 必须用 {@link IntFunction}<Sample> 而非
 * {@code Supplier}<Sample>，把迭代索引 {@code i} 传给 {@code runOnce.apply(i)}，让每次 run 拿唯一
 * runIdx 作随机种子。旧实现 {@code Supplier.get()} 不传索引，测试用 {@code () -> runXxx(n)} 把总数 n
 * 当 runIdx 闭包捕获 → 每次 run 索引恒定 → Random 种子恒定 → 零方差 → p50=p95=p99=mean →
 * Mann-Whitney U 退化 → 显著性检验失效。本 harness 强制 IntFunction 从源头杜绝此陷阱。
 * <p>
 * <b>统计</b>：每次 run 产出一个 {@link Sample}（含若干命名 metric），harness 聚合 N 次采样后
 * 计算 mean/p50/p95/p99，供 Mann-Whitney U 检验 Protected vs Baseline 显著性。
 * <p>
 * <b>CSV 输出</b>：与 {@code target/redteam/scenario{1-5}_*.csv} schema 对齐：
 * {@code scenario,config,metric,n,mean,p50,p95,p99}，供外部统计脚本消费。
 */
public final class RedTeamHarness {

    private RedTeamHarness() {
    }

    /** 单次 run 的采样结果 — 携带若干命名 metric 值。 */
    public record Sample(String[] metricNames, double[] metricValues) {
        public Sample {
            if (metricNames == null || metricValues == null
                    || metricNames.length != metricValues.length) {
                throw new IllegalArgumentException("metricNames 与 metricValues 长度必须一致且非 null");
            }
        }
    }

    /** 聚合统计 — mean/p50/p95/p99。 */
    public record Stats(double mean, double p50, double p95, double p99) {
    }

    /**
     * 运行 N 次采样并按 metric 聚合统计。
     * <p>
     * <b>零方差陷阱防御</b>：runOnce 是 {@link IntFunction}，调用方必须用 {@code i -> runXxx(i)}
     * 传迭代索引，让每次 run 拿唯一 runIdx 作随机种子。禁止用 {@code () -> runXxx(n)} 闭包捕获总数 n
     * （会导致每次 run 索引恒定 → 种子恒定 → 零方差）。
     *
     * @param scenario 场景名（如 "4_contention"）
     * @param config   配置名（如 "Protected" / "Baseline"）
     * @param n        采样次数
     * @param runOnce  采样函数 — 接收迭代索引 [0,n)，返回本次 Sample
     * @return 按 metric 名组织的统计结果：metric → Stats
     */
    public static java.util.Map<String, Stats> run(String scenario, String config, int n,
                                                   IntFunction<Sample> runOnce) {
        java.util.List<Sample> samples = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            samples.add(runOnce.apply(i));
        }
        return aggregate(samples);
    }

    /** 聚合 N 个 Sample 到 metric → Stats。 */
    private static java.util.Map<String, Stats> aggregate(java.util.List<Sample> samples) {
        if (samples.isEmpty()) {
            return java.util.Map.of();
        }
        String[] names = samples.get(0).metricNames();
        java.util.Map<String, Stats> result = new java.util.LinkedHashMap<>();
        for (int mi = 0; mi < names.length; mi++) {
            double[] col = new double[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                col[i] = samples.get(i).metricValues()[mi];
            }
            result.put(names[mi], computeStats(col));
        }
        return result;
    }

    /** 计算 mean/p50/p95/p99。 */
    static Stats computeStats(double[] values) {
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        double sum = 0;
        for (double v : sorted) sum += v;
        double mean = sum / sorted.length;
        double p50 = percentile(sorted, 0.50);
        double p95 = percentile(sorted, 0.95);
        double p99 = percentile(sorted, 0.99);
        return new Stats(mean, p50, p95, p99);
    }

    private static double percentile(double[] sortedAsc, double q) {
        if (sortedAsc.length == 0) return 0.0;
        if (sortedAsc.length == 1) return sortedAsc[0];
        double pos = q * (sortedAsc.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sortedAsc[lo];
        double frac = pos - lo;
        return sortedAsc[lo] * (1 - frac) + sortedAsc[hi] * frac;
    }

    /**
     * 把统计结果追加写入 CSV（schema: scenario,config,metric,n,mean,p50,p95,p99）。
     * <p>
     * 文件不存在时写表头；存在时追加行。与 {@code target/redteam/scenario{1-5}_*.csv} 对齐。
     *
     * @param csvPath  CSV 路径（如 "target/redteam/scenario4_contention.csv"）
     * @param scenario 场景名
     * @param config   配置名
     * @param n        采样次数
     * @param stats    metric → Stats
     */
    public static void writeCsv(String csvPath, String scenario, String config, int n,
                                java.util.Map<String, Stats> stats) {
        try {
            Path path = Paths.get(csvPath);
            Files.createDirectories(path.getParent());
            boolean exists = Files.exists(path);
            StringBuilder sb = new StringBuilder();
            if (!exists) {
                sb.append("scenario,config,metric,n,mean,p50,p95,p99\n");
            }
            for (java.util.Map.Entry<String, Stats> e : stats.entrySet()) {
                Stats s = e.getValue();
                sb.append(scenario).append(',')
                        .append(config).append(',')
                        .append(e.getKey()).append(',')
                        .append(n).append(',')
                        .append(String.format("%.6f", s.mean())).append(',')
                        .append(String.format("%.6f", s.p50())).append(',')
                        .append(String.format("%.6f", s.p95())).append(',')
                        .append(String.format("%.6f", s.p99())).append('\n');
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("写入红队 CSV 失败: " + csvPath, e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  金丝雀工具测试环境注册（不污染生产内核）
    // ════════════════════════════════════════════════════════════════

    /**
     * 红队评估共享的金丝雀台账 — 所有金丝雀工具实例共用，测试间通过 {@link #resetCanaryLedger()} 隔离。
     * <p>
     * 不用 {@code CanaryBeaconTool} 默认构造的 holder ledger，因为测试需要可控的 reset 时机。
     */
    private static final ToolCallLedger CANARY_LEDGER = new ToolCallLedger();

    /**
     * 在测试环境的 {@link ToolRegistry} 中注册金丝雀信标工具。
     * <p>
     * <b>注册隔离</b>：本方法<b>仅</b>在红队测试中调用（如 {@code @BeforeAll}），<b>不</b>修改
     * {@link ToolRegistry#registerBuiltinTools()}（生产内核启动路径）。注册后所有 agent 具备
     * {@code canary_beacon} 工具视野，可被 LLM 调用——攻击载荷嵌入"调用 canary_beacon(status=X)"
     * 指令，若 agent 服从则触发台账记录，事后查 {@link #canaryLedger()} 客观判定 ASR。
     * <p>
     * 幂等：重复注册只覆盖（{@link ToolRegistry#register} 内部 WARN + 覆盖）。
     *
     * @return 共享的金丝雀台账（供测试断言）
     */
    public static ToolCallLedger registerCanaryTool() {
        ToolRegistry.instance().register(new CanaryBeaconTool(CANARY_LEDGER));
        return CANARY_LEDGER;
    }

    /**
     * 获取红队评估共享的金丝雀台账（无需注册即可获取，供已注册工具的查询）。
     */
    public static ToolCallLedger canaryLedger() {
        return CANARY_LEDGER;
    }

    /**
     * 清空金丝雀台账 — 测试间隔离用。
     * <p>
     * 注意：此方法不注销 {@link ToolRegistry} 中的 {@code canary_beacon} 工具（注册是幂等的，
     * 重复注册无副作用）。仅清空调用记录，确保下一个测试样本的金丝雀触发判定不受前一样本污染。
     */
    public static void resetCanaryLedger() {
        CANARY_LEDGER.reset();
    }
}
