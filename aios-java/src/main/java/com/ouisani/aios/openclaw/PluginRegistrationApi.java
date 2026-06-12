package com.ouisani.aios.openclaw;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolInput;

/**
 * 插件注册 API — 每个插件获得的隔离注册接口。
 * <p>
 * 对标 OpenClaw TypeScript 版的 createApi(record, params)。
 * 插件只能通过此 API 声明自己的能力，不能直接操作 PluginRegistry。
 * <p>
 * OS 类比：相当于 Linux 模块的 EXPORT_SYMBOL — 模块只能通过
 * 合法接口导出符号，不能直接篡改内核符号表。
 */
public class PluginRegistrationApi {

    private final PluginRegistry registry;
    private final String pluginId;

    PluginRegistrationApi(PluginRegistry registry, String pluginId) {
        this.registry = registry;
        this.pluginId = pluginId;
    }

    /** 注册一个工具 */
    public PluginRegistrationApi registerTool(Tool<? extends ToolInput> tool) {
        registry.registerTool(pluginId, tool);
        return this;
    }

    /** 注册一个渠道 */
    public PluginRegistrationApi registerChannel(ChannelDescriptor channel) {
        registry.registerChannel(pluginId, channel);
        return this;
    }

    /** 注册一个钩子 */
    public PluginRegistrationApi registerHook(HookDescriptor hook) {
        registry.registerHook(pluginId, hook);
        return this;
    }

    /** 注册一个 Provider */
    public PluginRegistrationApi registerProvider(ProviderDescriptor provider) {
        registry.registerProvider(pluginId, provider);
        return this;
    }

    public String pluginId() {
        return pluginId;
    }
}
