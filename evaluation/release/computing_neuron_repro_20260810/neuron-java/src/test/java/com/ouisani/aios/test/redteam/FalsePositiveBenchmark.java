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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实误报率基准 — 在多租户良性负载下测量治理机制错误拦截合法请求的比率。
 * <p>
 * <b>动机</b>：旧 Evaluation §4.5 用 "1,369 单元测试 0 回归" 证明误报率 ≈ 0。但单元测试是
 * 代码层白盒契约，不是真实混杂负载下的误报测量。安全系统的"误报率"定义是：在真实良性请求流中，
 * 多少合法请求被系统错误拦截。本基准废弃单元测试论证，直接驱动多租户良性工作负载，测量真实 FP 率。
 * <p>
 * <b>负载设计</b>：3 租户 × 3 agent = 9 并发良性 agent，每个 agent 以受控速率发起三类操作：
 * <ul>
 *   <li>VFS read：40 ops/s/agent → 120 ops/s/tenant（限流上限 200/s，余量 40%）</li>
 *   <li>VFS write：5 ops/s/agent → 15 ops/s/tenant（限流上限 20/s，余量 25%）</li>
 *   <li>EventBus broadcast：10 ops/s/agent → 30 ops/s/tenant（限流上限 50/s，余量 40%）</li>
 * </ul>
 * 所有操作均为同租户访问（不触发跨租户所有权拦截）、非破坏性（不触发 escalation policy）。
 * 速率刻意设在 per-agent 和 per-tenant 双维度限流上限之下，模拟正常良性 agent 行为。
 * <p>
 * <b>真实方差来源</b>：agent 调度抖动、令牌桶 refill 时序、JVM GC 暂停 → 偶发瞬时 burst 可能
 * 触碰限流边界。FP 率不必恰好为 0 —— 报告真实测量值（含非零方差）比声称 "0 回归" 更可信。
 * <p>
 * <b>检测机制</b>：
 * <ul>
 *   <li>VFS read/write：{@link VfsManager#readText} / {@link VfsManager#writeText} 内部调用
 *       {@link VfsRateLimiter}，超限抛 {@link SecurityException} → catch 即计一次误报</li>
 *   <li>EventBus broadcast：直接调用 {@link EventBusRateLimiter#tryConsume} 检测配额；
 *       返回 false 即计一次误报（broadcast 本身 fire-and-forget 不返回是否丢弃）</li>
 * </ul>
 * <p>
 * <b>输出</b>：{@code target/redteam/false_positive.csv} — 每轮每类操作的 total/blocked/fp_rate。
 *
 * @see VfsRateLimiter
 * @see EventBusRateLimiter
 */
class FalsePositiveBenchmark {

    private static final int N = 10;                  // 重复轮数
    private static final int TENANTS = 3;
    private static final int AGENTS_PER_TENANT = 3;
    private static final int TOTAL_AGENTS = TENANTS * AGENTS_PER_TENANT;
    private static final long DURATION_MS = 3000;

    // Per-agent 目标速率（ops/s）— 刻意设在 per-agent 和 per-tenant 双维度限流上限之下
    private static final int READ_RATE = 40;          // 40/s agent, 120/s tenant (限流 200/s)
    private static final int WRITE_RATE = 5;          // 5/s agent,  15/s tenant (限流 20/s)
    private static final int BROADCAST_RATE = 10;     // 10/s agent, 30/s tenant (限流 50/s)

    private static final long READ_INTERVAL_NS = 1_000_000_000L / READ_RATE;
    private static final long WRITE_INTERVAL_NS = 1_000_000_000L / WRITE_RATE;
    private static final long BROADCAST_INTERVAL_NS = 1_000_000_000L / BROADCAST_RATE;

    private static final String CSV_PATH = "target/redteam/false_positive.csv";

    private static final String[] TENANT_IDS = {"tenant_fp_a", "tenant_fp_b", "tenant_fp_c"};

    @BeforeAll
    static void initVfs() {
        VfsManager.instance().init();
        VfsRateLimiter.instance().setEnabled(false);
        EventBusRateLimiter.instance().setEnabled(false);
        // 为每个 tenant/agent 预创建 VFS 文件（盖租户戳）
        for (int ti = 0; ti < TENANTS; ti++) {
            for (int ai = 0; ai < AGENTS_PER_TENANT; ai++) {
                String path = agentPath(ti, ai);
                String tenant = TENANT_IDS[ti];
                try {
                    CallerContext.clear();
                    VfsManager.instance().writeText(path, "init payload for " + path, tenant);
                } finally {
                    CallerContext.clear();
                }
            }
        }
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

    private static String agentPath(int tenantIdx, int agentIdx) {
        return "/fp-bench/" + TENANT_IDS[tenantIdx] + "/agent_" + agentIdx + "/data.txt";
    }

    /**
     * 单个良性 agent 线程：在 DURATION_MS 内以受控速率发起 read/write/broadcast，
     * 统计每类操作的 total / blocked 计数。
     */
    private static void runAgent(int tenantIdx, int agentIdx, long durationNs,
                                  AtomicBoolean stop,
                                  AtomicLong totalReads, AtomicLong blockedReads,
                                  AtomicLong totalWrites, AtomicLong blockedWrites,
                                  AtomicLong totalBroadcasts, AtomicLong blockedBroadcasts) {
        String agentId = "fp_agent_" + tenantIdx + "_" + agentIdx;
        String tenantId = TENANT_IDS[tenantIdx];
        String path = agentPath(tenantIdx, agentIdx);
        String channel = "user.bench." + tenantId;
        String payload = "benign broadcast from " + agentId;

        CallerContext.set(agentId, tenantId);
        try {
            long startNs = System.nanoTime();
            long nextRead = startNs;
            long nextWrite = startNs;
            long nextBroadcast = startNs;

            while (!stop.get()) {
                long now = System.nanoTime();
                if (now - startNs >= durationNs) break;

                if (now >= nextRead) {
                    totalReads.incrementAndGet();
                    try {
                        VfsManager.instance().readText(path);
                    } catch (SecurityException e) {
                        blockedReads.incrementAndGet();
                    }
                    nextRead = now + READ_INTERVAL_NS;
                }
                if (now >= nextWrite) {
                    totalWrites.incrementAndGet();
                    try {
                        VfsManager.instance().writeText(path, "write-" + now);
                    } catch (SecurityException e) {
                        blockedWrites.incrementAndGet();
                    }
                    nextWrite = now + WRITE_INTERVAL_NS;
                }
                if (now >= nextBroadcast) {
                    totalBroadcasts.incrementAndGet();
                    // 直接检测限流器（broadcast 是 void fire-and-forget，不返回是否丢弃）
                    boolean allowed = EventBusRateLimiter.instance().tryConsume(channel);
                    if (!allowed) {
                        blockedBroadcasts.incrementAndGet();
                    } else {
                        // 配额已消费，执行真实 broadcast（无 SSE 客户端时近乎 no-op）
                        try {
                            EventBus.instance().broadcast(channel, payload);
                        } catch (Exception ignored) {
                            // broadcast 不应抛异常，忽略防御性
                        }
                    }
                    nextBroadcast = now + BROADCAST_INTERVAL_NS;
                }
                // 短暂让出 CPU，避免 busy-wait 占满核心影响其他 agent 调度
                Thread.yield();
            }
        } finally {
            CallerContext.clear();
        }
    }

    @Test
    void measureFalsePositiveRate() throws Exception {
        Path csv = Paths.get(CSV_PATH);
        Files.createDirectories(csv.getParent());
        Files.deleteIfExists(csv);

        StringBuilder csvOut = new StringBuilder();
        csvOut.append("run_idx,category,total,blocked,fp_rate_pct\n");

        // warmup：1 轮不计入，触发 JIT
        runOneWarmup();

        long[] totalReadsAll = new long[N];
        long[] blockedReadsAll = new long[N];
        long[] totalWritesAll = new long[N];
        long[] blockedWritesAll = new long[N];
        long[] totalBroadcastsAll = new long[N];
        long[] blockedBroadcastsAll = new long[N];

        for (int run = 0; run < N; run++) {
            EventBusRateLimiter.instance().setEnabled(true);
            VfsRateLimiter.instance().setEnabled(true);
            EventBusRateLimiter.instance().resetForTest();
            VfsRateLimiter.instance().resetForTest();

            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicLong totalReads = new AtomicLong();
            AtomicLong blockedReads = new AtomicLong();
            AtomicLong totalWrites = new AtomicLong();
            AtomicLong blockedWrites = new AtomicLong();
            AtomicLong totalBroadcasts = new AtomicLong();
            AtomicLong blockedBroadcasts = new AtomicLong();

            long durationNs = DURATION_MS * 1_000_000L;

            Thread[] agents = new Thread[TOTAL_AGENTS];
            int idx = 0;
            for (int ti = 0; ti < TENANTS; ti++) {
                for (int ai = 0; ai < AGENTS_PER_TENANT; ai++) {
                    final int t = ti, a = ai;
                    agents[idx] = Thread.ofPlatform()
                            .name("fp-agent-" + t + "-" + a)
                            .start(() -> runAgent(t, a, durationNs, stop,
                                    totalReads, blockedReads,
                                    totalWrites, blockedWrites,
                                    totalBroadcasts, blockedBroadcasts));
                    idx++;
                }
            }

            // 等待所有 agent 完成
            for (Thread ag : agents) {
                ag.join(10_000);
            }
            stop.set(true);

            totalReadsAll[run] = totalReads.get();
            blockedReadsAll[run] = blockedReads.get();
            totalWritesAll[run] = totalWrites.get();
            blockedWritesAll[run] = blockedWrites.get();
            totalBroadcastsAll[run] = totalBroadcasts.get();
            blockedBroadcastsAll[run] = blockedBroadcasts.get();

            csvOut.append(String.format(Locale.US, "%d,VFS_read,%d,%d,%.4f%n",
                    run, totalReads.get(), blockedReads.get(),
                    pct(blockedReads.get(), totalReads.get())));
            csvOut.append(String.format(Locale.US, "%d,VFS_write,%d,%d,%.4f%n",
                    run, totalWrites.get(), blockedWrites.get(),
                    pct(blockedWrites.get(), totalWrites.get())));
            csvOut.append(String.format(Locale.US, "%d,EventBus_broadcast,%d,%d,%.4f%n",
                    run, totalBroadcasts.get(), blockedBroadcasts.get(),
                    pct(blockedBroadcasts.get(), totalBroadcasts.get())));
        }

        Files.writeString(csv, csvOut.toString());

        // 聚合
        long sumTotalReads = sum(totalReadsAll), sumBlockedReads = sum(blockedReadsAll);
        long sumTotalWrites = sum(totalWritesAll), sumBlockedWrites = sum(blockedWritesAll);
        long sumTotalBcast = sum(totalBroadcastsAll), sumBlockedBcast = sum(blockedBroadcastsAll);

        System.out.println("[FalsePositive] 跨 " + N + " 轮聚合 (每轮 " + DURATION_MS + "ms, "
                + TOTAL_AGENTS + " agents):");
        System.out.printf(Locale.US, "  VFS_read:            total=%d  blocked=%d  fp_rate=%.4f%%%n",
                sumTotalReads, sumBlockedReads, pct(sumBlockedReads, sumTotalReads));
        System.out.printf(Locale.US, "  VFS_write:           total=%d  blocked=%d  fp_rate=%.4f%%%n",
                sumTotalWrites, sumBlockedWrites, pct(sumBlockedWrites, sumTotalWrites));
        System.out.printf(Locale.US, "  EventBus_broadcast:  total=%d  blocked=%d  fp_rate=%.4f%%%n",
                sumTotalBcast, sumBlockedBcast, pct(sumBlockedBcast, sumTotalBcast));
        long grandTotal = sumTotalReads + sumTotalWrites + sumTotalBcast;
        long grandBlocked = sumBlockedReads + sumBlockedWrites + sumBlockedBcast;
        System.out.printf(Locale.US, "  OVERALL:             total=%d  blocked=%d  fp_rate=%.4f%%%n",
                grandTotal, grandBlocked, pct(grandBlocked, grandTotal));

        // 核心断言：良性负载下的误报率应 < 1%（治理机制不应显著阻碍合法操作）
        double overallFpRate = pct(grandBlocked, grandTotal);
        assertTrue(overallFpRate < 1.0,
                "良性负载下整体误报率应 < 1%，实际: " + String.format(Locale.US, "%.4f", overallFpRate) + "%");

        System.out.println("[FalsePositive] CSV → " + csv.toAbsolutePath());
    }

    private void runOneWarmup() throws InterruptedException {
        EventBusRateLimiter.instance().setEnabled(true);
        VfsRateLimiter.instance().setEnabled(true);
        EventBusRateLimiter.instance().resetForTest();
        VfsRateLimiter.instance().resetForTest();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong tr = new AtomicLong(), br = new AtomicLong();
        AtomicLong tw = new AtomicLong(), bw = new AtomicLong();
        AtomicLong tb = new AtomicLong(), bb = new AtomicLong();

        Thread[] agents = new Thread[TOTAL_AGENTS];
        int idx = 0;
        for (int ti = 0; ti < TENANTS; ti++) {
            for (int ai = 0; ai < AGENTS_PER_TENANT; ai++) {
                final int t = ti, a = ai;
                agents[idx] = Thread.ofPlatform().name("fp-warmup-" + t + "-" + a)
                        .start(() -> runAgent(t, a, 500_000_000L, stop, tr, br, tw, bw, tb, bb));
                idx++;
            }
        }
        for (Thread ag : agents) ag.join(5_000);
        stop.set(true);
    }

    private static double pct(long blocked, long total) {
        return total == 0 ? 0.0 : (100.0 * blocked) / total;
    }

    private static long sum(long[] xs) {
        long s = 0; for (long x : xs) s += x; return s;
    }
}
