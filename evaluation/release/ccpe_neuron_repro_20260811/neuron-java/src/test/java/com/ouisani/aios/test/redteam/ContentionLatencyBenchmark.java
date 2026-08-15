package com.ouisani.aios.test.redteam;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.ipc.CallerContext;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.network.EventBusRateLimiter;
import com.ouisani.aios.core.security.VfsRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景4 真实并发延迟基准 — 测量 benign 租户在攻击挤兑下的<b>真实墙钟延迟</b>。
 * <p>
 * <b>动机</b>：旧 {@link ContentionRedTeamTest} 用计数器累加人造 pressure，再用线性公式
 * {@code p95 = 12 + 0.004 × pressure} 把 pressure "算"成延迟——这是思想实验，不是测量。
 * 本基准废弃线性推导：并发攻击者真实调用 {@link VfsManager#writeText} + {@link EventBus#broadcast}，
 * 同时 benign 租户真实调用 {@link VfsManager#readText}，用 {@link System#nanoTime()} 测每次读的墙钟延迟。
 * <p>
 * <b>真实信号的代码根因</b>（已核实）：{@code readText→resolve} 获取全局 {@code rwLock.readLock()}，
 * {@code writeTextInternal} 获取同一全局 {@code rwLock.writeLock()}。ReentrantReadWriteLock 非公平模式
 * 下，持续写锁会饿死读锁。
 * <ul>
 *   <li><b>Baseline</b>（限流关）：攻击者 writeText 全成功 → 持续持有写锁 → benign 读锁阻塞 → 延迟升高</li>
 *   <li><b>Coupled</b>（限流开）：攻击者 {@code checkWrite} 在 burst 耗尽后于获取写锁<b>前</b>抛
 *       SecurityException → 写锁空闲 → benign 读锁不阻塞 → 延迟低</li>
 *   <li><b>Permission-only</b>（限流关 + campaign 中段截断）：前半窗口攻击者无限流 flood → benign
 *       被阻塞；截断后攻击者停 → 后半窗口 benign 恢复 → 产生双峰分布（攻击期高延迟 + 截断后低延迟）</li>
 * </ul>
 * <p>
 * <b>节流自保机制</b>：benign 调用 readText 的频率被 {@code PACING_MS=15ms} 限制在 ~67 reads/s，
 * 远低于 VfsRateLimiter 的 200 reads/s 上限。这保证 benign 永不自节流——SecurityException 只应
 * 来自攻击者侧。所有 benign 读取的延迟都是真实锁竞争测量值，包含 JVM 调度/GC/锁竞争的真实方差。
 * <p>
 * <b>Permission-only 双峰分布</b>：因截断在采样中点，per-run p95（第 28/30 样本）落入"截断后"快区，
 * 不能区分配置。故<b>论文报告 POOLED 统计</b>（mean/stddev/p95/p99 全部基于同一 900 样本池），
 * CDF 用全量样本池化绘制（30 runs × 30 samples = 900 样本/config），双峰分布直观可见。
 * <p>
 * <b>统计方法</b>：所有 4 个指标（mean, stddev, p95, p99）均在同一 900 个原始样本上计算，
 * 不做 trimming。JVM GC 暂停是 Java 内核架构的固有开销，必须如实包含在统计中，
 * 而非以 trimmed mean 隐去。混用 trimmed mean + untrimmed percentile 会造成分布不一致。
 * <p>
 * <b>输出</b>：
 * <ul>
 *   <li>{@code target/redteam/scenario4_latency.csv} — 每轮 per-config 摘要（mean/p50/p95/p99/stddev）</li>
 *   <li>{@code target/redteam/scenario4_latency_raw.jsonl} — 全量延迟样本，供 CDF 绘图</li>
 * </ul>
 *
 * @see ContentionRedTeamTest 旧的仿真版本（保留作 pressure 锚点断言）
 */
class ContentionLatencyBenchmark {

    private static final int N = 30;                  // 每配置重复轮数
    private static final int TARGET_SAMPLES = 30;     // 每轮 benign 读取次数
    private static final long MAX_WINDOW_MS = 3000;   // 硬性时间上限（防饿死卡死）
    private static final long PACING_MS = 15;         // benign 读取间隔 → ~67 reads/s，远低于 200/s 上限
    private static final long PACING_NS = PACING_MS * 1_000_000L;
    private static final int ATTACKER_THREADS = 4;
    /** 攻击 writeText 负载（2KB）—— 真实文件写入，加重写锁持有时间。 */
    private static final String ATTACK_PAYLOAD = "x".repeat(2048);
    private static final String BENIGN_PATH = "/vfs-bench/benign/data.txt";
    private static final String ATTACK_PATH = "/vfs-bench/attack/payload.txt";
    private static final String CSV_PATH = "target/redteam/scenario4_latency.csv";
    private static final String RAW_PATH = "target/redteam/scenario4_latency_raw.jsonl";

    /** 三配置：limiterOn=限流开关；truncateAtSample>0 表示 campaign 在此样本点截断（Permission-only）。 */
    enum Config {
        BASELINE("Baseline", false, Integer.MAX_VALUE),
        PERMISSION_ONLY("Permission-only", false, TARGET_SAMPLES / 2),
        COUPLED("Coupled", true, Integer.MAX_VALUE);

        final String label;
        final boolean limiterOn;
        final int truncateAtSample;

        Config(String l, boolean lo, int t) { label = l; limiterOn = lo; truncateAtSample = t; }
    }

    @BeforeAll
    static void initVfs() {
        VfsManager.instance().init();
        VfsRateLimiter.instance().setEnabled(false);
        EventBusRateLimiter.instance().setEnabled(false);
        try {
            CallerContext.clear(); // null context → 限流器豁免
            VfsManager.instance().writeText(BENIGN_PATH,
                    "benign data payload for co-resident latency measurement");
        } finally {
            CallerContext.clear();
        }
        // 注册一个真实 EventBus 订阅者：做 hash fan-out 工作，使 broadcast 有真实处理成本
        EventBus.instance().subscribe("user.attack", payload -> {
            int h = 0;
            for (int i = 0; i < payload.length(); i++) h = 31 * h + payload.charAt(i);
            // 防止死代码消除
            if (h == Integer.MIN_VALUE) System.nanoTime();
        });
    }

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
     * 运行一轮：并发攻击者 flood + benign 租户采集 TARGET_SAMPLES 次计时 readText。
     * <p>
     * benign 以 PACING_MS 间隔发起读取（~67 reads/s，远低于 200/s 限流上限），确保永不自节流。
     * Permission-only 在 benign 采集到第 truncateAtSample 个样本时停止攻击者（模拟 escalation 截断）。
     *
     * @return benign 每次读的墙钟延迟数组（ms）
     */
    private double[] runOnce(int runIdx, Config cfg) {
        EventBusRateLimiter.instance().setEnabled(cfg.limiterOn);
        VfsRateLimiter.instance().setEnabled(cfg.limiterOn);
        EventBusRateLimiter.instance().resetForTest();
        VfsRateLimiter.instance().resetForTest();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean attackerStop = new AtomicBoolean(false);
        long windowStart = System.nanoTime();
        long maxWindowNs = MAX_WINDOW_MS * 1_000_000L;

        // ── 攻击者线程：flood writeText(2KB) + broadcast ──
        List<Thread> attackers = new ArrayList<>(ATTACKER_THREADS);
        for (int t = 0; t < ATTACKER_THREADS; t++) {
            final int tid = t;
            Thread atk = Thread.ofPlatform().name("attacker-" + tid).start(() -> {
                CallerContext.set("attacker_" + tid, "tenant_evil");
                try {
                    int seq = 0;
                    while (!stop.get() && !attackerStop.get()) {
                        seq++;
                        try {
                            VfsManager.instance().writeText(ATTACK_PATH, ATTACK_PAYLOAD + seq);
                        } catch (SecurityException ignored) {
                            // Coupled：源节流 — 预期；限流关时不会走到此分支
                        }
                        try {
                            EventBus.instance().broadcast("user.attack", "payload-" + seq);
                        } catch (Exception ignored) {
                            // broadcast fire-and-forget
                        }
                    }
                } finally {
                    CallerContext.clear();
                }
            });
            attackers.add(atk);
        }

        // ── benign 租户：采集 TARGET_SAMPLES 次 readText，带 PACING_MS 节流 ──
        List<Double> samples = new ArrayList<>(TARGET_SAMPLES);
        CallerContext.set("benign", "tenant_good");
        long nextReadTime = System.nanoTime();
        try {
            while (samples.size() < TARGET_SAMPLES
                    && (System.nanoTime() - windowStart) < maxWindowNs) {
                // Permission-only：采集到截断点时停止攻击者（escalation 截断 campaign）
                if (samples.size() >= cfg.truncateAtSample) {
                    attackerStop.set(true);
                }
                // 节流：确保读取起始间隔 ≥ PACING_MS（→ ~67 reads/s，远低于 200/s 上限）
                long now = System.nanoTime();
                if (now < nextReadTime) {
                    sleepNs(nextReadTime - now);
                }
                long t0 = System.nanoTime();
                VfsManager.instance().readText(BENIGN_PATH);
                long t1 = System.nanoTime();
                samples.add((t1 - t0) / 1_000_000.0); // ns → ms
                nextReadTime = t0 + PACING_NS;
            }
        } finally {
            CallerContext.clear();
            stop.set(true);
            attackerStop.set(true);
            for (Thread a : attackers) {
                try { a.join(2000); } catch (InterruptedException ignored) {}
            }
        }
        double[] lat = new double[samples.size()];
        for (int i = 0; i < lat.length; i++) lat[i] = samples.get(i);
        return lat;
    }

    private static void sleepNs(long ns) {
        if (ns <= 0) return;
        long ms = ns / 1_000_000L;
        int remNanos = (int) (ns % 1_000_000L);
        try {
            Thread.sleep(ms, remNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double mean(double[] xs) {
        double s = 0; for (double x : xs) s += x; return s / xs.length;
    }

    private static double stddev(double[] xs) {
        if (xs.length < 2) return 0.0;
        double m = mean(xs), s = 0; for (double x : xs) s += (x - m) * (x - m);
        return Math.sqrt(s / (xs.length - 1));
    }

    private static double percentileSorted(double[] sortedAsc, double q) {
        if (sortedAsc.length == 0) return 0.0;
        if (sortedAsc.length == 1) return sortedAsc[0];
        double pos = q * (sortedAsc.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        if (lo == hi) return sortedAsc[lo];
        return sortedAsc[lo] * (hi - pos) + sortedAsc[hi] * (pos - lo);
    }

    @Test
    void measureBenignLatencyUnderContention() throws Exception {
        Path csv = Paths.get(CSV_PATH);
        Path raw = Paths.get(RAW_PATH);
        Files.createDirectories(csv.getParent());
        Files.deleteIfExists(csv);
        Files.deleteIfExists(raw);

        StringBuilder csvOut = new StringBuilder();
        csvOut.append("config,run_idx,sample_count,benign_mean_ms,benign_p50_ms,benign_p95_ms,benign_p99_ms,benign_stddev_ms\n");

        var perRunMean = new java.util.HashMap<Config, double[]>();
        var pooled = new java.util.HashMap<Config, java.util.List<Double>>();
        for (Config cfg : Config.values()) {
            perRunMean.put(cfg, new double[N]);
            pooled.put(cfg, new java.util.ArrayList<>());
        }

        // warmup：1 轮不计入，触发 JIT
        runOnce(0, Config.COUPLED);

        for (Config cfg : Config.values()) {
            for (int i = 0; i < N; i++) {
                double[] lat = runOnce(i, cfg);
                for (double v : lat) pooled.get(cfg).add(v);
                double[] sorted = lat.clone();
                Arrays.sort(sorted);
                // Per-run stats (for CSV diagnostics only). The paper reports POOLED stats below.
                double m = mean(sorted);
                double sd = stddev(sorted);
                double p50 = percentileSorted(sorted, 0.50);
                double p95 = percentileSorted(sorted, 0.95);
                double p99 = percentileSorted(sorted, 0.99);
                perRunMean.get(cfg)[i] = m;

                csvOut.append(String.format(Locale.US, "%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                        cfg.label, i, lat.length, m, p50, p95, p99, sd));

                StringBuilder rb = new StringBuilder();
                rb.append("{\"config\":\"").append(cfg.label)
                  .append("\",\"run_idx\":").append(i).append(",\"samples\":[");
                for (int j = 0; j < lat.length; j++) {
                    if (j > 0) rb.append(',');
                    rb.append(String.format(Locale.US, "%.6f", lat[j]));
                }
                rb.append("]}\n");
                Files.writeString(raw, rb.toString(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        }

        Files.writeString(csv, csvOut.toString());

        // ── POOLED statistics: all 4 metrics over the exact same 900 raw samples per config ──
        // No trimming, no mixed distributions. JVM GC pauses are inherent overhead of a Java
        // kernel and are included honestly. Mean/StdDev/P95/P99 all come from one pooled array.
        System.out.println("[ContentionLatency] POOLED statistics (900 raw samples per config, NO trimming):");
        var pooledStats = new java.util.HashMap<Config, double[]>(); // [mean, stddev, p50, p95, p99]
        for (Config cfg : Config.values()) {
            double[] pooledArr = pooled.get(cfg).stream().mapToDouble(Double::doubleValue).toArray();
            double[] sorted = pooledArr.clone();
            Arrays.sort(sorted);
            double m = mean(sorted);
            double sd = stddev(sorted);
            double p50 = percentileSorted(sorted, 0.50);
            double p95 = percentileSorted(sorted, 0.95);
            double p99 = percentileSorted(sorted, 0.99);
            pooledStats.put(cfg, new double[]{m, sd, p50, p95, p99});
            System.out.printf(Locale.US,
                    "  %-16s POOLED: mean=%.4f ms  stddev=%.4f ms  p50=%.4f  p95=%.4f  p99=%.4f  (n=%d)%n",
                    cfg.label, m, sd, p50, p95, p99, pooledArr.length);
        }

        double baselineMean = pooledStats.get(Config.BASELINE)[0];
        double permMean = pooledStats.get(Config.PERMISSION_ONLY)[0];
        double coupledMean = pooledStats.get(Config.COUPLED)[0];
        double baselineP95 = pooledStats.get(Config.BASELINE)[3];
        double coupledP95 = pooledStats.get(Config.COUPLED)[3];
        double baselineP99 = pooledStats.get(Config.BASELINE)[4];
        double coupledP99 = pooledStats.get(Config.COUPLED)[4];
        System.out.printf(Locale.US,
                "[ContentionLatency] Mean  — Baseline/Coupled = %.2fx  Permission/Coupled = %.2fx%n",
                baselineMean / Math.max(coupledMean, 1e-9),
                permMean / Math.max(coupledMean, 1e-9));
        System.out.printf(Locale.US,
                "[ContentionLatency] P95   — Baseline/Coupled = %.2fx   P99 — Baseline/Coupled = %.2fx%n",
                baselineP95 / Math.max(coupledP95, 1e-9),
                baselineP99 / Math.max(coupledP99, 1e-9));

        // 核心断言（基于 pooled mean）：Coupled < Permission-only < Baseline（真实源节流信号）
        assertTrue(coupledMean < permMean,
                "Coupled pooled mean 应低于 Permission-only: coupled=" + coupledMean + " perm=" + permMean);
        assertTrue(permMean < baselineMean,
                "Permission-only pooled mean 应低于 Baseline: perm=" + permMean + " baseline=" + baselineMean);

        System.out.println("[ContentionLatency] CSV → " + csv.toAbsolutePath());
        System.out.println("[ContentionLatency] RAW → " + raw.toAbsolutePath());
    }
}
