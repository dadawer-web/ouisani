package com.ouisani.aios.core.observability;

import com.ouisani.aios.core.observability.subscribers.logging.CacheLoggingSubscriber;
import com.ouisani.aios.core.observability.subscribers.metrics.CacheMetricsSubscriber;
import com.ouisani.aios.core.observability.subscribers.metrics.LLMMetricsSubscriber;
import com.ouisani.aios.core.observability.subscribers.tracing.SpanTracingSubscriber;

/**
 * 可观测性体系初始化入口。
 * <p>
 * 创建（配置）单例 {@link ObservabilityEventBus}，按 {@link ObservabilityConfig} 的开关注册
 * metrics/logging/tracing 订阅器，并启动 EventBus。
 */
public final class ObservabilityInit {

    private ObservabilityInit() {
    }

    /**
     * 初始化可观测性体系。
     * <p>
     * 步骤：
     * <ol>
     *   <li>获取单例 EventBus 并应用配置</li>
     *   <li>按配置开关注册 metrics / logging / tracing 订阅器</li>
     *   <li>启动 EventBus（drain 虚拟线程）</li>
     * </ol>
     *
     * @param config 配置
     * @return 已启动的 EventBus
     */
    public static ObservabilityEventBus init(ObservabilityConfig config) {
        ObservabilityConfig cfg = config != null ? config : ObservabilityConfig.defaults();
        ObservabilityEventBus bus = ObservabilityEventBus.instance();
        bus.configure(cfg);

        if (cfg.metricsEnabled()) {
            bus.registerSubscriber(new CacheMetricsSubscriber());
            bus.registerSubscriber(new LLMMetricsSubscriber());
        }
        if (cfg.loggingEnabled()) {
            bus.registerSubscriber(new CacheLoggingSubscriber());
        }
        if (cfg.tracingEnabled()) {
            bus.registerSubscriber(new SpanTracingSubscriber());
        }

        bus.start();
        return bus;
    }
}
