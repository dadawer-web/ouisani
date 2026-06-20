package com.ouisani.aios.core.plugin;

import java.util.Map;

/**
 * 扩展钩子 — 借鉴 Agent Zero 的 Extension 基类。
 * <p>
 * 插件实现此接口，注册到 ExtensibleHookRegistry，
 * 在标注了 @Extensible 的方法执行前/后注入逻辑。
 */
public interface ExtensionHook {

    /**
     * 扩展点名称（对应 @Extensible 的 value）。
     */
    String extensionPoint();

    /**
     * 执行时机：before（方法执行前）或 after（方法执行后）。
     */
    enum Phase { BEFORE, AFTER }

    Phase phase();

    /**
     * before 钩子：在目标方法执行前调用。
     *
     * @param target 目标对象（this）
     * @param args 方法参数（可修改）
     * @return 如果返回非 null，则短路方法执行，直接作为方法返回值
     */
    default Object beforeHook(Object target, Map<String, Object> args) { return null; }

    /**
     * after 钩子：在目标方法执行后调用。
     *
     * @param target 目标对象（this）
     * @param result 方法返回值（可修改）
     * @param args 方法参数
     * @return 修改后的返回值
     */
    default Object afterHook(Object target, Object result, Map<String, Object> args) { return result; }

    /**
     * 优先级（数字越小越先执行）。
     */
    default int priority() { return 100; }
}
