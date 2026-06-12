package com.ouisani.aios.openclaw;

/**
 * OpenClaw 插件接口 — 所有 OpenClaw 风格插件必须实现此接口。
 * <p>
 * 对标 OpenClaw TypeScript 版的插件入口函数。
 * 插件通过 {@link PluginRegistrationApi} 声明自己的能力
 * （工具、渠道、钩子、Provider）。
 * <p>
 * 示例：
 * <pre>
 * public class MyPlugin implements OpenClawPlugin {
 *     &#64;Override
 *     public void register(PluginRegistrationApi api) {
 *         api.registerTool(new MyTool())
 *            .registerChannel(new ChannelDescriptor("my_channel", "My Channel", api.pluginId(), true, false, false, false))
 *            .registerHook(new HookDescriptor("before_tool_call", api.pluginId(), this::onBeforeToolCall));
 *     }
 * }
 * </pre>
 */
public interface OpenClawPlugin {

    /**
     * 注册插件能力。
     * <p>
     * 此方法在插件加载时调用一次。插件通过 api 注册自己的工具、渠道、钩子和 Provider。
     *
     * @param api 隔离的注册 API，只能注册属于本插件的能力
     */
    void register(PluginRegistrationApi api);
}
