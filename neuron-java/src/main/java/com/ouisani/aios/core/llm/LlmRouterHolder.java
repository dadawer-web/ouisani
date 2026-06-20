package com.ouisani.aios.core.llm;

/**
 * LlmRouter 全局持有者 — 允许非 Shell 组件访问 LLM Router。
 * <p>
 * AiosShell 在初始化 LlmRouter 后调用 {@link #set(LlmRouter)} 注入实例，
 * 其他组件（如 OperatorAgent）通过 {@link #getProvider(String)} 获取已注册的 Provider。
 * <p>
 * 这是必要的，因为 OperatorAgent 在 AiosAppManager 中创建，
 * 无法直接访问 AiosShell 的局部变量。
 */
public final class LlmRouterHolder {

    private static volatile LlmRouter instance;

    private LlmRouterHolder() {}

    /** 注入 LlmRouter 实例（由 AiosShell 调用） */
    public static void set(LlmRouter router) {
        instance = router;
    }

    /** 获取 LlmRouter 实例 */
    public static LlmRouter get() {
        return instance;
    }

    /** 获取指定名称的 LlmProvider */
    public static LlmProvider getProvider(String name) {
        if (instance == null) return null;
        return instance.getProvider(name);
    }

    /** 检查 LlmRouter 是否已初始化 */
    public static boolean isInitialized() {
        return instance != null;
    }
}
