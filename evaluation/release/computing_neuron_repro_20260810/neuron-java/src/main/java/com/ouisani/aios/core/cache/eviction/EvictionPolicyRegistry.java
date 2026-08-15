package com.ouisani.aios.core.cache.eviction;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存驱逐策略注册表 — 配置驱动的策略选择，新增策略无需修改缓存管理器。
 * <p>
 * 参考 LMCache 的 {@code POLICY_MAPPING + get_cache_policy} 工厂模式：
 * 将策略名称到策略实例的映射集中管理，通过名称即可获取策略实例。
 * 内置注册 {@code STRICT_OS} 和 {@code BIONIC} 两种策略，支持运行时注册自定义策略。
 * <p>
 * 使用方式：
 * <pre>{@code
 * MemoryEvictionStrategy strategy = EvictionPolicyRegistry.instance().get("STRICT_OS");
 * }</pre>
 * <p>
 * 注册自定义策略：
 * <pre>{@code
 * EvictionPolicyRegistry.instance().register("MY_STRATEGY", new MyCustomStrategy());
 * }</pre>
 *
 * @see MemoryEvictionStrategy
 * @see StrictTokenEvictionStrategy
 * @see BionicCognitiveStrategy
 */
public final class EvictionPolicyRegistry {

    private static final EvictionPolicyRegistry INSTANCE = new EvictionPolicyRegistry();

    private final Map<String, MemoryEvictionStrategy> strategies = new ConcurrentHashMap<>();

    private EvictionPolicyRegistry() {
        // 注册内置策略
        register("STRICT_OS", new StrictTokenEvictionStrategy());
        register("BIONIC", new BionicCognitiveStrategy());
    }

    /**
     * 获取注册表单例。
     *
     * @return 注册表单例实例
     */
    public static EvictionPolicyRegistry instance() {
        return INSTANCE;
    }

    /**
     * 注册一个驱逐策略。
     * <p>
     * 策略名称大小写不敏感，存储时统一转为大写。
     * 如果同名策略已存在，将被覆盖。
     *
     * @param name     策略名称（大小写不敏感）
     * @param strategy 策略实例
     * @throws IllegalArgumentException 如果 name 或 strategy 为 null
     */
    public void register(String name, MemoryEvictionStrategy strategy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Strategy name cannot be null or blank");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy cannot be null");
        }
        strategies.put(name.toUpperCase(), strategy);
    }

    /**
     * 根据名称获取驱逐策略。
     * <p>
     * 查找大小写不敏感。如果未找到指定名称的策略，抛出
     * {@link IllegalArgumentException} 并列出所有可用策略，便于诊断。
     *
     * @param name 策略名称（大小写不敏感）
     * @return 对应的策略实例
     * @throws IllegalArgumentException 如果 name 为 null/空，或未找到对应策略
     */
    public MemoryEvictionStrategy get(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Strategy name cannot be null or blank");
        }
        MemoryEvictionStrategy strategy = strategies.get(name.toUpperCase());
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "Unknown eviction strategy: '" + name
                            + "'. Available strategies: " + strategies.keySet()
            );
        }
        return strategy;
    }

    /**
     * 返回所有已注册策略的名称集合。
     *
     * @return 不可变的策略名称集合
     */
    public Set<String> availableStrategies() {
        return Set.copyOf(strategies.keySet());
    }
}
