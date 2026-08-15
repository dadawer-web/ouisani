package com.ouisani.aios.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 扩展钩子注册表 — 借鉴 Agent Zero 的扩展发现与排序机制。
 * <p>
 * 管理所有注册的 ExtensionHook，按扩展点名称分组，
 * before 钩子按 priority 升序执行，after 钩子按 priority 降序执行。
 * <p>
 * 线程安全：使用 ConcurrentHashMap + CopyOnWriteArrayList。
 */
public class ExtensibleHookRegistry {
    private static final Logger log = LoggerFactory.getLogger(ExtensibleHookRegistry.class);

    private static final class Holder {
        static final ExtensibleHookRegistry INSTANCE = new ExtensibleHookRegistry();
    }

    public static ExtensibleHookRegistry getInstance() { return Holder.INSTANCE; }

    // extensionPoint → hooks
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ExtensionHook>> hooks = new ConcurrentHashMap<>();

    /**
     * 注册扩展钩子。
     */
    public void register(ExtensionHook hook) {
        if (hook == null || hook.extensionPoint() == null) return;
        hooks.computeIfAbsent(hook.extensionPoint(), k -> new CopyOnWriteArrayList<>()).add(hook);
        // 按 priority 排序
        hooks.get(hook.extensionPoint()).sort(Comparator.comparingInt(ExtensionHook::priority));
        log.info("[ExtensibleRegistry] 注册钩子: point='{}', phase={}, priority={}, hook={}",
                hook.extensionPoint(), hook.phase(), hook.priority(), hook.getClass().getSimpleName());
    }

    /**
     * 注销扩展钩子。
     */
    public void unregister(ExtensionHook hook) {
        if (hook == null) return;
        CopyOnWriteArrayList<ExtensionHook> list = hooks.get(hook.extensionPoint());
        if (list != null) list.remove(hook);
    }

    /**
     * before 阶段：执行所有 before 钩子。
     *
     * @param extensionPoint 扩展点名称
     * @param target 目标对象
     * @param args 方法参数
     * @return 如果有钩子返回非 null，则短路（直接返回该值作为方法结果）
     */
    public static Object before(String extensionPoint, Object target, Map<String, Object> args) {
        CopyOnWriteArrayList<ExtensionHook> list = getInstance().hooks.get(extensionPoint);
        if (list == null) return null;

        for (ExtensionHook hook : list) {
            if (hook.phase() != ExtensionHook.Phase.BEFORE) continue;
            try {
                Object shortCircuit = hook.beforeHook(target, args);
                if (shortCircuit != null) {
                    log.debug("[ExtensibleRegistry] 钩子短路: point='{}', hook={}",
                            extensionPoint, hook.getClass().getSimpleName());
                    return shortCircuit;
                }
            } catch (Exception e) {
                log.warn("[ExtensibleRegistry] before 钩子异常: point='{}', hook={}, error={}",
                        extensionPoint, hook.getClass().getSimpleName(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * after 阶段：执行所有 after 钩子。
     *
     * @param extensionPoint 扩展点名称
     * @param target 目标对象
     * @param result 方法返回值
     * @param args 方法参数
     * @return 修改后的返回值
     */
    public static Object after(String extensionPoint, Object target, Object result, Map<String, Object> args) {
        CopyOnWriteArrayList<ExtensionHook> list = getInstance().hooks.get(extensionPoint);
        if (list == null) return result;

        // after 钩子按 priority 降序执行（与 before 相反）
        List<ExtensionHook> reversed = new ArrayList<>(list);
        reversed.sort(Comparator.comparingInt(ExtensionHook::priority).reversed());

        for (ExtensionHook hook : reversed) {
            if (hook.phase() != ExtensionHook.Phase.AFTER) continue;
            try {
                result = hook.afterHook(target, result, args);
            } catch (Exception e) {
                log.warn("[ExtensibleRegistry] after 钩子异常: point='{}', hook={}, error={}",
                        extensionPoint, hook.getClass().getSimpleName(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 获取指定扩展点的钩子数量。
     */
    public int hookCount(String extensionPoint) {
        CopyOnWriteArrayList<ExtensionHook> list = hooks.get(extensionPoint);
        return list != null ? list.size() : 0;
    }

    /**
     * 清除所有钩子（用于测试）。
     */
    public void clear() {
        hooks.clear();
    }
}
