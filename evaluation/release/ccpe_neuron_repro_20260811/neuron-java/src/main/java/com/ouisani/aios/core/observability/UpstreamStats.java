package com.ouisani.aios.core.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上游调用聚合统计 — 由 {@link UpstreamMetaQuery#statsByUpstream} 产出，
 * 服务于用户需求"所有 syscall/tool 调用可统一聚合分析"。
 * <p>
 * 借鉴 nuwa 项目 {@code /api/upstream/stats} 的聚合返回结构，适配为
 * 纯内存计算（无外部依赖，无 Jackson 序列化）。
 *
 * <h3>聚合维度</h3>
 * <ul>
 *   <li><b>调用量</b> — {@link #callCount} / {@link #successCount} / {@link #errorCount}</li>
 *   <li><b>错误率</b> — {@link #errorRate}（0.0-1.0，errorCount/callCount）</li>
 *   <li><b>延迟分布</b> — avg / min / max / p50 / p99（毫秒）</li>
 *   <li><b>吞吐</b> — {@link #totalBytes}（响应字节累计）</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * 相当于 Linux 的 {@code /proc/<pid>/io} 聚合视图 +
 * {@code perf stat} 的延迟分位数 —— 一次查询拿到该上游的全景画像。
 *
 * <h3>百分位算法</h3>
 * {@code p50/p99} 用最简的"排序后取索引"实现，记录量 ≤ 数千时性能足够。
 * 大规模场景可后续替换为 t-digest / HDR Histogram（v2 路线）。
 *
 * @param upstreamName    上游标识（与 {@link UpstreamMeta#upstreamName} 对齐）
 * @param callCount      总调用次数
 * @param successCount   成功次数（status_code ∈ [200,300)）
 * @param errorCount     失败次数（含 rejection / 5xx / 4xx 除 200 外）
 * @param errorRate      错误率 = errorCount / callCount（无调用时 0.0）
 * @param avgLatencyMs   平均延迟（毫秒，向下取整）
 * @param minLatencyMs   最小延迟
 * @param maxLatencyMs   最大延迟
 * @param p50LatencyMs   p50 延迟（中位数）
 * @param p99LatencyMs   p99 延迟
 * @param totalBytes     累计响应字节
 */
public record UpstreamStats(
        String upstreamName,
        long   callCount,
        long   successCount,
        long   errorCount,
        double errorRate,
        long   avgLatencyMs,
        long   minLatencyMs,
        long   maxLatencyMs,
        long   p50LatencyMs,
        long   p99LatencyMs,
        long   totalBytes
) {

    /** 空统计 — 无调用记录时返回（避免 NPE）。 */
    public static UpstreamStats empty(String upstreamName) {
        return new UpstreamStats(upstreamName, 0L, 0L, 0L, 0.0, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /**
     * 从一批 UpstreamMeta 计算聚合统计。
     *
     * @param calls        调用记录列表（已去重，可任意序）
     * @param upstreamName 上游标识（用于回填，避免 calls 为空时丢失维度）
     * @return 聚合统计；calls 为空时返回 {@link #empty}
     */
    public static UpstreamStats from(List<UpstreamMeta> calls, String upstreamName) {
        if (calls == null || calls.isEmpty()) {
            return empty(upstreamName);
        }
        long success = 0L, error = 0L, totalBytes = 0L, totalLatency = 0L;
        long minLat = Long.MAX_VALUE, maxLat = Long.MIN_VALUE;
        List<Long> latencies = new ArrayList<>(calls.size());
        for (UpstreamMeta m : calls) {
            int sc = m.upstreamStatusCode();
            if (sc >= 200 && sc < 300) {
                success++;
            } else {
                error++;
            }
            totalBytes += m.upstreamBytes();
            totalLatency += m.upstreamDurationMs();
            latencies.add(m.upstreamDurationMs());
            if (m.upstreamDurationMs() < minLat) minLat = m.upstreamDurationMs();
            if (m.upstreamDurationMs() > maxLat) maxLat = m.upstreamDurationMs();
        }
        long n = calls.size();
        double errorRate = n == 0 ? 0.0 : (double) error / (double) n;
        long avg = n == 0 ? 0L : totalLatency / n;
        // 排序取百分位
        Collections.sort(latencies);
        long p50 = percentile(latencies, 0.50);
        long p99 = percentile(latencies, 0.99);
        return new UpstreamStats(
                upstreamName,
                n,
                success,
                error,
                errorRate,
                avg,
                minLat == Long.MAX_VALUE ? 0L : minLat,
                maxLat == Long.MIN_VALUE ? 0L : maxLat,
                p50,
                p99,
                totalBytes
        );
    }

    /**
     * 取排序后列表的指定分位数。
     * <p>
     * 用 "nearest-rank" 方法（最简实现，与 Grafana 默认一致）：
     * {@code idx = ceil(p * n) - 1}，越界回退到首/尾。
     */
    private static long percentile(List<Long> sortedAsc, double p) {
        if (sortedAsc.isEmpty()) return 0L;
        int n = sortedAsc.size();
        int idx = (int) Math.ceil(p * n) - 1;
        if (idx < 0) idx = 0;
        if (idx >= n) idx = n - 1;
        return sortedAsc.get(idx);
    }
}
