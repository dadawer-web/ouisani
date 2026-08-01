package com.ouisani.aios.test.redteam;

import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.cgroup.TokenOomException;
import com.ouisani.aios.core.cgroup.TokenSoftOomException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B1 数据采集器 —— 轻量启动 cgroup token 记账原语，采集真实 OOM 触发时序。
 * <p>
 * <b>轻量启动方案</b>：本测试不引导完整内核（无 HTTP / LLM / VFS / TaskScheduler），
 * 直接构造 {@link CgroupNode}（cgroup 子系统的 token 记账原语）并调用其
 * {@code consumeTokens} 代码路径。这是 Neuron 内核中 OOM 触发的真实执行路径
 * （{@link com.ouisani.aios.core.cgroup.CgroupManager#consumeTokensForCurrentThread}
 * 内部即调用 {@code node.consumeTokens}），故采集到的时序数据与生产路径一致。
 * <p>
 * <b>采集模型</b>：每轮创建一个 {@code QUOTA=500} token 的 cgroup，以小增量
 * （{@code INCREMENT=5}）+ 可变 sleep 模拟 LLM token 生成速率，记录
 * (elapsed_ms, cumulative_tokens) 轨迹，直到硬限 OOM 触发。每轮 baseSleepMs 由
 * {@link ThreadLocalRandom} 在 [15, 55) ms 随机选取，每次增量再叠加 ±5ms 抖动，
 * 模拟真实 LLM 生成速率波动 + JVM 调度噪声 → OOM 延迟含真实方差（非 byte-identical）。
 * <p>
 * <b>软/硬两级</b>：80% 软限首次触发抛 {@link TokenSoftOomException}（不消耗，标记 agent），
 * 重试同增量即放行；100% 硬限抛 {@link TokenOomException}（不消耗）→ OOM 触发点。
 * <p>
 * <b>输出</b>：
 * <ul>
 *   <li>{@code target/redteam/scenario_b1_oom.csv} —— 每轮聚合（rate / oom_latency / tokens / soft 触发）</li>
 *   <li>{@code target/redteam/b1_traces.jsonl} —— 每轮逐点轨迹，供图 B 绘制 token 消耗曲线</li>
 * </ul>
 * <p>
 * <b>门控</b>：默认跳过（assumeTrue），避免拖慢全量回归套件。显式采集：
 * {@code mvn test -Dtest=B1OomDataCollectorTest -Db1.collect=true}
 *
 * @see CgroupNode#consumeTokens(long, String)
 */
class B1OomDataCollectorTest {

    private static final int N = 30;
    private static final long QUOTA = 500;       // gas_limit — 与 run_evaluation.py B1_GAS_LIMIT 一致
    private static final long INCREMENT = 5;     // 每次消耗 5 token
    private static final double SOFT_RATIO = 0.8;
    private static final String CSV_PATH = "target/redteam/scenario_b1_oom.csv";
    private static final String TRACE_PATH = "target/redteam/b1_traces.jsonl";

    @Test
    void collectB1OomTimingData() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getProperty("b1.collect")),
                "B1 数据采集默认跳过；启用：-Db1.collect=true");

        Path csv = Paths.get(CSV_PATH);
        Path trace = Paths.get(TRACE_PATH);
        Files.createDirectories(csv.getParent());
        Files.deleteIfExists(csv);
        Files.deleteIfExists(trace);

        StringBuilder csvOut = new StringBuilder();
        csvOut.append("runIdx,rate_tok_per_sec,oom_latency_ms,tokens_at_oom,soft_trigger_ms,tokens_at_soft\n");

        for (int runIdx = 0; runIdx < N; runIdx++) {
            // 每轮随机基准速率：baseSleepMs ∈ [15, 55)ms → rate ≈ 91..333 tok/s
            long baseSleepMs = 15 + ThreadLocalRandom.current().nextLong(0, 41);
            double rate = INCREMENT / (baseSleepMs / 1000.0);

            String agentId = "bench_b1_" + runIdx;
            CgroupNode node = new CgroupNode(agentId, QUOTA, null, SOFT_RATIO);

            long startNs = System.nanoTime();
            long consumed = 0;
            long softTriggerMs = -1;
            long tokensAtSoft = -1;
            List<long[]> tracePoints = new ArrayList<>(120);
            tracePoints.add(new long[]{0, 0});

            boolean oomed = false;
            int softRetries = 0;
            while (!oomed) {
                try {
                    node.consumeTokens(INCREMENT, agentId);
                    consumed += INCREMENT;
                    long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
                    tracePoints.add(new long[]{elapsedMs, consumed});
                    // 每次增量叠加 ±5ms 随机抖动（模拟 LLM 生成速率波动 + JVM 调度噪声）
                    long jitter = ThreadLocalRandom.current().nextLong(-5, 6);
                    sleepMs(Math.max(1, baseSleepMs + jitter));
                } catch (TokenSoftOomException soft) {
                    // 软限首次触发：未消耗，记录后重试同增量（agent 已标记 → 放行）
                    if (softTriggerMs < 0) {
                        softTriggerMs = (System.nanoTime() - startNs) / 1_000_000;
                        tokensAtSoft = consumed; // 软限触发时已消耗量（本次未消耗）
                        tracePoints.add(new long[]{softTriggerMs, consumed});
                    }
                    softRetries++;
                    if (softRetries > 3) {
                        // 安全网：异常状态下不应连续重试，避免死循环
                        break;
                    }
                    // 不 sleep，立即重试
                } catch (TokenOomException oom) {
                    long oomLatencyMs = (System.nanoTime() - startNs) / 1_000_000;
                    tracePoints.add(new long[]{oomLatencyMs, consumed});
                    oomed = true;
                    csvOut.append(runIdx).append(',')
                            .append(String.format("%.2f", rate)).append(',')
                            .append(oomLatencyMs).append(',')
                            .append(consumed).append(',')
                            .append(softTriggerMs).append(',')
                            .append(tokensAtSoft).append('\n');
                    writeTrace(trace, runIdx, rate, tracePoints, softTriggerMs, oomLatencyMs, consumed);
                }
            }
            assertTrue(oomed, "runIdx=" + runIdx + " 应触发硬限 OOM");
        }

        Files.writeString(csv, csvOut.toString());
        System.out.println("[B1Collector] 写入 " + N + " 轮 → " + csv.toAbsolutePath());
        System.out.println("[B1Collector] 轨迹 → " + trace.toAbsolutePath());
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeTrace(Path trace, int runIdx, double rate, List<long[]> points,
                                   long softMs, long oomMs, long tokensAtOom) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"runIdx\":").append(runIdx);
        sb.append(",\"rate\":").append(String.format("%.2f", rate));
        sb.append(",\"soft_ms\":").append(softMs);
        sb.append(",\"oom_ms\":").append(oomMs);
        sb.append(",\"tokens_at_oom\":").append(tokensAtOom);
        sb.append(",\"trace\":[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(points.get(i)[0]).append(',').append(points.get(i)[1]).append(']');
        }
        sb.append("]}\n");
        Files.writeString(trace, sb.toString(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
