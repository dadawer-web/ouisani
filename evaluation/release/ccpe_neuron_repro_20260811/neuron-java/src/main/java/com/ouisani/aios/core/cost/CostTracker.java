package com.ouisani.aios.core.cost;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 成本追踪器 — 对标 Claude Code 的 cost-tracker.ts + costHook.ts。
 * <p>
 * 跟踪会话级别的 Token 用量和费用，提供阈值告警机制。
 * <p>
 * OS 类比：相当于 Linux 的 cgroup memory 控制器 —
 * 当进程（会话）的资源使用接近限制时发出警告，超过限制时触发 OOM 保护。
 * <p>
 * 阈值设计（基于 200k 上下文窗口）：
 * <ul>
 *   <li>WARNING: 100k tokens（50% 上下文）</li>
 *   <li>CRITICAL: 180k tokens（90% 上下文）</li>
 * </ul>
 */
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    /** 警告阈值：100k tokens */
    private static final long WARN_THRESHOLD = 100_000L;

    /** 临界阈值：180k tokens（200k 上下文窗口的 90%） */
    private static final long CRITICAL_THRESHOLD = 180_000L;

    /** 事件总线告警通道 */
    private static final String COST_WARNING_EVENT = "sys.cost.warning";

    /** 单例持有者，利用类加载机制保证线程安全 */
    private static final class Holder {
        static final CostTracker INSTANCE = new CostTracker();
    }

    /* ---- 线程安全的计数器 ---- */
    private final AtomicLong totalTokens = new AtomicLong(0);
    private final AtomicLong inputTokens = new AtomicLong(0);
    private final AtomicLong outputTokens = new AtomicLong(0);
    private final AtomicLong apiCallCount = new AtomicLong(0);

    /** 总费用（美元），使用 AtomicReference 保证线程安全的浮点累加 */
    private final AtomicReference<Double> totalCost = new AtomicReference<>(0.0);

    /** 上一次告警级别，避免重复发布相同级别的事件 */
    private final AtomicReference<CostLevel> lastAlertLevel = new AtomicReference<>(CostLevel.NORMAL);

    private CostTracker() {
    }

    /**
     * 获取单例实例。
     */
    public static CostTracker instance() {
        return Holder.INSTANCE;
    }

    /**
     * 记录一次 API 调用的 Token 使用量和费用。
     * <p>
     * 该方法线程安全，可从多个并发请求中调用。
     * 记录后会自动检查阈值，若超过阈值则通过 EventBus 发布告警事件。
     *
     * @param inputTokens  输入 Token 数
     * @param outputTokens 输出 Token 数
     * @param cost         本次调用费用（美元）
     */
    public void recordUsage(long inputTokens, long outputTokens, double cost) {
        this.inputTokens.addAndGet(inputTokens);
        this.outputTokens.addAndGet(outputTokens);
        this.totalTokens.addAndGet(inputTokens + outputTokens);
        this.apiCallCount.incrementAndGet();

        // 线程安全地累加浮点费用
        totalCost.updateAndGet(current -> current + cost);

        // 记录到遥测服务
        TelemetryService.instance().logEvent("cost.usage", Map.of(
                "inputTokens", inputTokens,
                "outputTokens", outputTokens,
                "cost", cost,
                "totalTokens", this.totalTokens.get(),
                "totalCost", totalCost.get()
        ));

        log.debug("[CostTracker] 记录用量: input={}, output={}, cost={}, 累计tokens={}",
                inputTokens, outputTokens, String.format("$%.4f", cost), this.totalTokens.get());

        // 检查阈值并发布告警
        checkAndPublishThreshold();
    }

    /**
     * 检查当前 Token 用量是否超过阈值。
     *
     * @return 当前成本级别
     */
    public CostLevel checkThreshold() {
        long current = totalTokens.get();
        if (current >= CRITICAL_THRESHOLD) {
            return CostLevel.CRITICAL;
        } else if (current >= WARN_THRESHOLD) {
            return CostLevel.WARNING;
        }
        return CostLevel.NORMAL;
    }

    /**
     * 检查阈值并在级别变化时通过 EventBus 发布告警。
     * <p>
     * 只有当级别发生跃迁时才发布事件，避免重复告警。
     */
    private void checkAndPublishThreshold() {
        CostLevel currentLevel = checkThreshold();
        CostLevel previousLevel = lastAlertLevel.get();

        // 级别未变化，无需重复告警
        if (currentLevel == previousLevel) {
            return;
        }

        // CAS 更新，确保只有一个线程发布告警
        if (lastAlertLevel.compareAndSet(previousLevel, currentLevel)) {
            String payload = String.format(
                    "{\"level\":\"%s\",\"totalTokens\":%d,\"totalCost\":%.4f,\"threshold\":\"%s\"}",
                    currentLevel.name(),
                    totalTokens.get(),
                    totalCost.get(),
                    currentLevel == CostLevel.CRITICAL ? "180k" : "100k"
            );

            EventBus.instance().broadcast(COST_WARNING_EVENT, payload);

            // 同时记录到遥测
            TelemetryService.instance().logEvent("cost.threshold_exceeded", Map.of(
                    "level", currentLevel.name(),
                    "totalTokens", totalTokens.get(),
                    "totalCost", totalCost.get()
            ));

            if (currentLevel == CostLevel.CRITICAL) {
                log.warn("[CostTracker] 临界阈值已超过! tokens={} (阈值={}), 费用={}",
                        totalTokens.get(), CRITICAL_THRESHOLD, String.format("$%.4f", totalCost.get()));
            } else if (currentLevel == CostLevel.WARNING) {
                log.warn("[CostTracker] 警告阈值已超过! tokens={} (阈值={}), 费用={}",
                        totalTokens.get(), WARN_THRESHOLD, String.format("$%.4f", totalCost.get()));
            }
        }
    }

    /**
     * 生成格式化的成本报告。
     *
     * @return 人类可读的成本报告字符串
     */
    public String formatReport() {
        long inTok = inputTokens.get();
        long outTok = outputTokens.get();
        long totTok = totalTokens.get();
        double cost = totalCost.get();
        long calls = apiCallCount.get();
        CostLevel level = checkThreshold();

        StringBuilder sb = new StringBuilder();
        sb.append("=== 会话成本报告 ===\n");
        sb.append(String.format("总 Token 数: %,d%n", totTok));
        sb.append(String.format("输入 Token:  %,d%n", inTok));
        sb.append(String.format("输出 Token:  %,d%n", outTok));
        sb.append(String.format("API 调用次数: %,d%n", calls));
        sb.append(String.format("总费用:      $%.4f%n", cost));
        sb.append(String.format("成本级别:    %s%n", level.name()));

        // 上下文窗口使用率
        double usagePercent = (totTok / (double) CRITICAL_THRESHOLD) * 100;
        sb.append(String.format("上下文使用率: %.1f%% (基于 200k 窗口)%n", usagePercent));

        // 阈值状态
        sb.append("\n阈值状态:\n");
        sb.append(String.format("  警告阈值 (100k): %s%n",
                totTok >= WARN_THRESHOLD ? "⚠ 已超过" : "✔ 正常"));
        sb.append(String.format("  临界阈值 (180k): %s%n",
                totTok >= CRITICAL_THRESHOLD ? "⚠ 已超过" : "✔ 正常"));

        return sb.toString();
    }

    /**
     * 重置所有计数器，将成本追踪器恢复到初始状态。
     * <p>
     * 通常在新会话开始时调用。同时重置告警级别，
     * 以便下一轮阈值检查能重新触发告警。
     */
    public void reset() {
        totalTokens.set(0);
        inputTokens.set(0);
        outputTokens.set(0);
        totalCost.set(0.0);
        apiCallCount.set(0);
        lastAlertLevel.set(CostLevel.NORMAL);

        TelemetryService.instance().logEvent("cost.reset", Map.of(
                "action", "reset"
        ));

        log.info("[CostTracker] 计数器已重置");
    }

    /* ---- Getter 方法 ---- */

    public long getTotalTokens() {
        return totalTokens.get();
    }

    public long getInputTokens() {
        return inputTokens.get();
    }

    public long getOutputTokens() {
        return outputTokens.get();
    }

    public double getTotalCost() {
        return totalCost.get();
    }

    public long getApiCallCount() {
        return apiCallCount.get();
    }

    /**
     * 成本级别枚举 — 表示当前会话的 Token 使用量级别。
     * <p>
     * 对应关系：
     * <ul>
     *   <li>NORMAL   — 低于 100k tokens，正常使用</li>
     *   <li>WARNING  — 100k~180k tokens，需注意用量</li>
     *   <li>CRITICAL — 超过 180k tokens，即将耗尽上下文窗口</li>
     * </ul>
     */
    public enum CostLevel {
        /** 正常：低于警告阈值 */
        NORMAL,
        /** 警告：超过 100k tokens */
        WARNING,
        /** 临界：超过 180k tokens，接近上下文窗口上限 */
        CRITICAL
    }
}
