package com.ouisani.aios.openclaw;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolInput;

import java.util.*;

/**
 * 插件注册表 — OpenClaw 核心抽象，对标 TypeScript 版的 PluginRegistry。
 * <p>
 * 管理工具、渠道、钩子、Provider 的注册与发现。
 * 每个插件通过 {@link PluginRegistrationApi} 获得隔离的注册能力，
 * 插件卸载后其注册的所有能力自动清理。
 * <p>
 * OS 类比：相当于 Linux 的 module_init/module_exit 机制 —
 * 模块加载时注册符号表，卸载时自动清理。
 *
 * @see PluginRegistrationApi
 * @see PluginRecord
 */
public class PluginRegistry {

    private final Map<String, PluginRecord> plugins = new LinkedHashMap<>();
    private final Map<String, Tool<? extends ToolInput>> tools = new LinkedHashMap<>();
    private final Map<String, ChannelDescriptor> channels = new LinkedHashMap<>();
    private final Map<String, HookDescriptor> hooks = new LinkedHashMap<>();
    private final Map<String, ProviderDescriptor> providers = new LinkedHashMap<>();

    /**
     * 注册一个插件并返回其隔离的注册 API。
     *
     * @param pluginId  插件唯一标识
     * @param meta      插件元数据
     * @return 隔离的注册 API，插件通过它声明自己的能力
     */
    public PluginRegistrationApi registerPlugin(String pluginId, PluginMeta meta) {
        PluginRecord record = new PluginRecord(pluginId, meta);
        plugins.put(pluginId, record);

        return new PluginRegistrationApi(this, pluginId);
    }

    /**
     * 卸载一个插件，自动清理其注册的所有能力。
     */
    public void unregisterPlugin(String pluginId) {
        PluginRecord record = plugins.remove(pluginId);
        if (record == null) return;

        // 清理工具
        tools.entrySet().removeIf(e -> pluginId.equals(e.getValue().getClass().getName()));
        // 清理渠道
        channels.entrySet().removeIf(e -> pluginId.equals(e.getValue().ownerPluginId()));
        // 清理钩子
        hooks.entrySet().removeIf(e -> pluginId.equals(e.getValue().ownerPluginId()));
        // 清理 Provider
        providers.entrySet().removeIf(e -> pluginId.equals(e.getValue().ownerPluginId()));
    }

    /** 注册工具（由 PluginRegistrationApi 调用） */
    void registerTool(String pluginId, Tool<? extends ToolInput> tool) {
        tools.put(tool.name(), tool);
        getRecord(pluginId).addToolName(tool.name());
    }

    /** 注册渠道（由 PluginRegistrationApi 调用） */
    void registerChannel(String pluginId, ChannelDescriptor channel) {
        channels.put(channel.id(), channel);
        getRecord(pluginId).addChannelId(channel.id());
    }

    /** 注册钩子（由 PluginRegistrationApi 调用） */
    void registerHook(String pluginId, HookDescriptor hook) {
        hooks.put(hook.name(), hook);
        getRecord(pluginId).addHookName(hook.name());
    }

    /** 注册 Provider（由 PluginRegistrationApi 调用） */
    void registerProvider(String pluginId, ProviderDescriptor provider) {
        providers.put(provider.id(), provider);
        getRecord(pluginId).addProviderId(provider.id());
    }

    private PluginRecord getRecord(String pluginId) {
        PluginRecord r = plugins.get(pluginId);
        if (r == null) throw new IllegalArgumentException("Plugin not registered: " + pluginId);
        return r;
    }

    // ── 查询方法 ──

    public Collection<Tool<? extends ToolInput>> allTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public Optional<Tool<? extends ToolInput>> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<ChannelDescriptor> allChannels() {
        return Collections.unmodifiableCollection(channels.values());
    }

    public Collection<HookDescriptor> allHooks() {
        return Collections.unmodifiableCollection(hooks.values());
    }

    public Collection<ProviderDescriptor> allProviders() {
        return Collections.unmodifiableCollection(providers.values());
    }

    public Collection<PluginRecord> allPlugins() {
        return Collections.unmodifiableCollection(plugins.values());
    }

    public boolean hasPlugin(String pluginId) {
        return plugins.containsKey(pluginId);
    }
}
