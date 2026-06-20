package com.ouisani.aios.core.config;

import java.util.function.Function;

/**
 * 单个配置项的定义 — 类型、默认值、环境变量转换器。
 * <p>
 * 参考 LMCache 的 {@code _CONFIG_DEFINITIONS} 字典中每个配置项的结构：
 * <pre>
 * "chunk_size": {"type": int, "default": 256, "env_converter": int}
 * </pre>
 * 此 record 将上述结构封装为类型安全的 Java 记录类。
 * <p>
 * 每个配置项包含：
 * <ul>
 *   <li>{@code name} — 配置项名称</li>
 *   <li>{@code type} — 值的 Java 类型</li>
 *   <li>{@code defaultValue} — 默认值（当环境变量和命令行均未设置时使用）</li>
 *   <li>{@code envConverter} — 将环境变量字符串转换为对应类型的函数</li>
 * </ul>
 *
 * @see DeclarativeConfig
 */
public record ConfigDefinition(
        String name,
        Class<?> type,
        Object defaultValue,
        Function<String, Object> envConverter
) {

    /**
     * 通用工厂方法 — 创建任意类型的配置项定义。
     *
     * @param name        配置项名称
     * @param type        值的 Java 类型
     * @param defaultValue 默认值
     * @param converter   环境变量字符串转换器
     * @return 新的 ConfigDefinition 实例
     */
    public static ConfigDefinition of(String name, Class<?> type, Object defaultValue,
                                      Function<String, Object> converter) {
        return new ConfigDefinition(name, type, defaultValue, converter);
    }

    /**
     * 创建 int 类型配置项。
     *
     * @param name         配置项名称
     * @param defaultValue 默认值
     * @return 类型为 {@code Integer} 的配置项定义
     */
    public static ConfigDefinition intDef(String name, int defaultValue) {
        return new ConfigDefinition(name, Integer.class, defaultValue, Integer::parseInt);
    }

    /**
     * 创建 boolean 类型配置项。
     * <p>
     * 环境变量转换器支持以下值（大小写不敏感）：
     * {@code true/false}、{@code 1/0}、{@code yes/no}、{@code on/off}。
     *
     * @param name         配置项名称
     * @param defaultValue 默认值
     * @return 类型为 {@code Boolean} 的配置项定义
     */
    public static ConfigDefinition boolDef(String name, boolean defaultValue) {
        return new ConfigDefinition(name, Boolean.class, defaultValue, ConfigDefinition::parseBoolean);
    }

    /**
     * 创建 String 类型配置项。
     *
     * @param name         配置项名称
     * @param defaultValue 默认值
     * @return 类型为 {@code String} 的配置项定义
     */
    public static ConfigDefinition stringDef(String name, String defaultValue) {
        return new ConfigDefinition(name, String.class, defaultValue, (String s) -> s);
    }

    /**
     * 创建 double 类型配置项。
     *
     * @param name         配置项名称
     * @param defaultValue 默认值
     * @return 类型为 {@code Double} 的配置项定义
     */
    public static ConfigDefinition doubleDef(String name, double defaultValue) {
        return new ConfigDefinition(name, Double.class, defaultValue, Double::parseDouble);
    }

    /**
     * 将环境变量字符串解析为布尔值。
     * <p>
     * 支持的值（大小写不敏感）：
     * <ul>
     *   <li>{@code true}、{@code 1}、{@code yes}、{@code on} → {@code true}</li>
     *   <li>{@code false}、{@code 0}、{@code no}、{@code off} → {@code false}</li>
     * </ul>
     *
     * @param value 环境变量字符串
     * @return 解析后的布尔值
     * @throws IllegalArgumentException 如果值无法识别
     */
    private static Boolean parseBoolean(String value) {
        String lower = value.trim().toLowerCase();
        return switch (lower) {
            case "true", "1", "yes", "on" -> Boolean.TRUE;
            case "false", "0", "no", "off" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(
                    "Cannot parse boolean from: '" + value + "'");
        };
    }
}
