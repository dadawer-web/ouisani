package com.ouisani.aios.core;

import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.ipc.CallerContext;
import com.ouisani.aios.core.network.EventBusRateLimiter;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.permission.SpawnPrivilegeContext;
import com.ouisani.aios.core.security.VfsRateLimiter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大规模可扩展性评测 — 回应审稿人质疑：3 租户×3 agent=9 并发撑不起 kernel 级可扩展性声明。
 * <p>
 * 测量 N=[3,10,20,30] 租户（每租户 3 agent = 9/30/60/90 并发）下三个治理原语的表现：
 * <ol>
 *   <li><b>审计链 append 吞吐</b>：{@link UnifiedAuditLog#append} 在并发下的 ops/s
 *       （FileChannel append + bufferLock 是否线性可扩展）</li>
 *   <li><b>token-bucket 锁竞争</b>：{@link VfsRateLimiter#checkWrite} 的延迟 p50/p95/p99
 *       （synchronized TokenBucket 是否成为瓶颈；per-tenant 分桶是否隔离有效）</li>
 *   <li><b>SpawnPrivilegeContext 传播成本</b>：虚拟线程 spawn + InheritableThreadLocal 传播的延迟</li>
 * </ol>
 * <p>
 * 评测采用 JVM 内嵌方式（无 HTTP），直接调用 Java 方法，用虚拟线程模拟 N×3 并发 agent。
 * 每个配置跑 10 秒（spawn 传播测试 5 秒），输出 CSV。
 * <p>
 * 可通过 {@code mvn -Dtest=ScalabilityBenchmarkTest test} 或 {@code main()} 运行。
 */
class ScalabilityBenchmarkTest {

    /** 抑制 slf4j-simple 日志输出（VfsRateLimiter 拒绝时 log.warn 会洪泛） */
    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
    }

    private static final int[] TENANT_COUNTS = {3, 10, 20, 30, 50, 100, 150};
    private static final int AGENTS_PER_TENANT = 3;
    private static final int DURATION_S = 10;
    private static final int SPAWN_DURATION_S = 5;
    private static final String CSV_PATH = "e:/ouisani/evaluation/target/scalability_benchmark.csv";

    @Test
    void scalabilityBenchmark() throws Exception {
        runBenchmark();
    }

    public static void main(String[] args) throws Exception {
        new ScalabilityBenchmarkTest().runBenchmark();
    }

    private void runBenchmark() throws Exception {
        System.out.println("[BENCH] === Governance Runtime Scalability Benchmark ===");
        System.out.println("[BENCH] Java " + System.getProperty("java.version")
                + " | carriers=" + Runtime.getRuntime().availableProcessors()
                + " | maxHeap=" + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + "MB");

        // VFS 初始化（幂等）
        VfsManager.instance().init();

        StringBuilder csv = new StringBuilder();
        csv.append("n_tenants,n_agents,duration_s,total_ops,throughput_ops_per_s,");
        csv.append("latency_p50_ms,latency_p95_ms,latency_p99_ms,");
        csv.append("audit_append_throughput_ops_per_s,spawn_propagation_p50_us\n");

        // JIT 预热
        warmupJit();

        for (int nTenants : TENANT_COUNTS) {
            int nAgents = nTenants * AGENTS_PER_TENANT;
            System.out.println("[BENCH] --- N=" + nTenants + " tenants, " + nAgents + " agents ---");

            // 注册租户根目录
            for (int t = 0; t < nTenants; t++) {
                VfsManager.instance().registerTenantRoot("bench_tenant_" + t, "/bench/bench_tenant_" + t);
            }

            // 每个配置使用独立的审计文件，避免文件膨胀跨配置累积
            Path auditFile = Files.createTempFile("bench_audit_n" + nTenants, ".jsonl");
            UnifiedAuditLog.setAuditFile(auditFile);
            UnifiedAuditLog.resetForTesting();
            UnifiedAuditLog.setEnabled(true);
            VfsRateLimiter.instance().setEnabled(true);
            EventBusRateLimiter.instance().setEnabled(true);
            VfsRateLimiter.instance().resetForTest();
            EventBusRateLimiter.instance().resetForTest();

            // ── 测试 1: writeText 治理路径（rate limiter check + VFS write lock + audit）──
            ConcurrentLinkedQueue<Long> writeSamples = new ConcurrentLinkedQueue<>();
            long writeTotalOps = runWriteTextLoop(nTenants, writeSamples);
            long[] writeLatSorted = toSortedPrimitives(writeSamples);
            double writeThroughput = writeTotalOps / (double) DURATION_S;
            double p50 = percentileMs(writeLatSorted, 0.50);
            double p95 = percentileMs(writeLatSorted, 0.95);
            double p99 = percentileMs(writeLatSorted, 0.99);
            System.out.printf("[BENCH]   writeText: ops=%d, throughput=%.1f ops/s, p50=%.4fms, p95=%.4fms, p99=%.4fms (samples=%d)%n",
                    writeTotalOps, writeThroughput, p50, p95, p99, writeLatSorted.length);

            // ── 测试 2: 审计链 append 吞吐（直接调用，隔离 FileChannel + bufferLock）──
            long auditOps = runAuditAppendLoop(nTenants);
            double auditThroughput = auditOps / (double) DURATION_S;
            System.out.printf("[BENCH]   auditAppend: ops=%d, throughput=%.1f ops/s%n", auditOps, auditThroughput);

            // ── 测试 3: SpawnPrivilegeContext 传播成本（虚拟线程 spawn + ITL 继承）──
            ConcurrentLinkedQueue<Long> spawnSamples = new ConcurrentLinkedQueue<>();
            runSpawnPropagationLoop(nTenants, spawnSamples);
            long[] spawnLatSorted = toSortedPrimitives(spawnSamples);
            double spawnP50Us = percentileUs(spawnLatSorted, 0.50);
            System.out.printf("[BENCH]   spawnProp: p50=%.3f us (samples=%d)%n", spawnP50Us, spawnLatSorted.length);

            // 写 CSV 行
            csv.append(nTenants).append(',');
            csv.append(nAgents).append(',');
            csv.append(DURATION_S).append(',');
            csv.append(writeTotalOps).append(',');
            csv.append(String.format("%.2f", writeThroughput)).append(',');
            csv.append(String.format("%.4f", p50)).append(',');
            csv.append(String.format("%.4f", p95)).append(',');
            csv.append(String.format("%.4f", p99)).append(',');
            csv.append(String.format("%.2f", auditThroughput)).append(',');
            csv.append(String.format("%.3f", spawnP50Us)).append('\n');

            // 清理本配置的审计临时文件
            try { Files.deleteIfExists(auditFile); } catch (Exception ignored) {}
        }

        // 输出 CSV
        Path csvPath = Path.of(CSV_PATH);
        Files.createDirectories(csvPath.getParent());
        Files.writeString(csvPath, csv.toString());
        System.out.println("[BENCH] === CSV written to " + CSV_PATH + " ===");
        System.out.print(csv);
    }

    // ════════════════════════════════════════════════════════════════
    //  测试 1: writeText 治理路径 — 完整 governance 决策路径（rate limiter + VFS + audit）
    //════════════════════════════════════════════════════════════════

    /**
     * N×3 个虚拟线程并发，每个线程循环 DURATION_S 秒：
     * CallerContext.set → SpawnPrivilegeContext.set → writeText → clear。
     * writeText 内部触发 VfsRateLimiter.checkWrite（可能抛 SecurityException = 限流拒绝）。
     */
    private long runWriteTextLoop(int nTenants, ConcurrentLinkedQueue<Long> samples) throws Exception {
        int nAgents = nTenants * AGENTS_PER_TENANT;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(nAgents);
        AtomicLong totalOps = new AtomicLong(0);
        long deadline = System.nanoTime() + DURATION_S * 1_000_000_000L;
        PermissionProfile profile = PermissionProfile.empty();

        for (int t = 0; t < nTenants; t++) {
            final String tenantId = "bench_tenant_" + t;
            for (int a = 0; a < AGENTS_PER_TENANT; a++) {
                final String agentId = "agent_" + t + "_" + a;
                final String path = "/bench/bench_tenant_" + t + "/file_" + a + ".txt";
                Thread.startVirtualThread(() -> {
                    try {
                        startLatch.await();
                        int stride = 0;
                        while (System.nanoTime() < deadline) {
                            long t0 = System.nanoTime();
                            try {
                                CallerContext.set(agentId, tenantId);
                                SpawnPrivilegeContext.set(profile);
                                VfsManager.instance().writeText(path, "benchmark-payload", tenantId);
                            } catch (SecurityException se) {
                                // rate limited — 仍是一次 governance 决策
                            } finally {
                                SpawnPrivilegeContext.clear();
                                CallerContext.clear();
                            }
                            long t1 = System.nanoTime();
                            totalOps.incrementAndGet();
                            if (++stride % 50 == 0) {
                                samples.add(t1 - t0);
                            }
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
        }
        startLatch.countDown();
        doneLatch.await();
        return totalOps.get();
    }

    // ════════════════════════════════════════════════════════════════
    //  测试 2: 审计链 append 吞吐 — 直接调用 UnifiedAuditLog.append
    //════════════════════════════════════════════════════════════════

    /**
     * N×3 个虚拟线程并发，每个线程循环 DURATION_S 秒直接调用 UnifiedAuditLog.append。
     * 隔离测量 FileChannel append + bufferLock 在 N×3 并发下的吞吐。
     */
    private long runAuditAppendLoop(int nTenants) throws Exception {
        int nAgents = nTenants * AGENTS_PER_TENANT;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(nAgents);
        AtomicLong totalOps = new AtomicLong(0);
        long deadline = System.nanoTime() + DURATION_S * 1_000_000_000L;

        for (int t = 0; t < nTenants; t++) {
            final String tenantId = "bench_tenant_" + t;
            for (int a = 0; a < AGENTS_PER_TENANT; a++) {
                final String agentId = "agent_" + t + "_" + a;
                Thread.startVirtualThread(() -> {
                    try {
                        startLatch.await();
                        while (System.nanoTime() < deadline) {
                            UnifiedAuditLog.append(UnifiedAuditLog.LAYER_PERMISSION, "BENCH",
                                    agentId, "bench:" + tenantId, "scalability benchmark audit append");
                            totalOps.incrementAndGet();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
        }
        startLatch.countDown();
        doneLatch.await();
        return totalOps.get();
    }

    // ════════════════════════════════════════════════════════════════
    //  测试 3: SpawnPrivilegeContext 传播成本 — 虚拟线程 spawn + ITL 继承
    //════════════════════════════════════════════════════════════════

    /**
     * N×3 个父虚拟线程并发，每个父线程循环 SPAWN_DURATION_S 秒：
     * set context → spawn 子虚拟线程（继承 ITL）→ 子线程读取继承的 context → clear → join。
     * 测量 spawn + ITL 传播 + read + join 的端到端延迟。
     */
    private void runSpawnPropagationLoop(int nTenants, ConcurrentLinkedQueue<Long> samples) throws Exception {
        int nAgents = nTenants * AGENTS_PER_TENANT;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(nAgents);
        long deadline = System.nanoTime() + SPAWN_DURATION_S * 1_000_000_000L;
        PermissionProfile profile = PermissionProfile.empty();

        for (int t = 0; t < nTenants; t++) {
            final String tenantId = "bench_tenant_" + t;
            for (int a = 0; a < AGENTS_PER_TENANT; a++) {
                final String agentId = "agent_" + t + "_" + a;
                Thread.startVirtualThread(() -> {
                    try {
                        startLatch.await();
                        int stride = 0;
                        while (System.nanoTime() < deadline) {
                            long t0 = System.nanoTime();
                            try {
                                CallerContext.set(agentId, tenantId);
                                SpawnPrivilegeContext.set(profile);
                                // spawn 子虚拟线程 — 继承父线程的 ITL 值
                                Thread child = Thread.startVirtualThread(() -> {
                                    // 子线程读取继承的 context（ITL 传播成本）
                                    CallerContext c = CallerContext.current();
                                    PermissionProfile p = SpawnPrivilegeContext.current();
                                    CallerContext.clear();
                                    SpawnPrivilegeContext.clear();
                                });
                                child.join();
                            } finally {
                                SpawnPrivilegeContext.clear();
                                CallerContext.clear();
                            }
                            long t1 = System.nanoTime();
                            if (++stride % 10 == 0) {
                                samples.add(t1 - t0);
                            }
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
        }
        startLatch.countDown();
        doneLatch.await();
    }

    // ════════════════════════════════════════════════════════════════
    //  JIT 预热
    //════════════════════════════════════════════════════════════════

    private void warmupJit() throws Exception {
        System.out.println("[BENCH] Warming up JIT (3s)...");
        VfsRateLimiter.instance().setEnabled(true);
        UnifiedAuditLog.setEnabled(true);
        VfsRateLimiter.instance().resetForTest();
        Path warmupAudit = Files.createTempFile("bench_warmup", ".jsonl");
        UnifiedAuditLog.setAuditFile(warmupAudit);
        UnifiedAuditLog.resetForTesting();

        int n = 3;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(n * AGENTS_PER_TENANT);
        long deadline = System.nanoTime() + 3 * 1_000_000_000L;
        PermissionProfile profile = PermissionProfile.empty();

        for (int t = 0; t < n; t++) {
            final String tenantId = "warmup_tenant_" + t;
            VfsManager.instance().registerTenantRoot(tenantId, "/warmup/" + tenantId);
            for (int a = 0; a < AGENTS_PER_TENANT; a++) {
                final String agentId = "warmup_" + t + "_" + a;
                final String path = "/warmup/warmup_tenant_" + t + "/file_" + a + ".txt";
                Thread.startVirtualThread(() -> {
                    try {
                        startLatch.await();
                        while (System.nanoTime() < deadline) {
                            try {
                                CallerContext.set(agentId, tenantId);
                                SpawnPrivilegeContext.set(profile);
                                VfsManager.instance().writeText(path, "warmup", tenantId);
                            } catch (SecurityException ignored) {
                            } finally {
                                SpawnPrivilegeContext.clear();
                                CallerContext.clear();
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
        }
        startLatch.countDown();
        doneLatch.await();
        VfsRateLimiter.instance().resetForTest();
        try { Files.deleteIfExists(warmupAudit); } catch (Exception ignored) {}
        System.out.println("[BENCH] Warmup done.");
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助
    //════════════════════════════════════════════════════════════════

    private static long[] toSortedPrimitives(ConcurrentLinkedQueue<Long> q) {
        long[] arr = new long[q.size()];
        int i = 0;
        for (Long v : q) arr[i++] = v;
        Arrays.sort(arr);
        return arr;
    }

    private static double percentileMs(long[] sortedNs, double p) {
        if (sortedNs.length == 0) return 0.0;
        int idx = (int) Math.ceil(p * sortedNs.length) - 1;
        idx = Math.max(0, Math.min(idx, sortedNs.length - 1));
        return sortedNs[idx] / 1_000_000.0;
    }

    private static double percentileUs(long[] sortedNs, double p) {
        if (sortedNs.length == 0) return 0.0;
        int idx = (int) Math.ceil(p * sortedNs.length) - 1;
        idx = Math.max(0, Math.min(idx, sortedNs.length - 1));
        return sortedNs[idx] / 1_000.0;
    }
}
