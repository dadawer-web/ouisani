package com.ouisani.aios.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 声明式配置 — 集中定义配置项 + 多来源合并 + extra_config 逃生舱口。
 * <p>
 * 参考 LMCache 的 {@code _CONFIG_DEFINITIONS + extra_config} 模式：
 * 所有配置项在一个地方集中声明（名称、类型、默认值、环境变量转换器），
 * 运行时从多个来源合并得到最终值。
 * <p>
 * 配置优先级（从低到高）：
 * <ol>
 *   <li>{@link ConfigDefinition} 中的 {@code default}</li>
 *   <li>环境变量（{@code AIOS_<NAME>}）</li>
 *   <li>命令行 overrides（通过 {@link #set(String, Object)} 设置）</li>
 * </ol>
 * <p>
 * {@code extra_config} 逃生舱口用于存放未在定义中声明的临时/实验性配置，
 * 避免频繁修改配置定义。
 * <p>
 * 使用示例：
 * <pre>{@code
 * DeclarativeConfig config = new DeclarativeConfig();
 * config.register(ConfigDefinition.intDef("cache_capacity", 128));
 * config.register(ConfigDefinition.boolDef("enable_cache", true));
 * config.loadFromEnv("AIOS_");
 *
 * int capacity = config.get("cache_capacity", Integer.class);
 * }</pre>
 *
 * @see ConfigDefinition
 */
public final class DeclarativeConfig {

    private static final Logger log = LoggerFactory.getLogger(DeclarativeConfig.class);

    /** 默认环境变量前缀 */
    private static final String DEFAULT_ENV_PREFIX = "AIOS_";

    private final Map<String, ConfigDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    /** 逃生舱口 — 存放未在定义中声明的临时/实验性配置 */
    private final Map<String, Object> extraConfig = new ConcurrentHashMap<>();

    /**
     * 注册一个配置项定义。
     *
     * @param def 配置项定义
     * @throws IllegalArgumentException 如果 def 为 null
     */
    public void register(ConfigDefinition def) {
        if (def == null) {
            throw new IllegalArgumentException("ConfigDefinition cannot be null");
        }
        definitions.put(def.name(), def);
    }

    /**
     * 设置配置项的值（来自命令行或环境变量）。
     * <p>
     * 此值优先级最高，将覆盖环境变量和默认值。
     *
     * @param name  配置项名称
     * @param value 配置项值
     * @throws IllegalArgumentException 如果 name 为 null/空
     */
    public void set(String name, Object value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Config name cannot be null or blank");
        }
        values.put(name, value);
    }

    /**
     * 获取配置项的值。
     * <p>
     * 查找优先级（从高到低）：
     * <ol>
     *   <li>{@link #set(String, Object)} 设置的值</li>
     *   <li>环境变量 {@code AIOS_<NAME>}（使用定义中的 envConverter 转换）</li>
     *   <li>{@link ConfigDefinition} 中的默认值</li>
     * </ol>
     *
     * @param name 配置项名称
     * @param type 期望的返回类型
     * @param <T>  返回类型
     * @return 配置项值，如果未找到则返回 null
     * @throws ClassCastException 如果值的类型与期望类型不兼容
     */
    public <T> T get(String name, Class<T> type) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Config name cannot be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Type cannot be null");
        }

        // 1. 命令行 overrides
        Object value = values.get(name);
        if (value != null) {
            return cast(value, type, name);
        }

        // 2. 环境变量 AIOS_<NAME>
        ConfigDefinition def = definitions.get(name);
        if (def != null) {
            String envName = DEFAULT_ENV_PREFIX + name.toUpperCase().replace('.', '_');
            String envValue = System.getenv(envName);
            if (envValue != null && def.envConverter() != null) {
                try {
                    Object converted = def.envConverter().apply(envValue);
                    return cast(converted, type, name);
                } catch (Exception e) {
                    log.warn("[DeclarativeConfig] 环境变量 {}={} 转换失败: {}",
                            envName, envValue, e.getMessage());
                }
            }

            // 3. 默认值
            if (def.defaultValue() != null) {
                return cast(def.defaultValue(), type, name);
            }
        }

        return null;
    }

    /**
     * 获取配置项的值，带默认值回退。
     * <p>
     * 如果配置项未设置且无定义，返回指定的 {@code defaultValue}。
     *
     * @param name         配置项名称
     * @param type         期望的返回类型
     * @param defaultValue 当配置项未找到时返回的默认值
     * @param <T>          返回类型
     * @return 配置项值，或 defaultValue
     */
    public <T> T get(String name, Class<T> type, T defaultValue) {
        T value = get(name, type);
        return value != null ? value : defaultValue;
    }

    /**
     * 设置 extra_config 逃生舱口中的值。
     * <p>
     * 用于存放未在配置定义中声明的临时/实验性配置。
     *
     * @param key   配置键
     * @param value 配置值
     * @throws IllegalArgumentException 如果 key 为 null/空
     */
    public void setExtra(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Extra config key cannot be null or blank");
        }
        extraConfig.put(key, value);
    }

    /**
     * 获取 extra_config 逃生舱口中的值。
     *
     * @param key  配置键
     * @param type 期望的返回类型
     * @param <T>  返回类型
     * @return 配置值，如果未找到则返回 null
     * @throws ClassCastException 如果值的类型与期望类型不兼容
     */
    public <T> T getExtra(String key, Class<T> type) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Extra config key cannot be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Type cannot be null");
        }
        Object value = extraConfig.get(key);
        if (value == null) {
            return null;
        }
        return cast(value, type, key);
    }

    /**
     * 返回 extra_config 逃生舱口的完整映射。
     *
     * @return extra_config 映射（不可变视图）
     */
    public Map<String, Object> extraConfig() {
        return Map.copyOf(extraConfig);
    }

    /**
     * 从环境变量加载所有已注册的配置项。
     * <p>
     * 对每个已注册的 {@link ConfigDefinition}，检查环境变量
     * {@code <prefix><NAME>}（NAME 为配置项名大写形式），如果存在则使用
     * 定义中的 envConverter 转换后存入 values。
     *
     * @param prefix 环境变量前缀（如 {@code "AIOS_"}）
     * @throws IllegalArgumentException 如果 prefix 为 null
     */
    public void loadFromEnv(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix cannot be null");
        }
        for (ConfigDefinition def : definitions.values()) {
            String envName = prefix + def.name().toUpperCase().replace('.', '_');
            String envValue = System.getenv(envName);
            if (envValue != null) {
                if (def.envConverter() != null) {
                    try {
                        Object converted = def.envConverter().apply(envValue);
                        values.put(def.name(), converted);
                        log.debug("[DeclarativeConfig] 从环境变量加载: {} = {}", envName, converted);
                    } catch (Exception e) {
                        log.warn("[DeclarativeConfig] 环境变量 {}={} 转换失败: {}",
                                envName, envValue, e.getMessage());
                    }
                } else {
                    values.put(def.name(), envValue);
                    log.debug("[DeclarativeConfig] 从环境变量加载: {} = {}", envName, envValue);
                }
            }
        }
    }

    /**
     * 返回所有已注册的配置项名称。
     *
     * @return 配置项名称集合（不可变）
     */
    public Set<String> registeredKeys() {
        return Set.copyOf(definitions.keySet());
    }

    /**
     * 将值安全地转换为指定类型。
     *
     * @param value 原始值
     * @param type  目标类型
     * @param name  配置项名称（用于错误信息）
     * @param <T>   目标类型
     * @return 转换后的值
     * @throws ClassCastException 如果类型不兼容
     */
    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value, Class<T> type, String name) {
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new ClassCastException(
                    "Config '" + name + "' expected type " + type.getName()
                            + " but got " + value.getClass().getName()
                            + " (value=" + value + ")");
        }
        return (T) value;
    }
}
