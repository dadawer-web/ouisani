package com.ouisani.aios.core.llm.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 电源调度中心 (Load Balancer & Circuit Breaker)
 * 负责跨多个 API Key 的请求分发与故障转移。
 * <p>
 * OS 类比: 数据中心的冗余电源管理器——多路电源并行供电，
 * 单路故障时自动切换到备用电源，全部故障时触发紧急告警。
 * <p>
 * 使用加权轮询（Weighted Round-Robin）算法进行负载分发，
 * 权重高的 Profile 获得更多请求，而非永远独占。
 */
public class AuthProfileManager {
    private static final Logger log = LoggerFactory.getLogger(AuthProfileManager.class);
    private static final AuthProfileManager INSTANCE = new AuthProfileManager();

    private final CopyOnWriteArrayList<AuthProfile> profiles = new CopyOnWriteArrayList<>();
    // 加权轮询计数器 — 跨请求持续递增，实现真正的轮转
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    private AuthProfileManager() {}

    public static AuthProfileManager getInstance() {
        return INSTANCE;
    }

    public void addProfile(AuthProfile profile) {
        profiles.add(profile);
        log.info("[Auth Manager] 已加载 Profile: {} ({}, 权重={})", profile.getProfileId(), profile.getProvider(), profile.getWeight());
    }

    /**
     * 移除指定 Profile
     */
    public void removeProfile(String profileId) {
        profiles.removeIf(p -> p.getProfileId().equals(profileId));
        log.info("[Auth Manager] 已移除 Profile: {}", profileId);
    }

    /**
     * 获取一个健康且适用于指定提供商的配置。
     * <p>
     * 使用加权轮询（Weighted Round-Robin）算法：
     * 1. 筛选健康的候选 Profile
     * 2. 按权重降序排列
     * 3. 用递增计数器取模，实现跨请求的均匀轮转
     * <p>
     * 这确保了权重高的 Profile 获得更多请求（但不独占），
     * 权重低的 Profile 也能分担流量，实现真正的负载均衡。
     */
    public AuthProfile acquireHealthyProfile(String provider) {
        List<AuthProfile> candidates = profiles.stream()
            .filter(p -> p.getProvider().equalsIgnoreCase(provider))
            .filter(AuthProfile::isHealthy)
            .sorted((p1, p2) -> Integer.compare(p2.getWeight(), p1.getWeight())) // 权重高的优先
            .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            // 绝境：所有 Key 都挂了或被限流了！
            log.error("[Auth Manager] 提供商 '{}' 的所有 Profile 已耗尽或处于冷却中！", provider);
            throw new RuntimeException("No healthy API keys available for provider: " + provider + ". Please wait for cooldown.");
        }

        // 加权轮询：用计数器取模实现轮转
        // 将候选列表按权重展开为虚拟槽位，计数器在槽位间轮转
        int totalWeight = candidates.stream().mapToInt(AuthProfile::getWeight).sum();
        int counter = roundRobinCounter.getAndIncrement() % totalWeight;
        if (counter < 0) counter += totalWeight; // 处理负数

        int accumulated = 0;
        for (AuthProfile candidate : candidates) {
            accumulated += candidate.getWeight();
            if (counter < accumulated) {
                log.debug("[Auth Manager] 加权轮询选中 Profile {} (权重={})，提供商 {}",
                        candidate.getProfileId(), candidate.getWeight(), provider);
                return candidate;
            }
        }

        // 兜底：返回第一个候选者（理论上不会走到这里）
        AuthProfile selected = candidates.get(0);
        log.debug("[Auth Manager] 兜底选中 Profile {}，提供商 {}", selected.getProfileId(), provider);
        return selected;
    }

    // 获取当前池状态，供前端雷达 (Telemetry) 监控
    public List<AuthProfile> getAllProfiles() {
        return profiles;
    }
}
