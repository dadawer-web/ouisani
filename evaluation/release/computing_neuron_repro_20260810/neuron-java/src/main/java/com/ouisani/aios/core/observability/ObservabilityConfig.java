package com.ouisani.aios.core.observability;

/**
 * 可观测性体系配置，参考 LMCache 的 {@code EventBusConfig} 并扩展为 metrics/logging/tracing 三通道开关。
 * <p>
 * 不可变 record，通过 {@link #builder()} 构造。默认值：
 * <ul>
 *   <li>{@code enabled = true}</li>
 *   <li>{@code maxQueueSize = 10000}</li>
 *   <li>{@code metricsEnabled = true}</li>
 *   <li>{@code loggingEnabled = true}</li>
 *   <li>{@code tracingEnabled = false}</li>
 * </ul>
 *
 * @param enabled         是否启用 EventBus（禁用时 publish 为 no-op，drain 线程不启动）
 * @param maxQueueSize    事件队列上限，满时尾丢弃并限速告警
 * @param metricsEnabled  是否注册 metrics 订阅器
 * @param loggingEnabled  是否注册 logging 订阅器
 * @param tracingEnabled  是否注册 tracing 订阅器
 */
public record ObservabilityConfig(
        boolean enabled,
        int maxQueueSize,
        boolean metricsEnabled,
        boolean loggingEnabled,
        boolean tracingEnabled
) {
    /**
     * 紧凑构造器：校验 maxQueueSize 为正，否则回退默认值。
     */
    public ObservabilityConfig {
        if (maxQueueSize <= 0) {
            maxQueueSize = 10_000;
        }
    }

    /**
     * 返回使用全部默认值的配置。
     *
     * @return 默认配置
     */
    public static ObservabilityConfig defaults() {
        return builder().build();
    }

    /**
     * 创建一个 Builder。
     *
     * @return 新的 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 可变 Builder，所有字段预置默认值。
     */
    public static final class Builder {
        private boolean enabled = true;
        private int maxQueueSize = 10_000;
        private boolean metricsEnabled = true;
        private boolean loggingEnabled = true;
        private boolean tracingEnabled = false;

        private Builder() {
        }

        /**
         * 设置是否启用 EventBus。
         *
         * @param enabled 是否启用
         * @return 当前 Builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * 设置事件队列上限。
         *
         * @param maxQueueSize 队列上限（必须为正）
         * @return 当前 Builder
         */
        public Builder maxQueueSize(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        /**
         * 设置是否注册 metrics 订阅器。
         *
         * @param metricsEnabled 是否启用 metrics
         * @return 当前 Builder
         */
        public Builder metricsEnabled(boolean metricsEnabled) {
            this.metricsEnabled = metricsEnabled;
            return this;
        }

        /**
         * 设置是否注册 logging 订阅器。
         *
         * @param loggingEnabled 是否启用 logging
         * @return 当前 Builder
         */
        public Builder loggingEnabled(boolean loggingEnabled) {
            this.loggingEnabled = loggingEnabled;
            return this;
        }

        /**
         * 设置是否注册 tracing 订阅器。
         *
         * @param tracingEnabled 是否启用 tracing
         * @return 当前 Builder
         */
        public Builder tracingEnabled(boolean tracingEnabled) {
            this.tracingEnabled = tracingEnabled;
            return this;
        }

        /**
         * 构建不可变配置。
         *
         * @return 配置实例
         */
        public ObservabilityConfig build() {
            return new ObservabilityConfig(enabled, maxQueueSize, metricsEnabled, loggingEnabled, tracingEnabled);
        }
    }
}
