package com.ouisani.aios.core.plugin;

import java.lang.annotation.*;

/**
 * 可扩展注解 — 借鉴 Agent Zero 的 @extensible 装饰器。
 * <p>
 * 标注在方法上，表示该方法支持 before/after 扩展点。
 * 插件可以注册 ExtensionHook 来拦截或修改方法行为。
 * <p>
 * Java 没有装饰器语法，用注解 + ExtensibleHookRegistry 实现。
 * 不使用动态代理（避免性能开销和复杂度），而是用显式的前后钩子调用。
 * <p>
 * 用法：
 * <pre>
 * {@literal @}Extensible("query")
 * public String query(String userMessage) {
 *     ExtensibleHookRegistry.before("query", this, userMessage);
 *     String result = doQuery(userMessage);
 *     return ExtensibleHookRegistry.after("query", this, result, userMessage);
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Extensible {
    /** 扩展点名称 */
    String value();
}
