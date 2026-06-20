package com.ouisani.aios.core.llm.auth;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 算力电源单元 (Auth Profile)
 * 代表一个具体的 API Key 及其健康状态。
 * <p>
 * OS 类比: 服务器的热插拔电源模块——每个电源有独立健康状态，
 * 故障时自动熔断隔离，恢复后重新纳入负载均衡池。
 */
public class AuthProfile {
    private final String profileId;
    private final String provider;   // 如: "openai", "anthropic"
    private final String apiKey;
    private final String baseUrl;    // 用于支持反代或不同区域
    private final int weight;        // 负载均衡权重

    // 熔断与健康状态追踪 (高并发安全)
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong cooldownUntil = new AtomicLong(0);

    public AuthProfile(String profileId, String provider, String apiKey, String baseUrl, int weight) {
        this.profileId = profileId;
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.weight = weight;
    }

    // 判断当前配置是否健康（未被熔断）
    public boolean isHealthy() {
        return System.currentTimeMillis() > cooldownUntil.get();
    }

    // 报告调用成功：清零失败计数
    public void reportSuccess() {
        failureCount.set(0);
    }

    // 报告调用失败 (如 429/500/503)：触发指数退避冷却
    public void reportFailure() {
        int count = failureCount.incrementAndGet();
        // 基础冷却时间 10 秒，随失败次数指数增长，最高 5 分钟
        long cooldownMs = Math.min(10000L * (1L << (count - 1)), 300000L);
        cooldownUntil.set(System.currentTimeMillis() + cooldownMs);
    }

    // Getters...
    public String getProfileId() { return profileId; }
    public String getProvider() { return provider; }
    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public int getWeight() { return weight; }
    public long getCooldownUntil() { return cooldownUntil.get(); }
}
