package com.ouisani.aios.openclaw.channel;

import java.util.*;

/**
 * 渠道注册表 — 对标 OpenClaw 的 Channel Registry。
 * <p>
 * 管理所有已注册的消息渠道（Telegram, Discord, Webchat 等），
 * 提供 ID 规范化、别名查找、能力查询。
 * <p>
 * OS 类比：相当于 Linux 的 /dev/ 设备注册表 — 每个设备有主设备号和别名，
 * 通过注册表可以查找和操作任何已注册的设备。
 */
public class ChannelRegistry {

    /** id -> entry */
    private final Map<String, ChannelEntry> byId = new LinkedHashMap<>();
    /** alias -> id */
    private final Map<String, String> aliasToId = new HashMap<>();

    /** 注册一个渠道 */
    public void register(ChannelEntry entry) {
        byId.put(entry.id().toLowerCase(), entry);
        for (String alias : entry.aliases()) {
            aliasToId.put(alias.toLowerCase(), entry.id().toLowerCase());
        }
    }

    /** 注销一个渠道 */
    public void unregister(String channelId) {
        ChannelEntry removed = byId.remove(channelId.toLowerCase());
        if (removed != null) {
            for (String alias : removed.aliases()) {
                aliasToId.remove(alias.toLowerCase());
            }
        }
    }

    /** 规范化渠道 ID — 支持别名解析 */
    public String normalizeChannelId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.toLowerCase().trim();
        // 先查别名
        String aliased = aliasToId.get(lower);
        if (aliased != null) return aliased;
        // 再查 ID
        if (byId.containsKey(lower)) return lower;
        return null;
    }

    /** 获取渠道条目 */
    public ChannelEntry get(String channelId) {
        String normalized = normalizeChannelId(channelId);
        return normalized != null ? byId.get(normalized) : null;
    }

    /** 列出所有已注册渠道 */
    public Collection<ChannelEntry> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** 已注册渠道数量 */
    public int size() { return byId.size(); }

    /**
     * 渠道条目 — 对标 OpenClaw 的 RegisteredChannelPluginEntry。
     *
     * @param id             渠道唯一 ID（如 "telegram", "discord", "webchat"）
     * @param name           人类可读名称
     * @param aliases        别名列表（如 "tg" → "telegram"）
     * @param markdownCapable 是否支持 Markdown 格式
     * @param supportsText   是否支持文本消息
     * @param supportsImage  是否支持图片
     * @param supportsFile   是否支持文件
     * @param supportsVoice  是否支持语音
     * @param ownerPluginId  所属插件 ID
     */
    public record ChannelEntry(
            String id,
            String name,
            List<String> aliases,
            boolean markdownCapable,
            boolean supportsText,
            boolean supportsImage,
            boolean supportsFile,
            boolean supportsVoice,
            String ownerPluginId
    ) {
        public ChannelEntry {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Channel id required");
            if (name == null || name.isBlank()) name = id;
            if (aliases == null) aliases = List.of();
            if (ownerPluginId == null) ownerPluginId = "";
        }
    }
}
