package com.ouisani.aios.openclaw;

import java.util.*;

/**
 * 插件记录 — 跟踪单个插件注册的所有能力，用于卸载时清理。
 * <p>
 * 对标 OpenClaw TypeScript 版的 PluginRecord。
 */
public class PluginRecord {

    private final String pluginId;
    private final PluginMeta meta;
    private final List<String> toolNames = new ArrayList<>();
    private final List<String> channelIds = new ArrayList<>();
    private final List<String> hookNames = new ArrayList<>();
    private final List<String> providerIds = new ArrayList<>();

    public PluginRecord(String pluginId, PluginMeta meta) {
        this.pluginId = pluginId;
        this.meta = meta;
    }

    public String pluginId() { return pluginId; }
    public PluginMeta meta() { return meta; }
    public List<String> toolNames() { return Collections.unmodifiableList(toolNames); }
    public List<String> channelIds() { return Collections.unmodifiableList(channelIds); }
    public List<String> hookNames() { return Collections.unmodifiableList(hookNames); }
    public List<String> providerIds() { return Collections.unmodifiableList(providerIds); }

    void addToolName(String name) { toolNames.add(name); }
    void addChannelId(String id) { channelIds.add(id); }
    void addHookName(String name) { hookNames.add(name); }
    void addProviderId(String id) { providerIds.add(id); }

    @Override
    public String toString() {
        return "PluginRecord{" + pluginId + " tools=" + toolNames + " channels=" + channelIds
                + " hooks=" + hookNames + " providers=" + providerIds + "}";
    }
}
