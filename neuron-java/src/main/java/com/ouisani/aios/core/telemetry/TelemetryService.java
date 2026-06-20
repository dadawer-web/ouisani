package com.ouisani.aios.core.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 遥测服务 — 对标 Claude Code 的 services/analytics/ + cost-tracker.ts。
 * <p>
 * 提供：
 * - 事件记录（双通道：日志 + 内存缓冲）
 * - Token 成本追踪
 * - API 调用统计
 * - 工具使用统计
 * <p>
 * OS 类比：相当于 Linux 的 perf + ftrace — 内核性能追踪基础设施。
 */
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private static final TelemetryService INSTANCE = new TelemetryService();

    private final Queue<TelemetryEvent> eventBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicLong totalCacheReadTokens = new AtomicLong(0);
    private final AtomicLong totalCacheCreationTokens = new AtomicLong(0);
    private final Map<String, ModelUsage> modelUsageMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> toolUsageCounts = new ConcurrentHashMap<>();
    private final AtomicLong totalApiDurationMs = new AtomicLong(0);
    private final AtomicLong totalToolDurationMs = new AtomicLong(0);
    private volatile boolean enabled = true;

    /** 遥测事件 */
    public record TelemetryEvent(
            String name,
            Map<String, Object> metadata,
            long timestamp
    ) {}

    /** 模型用量 */
    public record ModelUsage(
            String model,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            double costUSD
    ) {}

    /** 成本快照 */
    public record CostSnapshot(
            long totalInputTokens,
            long totalOutputTokens,
            long totalCacheReadTokens,
            long totalCacheCreationTokens,
            double totalCostUSD,
            long totalApiDurationMs,
            long totalToolDurationMs,
            Map<String, ModelUsage> modelUsage
    ) {}

    private TelemetryService() {}

    public static TelemetryService instance() { return INSTANCE; }

    /**
     * 记录事件。
     */
    public void logEvent(String name, Map<String, Object> metadata) {
        if (!enabled) return;

        TelemetryEvent event = new TelemetryEvent(name, metadata, Instant.now().toEpochMilli());
        eventBuffer.add(event);

        // 缓冲区上限 1000
        while (eventBuffer.size() > 1000) {
            eventBuffer.poll();
        }

        log.debug("[Telemetry] Event: {} | {}", name, metadata);
    }

    /**
     * 记录 Token 使用量。
     */
    public void recordTokenUsage(String model, long inputTokens, long outputTokens,
                                  long cacheReadTokens, long cacheCreationTokens, double costUSD) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalCacheReadTokens.addAndGet(cacheReadTokens);
        totalCacheCreationTokens.addAndGet(cacheCreationTokens);

        modelUsageMap.merge(model,
                new ModelUsage(model, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens, costUSD),
                (existing, newUsage) -> new ModelUsage(
                        model,
                        existing.inputTokens + newUsage.inputTokens,
                        existing.outputTokens + newUsage.outputTokens,
                        existing.cacheReadTokens + newUsage.cacheReadTokens,
                        existing.cacheCreationTokens + newUsage.cacheCreationTokens,
                        existing.costUSD + newUsage.costUSD
                ));

        log.debug("[Telemetry] Token usage: model={}, in={}, out={}, cost=${}",
                model, inputTokens, outputTokens, String.format("%.4f", costUSD));
    }

    /**
     * 记录工具使用。
     */
    public void recordToolUsage(String toolName, long durationMs) {
        toolUsageCounts.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
        totalToolDurationMs.addAndGet(durationMs);
    }

    /**
     * 记录 API 调用时长。
     */
    public void recordApiDuration(long durationMs) {
        totalApiDurationMs.addAndGet(durationMs);
    }

    /**
     * 获取成本快照。
     */
    public CostSnapshot getCostSnapshot() {
        double totalCost = modelUsageMap.values().stream()
                .mapToDouble(ModelUsage::costUSD)
                .sum();

        return new CostSnapshot(
                totalInputTokens.get(),
                totalOutputTokens.get(),
                totalCacheReadTokens.get(),
                totalCacheCreationTokens.get(),
                totalCost,
                totalApiDurationMs.get(),
                totalToolDurationMs.get(),
                new HashMap<>(modelUsageMap)
        );
    }

    /**
     * 格式化成本报告。
     */
    public String formatCostReport() {
        CostSnapshot snap = getCostSnapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("=== AIOS Cost Report ===\n");
        sb.append(String.format("Total Cost: $%.4f%n", snap.totalCostUSD()));
        sb.append(String.format("Input Tokens: %,d%n", snap.totalInputTokens()));
        sb.append(String.format("Output Tokens: %,d%n", snap.totalOutputTokens()));
        sb.append(String.format("Cache Read: %,d%n", snap.totalCacheReadTokens()));
        sb.append(String.format("Cache Creation: %,d%n", snap.totalCacheCreationTokens()));
        sb.append(String.format("API Duration: %,dms%n", snap.totalApiDurationMs()));
        sb.append(String.format("Tool Duration: %,dms%n", snap.totalToolDurationMs()));

        if (!snap.modelUsage().isEmpty()) {
            sb.append("\nModel Breakdown:\n");
            for (ModelUsage mu : snap.modelUsage().values()) {
                sb.append(String.format("  %s: in=%,d out=%,d cost=$%.4f%n",
                        mu.model(), mu.inputTokens(), mu.outputTokens(), mu.costUSD()));
            }
        }

        if (!toolUsageCounts.isEmpty()) {
            sb.append("\nTool Usage:\n");
            toolUsageCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .forEach(e -> sb.append(String.format("  %s: %d calls%n", e.getKey(), e.getValue().get())));
        }

        return sb.toString();
    }

    /**
     * 获取事件缓冲区。
     */
    public List<TelemetryEvent> drainEvents() {
        List<TelemetryEvent> events = new ArrayList<>();
        while (!eventBuffer.isEmpty()) {
            TelemetryEvent event = eventBuffer.poll();
            if (event != null) events.add(event);
        }
        return events;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
}
